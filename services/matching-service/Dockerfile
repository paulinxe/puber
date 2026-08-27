# The build stage needs a JDK, Gradle and the sources;
# The runtime needs a JRE and one jar.
# Do not collapse them -- the runtime image would then ship the toolchain and test sources.

# ---------------------------------------------------------------------------
# build -- also the image the test runner uses, with the repo mounted over /workspace.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25.0.3_9-jdk AS build

RUN groupadd --system --gid 10001 app \
 && useradd  --system --uid 10001 --gid app --create-home --home-dir /home/app app \
 && chmod 755 /home/app

WORKDIR /workspace

# Gradle needs a writable home; an arbitrary UID cannot write /root. The Compose runner
# overrides this to a path in the mounted workspace, so its downloads are host-owned.
ENV GRADLE_USER_HOME=/home/app/.gradle
RUN chown 10001:10001 /workspace
USER 10001:10001

# Build inputs before sources, so a source-only change does not re-resolve dependencies.
COPY --chown=10001:10001 gradlew settings.gradle build.gradle ./
COPY --chown=10001:10001 gradle gradle
COPY --chown=10001:10001 build/static-analyzers build/static-analyzers
RUN ./gradlew --no-daemon --console=plain dependencies --configuration runtimeClasspath > /dev/null

# BELOW the dependency resolution above and above `COPY src`, so editing a .proto rebuilds
# codegen and the sources but does not re-resolve the whole runtime classpath. Putting it
# above the RUN -- which is where it started -- busts that layer on every one-character
# contract edit.
COPY --chown=10001:10001 build/contracts build/contracts

COPY --chown=10001:10001 src src
RUN ./gradlew --no-daemon --console=plain bootJar

# Readable by any UID so `make gradle-home` can seed a fresh clone from it. Gradle creates
# parts of its own home mode 700, which the runner's host UID cannot read.
RUN chmod -R a+rX /home/app/.gradle

# ---------------------------------------------------------------------------
# runtime -- a JRE and one jar, and nothing that can build or test anything.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25.0.3_9-jre AS runtime

RUN groupadd --system --gid 10001 app \
 && useradd  --system --uid 10001 --gid app --no-create-home app

WORKDIR /app
# The exact name, not a glob: build.gradle pins archiveFileName, so a rename breaks here
# loudly instead of silently shipping the non-executable plain jar.
COPY --from=build --chown=10001:10001 /workspace/build/libs/matching-service.jar /app/matching-service.jar

# Numeric, not `USER app`: Kubernetes' runAsNonRoot can only verify a numeric UID (AD-49).
USER 10001:10001

# Both listeners: 8080 is HTTP/actuator, 9090 is the gRPC surface (spring.grpc.server.port).
# EXPOSE is metadata and gates nothing on a Compose network -- it is here so the image does
# not understate what it serves.
EXPOSE 8080 9090
ENTRYPOINT ["java", "-jar", "/app/matching-service.jar"]
