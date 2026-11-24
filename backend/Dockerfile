# ---- Build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# How many times a failed download from Maven Central is retried
# before the build is considered broken. The registry rate-limits
# runners, and a single dropped connection must not fail the build.
ARG MAVEN_RETRY_COUNT=5

# ---- Dependencies ----
# This layer depends only on pom.xml, so CI keeps it cached for as
# long as the dependency set does not change. It is the only step
# that talks to Maven Central, and it is also the step that breaks
# when the registry is slow or rate-limits the runner — which is
# what failed the "Docker (backend)" job.
#
# Two defences:
#
#   * the retry loop re-runs the resolution when it fails, and
#   * the HTTP retry settings make a single dropped connection
#     retryable instead of fatal.
#
# `dependency:go-offline` resolves the project's dependencies *and*
# the plugins bound to the build lifecycle. That is what lets the
# compile step below run offline.
COPY pom.xml ./
RUN set -eu; \
    resolved=0; \
    for attempt in 1 2 3 4 5; do \
      echo "Resolving dependencies (attempt ${attempt}/5)"; \
      if mvn -B -ntp -U -fae \
           -Dmaven.wagon.http.retryHandler.count=${MAVEN_RETRY_COUNT} \
           -Dmaven.wagon.rto=60000 \
           -Daether.connector.http.retryHandler.count=${MAVEN_RETRY_COUNT} \
           dependency:go-offline; then \
        resolved=1; \
        break; \
      fi; \
      echo "::warning::dependency:go-offline failed on attempt ${attempt}; retrying in 20s"; \
      sleep 20; \
    done; \
    if [ "$resolved" -ne 1 ]; then \
      echo "::warning::The dependencies did not resolve cleanly. The build step falls back to the network."; \
    fi

# ---- Compile ----
# Pass FORCE_REBUILD as a build arg so we can bump the cache key when
# controller layer changes without the pom.xml changing.
ARG FORCE_REBUILD=0
COPY src ./src
RUN set -eu; \
    if [ "$FORCE_REBUILD" != "0" ]; then \
      echo "force rebuild: $FORCE_REBUILD"; \
      find /app/target -type f -delete 2>/dev/null || true; \
    fi; \
    if mvn -B -e -ntp -o clean package -DskipTests; then \
      echo "The build completed offline."; \
    else \
      echo "::warning::The offline build failed. Retrying with network access."; \
      mvn -B -e -ntp -U -fae \
        -Dmaven.wagon.http.retryHandler.count=${MAVEN_RETRY_COUNT} \
        -Dmaven.wagon.rto=60000 \
        -Daether.connector.http.retryHandler.count=${MAVEN_RETRY_COUNT} \
        clean package -DskipTests; \
    fi

# ---- Runtime ----
FROM eclipse-temurin:21-jre
WORKDIR /app

# Copy the built jar from the build stage
COPY --from=build /app/target/livingdocs-backend.jar /app/livingdocs-backend.jar

EXPOSE 8080

ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/livingdocs-backend.jar"]
