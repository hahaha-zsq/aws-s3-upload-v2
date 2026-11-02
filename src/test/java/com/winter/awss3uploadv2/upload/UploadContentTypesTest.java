package com.winter.awss3uploadv2.upload;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import java.net.URI;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class UploadContentTypesTest {
    @Test void acceptsAllDeclaredTypesAndDefaultsMissingType() {
        assertEquals("video/mp4", UploadContentTypes.normalize("video/mp4"));
        assertEquals("application/x-custom-format", UploadContentTypes.normalize("application/x-custom-format"));
        assertEquals("application/octet-stream", UploadContentTypes.normalize(null));
        assertEquals("application/octet-stream", UploadContentTypes.normalize("bad\r\nheader"));
    }
    @Test void overridesLegacyVideoResponseBeforeSigning() {
        var original = GetObjectPresignRequest.builder().signatureDuration(Duration.ofMinutes(15))
                .getObjectRequest(GetObjectRequest.builder().bucket("files").key("uploads/opaque-key").build()).build();
        var preview = UploadContentTypes.forPreview(original, "video/mp4");
        assertEquals("video/mp4", preview.getObjectRequest().responseContentType());
        assertEquals("inline", preview.getObjectRequest().responseContentDisposition());
        assertEquals("uploads/opaque-key", preview.getObjectRequest().key());
        assertEquals(original.signatureDuration(), preview.signatureDuration());
        assertNull(original.getObjectRequest().responseContentType());
    }

    @Test void doesNotForceNonVideoContentToRenderInline() {
        var request = GetObjectPresignRequest.builder().signatureDuration(Duration.ofMinutes(15))
                .getObjectRequest(GetObjectRequest.builder().bucket("files").key("key").build()).build();
        assertSame(request, UploadContentTypes.forPreview(request, "text/html"));
        assertEquals("application/pdf",UploadContentTypes.forPreview(request,"application/pdf").getObjectRequest().responseContentType());
        assertEquals("inline",UploadContentTypes.forPreview(request,"audio/mpeg").getObjectRequest().responseContentDisposition());
        assertEquals("image/png",UploadContentTypes.forPreview(request,"image/png").getObjectRequest().responseContentType());
    }

    @Test void videoResponseOverridesAreIncludedInSignedUrl() {
        // 使用测试凭据本地签名，不连接 RustFS，验证参数在签名前加入且出现在最终链接中。
        try (var signer = S3Presigner.builder().endpointOverride(URI.create("http://localhost:9000"))
                .region(Region.US_EAST_1).credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test", "test"))).build()) {
            var request = GetObjectPresignRequest.builder().signatureDuration(Duration.ofMinutes(15))
                    .getObjectRequest(GetObjectRequest.builder().bucket("files").key("old-video").build()).build();
            String query = signer.presignGetObject(UploadContentTypes.forPreview(request, "video/mp4")).url().getQuery();
            assertTrue(query.contains("response-content-type=video%2Fmp4"));
            assertTrue(query.contains("response-content-disposition=inline"));
            assertTrue(query.contains("X-Amz-Signature="));
        }
    }
}
