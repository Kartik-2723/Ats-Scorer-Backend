package com.resumeshaper.storage;

import com.resumeshaper.common.exception.AppException;
import com.resumeshaper.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.UUID;

@Slf4j
@Service
public class S3FileStorageService {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final AppProperties.Storage.S3 cfg;

    public S3FileStorageService(AppProperties appProperties) {
        this.cfg = appProperties.getStorage().getS3();
        Region region = Region.of(cfg.getRegion());

        this.s3 = S3Client.builder()
                .region(region)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();

        this.presigner = S3Presigner.builder()
                .region(region)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    // ── Upload ───────────────────────────────────────────────

    /**
     * Upload a multipart file to S3.
     * @return the S3 object key
     */
    public String upload(MultipartFile file, String folder) {
        String key = folder + "/" + UUID.randomUUID() + "_" + sanitize(file.getOriginalFilename());
        try {
            s3.putObject(
                    PutObjectRequest.builder()
                            .bucket(cfg.getBucket())
                            .key(key)
                            .contentType(file.getContentType())
                            .contentLength(file.getSize())
                            .build(),
                    RequestBody.fromInputStream(file.getInputStream(), file.getSize())
            );
            log.info("Uploaded {} → s3://{}/{}", file.getOriginalFilename(), cfg.getBucket(), key);
            return key;
        } catch (IOException ex) {
            throw new AppException("Failed to upload file to S3", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Upload raw bytes (used for generated DOCX/PDF output).
     */
    public String uploadBytes(byte[] data, String key, String contentType) {
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(cfg.getBucket())
                        .key(key)
                        .contentType(contentType)
                        .contentLength((long) data.length)
                        .build(),
                RequestBody.fromBytes(data)
        );
        log.info("Uploaded bytes → s3://{}/{}", cfg.getBucket(), key);
        return key;
    }

    // ── Download ─────────────────────────────────────────────
    public InputStream download(String key) {
        return s3.getObject(GetObjectRequest.builder()
                .bucket(cfg.getBucket())
                .key(key)
                .build());
    }

    public byte[] downloadBytes(String key) {
        try (InputStream is = download(key)) {
            return is.readAllBytes();
        } catch (IOException ex) {
            log.error("S3 download failed for key={}", key, ex);
            throw new AppException("Failed to download file from storage", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Generate a pre-signed GET URL (expires in configured minutes).
     */
    public String presignedUrl(String key) {
        GetObjectPresignRequest req = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(cfg.getPresignedUrlExpiryMinutes()))
                .getObjectRequest(b -> b.bucket(cfg.getBucket()).key(key))
                .build();
        return presigner.presignGetObject(req).url().toString();
    }

    // ── Delete ───────────────────────────────────────────────

    public void delete(String key) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(cfg.getBucket())
                    .key(key)
                    .build());
            log.info("Deleted s3://{}/{}", cfg.getBucket(), key);
        } catch (Exception ex) {
            log.warn("Failed to delete S3 key {}: {}", key, ex.getMessage());
        }
    }

    // ── Helper ───────────────────────────────────────────────

    private String sanitize(String filename) {
        if (filename == null) return "upload";
        return filename.replaceAll("[^a-zA-Z0-9._-]", "_");
    }


}
