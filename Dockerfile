# Stage 1: Builder
# eclipse-temurin:24-jdk is available for linux/arm64
FROM eclipse-temurin:24-jdk AS builder

WORKDIR /build

# Copy gradle wrapper and build files first (layer caching)
COPY gradlew gradlew.bat ./
COPY gradle/ gradle/
COPY build.gradle.kts settings.gradle.kts ./

# Give execute permission to gradlew
RUN chmod +x gradlew

# Download dependencies (cached layer if build files unchanged)
RUN ./gradlew dependencies --no-daemon -q 2>/dev/null || true

# Copy source
COPY src/ src/

# Build fat jar, skip tests
RUN ./gradlew bootJar --no-daemon -x test

# Stage 2: Runtime
# eclipse-temurin:24-jre is available for linux/arm64
FROM eclipse-temurin:24-jre AS runtime

WORKDIR /app

# Copy fat jar from builder stage
COPY --from=builder /build/build/libs/*.jar app.jar

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
