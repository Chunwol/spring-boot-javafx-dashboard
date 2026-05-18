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

/**
 * 비동기 처리(@Async)와 스케줄링(@Scheduled)에 사용할 스레드 풀을 구성한다.
 *
 * <p>스레드 풀 구성 전략:
 * <ul>
 *   <li>{@code applicationTaskExecutor} — {@code @Async} 메서드용. 코어/최대 풀 크기와 큐 용량을
 *       application.yml로 외부화하여 환경별(개발/운영) 조정이 가능하다.</li>
 *   <li>{@code taskScheduler} — {@code @Scheduled} 태스크용. 단일 풀 크기로 주기적 작업을 담당한다.</li>
 * </ul>
 *
 * <p>WHY CallerRunsPolicy: 큐가 꽉 찼을 때 요청 스레드가 직접 실행함으로써
 * 태스크 유실 없이 자연스러운 역압(back-pressure)을 제공한다.
 *
 * <p>설정 키 ({@code application.yml}):
 * <pre>
 * app.runtime.async.core-pool-size      (기본 8)
 * app.runtime.async.max-pool-size       (기본 32)
 * app.runtime.async.queue-capacity      (기본 2000)
 * app.runtime.async.await-termination-seconds (기본 30)
 * app.runtime.scheduler.pool-size       (기본 4)
 * app.runtime.scheduler.await-termination-seconds (기본 30)
 * </pre>
 */
@Configuration
public class AsyncSchedulingConfig implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AsyncSchedulingConfig.class);

    /** @Async 스레드 풀 코어 스레드 수 (항상 유지되는 최소 스레드) */
    @Value("${app.runtime.async.core-pool-size:8}")
    private int asyncCorePoolSize;

    /** @Async 스레드 풀 최대 스레드 수 (큐가 꽉 찼을 때 증가 한계) */
    @Value("${app.runtime.async.max-pool-size:32}")
    private int asyncMaxPoolSize;

    /** @Async 대기 큐 용량 (최대 스레드 수에 도달한 후 작업을 보관하는 버퍼 크기) */
    @Value("${app.runtime.async.queue-capacity:2000}")
    private int asyncQueueCapacity;

    /** 종료 신호 수신 후 진행 중인 @Async 작업 완료를 기다릴 최대 시간(초) */
    @Value("${app.runtime.async.await-termination-seconds:30}")
    private int asyncAwaitTerminationSeconds;

    /** @Scheduled 스레드 풀 크기 (동시 실행 가능한 스케줄 태스크 수) */
    @Value("${app.runtime.scheduler.pool-size:4}")
    private int schedulerPoolSize;

    /** 종료 신호 수신 후 진행 중인 스케줄 작업 완료를 기다릴 최대 시간(초) */
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
