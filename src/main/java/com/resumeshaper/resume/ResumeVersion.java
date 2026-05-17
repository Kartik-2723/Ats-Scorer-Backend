package com.resumeshaper.resume;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "resume_versions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ResumeVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false)
    private ResumeJob job;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Type(JsonBinaryType.class)
    @Column(name = "shaped_resume", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> shapedResume;

    @Column(name = "ats_score")
    private Integer atsScore;

    @Column(name = "shaped_file_key")
    private String shapedFileKey;

    @Column(name = "created_at", updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
