package com.resumeshaper.resume;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ResumeJobRepository extends JpaRepository<ResumeJob, UUID> {

    @Query("""
        SELECT j FROM ResumeJob j
        WHERE j.user.id = :userId
          AND (:search IS NULL OR LOWER(j.roleLabel) LIKE LOWER(CONCAT('%', :search, '%')))
          AND (:starred IS NULL OR j.starred = :starred)
        ORDER BY j.createdAt DESC
        """)
    Page<ResumeJob> findByUserId(@Param("userId") UUID userId,
                                 @Param("search") String search,
                                 @Param("starred") Boolean starred,
                                 Pageable pageable);

    Optional<ResumeJob> findByIdAndUserId(UUID id, UUID userId);

    Optional<ResumeJob> findByIdAndGuestToken(UUID id, String guestToken);

    // Count versions to generate next version number
    long countByIdAndVersionsIsNotNull(UUID jobId);
}
