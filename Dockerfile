FROM maven:3.9.16-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -ntp -DskipTests dependency:go-offline
COPY src src
# Tests run as their own CI stage; skipping them here keeps image builds reproducible and fast.
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:25-jre-noble
RUN useradd --system --uid 10001 --create-home janus
WORKDIR /app
COPY --from=build /workspace/target/janus-*.jar app.jar
USER 10001
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=4 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080' || exit 1
# Exit on OOM rather than limping: an orchestrator restarting the process is far better
# than one that answers requests while unable to allocate.
#
# Compact object headers are a supported product option as of JDK 25 rather than the experiment they
# were in 24. Every object on the heap loses eight bytes of header, which on a workload that is
# mostly small short-lived objects (a proxied request's headers, its buffers, its cache entries) is
# read as more room inside the same MaxRAMPercentage rather than as a smaller number anywhere.
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-XX:+UseCompactObjectHeaders","-XX:+ExitOnOutOfMemoryError","-jar","/app/app.jar"]
