# Stage 1: Build stage using JDK Alpine (lightweight)
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app

# Copy all Java source files
COPY *.java /app/

# Compile the project into the 'bin' directory
RUN mkdir bin && javac -d bin *.java

# Stage 2: Runtime stage using a minimal JRE/JDK Alpine image
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Standard labels help people identify the image on Docker Hub
LABEL org.opencontainers.image.title="CherryDB-Java"
LABEL org.opencontainers.image.description="A Redis clone in Java"

# Copy the compiled class files from the builder stage
COPY --from=builder /app/bin /app/bin

# Expose the default Redis port
EXPOSE 6379

# Run the compiled application
CMD ["java", "-cp", "bin", "Main"]
