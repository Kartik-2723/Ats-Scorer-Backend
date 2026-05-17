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

# Non-root user for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

WORKDIR /app

# Copy layered JAR in optimal caching order
# (dependencies change least frequently → copy first)
COPY --from=builder /app/layers/dependencies/          ./
COPY --from=builder /app/layers/spring-boot-loader/    ./
COPY --from=builder /app/layers/snapshot-dependencies/ ./
COPY --from=builder /app/layers/application/           ./

EXPOSE 8080

# JVM tuning for containers
ENV JAVA_OPTS="\
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+UseG1GC \
  -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]