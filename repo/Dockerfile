# ---------- build stage ----------
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /workspace
COPY pom.xml .
# Download dependencies first (layer cache)
RUN mvn dependency:go-offline -q
COPY src ./src
COPY unit_tests ./unit_tests
RUN mvn package -DskipTests -q

# ---------- test stage (unit tests — no server needed) ----------
FROM maven:3.9-eclipse-temurin-21-alpine AS test
WORKDIR /workspace
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
COPY unit_tests ./unit_tests
ENTRYPOINT ["mvn", "test", "--no-transfer-progress"]

# ---------- runtime stage ----------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S ricms && adduser -S ricms -G ricms
COPY --from=build /workspace/target/*.jar app.jar
RUN chown ricms:ricms app.jar
USER ricms
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
