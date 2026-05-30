package com.resumeshaper.session;

import com.resumeshaper.resume.ResumeJob;
import com.resumeshaper.resume.ResumeJobRepository;
import com.resumeshaper.storage.S3FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
public class GuestCleanupScheduler {

    private final GuestSessionRepository guestSessionRepository;
    private final ResumeJobRepository    resumeJobRepository;
    private final S3FileStorageService   storage;

    // Runs every hour
    @Scheduled(fixedRateString = "PT1H")
    @Transactional
    public void cleanupExpiredGuestData() {
        log.info("Running guest cleanup scheduler...");

        // 1. Find expired sessions first — this is the source of truth
        List<GuestSession> expiredSessions = guestSessionRepository
                .findExpiredUnclaimed(OffsetDateTime.now());

        if (expiredSessions.isEmpty()) {
            log.info("Guest cleanup done: nothing to clean up");
            return;
        }

        List<String> tokens = expiredSessions.stream()
                .map(GuestSession::getToken)
                .toList();

        // 2. Load and delete all jobs belonging to those sessions
        List<ResumeJob> jobs = resumeJobRepository.findAllByGuestTokenIn(tokens);

        AtomicInteger filesDeleted = new AtomicInteger(0);
        for (ResumeJob job : jobs) {
            tryDelete(job.getOriginalFileKey(), job, filesDeleted);
            tryDelete(job.getShapedFileKey(),   job, filesDeleted);
        }
        resumeJobRepository.deleteAll(jobs);          // jobs gone before sessions

        // 3. Now safe to delete the sessions
        guestSessionRepository.deleteAll(expiredSessions);

        log.info("Guest cleanup done: {} jobs deleted, {} S3 files deleted, {} sessions deleted",
                jobs.size(), filesDeleted.get(), expiredSessions.size());
    }

    // ── Helpers ───────────────────────────────────────────────

    private void tryDelete(String key, ResumeJob job, AtomicInteger counter) {
        if (key == null) return;
        try {
            storage.delete(key);
            counter.incrementAndGet();
        } catch (Exception ex) {
            // Log and continue — a stale S3 file is not worth aborting the
            // whole cleanup run or rolling back DB deletes for other jobs
            log.warn("S3 delete failed during guest cleanup for job={} key={}: {}",
                    job.getId(), key, ex.getMessage());
        }
    }
}