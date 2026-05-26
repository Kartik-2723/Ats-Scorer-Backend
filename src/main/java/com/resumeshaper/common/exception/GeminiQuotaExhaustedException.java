package com.resumeshaper.common.exception;

import org.springframework.http.HttpStatus;

public class GeminiQuotaExhaustedException extends AppException {
    public GeminiQuotaExhaustedException() {
        super("QUOTA_EXHAUSTED", HttpStatus.SERVICE_UNAVAILABLE);
    }
}