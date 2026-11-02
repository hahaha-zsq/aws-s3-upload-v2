package com.winter.awss3uploadv2.upload;

/**
 * 上传协议的统一校验规则。
 * 前后端必须使用相同的分片大小和编号约定，否则恢复进度与合并结果会不一致。
 * 此类只校验元数据和长度，不读取文件内容，也不重新计算完整文件的 MD5。
 */
public final class UploadRules {
    /** 固定分片大小为 8 MiB；除最后一片外，每片都必须达到该长度。 */
    public static final long CHUNK_SIZE = 8L * 1024 * 1024;
    /** 工具类不需要创建实例。 */
    private UploadRules() {}

    /**
     * 校验初始化元数据：MD5 必须是 32 位十六进制字符串，大小不能为负，片数必须匹配。
     * 向上取整得到总片数；空文件仍约定为一片，但实际保存时走普通零字节对象上传。
     * MD5 由浏览器计算并提交，这里只验证格式，不能据此证明客户端内容真实可靠。
     *
     * @param md5 文件内容指纹，用于关联任务和查找秒传记录
     * @param size 完整文件长度，单位为字节
     * @param chunks 客户端声明的总片数
     */
    public static void validate(String md5, long size, int chunks) {
        if (md5 == null || !md5.matches("[a-fA-F0-9]{32}") || size < 0 || chunks != Math.max(1, (int) ((size + CHUNK_SIZE - 1) / CHUNK_SIZE)))
            throw new IllegalArgumentException("Invalid file metadata");
    }

    /**
     * 根据任务记录校验某一片的编号和实际长度，避免越界分片或错误切片进入 RustFS。
     *
     * @param size 完整文件长度，单位为字节
     * @param chunks 任务总片数
     * @param number 当前片号，前端接口与 S3 均从 1 开始
     * @param length 当前分片的实际长度，单位为字节
     */
    public static void validatePart(long size, int chunks, int number, long length) {
        if (number < 1 || number > chunks) throw new IllegalArgumentException("Invalid part number");
        // 最后一片使用总长度减去前面所有整片的长度，其余分片必须恰好为 8 MiB。
        long expected = number == chunks ? size - (long)(chunks - 1) * CHUNK_SIZE : CHUNK_SIZE;
        if (length != expected) throw new IllegalArgumentException("Invalid part size");
    }
    /** 恢复已合并对象的数据库记录前，用 HEAD 返回的长度核对任务声明的文件大小。 */
    public static void validateObjectSize(long expected, long actual) {
        if(expected!=actual) throw new IllegalStateException("Stored object size does not match upload task");
    }
}
