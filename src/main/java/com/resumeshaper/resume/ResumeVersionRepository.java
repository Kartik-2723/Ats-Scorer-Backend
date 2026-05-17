package com.resumeshaper.resume;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ResumeVersionRepository extends JpaRepository<ResumeVersion, UUID> {

    List<ResumeVersion> findByJobIdOrderByVersionNumberDesc(UUID jobId);

    int countByJobId(UUID jobId);
}
