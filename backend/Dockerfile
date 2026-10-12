# ---- Build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Cache dependencies first
COPY pom.xml ./
RUN mvn -B -e -ntp dependency:go-offline

# Build the application. Pass FORCE_REBUILD as a build arg so we can
# bump the cache key when controller layer changes without the
# pom.xml changing.
ARG FORCE_REBUILD=0
COPY src ./src
RUN if [ "$FORCE_REBUILD" != "0" ]; then \
      echo "force rebuild: $FORCE_REBUILD"; \
      find /app/target -type f -delete 2>/dev/null || true; \
    fi && \
    mvn -B -e -ntp clean package -DskipTests

# ---- Runtime ----
FROM eclipse-temurin:21-jre
WORKDIR /app

# Copy the built jar from the build stage
COPY --from=build /app/target/livingdocs-backend.jar /app/livingdocs-backend.jar

EXPOSE 8080

ENV JAVA_OPTS=""

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/livingdocs-backend.jar"]