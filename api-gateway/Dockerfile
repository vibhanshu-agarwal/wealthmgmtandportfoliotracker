# Use Amazon Corretto (AWS's official Java distribution)
# Note: Adjust '21' to '17' or '25' depending on the Java version in your build.gradle!
FROM amazoncorretto:25-alpine

# Set a volume for Spring Boot's embedded Tomcat temporary directories
VOLUME /tmp

# Copy the compiled Spring Boot executable JAR into the image.
# Expected artifact: api-gateway/build/libs/api-gateway.jar
COPY build/libs/api-gateway.jar app.jar

# Boot the application
ENTRYPOINT ["java", "-jar", "/app.jar"]
