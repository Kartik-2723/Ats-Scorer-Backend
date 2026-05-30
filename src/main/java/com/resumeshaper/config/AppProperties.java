package com.resumeshaper.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Jwt jwt = new Jwt();
    private Guest guest = new Guest();
    private Gemini gemini = new Gemini();
    private Storage storage = new Storage();
    private Cors cors = new Cors();
    private OAuth2 oauth2 = new OAuth2();
    private Ats ats = new Ats();

    @Data
    public static class Jwt {
        private String secret;
        private long expiryMs = 86_400_000L;
        private long refreshExpiryMs = 604_800_000L;
    }

    @Data
    public static class Guest {
        private int sessionTtlHours = 24;
    }

    // Add inside your existing AppProperties.Gemini inner class:

    @Data
    public static class Gemini {
        private String   apiKey;          // single-key fallback
        private List<String> models;
        private String   baseUrl;
        private int      timeoutSeconds = 60;
        private int      maxOutputTokens = 8192;

        // ── NEW: multi-key pool ──────────────────────────────────────────────────
        private List<KeyEntry> keyPool = new ArrayList<>();
        @Data
        public static class KeyEntry {
            /** Logical name, e.g. "project-alpha". Used in Redis keys and logs. */
            private String id;
            /** Raw GCP API key string for this project. */
            private String apiKey;
            /** RPM quota limit for this project — used for weighted scheduling. */
            private int quota = 100;
        }
    }

    @Data
    public static class Storage {
        private S3 s3 = new S3();

        @Data
        public static class S3 {
            private String bucket;
            private String region = "ap-south-1";
            private int presignedUrlExpiryMinutes = 60;
        }
    }

    @Data
    public static class Cors {
        private List<String> allowedOrigins = List.of("http://localhost:5173");
    }

    @Data
    public static class OAuth2 {
        private String redirectUri = "http://localhost:5173/auth/callback";
    }

    @Data
    public static class Ats {
        private double keywordMatchWeight = 0.5;
        private double sectionCompletenessWeight = 0.3;
        private double formatQualityWeight = 0.2;
    }
}
