package com.task.ing.orderaudit.adapter.out.rest;

import com.task.ing.orderaudit.application.port.out.SourceUnavailableException;
import com.task.ing.orderaudit.adapter.out.rest.SourceClientProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
public class SourceHttpGateway {

    private final RestClient restClient;
    private final SourceClientProperties.Endpoint endpoint;
    private final String systemName;

    public <T> Optional<T> getOptional(String uriTemplate, Class<T> type, Object... uriVariables) {
        ResponseEntity<T> response = execute(uriTemplate, type, uriVariables);
        if (response.getStatusCode().value() == 404) {
            return Optional.empty();
        }
        return Optional.ofNullable(response.getBody());
    }

    public <T> T getRequired(String uriTemplate, Class<T> type, Object... uriVariables) {
        ResponseEntity<T> response = execute(uriTemplate, type, uriVariables);
        if (response.getStatusCode().value() == 404 || response.getBody() == null) {
            throw new SourceUnavailableException(
                    systemName + " returned no body for " + uriTemplate);
        }
        return response.getBody();
    }

    private <T> ResponseEntity<T> execute(String uriTemplate, Class<T> type, Object... uriVariables) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= endpoint.maxAttempts(); attempt++) {
            try {
                ResponseEntity<T> response = restClient.get()
                        .uri(uriTemplate, uriVariables)
                        .exchange((request, clientResponse) -> {
                            HttpStatusCode status = clientResponse.getStatusCode();
                            if (status.isError()) {
                                return ResponseEntity.status(status).<T>build();
                            }
                            return ResponseEntity.status(status).body(clientResponse.bodyTo(type));
                        });
                if (isRetryable(response.getStatusCode())) {
                    lastFailure = new SourceUnavailableException(
                            systemName + " answered " + response.getStatusCode() + " for " + uriTemplate);
                } else if (response.getStatusCode().isError() && response.getStatusCode().value() != 404) {
                    throw new SourceUnavailableException(
                            systemName + " answered " + response.getStatusCode() + " for " + uriTemplate);
                } else {
                    return response;
                }
            } catch (RestClientException e) {
                lastFailure = new SourceUnavailableException(
                        systemName + " call to " + uriTemplate + " failed: " + e.getMessage(), e);
            }
            if (attempt < endpoint.maxAttempts()) {
                log.warn("{}: attempt {}/{} for {} failed, retrying",
                        systemName, attempt, endpoint.maxAttempts(), uriTemplate);
                sleepBeforeRetry();
            }
        }
        throw lastFailure != null
                ? lastFailure
                : new SourceUnavailableException(systemName + " call to " + uriTemplate + " failed");
    }

    private boolean isRetryable(HttpStatusCode status) {
        return status.is5xxServerError() || status.value() == 429;
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(endpoint.retryDelay().toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceUnavailableException(systemName + " retry interrupted", e);
        }
    }
}
