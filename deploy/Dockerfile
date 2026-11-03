FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN mvn --batch-mode --no-transfer-progress -pl codebridge-web -am -DskipTests package

FROM eclipse-temurin:21-jdk
WORKDIR /app
COPY --from=build /workspace/codebridge-web/target/codebridge-web-0.1.0-SNAPSHOT.jar /app/codebridge.jar
COPY --from=build /workspace/codebridge-core/target/codebridge-core-0.1.0-SNAPSHOT.jar /app/lib/
COPY --from=build /workspace/codebridge-frontend-clang/target/codebridge-frontend-clang-0.1.0-SNAPSHOT.jar /app/lib/
COPY --from=build /workspace/codebridge-rules/target/codebridge-rules-0.1.0-SNAPSHOT.jar /app/lib/
COPY --from=build /workspace/codebridge-validation/target/codebridge-validation-0.1.0-SNAPSHOT.jar /app/lib/
COPY --from=build /workspace/codebridge-lang-c/target/codebridge-lang-c-0.1.0-SNAPSHOT.jar /app/lib/
COPY --from=build /workspace/codebridge-lang-cpp/target/codebridge-lang-cpp-0.1.0-SNAPSHOT.jar /app/lib/
COPY --from=build /workspace/codebridge-lang-java/target/codebridge-lang-java-0.1.0-SNAPSHOT.jar /app/lib/
COPY --from=build /root/.m2/repository/com/fasterxml/jackson/core/jackson-databind/2.22.3/jackson-databind-2.22.3.jar /app/lib/
COPY --from=build /root/.m2/repository/com/fasterxml/jackson/core/jackson-core/2.22.3/jackson-core-2.22.3.jar /app/lib/
COPY --from=build /root/.m2/repository/com/fasterxml/jackson/core/jackson-annotations/2.22/jackson-annotations-2.22.jar /app/lib/
COPY --from=build /root/.m2/repository/com/fasterxml/jackson/datatype/jackson-datatype-jdk8/2.22.3/jackson-datatype-jdk8-2.22.3.jar /app/lib/
RUN useradd --system --uid 10001 --create-home codebridge
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65", "-XX:MaxMetaspaceSize=192m", "-Djava.io.tmpdir=/tmp", "-cp", "/app/codebridge.jar:/app/lib/*", "com.tascode.codebridge.web.CodeBridgeHttpServer"]
