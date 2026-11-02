package com.winter.awss3uploadv2.upload;

import org.springframework.http.MediaType;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * 接收前端提供的 MIME 元数据，不检测文件内容，也不设置文件类型白名单。
 */
public final class UploadContentTypes {
    private UploadContentTypes() {}

    /** 仅规范化 HTTP 头格式，缺失或非法值回退为二进制，不因未知文件类型拒绝上传。 */
    public static String normalize(String type) {
        if (type == null || type.isBlank() || type.contains("\r") || type.contains("\n")) return "application/octet-stream";
        try {
            MediaType parsed=MediaType.parseMediaType(type);
            return parsed.isConcrete() ? parsed.toString() : "application/octet-stream";
        } catch (IllegalArgumentException e) { return "application/octet-stream"; }
    }

    /**
     * 在签名前覆盖受支持预览类型的 GET 响应头，兼容历史上以 octet-stream 保存的对象。
     * 修改的是此链接的响应，不改写原对象；必须先设置响应参数再签名，不能签名后拼 URL。
     * 只对图片、音视频和 PDF 开启内联，HTML 等其他文档保留原请求。
     */
    public static GetObjectPresignRequest forPreview(GetObjectPresignRequest request, String type) {
        if (!(type.startsWith("video/") || type.startsWith("audio/") || type.startsWith("image/") || "application/pdf".equals(type))) return request;
        return request.toBuilder().getObjectRequest(request.getObjectRequest().toBuilder()
                .responseContentType(type).responseContentDisposition("inline").build()).build();
    }
}
