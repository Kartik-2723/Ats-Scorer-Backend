package com.resumeshaper.user;

import com.resumeshaper.common.dto.ApiResponse;
import com.resumeshaper.resume.ResumeJobService;
import com.resumeshaper.resume.dto.ResumeDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService      userService;
    private final ResumeJobService resumeJobService;

    /** GET /api/user/me */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserDto>> me(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.success(UserDto.from(user)));
    }

    /** GET /api/user/resumes */
    @GetMapping("/resumes")
    public ResponseEntity<ApiResponse<Page<ResumeDto.ResumeJobSummaryDto>>> myResumes(
            @AuthenticationPrincipal User user,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean starred,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {

        Page<ResumeDto.ResumeJobSummaryDto> page =
                resumeJobService.findByUser(user.getId(), search, starred, pageable);
        return ResponseEntity.ok(ApiResponse.success(page));
    }

    /** PATCH /api/user/resumes/{jobId}/star */
    @PatchMapping("/resumes/{jobId}/star")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> toggleStar(
            @AuthenticationPrincipal User user,
            @PathVariable String jobId) {

        boolean starred = resumeJobService.toggleStar(user.getId(), jobId);
        return ResponseEntity.ok(ApiResponse.success(Map.of("starred", starred)));
    }

    /** DELETE /api/user/resumes/{jobId} — Priority 4 */
    @DeleteMapping("/resumes/{jobId}")
    public ResponseEntity<ApiResponse<Map<String, String>>> deleteResume(
            @AuthenticationPrincipal User user,
            @PathVariable UUID jobId) {

        resumeJobService.deleteResume(jobId, user.getId());
        return ResponseEntity.ok(ApiResponse.success(Map.of("message", "Resume deleted successfully")));
    }
}