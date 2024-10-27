## GRpcServ: REST to gRPC Migration Plugin

This Maven plugin simplifies the migration of REST APIs to gRPC services. It automatically generates gRPC service
definitions (proto files) and corresponding services Java code based on your existing REST controllers.

### Features

* **Automatic Proto File Generation:**  Generates proto files from your REST controllers, defining gRPC services and
  messages.
* **gRPC Service Implementation:** Creates Java code for gRPC service implementations, handling request/response mapping
  and error handling.
* **Reflection Service:** Includes a reflection service for easy introspection of your gRPC services.
* **Maven Integration:** Seamlessly integrates with Maven's build lifecycle, making it easy to incorporate into your
  project.

### Installation

1. **Clone repository [https://github.com/hypen-code/GRpcServ.git](https://github.com/hypen-code/GRpcServ.git)**
2. **Run command:**
   ```bash
   mvn clean install
   ```

### Setting up your project

1. **Add the plugin to your project's `pom.xml`:**
   ```xml
   <plugin>
       <groupId>org.HypeN.maven</groupId>
       <artifactId>GRpcServ</artifactId>
       <version>0.1.0</version>
       <executions>
           <execution>
               <goals>
                   <goal>proto-gen</goal>
                   <goal>protobuf-gen</goal>
                   <goal>svc-gen</goal>
               </goals>
               <configuration>
                   <sourceDirectories>src/main/java</sourceDirectories>
               </configuration>
           </execution>
       </executions>
   </plugin>
   ```
2. **Add the extension to your project's `pom.xml`:**
   ```xml
   <extension>
       <groupId>kr.motd.maven</groupId>
       <artifactId>os-maven-plugin</artifactId>
       <version>1.7.1</version>
   </extension>
   ```
3. **Add the dependencies to your project's `pom.xml`:**
   ```xml
   <dependency>
        <groupId>org.HypeN.maven</groupId>
        <artifactId>GRpcServ</artifactId>
        <version>0.1.0</version>
    </dependency>
    <dependency>
        <groupId>javax.annotation</groupId>
        <artifactId>javax.annotation-api</artifactId>
        <version>1.3.2</version>
    </dependency>
   ```
4. **Configure the `sourceDirectories` parameter:** This parameter specifies the directory containing your REST
   controller Java files.

### Usage

1. **Build your project:** The plugin will automatically execute during the `generate-sources` phase of your Maven
   build.
2. **Generated Files:** The plugin will generate the following files in the `target/generated-sources/proto` and
   `target/generated-sources/protosvc` directories:
    * **Proto files:**  `.proto` files defining your gRPC services and messages.
    * **gRPC Service Implementations:** Java code for your gRPC service implementations.
    * **Reflection Service:** Java code for the reflection service.
3. **Example:** Annotate your methods.
  ```java
  public class MyService {
    @GRpcServ
    public String myMethod(String param1, int param2);
      // Method implementation
    }
  }
  ```


---

## GRpcServ Project Design Document

This document outlines the design and functionality of the GRpcServ Maven plugin. This plugin automates the generation of gRPC service implementations and corresponding protocol buffer definitions from annotated Java interfaces.

### 1. Overview

The GRpcServ plugin simplifies the process of creating gRPC services by:

- **Generating .proto files:** Automatically creates protocol buffer definitions based on annotated Java interfaces.
- **Generating gRPC service implementations:** Generates Java classes that implement the defined gRPC services, handling request/response mapping and exception handling.

This automation reduces boilerplate code and ensures consistency between Java service definitions and their protocol buffer representations.

### 2. Plugin Architecture

The plugin consists of two main components:

- **ProtoGenerator:** Responsible for parsing Java source files, extracting service definitions from annotations, and generating .proto files.
- **ServiceGenerator:** Responsible for reading generated .proto files and generating Java service implementation classes.

These components work together to provide a seamless experience for developers creating gRPC services.

### 3. Functionality

#### 3.1 ProtoGenerator

The `ProtoGenerator` class is a Maven Mojo that executes during the `GENERATE_SOURCES` phase. It performs the following tasks:

- **Identifies source directories:** Reads the configured `sourceDirectories` parameter to determine the locations of Java source files.
- **Parses Java files:** Iterates through each Java file in the source directories, parsing them using JavaParser.
- **Extracts service definitions:** Identifies methods annotated with `@GRpcServ` and extracts relevant information such as method name, parameters, and return type.
- **Generates .proto files:** Uses a Freemarker template to generate .proto files based on the extracted service definitions.
- **Serializes metadata:** Serializes a list of `ProtoObject` instances containing service metadata to a file for later use by the `ServiceGenerator`.

#### 3.2 ServiceGenerator

The `ServiceGenerator` class is another Maven Mojo that also executes during the `GENERATE_SOURCES` phase. It performs the following tasks:

- **Deserializes metadata:** Reads the serialized `ProtoObject` instances generated by the `ProtoGenerator`.
- **Generates service implementations:** For each `ProtoObject`, generates a Java class that implements the defined gRPC service.
- **Handles request/response mapping:** Generates code to map request parameters from gRPC messages to Java objects and vice versa.
- **Implements exception handling:** Includes logic to translate Java exceptions into gRPC status codes.
- **Writes Java files:** Writes the generated service implementation classes to the `target/generated-sources/protosvc` directory.

### 4. Usage

To use the GRpcServ plugin, follow these steps:

1. **Add the plugin dependency:** Include the plugin in your project's `pom.xml` file.
2. **Configure the plugin:** Specify the `sourceDirectories` parameter to indicate the locations of your Java source files.
3. **Annotate your service interfaces:** Annotate methods in your Java interfaces with `@GRpcServ` to mark them as gRPC endpoints.
4. **Run Maven:** Execute the `generate-sources` phase to trigger the plugin execution.

### 5. Example

Running the plugin on this code would generate:

- A .proto file defining the `MyService` service and its `myMethod` endpoint.
- A Java class implementing the `MyService` service, handling request/response mapping for `myMethod`.

### 6. Future Enhancements

- **Support for wildcard imports:** Improve handling of wildcard imports in Java source files.
- **Customizable code generation:** Allow users to provide custom Freemarker templates for generating .proto files and service implementations.
- **Integration with other gRPC frameworks:** Explore integration with other gRPC frameworks beyond Spring Boot.

This design document provides a high-level overview of the GRpcServ plugin. For detailed implementation details, please refer to the source code and JavaDoc comments.
