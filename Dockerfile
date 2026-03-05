# ─────────────────────────────────────────────
# Stage 1: Build
# ─────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /app

COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src ./src
RUN mvn package -DskipTests -q

# ─────────────────────────────────────────────
# Stage 2: Runtime
# ─────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

# Install keytool (part of JDK, available in the JRE image via the JDK tools)
# Generate a self-signed SSL certificate for HTTPS
# In production: mount a real certificate via Docker secret or volume instead
RUN mkdir -p /app/ssl && \
    keytool -genkeypair \
        -alias certmonitor \
        -keyalg RSA \
        -keysize 2048 \
        -validity 365 \
        -keystore /app/ssl/certmonitor.jks \
        -storepass certmonitor_ssl \
        -keypass certmonitor_ssl \
        -dname "CN=certmonitor,OU=CertMonitor,O=CodeCatalyst,L=Unknown,ST=Unknown,C=US" \
        -noprompt

# Non-root user for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Give appuser ownership of ssl dir and app dir
RUN chown -R appuser:appgroup /app
USER appuser

WORKDIR /app

COPY --from=builder /app/target/*.jar app.jar

EXPOSE 443

ENTRYPOINT ["java", "-jar", "-Dspring.profiles.active=prod", "app.jar"]
