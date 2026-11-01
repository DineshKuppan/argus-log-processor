# The Argus image: the Spring Boot jar on a JRE 17. Build the jar first:
#   mvn -P ci package -DskipTests && docker build -f deploy/Dockerfile -t argus:dev .
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY target/argus-log-processor-0.1.0.jar app.jar
RUN useradd --uid 10001 --no-create-home argus && mkdir /data && chown argus /data
USER 10001
EXPOSE 9000 9001
# Heap ≈60% of the container's memory limit, bounded off-heap (guardrail 5).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-XX:MaxDirectMemorySize=512m", \
            "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
