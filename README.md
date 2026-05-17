# Resume Shaper – Backend

Spring Boot 3 + Gemini 2.0 Flash + PostgreSQL + S3

---

## Architecture

```
Client (Guest / Auth)
       │
       ▼
┌─────────────────────────────────────────────────────┐
│               Spring Boot API (:8080)               │
│                                                     │
│  /api/guest      → GuestSessionController           │
│  /api/resume     → ResumeController                 │
│  /api/jd         → JDController                     │
│  /api/user       → UserController                   │
│  /api/auth       → AuthController                   │
│  /api/roles      → RoleController                   │
│                                                     │
│  LLM Pipeline (async)                               │
│    1. Parse resume (PDFBox / POI)                   │
│    2. Analyze JD  → Gemini 2.0 Flash                │
│    3. Gap analysis → Gemini                         │
│    4. Reshape     → Gemini                          │
│    5. ATS score   → Gemini + rule engine            │
│    6. Render DOCX → Apache POI → S3                 │
└─────────────────────────────────────────────────────┘
       │                   │
       ▼                   ▼
  PostgreSQL 16         AWS S3
  (Flyway schema)    (resume files)
       │
    Redis 7
  (guest sessions TTL)
```

---

## Quick Start (Docker)

### 1. Prerequisites
- Docker + Docker Compose
- Gemini API key → https://aistudio.google.com/app/apikey
- AWS S3 bucket + IAM credentials
- Google & GitHub OAuth2 apps

### 2. Set up environment

```bash
cp .env.example .env
# Edit .env with your actual values
```

Generate a JWT secret:
```bash
openssl rand -base64 64
```

### 3. Run

```bash
docker compose up --build
```

API is live at `http://localhost:8080`
Health check: `http://localhost:8080/actuator/health`

---

## API Reference

### Guest Flow (no auth)

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/guest/session` | Issue a guest token |
| `GET`  | `/api/roles` | Predefined role grid |
| `POST` | `/api/jd/analyze` | Analyze a job description |
| `POST` | `/api/resume/upload` | Upload resume + kick off pipeline |
| `GET`  | `/api/resume/status/{jobId}?guestToken=` | Poll pipeline status |
| `GET`  | `/api/resume/{jobId}?guestToken=` | Full result (scores, JSON, versions) |
| `GET`  | `/api/resume/download/{jobId}?guestToken=` | Pre-signed S3 download URL |
| `POST` | `/api/resume/edit` | Apply manual block edits |
| `POST` | `/api/resume/{jobId}/reshape` | Re-run AI pipeline |

### Auth Flow

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET`  | `/api/auth/oauth2/authorize/google` | Start Google OAuth2 |
| `GET`  | `/api/auth/oauth2/authorize/github` | Start GitHub OAuth2 |
| `POST` | `/api/auth/refresh` | Refresh JWT token |
| `GET`  | `/api/auth/me` | Validate token |
| `POST` | `/api/guest/claim` | Claim guest session → link to account |

### Authenticated

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET`  | `/api/user/me` | Current user profile |
| `GET`  | `/api/user/resumes?search=&starred=&page=&size=` | Resume history |
| `PATCH`| `/api/user/resumes/{jobId}/star` | Toggle star |

---

## Upload Flow Example

```bash
# 1. Get a guest token
curl -X POST http://localhost:8080/api/guest/session
# → { "guestToken": "abc123...", "expiresAt": "..." }

# 2. Upload resume (poll jobId)
curl -X POST http://localhost:8080/api/resume/upload \
  -F "file=@resume.pdf" \
  -F "roleLabel=Software Engineer" \
  -F "jdText=We are looking for..." \
  -F "guestToken=abc123..."
# → { "jobId": "uuid", "status": "PENDING" }

# 3. Poll until DONE
curl "http://localhost:8080/api/resume/status/{jobId}?guestToken=abc123..."
# → { "status": "RESHAPING", "currentStep": "Reshaping with AI" }

# 4. Get result
curl "http://localhost:8080/api/resume/{jobId}?guestToken=abc123..."
# → { atsScoreBefore: 41, atsScoreAfter: 84, shapedResume: {...}, ... }

# 5. Download
curl "http://localhost:8080/api/resume/download/{jobId}?guestToken=abc123..."
# → { "downloadUrl": "https://s3.presigned.url/..." }
```

---

## Pipeline Stages

```
PENDING → ANALYZING → RESHAPING → SCORING → DONE
                                          ↘ FAILED
