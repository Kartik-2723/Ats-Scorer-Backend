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
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
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

    // ── LaTeX pipeline fields ────────────────────────────────────────────────

    /**
     * Whether the user uploaded a PDF or raw LaTeX.
     * Null for jobs created via the old JSON-reshape pipeline.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "input_type", length = 10)
    private InputType inputType;

    /**
     * Original LaTeX source:
     *  - If inputType=LATEX: the raw uploaded .tex content
     *  - If inputType=PDF:   the LLM-converted LaTeX from PDF text extraction
     * Kept for audit and retry. Never sent to frontend.
     */
    @Column(name = "raw_latex", columnDefinition = "TEXT")
    private String rawLatex;

    /**
     * Final reshaped + Tectonic-validated LaTeX.
     * This is what the frontend editor receives and pre-renders.
     */
    @Column(name = "shaped_latex", columnDefinition = "TEXT")
    private String shapedLatex;

    /**
     * S3 key of the backend-compiled PDF.
     * Pre-signed URL generated on result fetch — frontend renders PDF
     * instantly from Redis cache (LatexCompilerService caches by SHA-256).
     */
    @Column(name = "compiled_pdf_key")
    private String compiledPdfKey;

    /**
     * How many Tectonic compile attempts have been made.
     * Max 3 (1 initial + 2 LLM fix retries). If all fail → FAILED.
     */
    @Column(name = "latex_compile_attempts")
    @Builder.Default
    private int latexCompileAttempts = 0;

    // ── Planner output fields (populated during RESHAPING_LATEX phase) ───────

    /**
     * Candidate career stage detected by the planner LLM.
     * Values: STUDENT_FRESHER | EARLY_CAREER | MID_SENIOR | CAREER_SWITCHER
     * Stored for analytics and optional frontend display.
     */
    @Column(name = "profile_type_detected", length = 30)
    private String profileTypeDetected;

    /**
     * JD keywords that are completely absent from the resume with no
     * injectable basis. Surfaced to the frontend after DONE as
     * "Add these keywords manually to improve your ATS score further".
     *
     * Stored as a JSON string array: ["Kubernetes", "CI/CD", "Terraform"]
     */
    @Type(JsonBinaryType.class)
    @Column(name = "ats_gap_keywords", columnDefinition = "jsonb")
    private List<String> atsGapKeywords;

    /**
     * Per-bullet content improvement suggestions from the planner.
     * Each entry: { section, bullet, suggestion }
     * Stored for optional "Improve your resume" panel in the frontend.
     *
     * Non-blocking — never affects LaTeX compilation or job status.
     */
    @Type(JsonBinaryType.class)
    @Column(name = "content_flags", columnDefinition = "jsonb")
    private List<Map<String, String>> contentFlags;

    // ── Old JSON-reshape pipeline fields (unchanged) ─────────────────────────

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

    // ATS scores (used by both pipelines)
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