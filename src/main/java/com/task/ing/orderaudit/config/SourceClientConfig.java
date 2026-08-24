package com.task.ing.orderaudit.config;

import com.task.ing.orderaudit.adapter.out.rest.OrderRestClientAdapter;
import com.task.ing.orderaudit.adapter.out.rest.PaymentRestClientAdapter;
import com.task.ing.orderaudit.adapter.out.rest.SourceClientProperties;
import com.task.ing.orderaudit.adapter.out.rest.SourceEventParser;
import com.task.ing.orderaudit.adapter.out.rest.SourceHttpGateway;
import com.task.ing.orderaudit.application.port.out.OrderSourceClient;
import com.task.ing.orderaudit.application.port.out.PaymentSourceClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class SourceClientConfig {

    @Bean
    public OrderSourceClient orderSourceClient(
            SourceClientProperties properties, ObjectMapper objectMapper) {
        SourceHttpGateway gateway = new SourceHttpGateway(
                restClient(properties.order()), properties.order(), "order-service");
        return new OrderRestClientAdapter(
                gateway, new SourceEventParser(objectMapper, "orderId", "order-service"));
    }

    @Bean
    public PaymentSourceClient paymentSourceClient(
            SourceClientProperties properties, ObjectMapper objectMapper) {
        SourceHttpGateway gateway = new SourceHttpGateway(
                restClient(properties.payment()), properties.payment(), "payment-service");
        return new PaymentRestClientAdapter(
                gateway, new SourceEventParser(objectMapper, "paymentId", "payment-service"));
    }

    private RestClient restClient(SourceClientProperties.Endpoint endpoint) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(endpoint.connectTimeout());
        factory.setReadTimeout(endpoint.readTimeout());
        return RestClient.builder()
                .baseUrl(endpoint.baseUrl())
                .requestFactory(factory)
                .build();
    }
}
