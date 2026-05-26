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
}