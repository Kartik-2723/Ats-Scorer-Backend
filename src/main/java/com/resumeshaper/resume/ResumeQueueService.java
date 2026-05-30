package com.resumeshaper.resume;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Redis List-backed job queue for the resume pipeline.
 *
 * Producer side: LatexReshapeOrchestrator.submit() → enqueue(jobId)
 * Consumer side: ResumeWorker (BLPOP loop) → dequeue(timeoutSeconds)
 *
 * One job ID on the queue = one resume pipeline invocation.
 * BLPOP is atomic — two workers never receive the same job ID.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeQueueService {

    static final String QUEUE_KEY = "resume:pipeline:queue";

    private final StringRedisTemplate redis;

    private static final long BLPOP_TIMEOUT_SEC = 30;

    /** Push a job to the back of the queue (producer side). */
    public void enqueue(UUID jobId) {
        redis.opsForList().rightPush(QUEUE_KEY, jobId.toString());
        log.info("ResumeQueue: enqueued job={} depth={}", jobId, depth());
    }

    /**
     * Blocking dequeue — parks the calling thread for up to timeoutSeconds.
     * Returns null on timeout; caller loops back to dequeue again.
     */
    public String dequeue(long timeoutSeconds) {
        return redis.opsForList().leftPop(QUEUE_KEY, Duration.ofSeconds(timeoutSeconds));
    }

    /** Current queue depth — used for logging and monitoring endpoints. */
    public long depth() {
        Long size = redis.opsForList().size(QUEUE_KEY);
        return size != null ? size : 0L;
    }
}