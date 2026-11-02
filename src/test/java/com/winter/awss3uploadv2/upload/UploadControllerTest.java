package com.winter.awss3uploadv2.upload;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.model.S3Exception;
import static org.junit.jupiter.api.Assertions.*;

class UploadControllerTest {
    @Test void upstreamBadGatewayIsReportedAsServiceUnavailable() {
        var response = new UploadController(null).storageError((S3Exception) S3Exception.builder().statusCode(502).message("Bad Gateway").build());
        assertEquals(503, response.getStatusCodeValue());
        assertEquals("RustFS unavailable (upstream HTTP 502); retry the upload", response.getBody().get("error"));
    }
}
