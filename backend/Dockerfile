FROM maven:3.9.11-eclipse-temurin-17-noble AS build
WORKDIR /app
COPY pom.xml ./
COPY src ./src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:17-jre-noble
WORKDIR /app
RUN groupadd --system pitflow && useradd --system --gid pitflow pitflow
COPY --from=build --chown=pitflow:pitflow /app/target/pitflow-api-0.1.0.jar app.jar
USER pitflow
ENV JAVA_TOOL_OPTIONS="-Xms64m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
