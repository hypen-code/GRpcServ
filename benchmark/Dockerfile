# Use a Maven image to build the application
FROM maven:3.9.5-eclipse-temurin-17 AS build

# Set the working directory inside the container
WORKDIR /app

# Clone the GRpcServ repository and install it
RUN git clone https://github.com/hypen-code/GRpcServ.git /app/GRpcServ \
    && cd /app/GRpcServ \
    && mvn clean install

# Copy the pom.xml and resolve dependencies (layer caching)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy the source code
COPY src ./src

# Package the application
RUN mvn clean install -DskipTests

# Use a lightweight JDK runtime for the final image
FROM eclipse-temurin:17-jre

# Set the working directory inside the container
WORKDIR /app

# Copy the built JAR file from the build stage
COPY --from=build /app/target/benchmark-0.0.1-SNAPSHOT.jar app.jar

# Set JMX-related environment variables
ENV JAVA_OPTS="-Dcom.sun.management.jmxremote \
               -Dcom.sun.management.jmxremote.port=9010 \
               -Dcom.sun.management.jmxremote.local.only=false \
               -Dcom.sun.management.jmxremote.authenticate=false \
               -Dcom.sun.management.jmxremote.ssl=false \
               -Djava.rmi.server.hostname=0.0.0.0 \
               -Dcom.sun.management.jmxremote.rmi.port=9010"

# Expose the application ports (gRPC, HTTP, JMX)
EXPOSE 6565 8080 9010

# Run the application with the modified JVM options
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
