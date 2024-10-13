## GRpcServ: REST to gRPC Migration Plugin

This Maven plugin simplifies the migration of REST APIs to gRPC services. It automatically generates gRPC service definitions (proto files) and corresponding services Java code based on your existing REST controllers.

### Features

* **Automatic Proto File Generation:**  Generates proto files from your REST controllers, defining gRPC services and messages.
* **gRPC Service Implementation:** Creates Java code for gRPC service implementations, handling request/response mapping and error handling.
* **Reflection Service:** Includes a reflection service for easy introspection of your gRPC services.
* **Maven Integration:** Seamlessly integrates with Maven's build lifecycle, making it easy to incorporate into your project.

### Installation

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
2. **Configure the `sourceDirectories` parameter:** This parameter specifies the directory containing your REST controller Java files.

### Usage

1. **Build your project:** The plugin will automatically execute during the `generate-sources` phase of your Maven build.
2. **Generated Files:** The plugin will generate the following files in the `target/generated-sources/proto` and `target/generated-sources/protosvc` directories:
    * **Proto files:**  `.proto` files defining your gRPC services and messages.
    * **gRPC Service Implementations:** Java code for your gRPC service implementations.
    * **Reflection Service:** Java code for the reflection service.
