# =================================================================================================
# Multi-stage build.
#
# Stage 1 resolves dependencies against pom.xml alone, so day-to-day source changes rebuild only the
# final COPY layers instead of re-downloading the Spring dependency tree. Stage 2 unpacks the boot
# jar into Spring Boot's layertools layers (dependencies / loader / snapshots / application) for the
# same reason at the image level: a code change invalidates the ~50KB application layer, not the
# ~40MB dependency layer.
#
# Tests are deliberately NOT run here — the image build should be reproducible packaging, and the
# test suite runs in its own CI job against both H2 and PostgreSQL where its output is actually
# readable.
#
#   docker build -t hotel-booking-service .
#   docker run --rm -p 8080:8080 hotel-booking-service                          # H2, demo data
#   docker compose --profile app up --build                                     # against Postgres
# =================================================================================================

FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace

# Dependency layer: only pom.xml and the wrapper, so this layer is cached until the pom changes.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN ./mvnw -B -q dependency:go-offline

COPY src src
RUN ./mvnw -B package -DskipTests \
    && java -Djarmode=layertools -jar target/hotel-booking-service-*.jar extract --destination target/extracted

# -------------------------------------------------------------------------------------------------

FROM eclipse-temurin:17-jre-jammy

# curl exists solely for the HEALTHCHECK; a JRE image ships neither curl nor wget.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system spring \
    && useradd --system --gid spring --no-create-home spring

USER spring:spring
WORKDIR /app

ARG EXTRACTED=/workspace/target/extracted
COPY --from=build ${EXTRACTED}/dependencies/          ./
COPY --from=build ${EXTRACTED}/spring-boot-loader/    ./
COPY --from=build ${EXTRACTED}/snapshot-dependencies/ ./
COPY --from=build ${EXTRACTED}/application/           ./

EXPOSE 8080

# Overridable at run time: -e JAVA_OPTS="-Xmx512m" / -e SPRING_PROFILES_ACTIVE=postgres,prod
ENV JAVA_OPTS=""

# The health signal is the actuator, not the process: a started JVM whose connection pool never came
# up must read as unhealthy, or an orchestrator will happily route traffic to it.
HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=5 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
