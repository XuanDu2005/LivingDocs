# ---- Build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Cache dependencies first
COPY pom.xml ./
RUN mvn -B -e -ntp dependency:go-offline

# Build the application
COPY src ./src
RUN mvn -B -e -ntp clean package -DskipTests

# ---- Runtime ----
FROM eclipse-temurin:21-jre
WORKDIR /app

# Copy the built jar from the build stage
COPY --from=build /app/target/livingdocs-backend.jar /app/livingdocs-backend.jar

EXPOSE 8080

ENV JAVA_OPTS=""

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/livingdocs-backend.jar"]