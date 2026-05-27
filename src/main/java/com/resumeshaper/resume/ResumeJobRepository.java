package com.resumeshaper.resume;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ResumeJobRepository extends JpaRepository<ResumeJob, UUID>,
        JpaSpecificationExecutor<ResumeJob> {

    Optional<ResumeJob> findByIdAndUserId(UUID id, UUID userId);

    Optional<ResumeJob> findByIdAndGuestToken(UUID id, String guestToken);

    // Count user's resumes (for 5 limit check)
    long countByUserId(UUID userId);

    // Find all guest jobs for a token (for cleanup on new upload)
    List<ResumeJob> findAllByGuestToken(String guestToken);

    // Find expired guest jobs (for scheduled cleanup)
    @Query("""
        SELECT j FROM ResumeJob j
        WHERE j.guestToken IS NOT NULL
          AND j.user IS NULL
          AND j.createdAt < :expiry
        """)
    List<ResumeJob> findExpiredGuestJobs(@Param("expiry") OffsetDateTime expiry);

    // Find guest jobs that belong to claimed sessions (for transition)
    @Query("""
        SELECT j FROM ResumeJob j
        WHERE j.guestToken = :token
          AND j.user IS NULL
        """)
    List<ResumeJob> findUnclaimedByGuestToken(@Param("token") String token);

    // ── Idempotency guards (time-bounded) ────────────────────────────────────
    // Time-bounded so stuck jobs from crashed/failed pipeline runs never
    // permanently block new submissions. Window = 30 minutes: long enough to
    // cover the slowest legitimate pipeline run, short enough to self-heal.
    //
    // Without the time window, jobs stuck in PENDING (e.g. from the pre-fix
    // race condition) would block the user indefinitely until manually cleaned.

    /**
     * Returns true if the guest session has an active job created within the
     * last N minutes (caller passes the since timestamp).
     * Stuck jobs older than the window are ignored — cleaned by the scheduled task.
     */
    @Query("""
        SELECT COUNT(j) > 0 FROM ResumeJob j
        WHERE j.guestToken = :guestToken
          AND j.status IN :statuses
          AND j.createdAt >= :since
        """)
    boolean hasActiveGuestJob(@Param("guestToken") String guestToken,
                              @Param("statuses")   List<JobStatus> statuses,
                              @Param("since")      OffsetDateTime since);

    /**
     * Returns true if the authenticated user has an active job created within
     * the last N minutes (caller passes the since timestamp).
     */
    @Query("""
        SELECT COUNT(j) > 0 FROM ResumeJob j
        WHERE j.user.id = :userId
          AND j.status IN :statuses
          AND j.createdAt >= :since
        """)
    boolean hasActiveUserJob(@Param("userId")   UUID userId,
                             @Param("statuses") List<JobStatus> statuses,
                             @Param("since")    OffsetDateTime since);
}