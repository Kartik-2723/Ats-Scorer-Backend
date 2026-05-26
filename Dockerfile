# ─────────────────────────────────────────────────────────────
# Stage 1: Build
# ─────────────────────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder

WORKDIR /app

# Cache Maven deps first (layer caching)
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Build the app
COPY src ./src
RUN mvn package -DskipTests -q

# Extract layered JAR
RUN java -Djarmode=layertools -jar target/*.jar extract --destination /app/layers

# ─────────────────────────────────────────────────────────────
# Stage 2: Runtime
# ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

# ── Install Tectonic (musl binary — required for Alpine) ──────
# Must run as root before we switch to appuser
ARG TECTONIC_VERSION=0.15.0
RUN apk add --no-cache curl \
    && curl -fsSL \
       "https://github.com/tectonic-typesetting/tectonic/releases/download/tectonic%40${TECTONIC_VERSION}/tectonic-${TECTONIC_VERSION}-x86_64-unknown-linux-musl.tar.gz" \
       | tar xz -C /usr/local/bin \
    && chmod +x /usr/local/bin/tectonic \
    && apk del curl \
    && tectonic --version

# ── Non-root user ─────────────────────────────────────────────
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Tectonic downloads LaTeX packages on first compile.
# Give appuser a writable cache directory that persists in the container.
RUN mkdir -p /home/appuser/.cache/tectonic \
    && chown -R appuser:appgroup /home/appuser/.cache

USER appuser

WORKDIR /app

# Copy layered JAR in optimal caching order
COPY --from=builder /app/layers/dependencies/          ./
COPY --from=builder /app/layers/spring-boot-loader/    ./
COPY --from=builder /app/layers/snapshot-dependencies/ ./
COPY --from=builder /app/layers/application/           ./

EXPOSE 8080

# Tell Tectonic where to store its package cache
ENV XDG_CACHE_HOME=/home/appuser/.cache

# JVM tuning for containers
ENV JAVA_OPTS="\
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+UseG1GC \
  -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]