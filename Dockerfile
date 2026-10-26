FROM maven:3.9.9-eclipse-temurin-17-alpine AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S -g 10001 app && adduser -S -u 10001 -G app app \
    && mkdir -p /data && chown app:app /data
WORKDIR /app
COPY --from=build /workspace/target/hotel-booking-system-1.0.0.jar app.jar
ENV PORT=8080
ENV BOOKINGS_FILE=/data/bookings.tsv
EXPOSE 8080
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
