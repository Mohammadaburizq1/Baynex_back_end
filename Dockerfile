FROM maven:4.0.0-rc-5-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:25-jdk
WORKDIR /app
RUN useradd -r -u 10001 shoplink \
 && mkdir -p /var/lib/shoplink/media \
 && chown shoplink /var/lib/shoplink/media
COPY --from=build /app/target/shoplink-backend-0.0.1-SNAPSHOT.jar app.jar
USER shoplink
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
