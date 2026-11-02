package com.winter.awss3uploadv2.upload;

import com.zsq.winter.minio.service.AmazonS3Template;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import com.winter.awss3uploadv2.upload.persistence.*;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.services.s3.model.Part;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 文件上传业务服务，协调 MySQL、Redis 和 RustFS 三种资源。
 * <p>MySQL 的 upload_task 保存未完成任务和 RustFS uploadId；uploaded_file 保存完成记录，
 * 用于秒传和文件列表。Redis 负责同一 MD5 的初始化、合并和取消互斥；
 * RustFS 保存实际内容，续传时以其返回的分片列表为准。</p>
 * <p>MySQL 操作与 S3 调用不属于同一个数据库事务，因此部分步骤采用补偿和重试恢复，
 * 不能把数据库事务等同于跨存储的一致性保证。</p>
 */
@Service
public class UploadService {
    /** 共享守护线程定期续租 Redis 锁；守护线程本身不会阻止 JVM 退出。 */
    private static final ScheduledExecutorService LOCK_RENEWER=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"upload-lock-renewer");t.setDaemon(true);return t;});
    /** 原子检查锁的持有者并续租 60 秒，防止续租到其他请求后来获取的锁。 */
    private static final DefaultRedisScript<Long> RENEW=new DefaultRedisScript<>("if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('pexpire',KEYS[1],60000) else return 0 end",Long.class);
    /** 原子比较令牌后删除锁，避免普通 GET + DELETE 之间发生误删。 */
    private static final DefaultRedisScript<Long> RELEASE=new DefaultRedisScript<>("if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end",Long.class);
    /** 通过 MyBatis-Plus 操作未完成任务和完成记录，不在业务层编写 SQL。 */
    private final UploadTaskMapper taskMapper;
    private final UploadedFileMapper fileMapper;
    /** 只存储临时互斥锁，不以 Redis 缓存的进度代替 RustFS 实际分片状态。 */
    private final StringRedisTemplate redis;
    /** AWS SDK 2.x starter 的高级封装，用于上传、合并、中止和生成下载链接。 */
    private final AmazonS3Template s3;
    /** 原生 SDK 客户端用于遍历 ListParts 的所有分页，避免仅返回第一页而漏片。 */
    private final S3Client client;
    /** 配置中的私有桶名；调用初始化接口前需要已完成桶的创建。 */
    private final String bucket;

    /** Spring 注入数据库、锁服务、S3 组件及 winter-aws.bucket 配置。 */
    public UploadService(UploadTaskMapper taskMapper, UploadedFileMapper fileMapper, StringRedisTemplate redis, AmazonS3Template s3, S3Client client, @Value("${winter-aws.bucket}") String bucket) {
        this.taskMapper=taskMapper; this.fileMapper=fileMapper; this.redis = redis; this.s3 = s3; this.client=client; this.bucket = bucket;
    }

    /** 初始化请求 JSON；字段由 Jackson 绑定，进入业务方法后统一执行 UploadRules 校验。 */
    public static class InitRequest {
        /** 前端识别的 MIME；不做文件内容检测，缺失时使用二进制类型。 */
        public String contentType;
        /** 完整文件 MD5 与用户选择的原始文件名；原始文件名不直接拼入对象路径。 */
        public String md5, fileName;
        /** 完整文件字节数，零字节文件也允许上传。 */
        public long fileSize;
        /** 按固定 8 MiB 向上取整得到的片数，空文件约定为 1。 */
        public int totalChunks;
    }
    /** 按 MD5 主键查找上传任务，不存在时返回空。 */
    private Optional<UploadTask> task(String md5) {
        return Optional.ofNullable(taskMapper.selectById(md5));
    }
    /** 完成记录并不代表对象始终存在，秒传时仍需校验 RustFS 对象。 */
    private Optional<UploadedFile> file(String md5) {
        return Optional.ofNullable(fileMapper.selectById(md5));
    }
    /**
     * 对同一 MD5 执行互斥操作，适用于不能同时执行的初始化、合并和取消。
     * SET NX 加 60 秒过期时间实现抢锁，UUID 令牌区分不同持有者；抢锁失败立即报任务忙。
     * 每 10 秒尝试续租，finally 中停止续租并原子释放锁。
     * 续租属于尽力维护：Redis 长时间不可达或 JVM 长时间停顿时，锁仍可能过期。
     *
     * @param md5 锁作用域对应的完整文件指纹
     * @param action 需要互斥执行的业务操作
     * @return 业务操作的结果
     */
    private <T> T locked(String md5, Supplier<T> action) {
        String key="upload:lock:"+md5, token=UUID.randomUUID().toString();
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key,token,Duration.ofSeconds(60)))) throw new IllegalStateException("Upload is busy");
        ScheduledFuture<?> renewal=LOCK_RENEWER.scheduleAtFixedRate(()->{
            // 当前实现忽略单次续租异常，让后续周期继续尝试；这不保证故障期间一直持有锁。
            try { redis.execute(RENEW,Collections.singletonList(key),token); } catch(RuntimeException ignored) { }
        },10,10,TimeUnit.SECONDS);
        try { return action.get(); }
        finally { renewal.cancel(false); redis.execute(RELEASE,Collections.singletonList(key),token); }
    }
    /**
     * 查询 RustFS 实际收到的全部分片，并按从 1 开始的片号排序。
     * SDK 分页器自动继续读取后续页，避免超过单页上限时漏掉已经上传的分片。
     */
    private List<Part> parts(UploadTask task) {
        List<Part> result=new ArrayList<>();
        for(var page:client.listPartsPaginator(b->b.bucket(bucket).key(task.getObjectKey()).uploadId(task.getUploadId()))) result.addAll(page.parts());
        result.sort(Comparator.comparing(Part::partNumber));
        return result;
    }
    /** 初始化和续传检查共用真实片号响应，避免前端使用初始化前的旧状态。 */
    private Map<String,Object> uploadingStatus(UploadTask task) {
        List<Integer> uploaded=task.getFileSize()==0 ? List.of() : parts(task).stream().map(Part::partNumber).collect(Collectors.toList());
        return Map.of("status","uploading","uploadedParts",uploaded);
    }
    /**
     * 秒传与断点续传的统一入口，依次检查完成记录、对象存在性以及未完成任务。
     * 返回 complete 表示已完成；uploading 附带 RustFS 已确认的片号；new 表示需要初始化。
     * 文件大小用于防止同一 MD5 被错误地关联到不同大小的文件。
     */
    public Map<String,Object> check(String md5,long size) {
        UploadRules.validate(md5,size,Math.max(1,(int)((size+UploadRules.CHUNK_SIZE-1)/UploadRules.CHUNK_SIZE)));
        var existing=file(md5);
        if(existing.isPresent()) {
            var f=existing.get();
            if(f.getFileSize()!=size) throw new IllegalArgumentException("MD5 and size conflict");
            String key=f.getObjectKey();
            if(s3.doesObjectExist(bucket,key)) return Map.of("status","complete","md5",md5);
            // 数据库仍有记录但对象已被外部删除，移除失效记录，避免错误地提示秒传成功。
            fileMapper.deleteById(md5);
        }
        var task=task(md5);
        if(task.isEmpty()) return Map.of("status","new","uploadedParts",List.of());
        if(task.get().getFileSize()!=size) throw new IllegalArgumentException("MD5 and size conflict");
        try { return uploadingStatus(task.get()); }
        catch(S3Exception e) {
            // 只有明确的 NoSuchUpload 才表示会话已消失；不能把任意 400/404 当成任务失效。
            // 网关、签名或参数错误应保留 uploadId 供重试，避免删除数据库断点记录。
            if(e.awsErrorDetails()==null || !"NoSuchUpload".equals(e.awsErrorDetails().errorCode())) throw e;
            taskMapper.deleteById(md5); return Map.of("status","new","uploadedParts",List.of());
        }
    }
    /**
     * 幂等初始化：已完成则返回完成状态，已有兼容任务则复用，否则向 RustFS 申请 uploadId。
     * 先申请存储会话，再保存数据库任务。桶需预先存在，接口不再为每个文件额外检查桶。
     * 零字节文件用内部标记 empty 代替真实 uploadId，稍后通过普通上传创建空对象。
     */
    public Map<String,Object> init(InitRequest r) {
        UploadRules.validate(r.md5,r.fileSize,r.totalChunks);
        if(r.fileName==null || r.fileName.isBlank() || r.fileName.length()>255) throw new IllegalArgumentException("Invalid file name");
        // 接收前端类型元数据；仅检查 HTTP 头格式，不对文件内容或类型做准入限制。
        String contentType=UploadContentTypes.normalize(r.contentType);
        return locked(r.md5,()->{
            var existing=file(r.md5);
            if(existing.isPresent()) {
                if(existing.get().getFileSize()!=r.fileSize) throw new IllegalArgumentException("MD5 and size conflict");
                return Map.of("status","complete");
            }
            var old=task(r.md5);
            if(old.isPresent()) {
                if(old.get().getFileSize()!=r.fileSize || old.get().getTotalChunks()!=r.totalChunks) throw new IllegalArgumentException("MD5 conflict");
                return uploadingStatus(old.get());
            }
            // 路径由服务端生成，UUID 区分不同上传会话，避免使用原始文件名导致路径冲突。
            String key="uploads/"+r.md5+"/"+UUID.randomUUID();
            // 最终对象的 Content-Type 在初始化会话时设置，不能依赖各分片的表单类型。
            String id=r.fileSize==0 ? "empty" : s3.initiateMultipartUpload(bucket,key,contentType).uploadId();
            UploadTask created=new UploadTask();
            created.setMd5(r.md5); created.setFileName(r.fileName); created.setFileSize(r.fileSize);
            created.setTotalChunks(r.totalChunks); created.setObjectKey(key); created.setUploadId(id);
            try { taskMapper.insert(created); }
            // 并发插入发生主键冲突时，中止本次新建的会话，避免留下无数据库记录的临时分片。
            // 此处仅补偿主键冲突；其他数据库异常仍需按异常原因排查和清理。
            catch(DuplicateKeyException e) { if(!id.equals("empty")) s3.abortMultipartUpload(bucket,key,id); throw e; }
            return Map.of("status","uploading","uploadedParts",List.of());
        });
    }
    /**
     * 校验任务和单片长度后，通过 starter 将分片传给 RustFS。
     * 片号在整个接口链路中统一从 1 开始，不做前端索引加一的转换。
     * 此方法不获取任务互斥锁，因此不同片号可以并行上传；相同 uploadId 和片号重传会覆盖该片。
     */
    public void uploadPart(String md5,int number,MultipartFile part) throws IOException {
        UploadTask t=task(md5).orElseThrow(()->new IllegalArgumentException("Upload task not found"));
        if(t.getFileSize()==0) throw new IllegalArgumentException("Empty file has no parts");
        UploadRules.validatePart(t.getFileSize(),t.getTotalChunks(),number,part.getSize());
        s3.uploadPart(bucket,t.getUploadId(),t.getObjectKey(),number,part);
    }
    /**
     * 完成上传：验证分片数量、连续片号和各片大小，再请求 RustFS 合并。
     * 合并成功后保存完成记录，最后删除未完成任务；重复调用在已有完成记录时直接返回。
     * 若上次已合并对象但尚未写完数据库，本次通过对象存在性与长度校验恢复记录。
     * 这里核对的是长度，不重新下载对象计算 MD5。
     */
    public Map<String,Object> complete(String md5) {
        return locked(md5,()->{
            if(file(md5).isPresent()) return Map.of("status","complete");
            UploadTask t=task(md5).orElseThrow(()->new IllegalArgumentException("Upload task not found"));
            if(s3.doesObjectExist(bucket,t.getObjectKey())) {
                // 恢复“对象已提交、数据库完成记录尚未保存”的情况，禁止把长度不符的对象标为完成。
                UploadRules.validateObjectSize(t.getFileSize(),s3.getObjectInfo(bucket,t.getObjectKey()).contentLength());
            } else if(t.getFileSize()==0) s3.putObject(bucket,t.getObjectKey(),"application/octet-stream",new ByteArrayInputStream(new byte[0]),0L,null);
            else {
                List<Part> all=parts(t);
                if(all.size()!=t.getTotalChunks()) throw new IllegalStateException("Missing parts");
                long bytes=0;
                for(int i=0;i<all.size();i++) {
                    Part p=all.get(i);
                    // 片数正确仍可能缺少中间片号，因此还必须验证排序后片号恰好为 1..N。
                    if(p.partNumber()!=i+1) throw new IllegalStateException("Missing parts");
                    UploadRules.validatePart(t.getFileSize(),t.getTotalChunks(),p.partNumber(),p.size());
                    bytes+=p.size();
                }
                if(bytes!=t.getFileSize()) throw new IllegalStateException("Wrong total size");
                s3.completeMultipartUpload(bucket,t.getObjectKey(),t.getUploadId());
            }
            // 先写完成记录再删任务，确保文件不会在两张表中同时消失；两条 SQL 当前独立执行。
            UploadedFile completed=new UploadedFile();
            completed.setMd5(t.getMd5()); completed.setFileName(t.getFileName());
            completed.setFileSize(t.getFileSize()); completed.setObjectKey(t.getObjectKey());
            fileMapper.insert(completed);
            taskMapper.deleteById(md5);
            return Map.of("status","complete");
        });
    }
    /**
     * 取消未完成上传：先中止 RustFS 会话以释放分片，再删除 MySQL 任务。
     * 任务不存在时按成功处理；不会删除 uploaded_file 中的完成记录或已完成对象。
     * 中止失败时异常会向上传播，本地任务保留，便于再次尝试清理。
     */
    public void cancel(String md5) {
        locked(md5,()->{
            task(md5).ifPresent(t->{ if(!t.getUploadId().equals("empty")) s3.abortMultipartUpload(bucket,t.getObjectKey(),t.getUploadId()); taskMapper.deleteById(md5); });
            return null;
        });
    }
    /**
     * 删除已完成文件，与取消未完成任务使用不同接口。
     * 按 MD5 加锁，先删除 RustFS 对象，成功后清理残留任务及完成记录。
     * 存储删除失败时保留数据库记录供重试；记录不存在时幂等返回，不干扰正在上传的任务。
     */
    public void deleteFile(String md5) {
        if(md5==null || !md5.matches("[a-fA-F0-9]{32}")) throw new IllegalArgumentException("Invalid MD5");
        locked(md5,()->{
            var record=file(md5);
            if(record.isEmpty()) return null;
            s3.removeObject(bucket,record.get().getObjectKey());
            // 清理合并后可能遗留的任务，避免删除对象后旧任务再次恢复完成记录。
            taskMapper.deleteById(md5);
            fileMapper.deleteById(md5);
            return null;
        });
    }

    /** 按完成时间倒序返回最近 100 条记录，不在列表中生成或暴露长期有效的公开地址。 */
    public List<Map<String,Object>> files() {
        var page=fileMapper.selectPage(new Page<UploadedFile>(1,100,false),
                Wrappers.<UploadedFile>lambdaQuery().select(UploadedFile::getMd5, UploadedFile::getFileName,
                        UploadedFile::getFileSize, UploadedFile::getCreatedAt).orderByDesc(UploadedFile::getCreatedAt));
        // 保留原接口的下划线字段及日期格式，前端无须跟随实体字段命名调整。
        return page.getRecords().stream().map(row->{
            Map<String,Object> result=new LinkedHashMap<>();
            result.put("md5",row.getMd5()); result.put("file_name",row.getFileName());
            result.put("file_size",row.getFileSize()); result.put("created_at",row.getCreatedAt());
            return result;
        }).collect(Collectors.toList());
    }
    /** 根据完成记录中的对象键生成 15 分钟有效的 GET 预签名链接，浏览器凭该链接下载私有对象。 */
    public Map<String,String> download(String md5, String contentType) {
        var f=file(md5).orElseThrow(()->new IllegalArgumentException("File not found"));
        // 视频通过预签名响应头覆盖兼容已有错误元数据，仍使用 starter 的预签名能力。
        var request=s3.generateGetObjectPresignRequest(bucket,f.getObjectKey(),15,TimeUnit.MINUTES);
        // 页面可传入识别出的类型兼容旧对象；未提供时读取已有对象元数据，不读取文件内容。
        if (contentType==null || contentType.isBlank()) contentType=s3.getObjectInfo(bucket,f.getObjectKey()).contentType();
        contentType=UploadContentTypes.normalize(contentType);
        return Map.of("url",s3.getObjectUrl(UploadContentTypes.forPreview(request,contentType)));
    }
}
