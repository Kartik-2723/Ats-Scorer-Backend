package com.resumeshaper.session;

import com.resumeshaper.resume.ResumeJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface GuestSessionRepository extends JpaRepository<GuestSession, String> {

    Optional<GuestSession> findByToken(String token);

    // Find expired unclaimed sessions (for scheduled cleanup)
    @Query("""
        SELECT s FROM GuestSession s
        WHERE s.expiresAt < :now
          AND s.claimedBy IS NULL
        """)
    List<GuestSession> findExpiredUnclaimed(@Param("now") OffsetDateTime now);

}