# Backend image for ECS Fargate (docs/aws-setup-guide.md step 9). It only copies the distribution Gradle built on
# the host, so it has no RUN step and builds for linux/arm64 on an x86 machine without emulation:
#   ./gradlew -Ptrainer.ui=false :server:installDist
#   docker buildx build --platform linux/arm64 -t <repo-uri>:latest --push server
FROM eclipse-temurin:17-jre
COPY build/install/server /app
# The heap may use most of the task's memory (the JVM default is a quarter of it).
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
# Media is kept on the task's local disk until an S3 storage exists, so it is lost on every redeploy.
ENV MEDIA_STORAGE_DIR=/tmp/media-storage MEDIA_DIR=/tmp/media
WORKDIR /tmp
USER 1000
EXPOSE 8080
ENTRYPOINT ["/app/bin/server"]
