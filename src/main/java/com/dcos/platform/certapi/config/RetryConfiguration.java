package com.dcos.platform.certapi.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;

/** Enables Spring Retry for @Retryable annotations throughout the application. */
@Configuration
@EnableRetry
public class RetryConfiguration {}
