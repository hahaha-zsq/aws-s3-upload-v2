package com.winter.awss3uploadv2;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 文件上传服务启动入口。
 * Spring Boot 从当前包向下扫描上传接口与业务服务，并根据配置自动装配
 * MySQL、Redis 以及 AWS SDK 2.x starter 提供的 S3 客户端和操作模板。
 */
@SpringBootApplication
public class AwsS3UploadV2Application {

    /** 启动内嵌 Web 容器，接收前端上传、续传、合并和下载请求。 */
    public static void main(String[] args) {
        SpringApplication.run(AwsS3UploadV2Application.class, args);
    }

}
