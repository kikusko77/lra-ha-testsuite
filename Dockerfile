FROM eclipse-temurin:21-jre
WORKDIR /work
COPY target/quarkus-app/ /work/quarkus-app/
EXPOSE 8081
ENV QUARKUS_HTTP_PORT=8081
CMD ["java","-jar","/work/quarkus-app/quarkus-run.jar"]
