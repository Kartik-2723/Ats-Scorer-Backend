package com.resumeshaper.latex;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class LatexCompilerService {

    private static final String CACHE_PREFIX = "latex:pdf:";
    private static final long   CACHE_TTL_HOURS = 24;

    @Qualifier("latexRedisTemplate")
    private final RedisTemplate<String, byte[]> redisTemplate;

    @Value("${latex.tectonic.path:tectonic}")
    private String tectonicPath;

    @Value("${latex.compile.timeout-seconds:60}")
    private int timeoutSeconds;

    /**
     * Compile LaTeX source to PDF bytes.
     * Results are cached in Redis by SHA-256 hash of the source for 24 hours.
     */
    public byte[] compile(String latexSource) {
        String cacheKey = CACHE_PREFIX + sha256(latexSource);

        // ── Cache hit ────────────────────────────────────────────────────────
        byte[] cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            log.debug("LaTeX cache hit for key {}", cacheKey);
            return cached;
        }

        // ── Compile ──────────────────────────────────────────────────────────
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("tectonic-");
            Path texFile = workDir.resolve("document.tex");
            Files.writeString(texFile, latexSource);

            ProcessBuilder pb = new ProcessBuilder(
                    tectonicPath,
                    "--outdir", workDir.toString(),
                    "--keep-logs",           // keep .log on failure for debugging
                    texFile.toString()
            );
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);   // stderr → stdout

            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new LatexCompileException("Compilation timed out after " + timeoutSeconds + "s", output);
            }

            if (process.exitValue() != 0) {
                throw new LatexCompileException("Tectonic exited with code " + process.exitValue(), output);
            }

            // Tectonic outputs <basename>.pdf in --outdir
            Path pdfFile = workDir.resolve("document.pdf");
            if (!Files.exists(pdfFile)) {
                throw new LatexCompileException("PDF not produced — check LaTeX source", output);
            }

            byte[] pdf = Files.readAllBytes(pdfFile);

            // ── Cache store ──────────────────────────────────────────────────
            redisTemplate.opsForValue().set(cacheKey, pdf, CACHE_TTL_HOURS, TimeUnit.HOURS);
            log.debug("LaTeX compiled and cached ({} bytes)", pdf.length);

            return pdf;

        } catch (LatexCompileException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LatexCompileException("Compilation interrupted", e.getMessage());
        } catch (IOException e) {
            throw new LatexCompileException("IO error during compilation", e.getMessage());
        } finally {
            // ── Cleanup temp directory ───────────────────────────────────────
            if (workDir != null) {
                deleteSilently(workDir);
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes());
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private void deleteSilently(Path dir) {
        try {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(java.io.File::delete);
            }
        } catch (IOException e) {
            log.warn("Failed to clean up temp dir {}: {}", dir, e.getMessage());
        }
    }
}