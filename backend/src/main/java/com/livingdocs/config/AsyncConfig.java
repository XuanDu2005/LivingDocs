package com.livingdocs.config;

import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Async executors used by the platform.
 *
 * <p>{@code indexingExecutor} is the pool that runs {@link
 * com.livingdocs.modules.ai.indexing.IndexingService} jobs. We keep it
 * separate from the common {@code applicationTaskExecutor} Spring Boot
 * provides so that:
 * <ul>
 *   <li>A flood of indexing work can't starve other async paths.</li>
 *   <li>The pool can be sized for the workload (small, IO-bound).</li>
 *   <li>Failures can be logged with context, not via the default
 *       "Async exception" handler.</li>
 * </ul>
 */
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    @Bean(name = "indexingExecutor")
    public TaskExecutor indexingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("indexing-");
        // Reject early so the calling code sees a clear failure
        // instead of accumulating forever in the queue.
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * Default executor used by {@code @Async} without an explicit name.
     * Spring Boot's autoconfig already provides one but we wire it
     * through here so we get the same logging handler.
     */
    @Override
    public Executor getAsyncExecutor() {
        return indexingExecutor();
    }

    /**
     * Log uncaught exceptions in async methods. Without this, Spring
     * just swallows them after printing a generic warning.
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) -> {
            org.slf4j.LoggerFactory.getLogger(AsyncConfig.class)
                    .error("Async method {} threw: {}", method.getName(), ex.getMessage(), ex);
        };
    }
}