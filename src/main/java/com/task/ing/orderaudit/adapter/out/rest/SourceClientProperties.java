package com.task.ing.orderaudit.adapter.out.rest;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "sources")
public record SourceClientProperties(
        @DefaultValue Endpoint order,
        @DefaultValue Endpoint payment) {
    public record Endpoint(
            @DefaultValue("http://localhost:9101") String baseUrl,
            @DefaultValue("PT2S") Duration connectTimeout,
            @DefaultValue("PT10S") Duration readTimeout,
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("PT0.2S") Duration retryDelay) {
    }
}
