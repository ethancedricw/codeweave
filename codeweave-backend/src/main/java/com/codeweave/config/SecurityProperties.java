package com.codeweave.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "codeweave.security")
public record SecurityProperties(String encryptionKey) {}
