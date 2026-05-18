package com.example.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class AsyncSchedulingConfig implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AsyncSchedulingConfig.class);

    @Value("${app.runtime.async.core-pool-size:8}")
    private int asyncCorePoolSize;

    @Value("${app.runtime.async.max-pool-size:32}")
    private int asyncMaxPoolSize;

    @Value("${app.runtime.async.queue-capacity:2000}")
    private int asyncQueueCapacity;

    @Value("${app.runtime.async.await-termination-seconds:30}")
    private int asyncAwaitTerminationSeconds;

    @Value("${app.runtime.scheduler.pool-size:4}")
    private int schedulerPoolSize;

    @Value("${app.runtime.scheduler.await-termination-seconds:30}")
    private int schedulerAwaitTerminationSeconds;

    @Bean(name = {"applicationTaskExecutor", "taskExecutor"})
    public ThreadPoolTaskExecutor applicationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(2, asyncCorePoolSize));
        executor.setMaxPoolSize(Math.max(asyncCorePoolSize, asyncMaxPoolSize));
        executor.setQueueCapacity(Math.max(100, asyncQueueCapacity));
        executor.setThreadNamePrefix("backend-async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(Math.max(10, asyncAwaitTerminationSeconds));
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    @Bean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(2, schedulerPoolSize));
        scheduler.setThreadNamePrefix("backend-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(Math.max(10, schedulerAwaitTerminationSeconds));
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setErrorHandler(t -> log.error("Scheduled task execution failed: {}", t.getMessage(), t));
        scheduler.initialize();
        return scheduler;
    }

    @Override
    public Executor getAsyncExecutor() {
        return applicationTaskExecutor();
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return new AsyncUncaughtExceptionHandler() {
            @Override
            public void handleUncaughtException(Throwable ex, Method method, Object... params) {
                log.error("Async method failed: {}#{} message={}",
                        method.getDeclaringClass().getSimpleName(),
                        method.getName(),
                        ex.getMessage(),
                        ex);
            }
        };
    }
}
