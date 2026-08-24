package com.task.ing.orderaudit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class OrderAuditServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderAuditServiceApplication.class, args);
    }
}
