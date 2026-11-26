FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/target/memory-design-*.jar /app/memory-design.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/memory-design.jar"]
