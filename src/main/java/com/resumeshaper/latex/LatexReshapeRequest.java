package com.resumeshaper.latex;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Multipart form fields for POST /api/latex/reshape.
 *
 * The actual file is received as MultipartFile in the controller —
 * this DTO carries the metadata fields only.
 *
 * Either pdf OR latex must be provided (enforced in controller).
 */
@Getter
@Setter
@NoArgsConstructor
public class LatexReshapeRequest {

    /**
     * Target role label (e.g. "Backend Developer", "ML Engineer").
     * Used to pick the default JD and drive section prioritisation.
     */
    @NotBlank(message = "roleLabel is required")
    @Size(max = 255)
    private String roleLabel;

    /** Optional role category (e.g. "Engineering", "Data"). */
    @Size(max = 100)
    private String roleCategory;

    /**
     * True when the user typed a custom role not in the predefined list.
     * When true, jdText is the sole source for JD analysis.
     */
    private boolean customRole = false;

    /**
     * Optional job description text.
     * If provided → merged 50/50 with system default JD for the role.
     * If absent   → system default JD is used alone.
     */
    @Size(max = 20_000, message = "JD text must not exceed 20 000 characters")
    private String jdText;

    /**
     * Guest session token. Null for authenticated users.
     * One of guestToken or a valid JWT must be present.
     */
    private String guestToken;
}