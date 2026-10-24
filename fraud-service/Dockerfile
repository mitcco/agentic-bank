# Build e runtime dell'immagine di fraud-service. Contesto di build: la cartella fraud-service/.
# Non servono Java ne Maven sulla macchina: il build avviene qui dentro.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY . .
RUN --mount=type=cache,target=/root/.m2     ./mvnw -B -q -pl fraud-bootstrap -am package -DskipTests

FROM eclipse-temurin:21-jre
RUN useradd --system --no-create-home app
WORKDIR /app
COPY --from=build /build/fraud-bootstrap/target/fraud-bootstrap-*.jar app.jar
USER app
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
