FROM eclipse-temurin:21-jre
WORKDIR /app
COPY build/libs/server-all.jar /app/server-all.jar
ENV MJOLNIR_DB=/data/mjolnir.db MJOLNIR_FILES=/data/files
EXPOSE 8080
CMD ["java","-jar","/app/server-all.jar"]
