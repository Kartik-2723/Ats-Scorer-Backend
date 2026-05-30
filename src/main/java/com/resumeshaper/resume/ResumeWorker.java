package com.resumeshaper.resume;

import com.resumeshaper.latex.LatexReshapeOrchestrator;
import com.resumeshaper.llm.GeminiKeyPoolService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Worker pool that drains the Redis resume pipeline queue.
 *
 * Each worker thread:
 *   1. BLPOPs a job ID (blocks ≤30s, then loops — normal, not an error)
 *   2. Acquires the least-loaded Gemini API key from the pool
 *   3. Delegates runPipeline() to LatexReshapeOrchestrator
 *   4. Releases the key in a finally block regardless of outcome
 *
 * Concurrency per JVM = NUM_WORKERS (= workerExecutor.corePoolSize).
 * Horizontal scaling: run N containers → N × NUM_WORKERS concurrent pipelines.
 * BLPOP is atomic on Redis — multiple containers never receive the same job.
 */
@Slf4j
@Component
public class ResumeWorker {

    private static final int  NUM_WORKERS       = 4;   // must match workerExecutor.corePoolSize
    private static final long BLPOP_TIMEOUT_SEC = 30;  // 30s park, then loop — keeps threads alive

    private final ResumeQueueService       queueService;
    private final GeminiKeyPoolService     keyPool;
    private final LatexReshapeOrchestrator orchestrator;
    private final Executor                 workerExecutor;

    // Signals all worker loops to exit gracefully on shutdown
    private final AtomicBoolean running = new AtomicBoolean(true);

    public ResumeWorker(
            ResumeQueueService       queueService,
            GeminiKeyPoolService     keyPool,
            LatexReshapeOrchestrator orchestrator,
            @Qualifier("workerExecutor") Executor workerExecutor) {
        this.queueService   = queueService;
        this.keyPool        = keyPool;
        this.orchestrator   = orchestrator;
        this.workerExecutor = workerExecutor;
    }

    @PostConstruct
    public void startWorkers() {
        for (int i = 0; i < NUM_WORKERS; i++) {
            final int id = i;
            workerExecutor.execute(() -> workerLoop(id));
        }
        log.info("ResumeWorker: {} worker thread(s) started", NUM_WORKERS);
    }

    @PreDestroy
    public void stop() {
        log.info("ResumeWorker: shutdown signal — workers will exit after current BLPOP timeout");
        running.set(false);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Worker loop
    // ─────────────────────────────────────────────────────────────────────────

    private void workerLoop(int workerId) {
        log.info("ResumeWorker[{}]: started on thread '{}'", workerId, Thread.currentThread().getName());

        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                String jobIdStr = queueService.dequeue(BLPOP_TIMEOUT_SEC);
                if (jobIdStr == null) continue; // normal timeout — loop back

                UUID jobId = UUID.fromString(jobIdStr);
                log.info("ResumeWorker[{}]: dequeued job={} remainingDepth={}",
                        workerId, jobId, queueService.depth());

                processJob(workerId, jobId);

            } catch (Exception ex) {
                if (Thread.currentThread().isInterrupted()) break;

                // Log, backoff 1s, keep the worker alive — a single bad event shouldn't kill the loop
                log.error("ResumeWorker[{}]: unexpected error in worker loop — backing off 1s", workerId, ex);
                try { Thread.sleep(1_000); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }

        log.info("ResumeWorker[{}]: stopped", workerId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Per-job processing
    // ─────────────────────────────────────────────────────────────────────────

    private void processJob(int workerId, UUID jobId) {
        String keyId = null;
        try {
            keyId = keyPool.acquireKeyId(); // null in single-key mode — that's fine
            log.info("ResumeWorker[{}]: job={} using keyId={}", workerId, jobId, keyId != null ? keyId : "default");

            orchestrator.runPipeline(jobId, keyId);

        } catch (Exception ex) {
            // runPipeline marks the job FAILED internally; we just log at the worker level
            log.error("ResumeWorker[{}]: pipeline raised uncaught exception for job={}", workerId, jobId, ex);
        } finally {
            keyPool.releaseKeyId(keyId);
            log.debug("ResumeWorker[{}]: released keyId={}", workerId, keyId);
        }
    }
}