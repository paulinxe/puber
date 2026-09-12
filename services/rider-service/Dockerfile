# The build stage needs a JDK, Gradle and the sources;
# The runtime needs a JRE and one jar.
# Do not collapse them -- the runtime image would then ship the toolchain and test sources.

# ---------------------------------------------------------------------------
# build
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25.0.3_9-jdk AS build

RUN groupadd --system --gid 10001 app \
 && useradd  --system --uid 10001 --gid app --create-home --home-dir /home/app app \
 && chmod 755 /home/app

WORKDIR /workspace

# Gradle needs a writable home; an arbitrary UID cannot write /root.
ENV GRADLE_USER_HOME=/home/app/.gradle
RUN chown 10001:10001 /workspace
USER 10001:10001

# Build inputs before sources, so a source-only change does not re-resolve dependencies.
COPY --chown=10001:10001 gradlew settings.gradle build.gradle ./
COPY --chown=10001:10001 gradle gradle
COPY --chown=10001:10001 build/static-analyzers build/static-analyzers
RUN ./gradlew --no-daemon --console=plain dependencies --configuration runtimeClasspath > /dev/null

# BELOW the dependency resolution above and above `COPY src`, so editing a .proto rebuilds
# codegen and the sources but does not re-resolve the whole runtime classpath.
COPY --chown=10001:10001 build/contracts build/contracts

COPY --chown=10001:10001 src src
RUN ./gradlew --no-daemon --console=plain bootJar

# ---------------------------------------------------------------------------
# runtime -- a JRE and one jar, and nothing that can build or test anything.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25.0.3_9-jre AS runtime

RUN groupadd --system --gid 10001 app \
 && useradd  --system --uid 10001 --gid app --no-create-home app

WORKDIR /app
# The exact name, not a glob: build.gradle pins archiveFileName, so a rename breaks here
# loudly instead of silently shipping the non-executable plain jar.
COPY --from=build --chown=10001:10001 /workspace/build/libs/rider-service.jar /app/rider-service.jar

# Numeric, not `USER app`: Kubernetes' runAsNonRoot can only verify a numeric UID (AD-49).
USER 10001:10001

# One listener: this service speaks gRPC only as a client, so it binds no gRPC port.
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/rider-service.jar"]
