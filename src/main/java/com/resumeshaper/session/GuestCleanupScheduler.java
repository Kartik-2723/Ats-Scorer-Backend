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

        List<ResumeJob> expiredJobs = resumeJobRepository
                .findExpiredGuestJobs(OffsetDateTime.now().minusHours(24));

        // Fix #3: use AtomicInteger so tryDelete can increment from a lambda/helper
        AtomicInteger jobsDeleted  = new AtomicInteger(0);
        AtomicInteger filesDeleted = new AtomicInteger(0);

        for (ResumeJob job : expiredJobs) {
            // Fix #3: each delete is isolated — a single S3 failure no longer
            // aborts the loop or rolls back the entire @Transactional method
            tryDelete(job.getOriginalFileKey(), job, filesDeleted);
            tryDelete(job.getShapedFileKey(),   job, filesDeleted);

            resumeJobRepository.delete(job);
            jobsDeleted.incrementAndGet();
        }

        // Delete expired unclaimed guest sessions
        List<GuestSession> expiredSessions = guestSessionRepository
                .findExpiredUnclaimed(OffsetDateTime.now());
        guestSessionRepository.deleteAll(expiredSessions);

        log.info("Guest cleanup done: {} jobs deleted, {} S3 files deleted, {} sessions deleted",
                jobsDeleted.get(), filesDeleted.get(), expiredSessions.size());
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