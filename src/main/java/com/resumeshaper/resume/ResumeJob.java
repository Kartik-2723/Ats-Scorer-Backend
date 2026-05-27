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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "guest_token")
    private String guestToken;

    @Column(name = "role_label", nullable = false)
    private String roleLabel;

    @Column(name = "role_category")
    private String roleCategory;

    @Column(name = "is_custom_role")
    @Builder.Default
    private boolean customRole = false;

    @Column(name = "jd_text", columnDefinition = "TEXT")
    private String jdText;

    @Column(name = "original_file_key", nullable = false)
    private String originalFileKey;

    @Column(name = "original_file_name", nullable = false)
    private String originalFileName;

    @Column(name = "shaped_file_key")
    private String shapedFileKey;

    // ── LaTeX pipeline fields ────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "input_type", length = 10)
    private InputType inputType;

    @Column(name = "raw_latex", columnDefinition = "TEXT")
    private String rawLatex;

    @Column(name = "shaped_latex", columnDefinition = "TEXT")
    private String shapedLatex;

    @Column(name = "compiled_pdf_key")
    private String compiledPdfKey;

    @Column(name = "latex_compile_attempts")
    @Builder.Default
    private int latexCompileAttempts = 0;

    // ── Planner output fields ────────────────────────────────────────────────

    /**
     * Career stage detected by the planner.
     * STUDENT_FRESHER | EARLY_CAREER | MID_SENIOR | CAREER_SWITCHER
     */
    @Column(name = "profile_type_detected", length = 30)
    private String profileTypeDetected;

    /**
     * JD keywords absent from resume with no injectable basis.
     * Surfaced to frontend as "Add these manually" suggestions.
     */
    @Type(JsonBinaryType.class)
    @Column(name = "ats_gap_keywords", columnDefinition = "jsonb")
    private List<String> atsGapKeywords;

    /**
     * Per-bullet content improvement suggestions from the planner.
     * Each entry: { section, bullet, suggestion }
     */
    @Type(JsonBinaryType.class)
    @Column(name = "content_flags", columnDefinition = "jsonb")
    private List<Map<String, String>> contentFlags;

    // ── ATS scoring fields ───────────────────────────────────────────────────

    /**
     * FIX 4: Full rules-based ATS breakdown for the ORIGINAL resume.
     * Stored as JSONB: { overallScore, keywordScore, sectionScore,
     *                    formatScore, verbScore, matchedKeywords,
     *                    missingKeywords, presentSections, formatIssues }
     * Surfaced to frontend as a score breakdown chart.
     * Requires migration: V4__add_ats_report_column.sql
     */
    @Type(JsonBinaryType.class)
    @Column(name = "ats_report", columnDefinition = "jsonb")
    private Map<String, Object> atsReport;

    // ── Old JSON-reshape pipeline fields ─────────────────────────────────────

    @Type(JsonBinaryType.class)
    @Column(name = "parsed_resume", columnDefinition = "jsonb")
    private Map<String, Object> parsedResume;

    @Type(JsonBinaryType.class)
    @Column(name = "shaped_resume", columnDefinition = "jsonb")
    private Map<String, Object> shapedResume;

    @Type(JsonBinaryType.class)
    @Column(name = "jd_analysis", columnDefinition = "jsonb")
    private Map<String, Object> jdAnalysis;

    // ATS scores (both pipelines)
    @Column(name = "ats_score_before")
    private Integer atsScoreBefore;

    @Column(name = "ats_score_after")
    private Integer atsScoreAfter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private JobStatus status = JobStatus.PENDING;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Builder.Default
    private boolean starred = false;

    @OneToMany(mappedBy = "job", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ResumeVersion> versions = List.of();

    @Column(name = "created_at", updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at")
    @Builder.Default
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    void onUpdate() { this.updatedAt = OffsetDateTime.now(); }
}