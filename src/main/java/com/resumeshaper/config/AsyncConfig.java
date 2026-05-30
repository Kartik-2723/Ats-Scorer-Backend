package com.resumeshaper.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    /** LLM pipeline thread pool — used by @Async scoreAsync and any remaining @Async methods. */
    @Bean(name = "llmExecutor")
    public Executor llmExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("llm-pipeline-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    /**
     * Dedicated pool for ResumeWorker BLPOP loops.
     * corePoolSize must match ResumeWorker.NUM_WORKERS.
     * These threads block on Redis — keep them separate from llmExecutor.
     */
    @Bean(name = "workerExecutor")
    public Executor workerExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);      // = ResumeWorker.NUM_WORKERS
        executor.setMaxPoolSize(4);       // no burst — BLPOP threads are always alive
        executor.setQueueCapacity(0);     // reject immediately if all 4 slots taken (startup guard)
        executor.setThreadNamePrefix("resume-worker-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(35); // > BLPOP_TIMEOUT_SEC so loops can exit cleanly
        executor.initialize();
        return executor;
    }
}
