package com.resumeshaper.ats;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ATSReport {
    private int          overallScore;
    private int          keywordScore;
    private int          sectionScore;
    private int          formatScore;
    private int          verbScore;          // FIX 4: new dimension
    private List<String> matchedKeywords;
    private List<String> missingKeywords;
    private List<String> presentSections;
    private List<String> formatIssues;       // includes weak verb warnings
}