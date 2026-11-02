package com.winter.awss3uploadv2.upload;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.util.*;

/**
 * 上传 HTTP 接口，统一使用 /api/uploads 前缀。
 * 控制器负责参数接收、响应格式和异常状态码；上传状态流转由 UploadService 完成。
 * 分片数据经后端转发到 RustFS，浏览器不会获取 S3 访问密钥。
 */
@RestController
@RequestMapping("/api/uploads")
public class UploadController {
    /** 通过构造器注入业务服务，所有接口共享同一套上传协议。 */
    private final UploadService uploads;

    public UploadController(UploadService uploads) {
        this.uploads = uploads;
    }

    /**
     * 查询秒传或续传状态。md5 是完整文件指纹，size 是完整文件字节数。
     * status=complete 表示可以秒传；uploading 附带已上传片号；new 表示需要初始化。
     */
    @GetMapping("/check")
    public Map<String, Object> check(@RequestParam String md5, @RequestParam long size) {
        return uploads.check(md5, size);
    }

    /** 接收前端 JSON 元数据，包括 contentType；所有文件类型都允许上传。 */
    @PostMapping("/init")
    public Map<String, Object> init(@RequestBody UploadService.InitRequest request) {
        return uploads.init(request);
    }

    /**
     * 接收 multipart/form-data 中名为 file 的单个分片。
     * number 从 1 开始；返回 uploaded 仅表示当前分片已上传，并不代表整个文件完成。
     */
    @PutMapping("/{md5}/parts/{number}")
    public Map<String, String> part(@PathVariable String md5, @PathVariable int number, @RequestParam("file") MultipartFile file) throws IOException {
        uploads.uploadPart(md5, number, file);
        return Map.of("status", "uploaded");
    }

    /** 所有分片上传后调用；服务端重新核对 RustFS 中的分片，再提交合并并保存完成记录。 */
    @PostMapping("/{md5}/complete")
    public Map<String, Object> complete(@PathVariable String md5) {
        return uploads.complete(md5);
    }

    /** 明确取消未完成任务并清理临时分片；前端的“暂停”不应调用此接口。 */
    @DeleteMapping("/{md5}")
    public Map<String, String> cancel(@PathVariable String md5) {
        uploads.cancel(md5);
        return Map.of("status", "cancelled");
    }

    /** 返回最近 100 条完成记录，供前端展示文件库。 */
    @GetMapping("/files")
    public List<Map<String, Object>> files() {
        return uploads.files();
    }

    /** 删除完成对象及其数据库记录；不等同于 /{md5} 的取消上传操作。 */
    @DeleteMapping("/files/{md5}")
    public Map<String, String> deleteFile(@PathVariable String md5) {
        uploads.deleteFile(md5);
        return Map.of("status", "deleted");
    }

    /** 按需生成有效期 15 分钟的预签名下载 URL，不在数据库中长期保存会过期的链接。 */
    @GetMapping("/files/{md5}/download")
    public Map<String, String> download(@PathVariable String md5, @RequestParam(required=false) String contentType) {
        return uploads.download(md5,contentType);
    }

    /** 参数错误返回 400；任务忙、缺片等状态冲突返回 409，错误文本放在 error 字段中。 */
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException e) {
        return ResponseEntity.status(e instanceof IllegalArgumentException ? HttpStatus.BAD_REQUEST : HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    /**
     * 将 S3 服务错误转换成 503，并保留上游状态码用于定位 RustFS 或网关故障。
     * 此处仅转换 HTTP 响应，不代表上游故障已恢复，也不会自动重新发起初始化请求。
     */
    @ExceptionHandler(S3Exception.class)
    public ResponseEntity<Map<String, String>> storageError(S3Exception e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "RustFS unavailable (upstream HTTP " + e.statusCode() + "); retry the upload"));
    }
}
