package com.resumeshaper.resume.dto;

import com.resumeshaper.resume.JobStatus;
import com.resumeshaper.resume.ResumeJob;
import com.resumeshaper.resume.ResumeVersion;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// ── Upload request ────────────────────────────────────────────
public class ResumeDto {

    public record UploadRequest(
            String roleLabel,
            String roleCategory,
            boolean customRole,
            String jdText,       // optional
            String guestToken    // null if authenticated
    ) {}

    // ── Job summary (dashboard card) ─────────────────────────
    public record ResumeJobSummaryDto(
            UUID          id,
            String        roleLabel,
            String        roleCategory,
            Integer       atsScoreBefore,
            Integer       atsScoreAfter,
            JobStatus     status,
            boolean       starred,
            OffsetDateTime createdAt,
            int           versionCount
    ) {
        public static ResumeJobSummaryDto from(ResumeJob j) {
            return new ResumeJobSummaryDto(
                    j.getId(), j.getRoleLabel(), j.getRoleCategory(),
                    j.getAtsScoreBefore(), j.getAtsScoreAfter(),
                    j.getStatus(), j.isStarred(), j.getCreatedAt(),
                    j.getVersions().size()
            );
        }
    }

    // ── Full job detail ──────────────────────────────────────
    public record ResumeJobDetailDto(
            UUID               id,
            String             roleLabel,
            String             roleCategory,
            String             jdText,
            Map<String,Object> parsedResume,
            Map<String,Object> shapedResume,
            Map<String,Object> jdAnalysis,
            Map<String,Object> atsReport,
            Integer            atsScoreBefore,
            Integer            atsScoreAfter,
            JobStatus          status,
            boolean            starred,
            String             downloadUrl,   // pre-signed S3 URL
            OffsetDateTime     createdAt,
            List<VersionSummaryDto> versions
    ) {
        public static ResumeJobDetailDto from(ResumeJob j, String downloadUrl,
                                              List<VersionSummaryDto> versions) {
            return new ResumeJobDetailDto(
                    j.getId(), j.getRoleLabel(), j.getRoleCategory(), j.getJdText(),
                    j.getParsedResume(), j.getShapedResume(), j.getJdAnalysis(), j.getAtsReport(),
                    j.getAtsScoreBefore(), j.getAtsScoreAfter(),
                    j.getStatus(), j.isStarred(), downloadUrl,
                    j.getCreatedAt(), versions
            );
        }
    }

    // ── Version summary ──────────────────────────────────────
    public record VersionSummaryDto(
            UUID            id,
            int             versionNumber,
            Integer         atsScore,
            String          downloadUrl,
            OffsetDateTime  createdAt
    ) {
        public static VersionSummaryDto from(ResumeVersion v, String url) {
            return new VersionSummaryDto(
                    v.getId(), v.getVersionNumber(), v.getAtsScore(), url, v.getCreatedAt());
        }
    }

    // ── Process / re-shape request ───────────────────────────
    public record ProcessRequest(
            UUID   jobId,
            String guestToken  // null if authenticated
    ) {}

    // ── Manual block edit ────────────────────────────────────
    public record BlockEditRequest(
            UUID               jobId,
            Map<String,Object> shapedResume,
            String             guestToken
    ) {}

    // ── Status polling response ──────────────────────────────
    public record StatusResponse(
            UUID      jobId,
            JobStatus status,
            String    currentStep,   // human-readable label
            Integer   atsScoreAfter
    ) {}
}
