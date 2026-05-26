package com.resumeshaper.latex;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class LatexRequest {

    @NotBlank(message = "LaTeX source must not be blank")
    @Size(max = 100_000, message = "LaTeX source must not exceed 100 000 characters")
    private String latex;
}