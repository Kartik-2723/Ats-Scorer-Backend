package com.resumeshaper.latex;

public record LatexErrorResponse(
        String message,
        String compilerLog
) {}