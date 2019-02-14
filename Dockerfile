#https://github.com/kubernetes-client/java/blob/master/examples/Dockerfile
FROM openjdk:8-jre

COPY target/terminatedPodCleaner-*-SNAPSHOT-jar-with-dependencies.jar /terminatedPodCleaner.jar

CMD ["java", "-jar", "/terminatedPodCleaner.jar"]