```

| Stage | What happens |
|-------|-------------|
| ANALYZING | Gemini extracts skills/keywords from JD; gap analysis vs resume |
| RESHAPING | Gemini rewrites entire resume: 1-page, ATS-optimized, role-targeted |
| SCORING | Gemini + rule engine compute before/after ATS scores |
| DONE | DOCX rendered via Apache POI, uploaded to S3, pre-signed URL ready |

---

## Environment Variables

| Variable | Required | Description |
|----------|----------|-------------|
| `JWT_SECRET` | ✅ | Base64 encoded secret (min 64 chars) |
| `GEMINI_API_KEY` | ✅ | Google AI Studio API key |
| `S3_BUCKET` | ✅ | AWS S3 bucket name |
| `AWS_REGION` | ✅ | e.g. `ap-south-1` |
| `AWS_ACCESS_KEY_ID` | ✅ | AWS credentials |
| `AWS_SECRET_ACCESS_KEY` | ✅ | AWS credentials |
| `GOOGLE_CLIENT_ID` | ✅ | Google OAuth2 |
| `GOOGLE_CLIENT_SECRET` | ✅ | Google OAuth2 |
| `GITHUB_CLIENT_ID` | ✅ | GitHub OAuth2 |
| `GITHUB_CLIENT_SECRET` | ✅ | GitHub OAuth2 |
| `OAUTH2_REDIRECT_URI` | ✅ | Frontend callback URL |
| `CORS_ORIGINS` | ✅ | Comma-separated frontend origins |
| `DB_URL` | optional | Defaults to Docker postgres service |
| `REDIS_HOST` | optional | Defaults to Docker redis service |

---

## OAuth2 Setup

### Google
1. Go to https://console.cloud.google.com/apis/credentials
2. Create OAuth 2.0 Client ID (Web application)
3. Add authorized redirect URI: `http://localhost:8080/api/auth/oauth2/callback/google`

### GitHub
1. Go to https://github.com/settings/developers → New OAuth App
2. Authorization callback URL: `http://localhost:8080/api/auth/oauth2/callback/github`

---

## AWS S3 Setup

```bash
# Create bucket
aws s3 mb s3://your-resume-shaper-bucket --region ap-south-1

# Create IAM policy (attach to your IAM user/role)
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"],
    "Resource": "arn:aws:s3:::your-resume-shaper-bucket/*"
  }]
}
```

---

## Production Deployment

### Railway / Render / Fly.io
1. Push to GitHub
2. Connect repo → set all env vars from `.env.example`
3. Set start command: `java -jar target/*.jar` (or use Dockerfile)

### EC2 / VPS
```bash
docker compose -f docker-compose.yml up -d
```

### Health check
```
GET /actuator/health
```

---

## Project Structure

```
src/main/java/com/resumeshaper/
├── ResumeShaperApplication.java
├── config/
│   ├── AppProperties.java       # Typed config binding
│   ├── AppConfig.java           # Jackson, beans
│   ├── AsyncConfig.java         # LLM thread pool
│   └── SecurityConfig.java      # Spring Security + OAuth2 + JWT
├── auth/
│   ├── JwtTokenProvider.java    # Issue / validate JWT
│   ├── JwtAuthFilter.java       # Per-request filter
│   ├── OAuth2SuccessHandler.java# Post-login redirect with token
│   └── AuthController.java      # /refresh, /me
├── user/
│   ├── User.java                # Entity + UserDetails
│   ├── UserRepository.java
│   ├── UserService.java         # OAuth2 upsert, loadUserById
│   ├── UserController.java      # /me, /resumes
│   └── UserDto.java
├── session/
│   ├── GuestSession.java        # Entity
│   ├── GuestSessionRepository.java
│   ├── GuestSessionService.java # Create / validate / claim
│   └── GuestSessionController.java
├── resume/
│   ├── ResumeJob.java           # Entity
│   ├── ResumeVersion.java       # Entity
│   ├── JobStatus.java           # Enum
│   ├── ResumeJobRepository.java
│   ├── ResumeVersionRepository.java
│   ├── ResumeParserService.java # PDF/DOCX → structured JSON
│   ├── ResumeRendererService.java # JSON → DOCX (Apache POI)
│   ├── ResumeJobService.java    # Orchestrates upload/edit/reshape
│   ├── ResumeController.java    # All /api/resume/* endpoints
│   └── dto/ResumeDto.java       # All resume DTOs
├── jd/
│   ├── JDAnalyzerService.java
│   └── JDController.java
├── llm/
│   ├── GeminiApiClient.java     # HTTP → Gemini 2.0 Flash
│   ├── PromptBuilder.java       # All prompt templates
│   └── LLMOrchestrator.java     # 5-stage async pipeline
├── ats/
│   ├── ATSScoreService.java     # Rule-based scorer
│   └── ATSReport.java           # Score breakdown DTO
├── role/
│   └── RoleController.java      # Predefined role catalog
├── storage/
│   └── S3FileStorageService.java# Upload / presign / delete
└── common/
    ├── dto/ApiResponse.java     # Unified response wrapper
    └── exception/
        ├── AppException.java
        ├── ResourceNotFoundException.java
        └── GlobalExceptionHandler.java
```
