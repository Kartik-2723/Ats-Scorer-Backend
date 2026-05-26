package com.resumeshaper.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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

    @Data
    public static class Gemini {
        private String apiKey;
        private List<String> models = List.of("gemini-2.5-flash");  // String model → List<String> models
        private String baseUrl = "https://generativelanguage.googleapis.com/v1beta";
        private int timeoutSeconds = 60;
        private int maxOutputTokens = 8192;
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
