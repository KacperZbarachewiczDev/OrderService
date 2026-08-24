package com.task.ing.orderaudit.config;

import com.task.ing.orderaudit.application.config.AuditProperties;
import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableScheduling
public class AsyncConfig {

    @Bean("resyncExecutor")
    public Executor resyncExecutor(AuditProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.resync().poolSize());
        executor.setMaxPoolSize(properties.resync().poolSize());
        executor.setQueueCapacity(properties.resync().queueCapacity());
        executor.setThreadNamePrefix("resync-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
