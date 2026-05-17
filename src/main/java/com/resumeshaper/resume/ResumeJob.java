package com.resumeshaper.resume;

import com.resumeshaper.user.User;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "resume_jobs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ResumeJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Owner – either a logged-in user or a guest token
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "guest_token")
    private String guestToken;

    // Role
    @Column(name = "role_label", nullable = false)
    private String roleLabel;

    @Column(name = "role_category")
    private String roleCategory;

    @Column(name = "is_custom_role")
    @Builder.Default
    private boolean customRole = false;

    // Job description
    @Column(name = "jd_text", columnDefinition = "TEXT")
    private String jdText;

    // Files (S3 keys)
    @Column(name = "original_file_key", nullable = false)
    private String originalFileKey;

    @Column(name = "original_file_name", nullable = false)
    private String originalFileName;

    @Column(name = "shaped_file_key")
    private String shapedFileKey;

    // JSONB columns
    @Type(JsonBinaryType.class)
    @Column(name = "parsed_resume", columnDefinition = "jsonb")
    private Map<String, Object> parsedResume;

    @Type(JsonBinaryType.class)
    @Column(name = "shaped_resume", columnDefinition = "jsonb")
    private Map<String, Object> shapedResume;

    @Type(JsonBinaryType.class)
    @Column(name = "jd_analysis", columnDefinition = "jsonb")
    private Map<String, Object> jdAnalysis;

    @Type(JsonBinaryType.class)
    @Column(name = "ats_report", columnDefinition = "jsonb")
    private Map<String, Object> atsReport;

    // ATS scores
    @Column(name = "ats_score_before")
    private Integer atsScoreBefore;

    @Column(name = "ats_score_after")
    private Integer atsScoreAfter;

    // Pipeline status
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private JobStatus status = JobStatus.PENDING;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Builder.Default
    private boolean starred = false;

    // Versions
    @OneToMany(mappedBy = "job", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ResumeVersion> versions = List.of();

    // Timestamps
    @Column(name = "created_at", updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at")
    @Builder.Default
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    void onUpdate() { this.updatedAt = OffsetDateTime.now(); }
}
