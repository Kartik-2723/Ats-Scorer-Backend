package com.resumeshaper.resume;

/**
 * Tracks what the user uploaded.
 * PDF   → extract text → LLM converts + reshapes → Tectonic compile
 * LATEX → LLM reshapes directly         → Tectonic compile
 */
public enum InputType {
    PDF,
    LATEX
}