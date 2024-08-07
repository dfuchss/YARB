FROM maven:3-eclipse-temurin-21 AS builder

WORKDIR /usr/src/bot
COPY src src
COPY pom.xml pom.xml
RUN mvn clean package

FROM eclipse-temurin:21-jre

ENV RUN_IN_DOCKER=true
ENV TZ=Europe/Berlin
ENV CONFIG_PATH=/usr/src/bot/data/config.json

WORKDIR /usr/src/bot
COPY --from=builder /usr/src/bot/target/yarb-*-jar-with-dependencies.jar yarb.jar

VOLUME /usr/src/bot/data

ENTRYPOINT java -jar /usr/src/bot/yarb.jar
