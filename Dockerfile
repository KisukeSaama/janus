FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -ntp -DskipTests dependency:go-offline
COPY src src
# Tests run as their own CI stage; skipping them here keeps image builds reproducible and fast.
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre-jammy
RUN useradd --system --uid 10001 --create-home janus
WORKDIR /app
COPY --from=build /workspace/target/janus-*.jar app.jar
USER 10001
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=4 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080' || exit 1
# Exit on OOM rather than limping: an orchestrator restarting the process is far better
# than one that answers requests while unable to allocate.
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-XX:+ExitOnOutOfMemoryError","-jar","/app/app.jar"]
