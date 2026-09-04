# Multi-stage so the runtime image carries a JRE and the application, and none of the
# build toolchain. Tests are not run here: CI runs `./mvnw verify` with the coverage gate
# before it ever builds an image, and repeating them would double the pipeline for no
# extra signal.
FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /build

# Wrapper and POM first: this layer is cached until a dependency actually changes, so a
# source-only edit does not re-resolve the whole tree.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline

COPY src/ src/
RUN ./mvnw -B -q -DskipTests package \
    && cp target/ledger-node-*.jar application.jar \
    # Split the jar into layers that change at different rates. Dependencies are ~120 jars
    # and change rarely; application code changes every commit. Separating them means a
    # release pushes kilobytes instead of the whole 80MB image.
    && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:21-jre-jammy AS runtime

# A non-root, no-login account. Nothing in the container needs to write to disk or to be
# logged into, so the process gets neither capability. Files stay owned by root and
# read-only to the runtime user, so a compromised process cannot rewrite its own code.
RUN groupadd --system --gid 10001 ledger \
    && useradd --system --uid 10001 --gid ledger --no-create-home --shell /usr/sbin/nologin ledger

WORKDIR /app

# Ordered slowest-changing first, so Docker reuses the dependency layer across releases.
COPY --from=build --chown=root:root /build/extracted/dependencies/ ./
COPY --from=build --chown=root:root /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=root:root /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=root:root /build/extracted/application/ ./

USER ledger:ledger

# 8087 is the API, 55437 is telemetry. Expose both; a network policy decides who reaches
# which, and only /actuator/health is reachable without a token.
EXPOSE 8087 55437

# No HEALTHCHECK is declared on purpose. The image has no HTTP client, and adding curl to
# run one would put a network tool inside the runtime for no reason. Health lives at
# http://<host>:55437/actuator/health and needs no credential, so the orchestrator probes
# it directly — see the readiness and liveness groups in application.yml.

# Container memory, not host memory, decides the heap. Dying on OOM is better than
# limping: the orchestrator can replace a dead node but not a wedged one.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# No secrets are baked in, and none have defaults. LEDGER_SIGNING_KEY, LEDGER_JWT_SECRET,
# LEDGER_CLIENT_ID and LEDGER_CLIENT_SECRET must all arrive from the process environment;
# the node fails startup with a named-property error if any is missing.
ENTRYPOINT ["java", "-jar", "application.jar"]
