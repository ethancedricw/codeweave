package com.codeweave.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "codeweave.storage.minio")
public record MinioProperties(
    String endpoint,
    String accessKey,
    String secretKey,
    String bucket,
    boolean secure,
    int presignedUrlExpirySeconds
) {}
