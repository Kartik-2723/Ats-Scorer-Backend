package com.resumeshaper;

import com.resumeshaper.resume.JobStatus;
import com.resumeshaper.resume.ResumeJob;
import com.resumeshaper.resume.ResumeJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;

/**
 * Fires 5 reshape requests concurrently using real resume files.
 *
 * ── Where to put your resume files ──────────────────────────────────────────
 *
 *   src/test/resources/resumes/
 *     resume_1.pdf        ← drop your PDFs here
 *     resume_2.pdf
 *     resume_3.pdf
 *     resume_4.pdf
 *     resume_5.pdf
 *
 *   Supported formats : .pdf  |  .docx  |  .tex
 *   Priority per slot : .pdf → .docx → .tex → stub LaTeX fallback
 *   Mixed formats OK  : resume_1.pdf + resume_2.docx + resume_3.tex all work
 *
 * ── Prerequisites ────────────────────────────────────────────────────────────
 *   1. Postgres running  →  resumeshaper_test DB exists
 *   2. Redis running     →  localhost:6379
 *   3. tectonic on PATH  →  needed for DONE status (LaTeX compile step)
 *   4. Real Gemini keys  →  filled in src/test/resources/application-test.yml
 *
 * ── Run ──────────────────────────────────────────────────────────────────────
 *   mvn test -Dtest=ConcurrentReshapeTest -DDB_TEST_PASSWORD=your_pg_password
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConcurrentReshapeTest {

    private static final int RESUME_COUNT       = 5;
    private static final int SUBMIT_TIMEOUT_S   = 30;
    private static final int PIPELINE_TIMEOUT_M = 10;

    // Checked in order for each resume slot — first match wins
    private static final String[] EXTENSIONS = { ".pdf", ".docx", ".tex" };

    // Fallback stub — used only when no real file is found for a slot
    private static final String STUB_LATEX = """
            \\documentclass[11pt,a4paper]{article}
            \\usepackage[margin=1in]{geometry}
            \\begin{document}
            \\begin{center}
              {\\Large \\textbf{Test User %d}} \\\\[4pt]
              test%d@example.com
            \\end{center}
            \\section*{Experience}
            \\textbf{Senior Engineer} \\hfill 2020--2024 \\\\
            Designed REST APIs with Spring Boot and PostgreSQL.
            \\section*{Skills}
            Java, Spring Boot, PostgreSQL, Docker, Redis, AWS
            \\end{document}
            """;

    @Autowired MockMvc             mvc;
    @Autowired ResumeJobRepository jobRepository;
    @Autowired ObjectMapper        mapper;

    // ─────────────────────────────────────────────────────────────────────────
    // Main test
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Timeout(value = PIPELINE_TIMEOUT_M, unit = TimeUnit.MINUTES)
    void ConcurrentReshapesAllReachTerminalState() throws Exception {

        // ── 1. Create 5 guest tokens ──────────────────────────────────────────
        List<String> guestTokens = createGuestTokens(RESUME_COUNT);

        // ── 2. Submit 5 reshapes concurrently ─────────────────────────────────
        ExecutorService pool = Executors.newFixedThreadPool(RESUME_COUNT);

        List<Future<UUID>> futures = IntStream.range(0, RESUME_COUNT)
                .mapToObj(i -> pool.submit(() -> submitReshape(i, guestTokens.get(i))))
                .toList();

        pool.shutdown();
        pool.awaitTermination((long) SUBMIT_TIMEOUT_S * RESUME_COUNT, TimeUnit.SECONDS);

        List<UUID> jobIds = new ArrayList<>();
        for (Future<UUID> f : futures) {
            UUID id = f.get(SUBMIT_TIMEOUT_S, TimeUnit.SECONDS);
            assertThat(id).as("Submit should return a valid job ID").isNotNull();
            jobIds.add(id);
        }

        System.out.printf("%n✓ All %d jobs submitted: %s%n", RESUME_COUNT, jobIds);

        // ── 3. Assert all jobs have a status immediately after submission ──────
        jobIds.forEach(id -> {
            ResumeJob job = jobRepository.findById(id).orElseThrow(
                    () -> new AssertionError("Job " + id + " not found in DB after submission"));
            assertThat(job.getStatus())
                    .as("job=%s should have a status right after submission", id)
                    .isNotNull();
            System.out.printf("  job=%s  initial status=%s%n", id, job.getStatus());
        });

        // ── 4. Poll until every job reaches a terminal state ──────────────────
        List<JobStatus> terminal = List.of(JobStatus.DONE, JobStatus.FAILED);

        await()
                .pollInterval(Duration.ofSeconds(10))
                .atMost(Duration.ofMinutes(PIPELINE_TIMEOUT_M))
                .untilAsserted(() -> {
                    List<ResumeJob> jobs = jobRepository.findAllById(jobIds);
                    assertThat(jobs).hasSize(RESUME_COUNT);
                    jobs.forEach(job ->
                            assertThat(terminal)
                                    .as("job=%s stuck in non-terminal status=%s",
                                            job.getId(), job.getStatus())
                                    .contains(job.getStatus())
                    );
                });

        // ── 5. Print results ──────────────────────────────────────────────────
        List<ResumeJob> completed = jobRepository.findAllById(jobIds);

        long done   = completed.stream().filter(j -> j.getStatus() == JobStatus.DONE).count();
        long failed = completed.stream().filter(j -> j.getStatus() == JobStatus.FAILED).count();

        System.out.println("\n── Concurrent reshape results ──────────────────────────────────────────");
        completed.forEach(j -> System.out.printf(
                "  job=%-36s  status=%-6s  scoreBefore=%-3s  scoreAfter=%-3s  attempts=%s  error=%s%n",
                j.getId(),
                j.getStatus(),
                j.getAtsScoreBefore(),
                j.getAtsScoreAfter(),
                j.getLatexCompileAttempts(),
                j.getErrorMessage() != null
                        ? j.getErrorMessage().substring(0, Math.min(80, j.getErrorMessage().length()))
                        : "—"
        ));
        System.out.printf("  DONE=%d  FAILED=%d  TOTAL=%d%n%n", done, failed, RESUME_COUNT);

        assertThat(done)
                .as("At least 1 of %d jobs should complete successfully", RESUME_COUNT)
                .isGreaterThanOrEqualTo(1);

        assertThat(done + failed)
                .as("All %d jobs should reach a terminal state", RESUME_COUNT)
                .isEqualTo(RESUME_COUNT);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private List<String> createGuestTokens(int count) throws Exception {
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            MvcResult res = mvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                    .post("/api/guest/session")
                                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andReturn();

            JsonNode body = mapper.readTree(res.getResponse().getContentAsString());
            String token  = body.at("/data/guestToken").asText();

            assertThat(token)
                    .as("Guest token %d should not be blank", i)
                    .isNotBlank();

            tokens.add(token);
            System.out.printf("  → Guest token[%d] created%n", i);
        }
        return tokens;
    }

    /**
     * Submits one reshape and returns the assigned jobId.
     * Content-type is set automatically based on the file extension found.
     */
    private UUID submitReshape(int index, String guestToken) throws Exception {
        ResumeFile resume = loadResumeFile(index + 1);

        MockMultipartFile file = new MockMultipartFile(
                "file",
                resume.filename(),
                resume.contentType(),
                resume.bytes()
        );

        MvcResult result = mvc.perform(
                        multipart("/api/latex/reshape")
                                .file(file)
                                .param("roleLabel",  "Backend Developer")
                                .param("jdText",
                                        "Java Spring Boot REST APIs PostgreSQL Docker Redis AWS microservices")
                                .param("guestToken", guestToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body   = mapper.readTree(result.getResponse().getContentAsString());
        String jobIdStr = body.at("/data/jobId").asText();

        assertThat(jobIdStr)
                .as("Response for resume[%d] should contain a jobId", index)
                .isNotBlank();

        System.out.printf("  → Submitted resume[%d] (%s, %s) — jobId=%s%n",
                index, resume.filename(), resume.contentType(), jobIdStr);

        return UUID.fromString(jobIdStr);
    }

    /**
     * Tries each extension in order: .pdf → .docx → .tex
     * Falls back to stub LaTeX if nothing is found.
     *
     * Place files at:  src/test/resources/resumes/resume_{n}.pdf
     *                  src/test/resources/resumes/resume_{n}.docx
     *                  src/test/resources/resumes/resume_{n}.tex
     */
    private ResumeFile loadResumeFile(int n) throws IOException {
        for (String ext : EXTENSIONS) {
            String path = "/resumes/resume_" + n + ext;
            try (InputStream is = getClass().getResourceAsStream(path)) {
                if (is != null) {
                    byte[] bytes = is.readAllBytes();
                    System.out.printf("  [INFO] Loaded real resume: classpath:%s (%d bytes)%n",
                            path, bytes.length);
                    return new ResumeFile("resume_" + n + ext, contentTypeFor(ext), bytes);
                }
            }
        }

        // Nothing found — fall back to stub LaTeX
        System.out.printf("  [WARN] No resume file found for slot %d " +
                "(tried .pdf / .docx / .tex) — using stub LaTeX%n", n);
        byte[] bytes = STUB_LATEX.formatted(n, n).getBytes(StandardCharsets.UTF_8);
        return new ResumeFile("resume_" + n + ".tex", "text/plain", bytes);
    }

    private String contentTypeFor(String ext) {
        return switch (ext) {
            case ".pdf"  -> "application/pdf";
            case ".docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case ".tex"  -> "text/plain";
            default      -> "application/octet-stream";
        };
    }

    /** Holds a loaded resume file ready to submit. */
    private record ResumeFile(String filename, String contentType, byte[] bytes) {}
}