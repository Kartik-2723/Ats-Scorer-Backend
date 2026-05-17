package com.resumeshaper.role;

import com.resumeshaper.common.dto.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * GET /api/roles  – returns the predefined role grid for the upload flow.
 * Public endpoint, no auth required.
 */
@RestController
@RequestMapping("/api/roles")
public class RoleController {

    private static final List<Map<String, String>> ROLES = List.of(
        role("Software Engineer",       "Engineering",  "💻"),
        role("Frontend Developer",       "Engineering",  "🎨"),
        role("Backend Developer",        "Engineering",  "⚙️"),
        role("Full Stack Developer",     "Engineering",  "🔧"),
        role("DevOps / SRE",            "Engineering",  "🚀"),
        role("Data Engineer",            "Data",         "🔢"),
        role("Data Scientist",           "Data",         "📊"),
        role("ML Engineer",              "AI/ML",        "🤖"),
        role("AI/LLM Engineer",          "AI/ML",        "🧠"),
        role("Product Manager",          "Product",      "📋"),
        role("Product Designer / UX",    "Design",       "✏️"),
        role("UI Designer",              "Design",       "🖌️"),
        role("Mobile Developer (iOS)",   "Engineering",  "📱"),
        role("Mobile Developer (Android)","Engineering", "🤖"),
        role("QA / SDET",               "Engineering",  "🧪"),
        role("Security Engineer",        "Engineering",  "🔐"),
        role("Cloud Architect",          "Engineering",  "☁️"),
        role("Business Analyst",         "Business",     "📈"),
        role("Marketing Manager",        "Marketing",    "📣"),
        role("Sales Engineer",           "Sales",        "🤝"),
        role("Finance Analyst",          "Finance",      "💰"),
        role("HR Manager",               "HR",           "👥"),
        role("Technical Writer",         "Content",      "✍️"),
        role("Scrum Master / Agile Coach","Product",     "🏃")
    );

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, String>>>> list() {
        return ResponseEntity.ok(ApiResponse.success(ROLES));
    }

    private static Map<String, String> role(String label, String category, String emoji) {
        return Map.of("label", label, "category", category, "emoji", emoji);
    }
}
