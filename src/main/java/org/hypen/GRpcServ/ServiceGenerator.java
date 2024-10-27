package org.hypen.GRpcServ;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.hypen.GRpcServ.models.Endpoint;
import org.hypen.GRpcServ.models.Message;
import org.hypen.GRpcServ.models.ProtoObject;
import org.hypen.GRpcServ.utils.NameMapper;
import org.springframework.util.CollectionUtils;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.apache.maven.shared.utils.StringUtils.capitalizeFirstLetter;

@Mojo(name = "svc-gen", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
public class ServiceGenerator extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    @Parameter(property = "enablePrintException", readonly = true, defaultValue = "false")
    private boolean enablePrintException;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        List<ProtoObject> protoObjects;
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(
                project.getBasedir() + "/target/generated-sources/proto/proto-metadata.dat"))) {
            protoObjects = (List<ProtoObject>) ois.readObject();
        } catch (Exception e) {
            getLog().error("Error reading proto-metadata.dat\n" + e.getMessage());
            return;
        }

        if (!CollectionUtils.isEmpty(protoObjects)) genGrpcReflection(protoObjects.get(0));

        protoObjects.forEach(this::genGrpcService);
    }

    /**
     * Generates a gRPC service implementation class from a {@link ProtoObject}.
     *
     * This method takes a {@link ProtoObject} representing a gRPC service definition
     * and generates a Java class that implements the service. The generated class
     * includes:
     *
     * - Necessary imports based on the service definition.
     * - A class declaration annotated with `@Slf4j` and `@GRpcService`.
     * - A field declaration for the service implementation, annotated with `@Autowired`.
     * - A field declaration for a `ModelMapper` instance.
     * - Method implementations for each endpoint defined in the {@link ProtoObject},
     *   handling request parameter mapping, service method invocation, response mapping,
     *   and exception handling.
     *
     * The generated Java class file is then written to the `target/generated-sources/protosvc`
     * directory within the project.
     *
     * @param proto The {@link ProtoObject} containing the gRPC service definition.
     */
    private void genGrpcService(ProtoObject proto) {
        getLog().info("GRpcServ generating service: " + proto.getServiceName());
        CompilationUnit cu = new CompilationUnit();
        String defaultPackage = proto.getPackageName() + "." + proto.getServiceName() + "Gen";
        cu.setPackageDeclaration(defaultPackage);

        generateImports(proto, cu, defaultPackage);

//        Create class declaration
        ClassOrInterfaceDeclaration classDeclaration = cu.addClass(proto.getServiceName() + "Gen").setModifiers(Modifier.Keyword.PUBLIC);
        classDeclaration.addAnnotation(new MarkerAnnotationExpr("Slf4j"));
        classDeclaration.addAnnotation(new MarkerAnnotationExpr("GRpcService"));
        ClassOrInterfaceType superclass = StaticJavaParser.parseClassOrInterfaceType(proto.getServiceName() + "Grpc." + proto.getServiceName() + "ImplBase");
        cu.addImport(defaultPackage + "." + proto.getServiceName() + "Grpc");
        classDeclaration.setExtendedTypes(NodeList.nodeList(superclass));

//        Autowire service implementation
        FieldDeclaration field = classDeclaration.addField(StaticJavaParser.parseType(proto.getServiceName()), proto.getServiceName());
        field.setModifiers(Modifier.Keyword.PRIVATE);
        field.addAnnotation(new MarkerAnnotationExpr("Autowired"));
        cu.addImport(proto.getPackageName() + "." + proto.getServiceName());

//        Add model mapper
        ClassOrInterfaceType modelMapperType = StaticJavaParser.parseClassOrInterfaceType("ModelMapper");
        ObjectCreationExpr objectCreationExpr = new ObjectCreationExpr();
        objectCreationExpr.setType(modelMapperType);
        VariableDeclarator variableDeclarator = new VariableDeclarator();
        variableDeclarator.setName("modelMapper");
        variableDeclarator.setType(modelMapperType);
        variableDeclarator.setInitializer(objectCreationExpr);
        FieldDeclaration fieldDeclaration = new FieldDeclaration();
        fieldDeclaration.setModifiers(Modifier.Keyword.PRIVATE);
        fieldDeclaration.addVariable(variableDeclarator);
        classDeclaration.addMember(fieldDeclaration);

//        Generate service methods for each endpoint
        proto.getEndpoints().forEach(endpoint -> {
            try {
                generateMethods(classDeclaration, endpoint, proto);
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            }
        });

        storeClassFile(cu, proto.getServiceName() + "Gen.java");
    }

    /**
     * Generates and adds necessary import statements to the given CompilationUnit based on the ProtoObject and default package.
     *
     * This method iterates through the messages in the ProtoObject and adds import statements for each message
     * using the provided default package. It also adds imports for commonly used classes like
     * `io.grpc.Status`, `io.grpc.stub.StreamObserver`, Spring annotations, ModelMapper,
     * custom utility classes, and Java collections based on the data types used in the ProtoObject.
     *
     * @param proto          The ProtoObject containing information about the service and its messages.
     * @param cu             The CompilationUnit to which the import statements will be added.
     * @param defaultPackage The default package to use for generating import statements for messages.
     */
    private void generateImports(ProtoObject proto, CompilationUnit cu, String defaultPackage) {
        proto.getMessages().forEach(message -> cu.addImport(defaultPackage + "." + message.getName()));
        cu.addImport("io.grpc.Status");
        cu.addImport("io.grpc.stub.StreamObserver");
        cu.addImport("org.lognet.springboot.grpc.GRpcService");
        cu.addImport("org.springframework.beans.factory.annotation.Autowired");
        cu.addImport("org.modelmapper.ModelMapper");
        cu.addImport("org.hypen.GRpcServ.utils.ExceptionToGrpcStatus");
        cu.addImport("lombok.extern.slf4j.Slf4j");

        List<String> paramDTs = new ArrayList<>();
        proto.getEndpoints().forEach(e->paramDTs.addAll(e.getParams().values().stream().toList()));
        cu.addImport("java.util.ArrayList");
        if (NameMapper.anyStartWithStr("List", paramDTs)) cu.addImport("java.util.List");
        if (NameMapper.anyStartWithStr("Map", paramDTs)) cu.addImport("java.util.Map");
        if (NameMapper.anyStartWithStr("Set", paramDTs)) cu.addImport("java.util.Set");
        if (NameMapper.anyStartWithStr("Collection", paramDTs)) cu.addImport("java.util.Collection");

        proto.getDtoMap().keySet().forEach(key -> {
            if (NameMapper.anyStartWithStr(key, paramDTs)) cu.addImport(proto.getDtoMap().get(key));
        });
    }

    /**
     * Generates a gRPC service method for the given endpoint.
     *
     * This method creates a new method declaration in the given class declaration,
     * representing a gRPC service method that corresponds to the provided endpoint.
     *
     * The generated method handles the following:
     * - Mapping request parameters from the gRPC request object to local variables.
     * - Invoking the corresponding method in the service implementation.
     * - Mapping the response object from the service implementation to a gRPC response object.
     * - Handling exceptions and converting them to gRPC status codes.
     *
     * @param classDeclaration The class declaration to add the method to.
     * @param endpoint         The endpoint information, including the method name, request/response types, and parameters.
     * @param proto            The proto object containing the service definition and message/enum mappings.
     * @throws FileNotFoundException If a Java source file for a DTO or enum cannot be found.
     */
    private void generateMethods(ClassOrInterfaceDeclaration classDeclaration, Endpoint endpoint, ProtoObject proto) throws FileNotFoundException {
        getLog().info("Generating method: " + endpoint.getName());
        MethodDeclaration method = classDeclaration.addMethod(endpoint.getName(), Modifier.Keyword.PUBLIC);
        method.addAnnotation(new MarkerAnnotationExpr("Override"));

//        Generating method parameters
        NodeList<com.github.javaparser.ast.body.Parameter> params = new NodeList<>(
                new com.github.javaparser.ast.body.Parameter(StaticJavaParser.parseType(endpoint.getRequest().getName()), "request"),
                new com.github.javaparser.ast.body.Parameter(StaticJavaParser.parseType("StreamObserver<" + endpoint.getResponse().getName() + ">"), "responseObserver"));
        method.setParameters(params);

        BlockStmt methodBody = new BlockStmt();

        MethodCallExpr methodCallExpr = new MethodCallExpr(new NameExpr(proto.getServiceName()), endpoint.getName());
        for (Map.Entry<String, String> param : endpoint.getParams().entrySet()) {
//            Process all method parameters and add to method arguments
            if (param.getKey().equals("genResponse")) continue;

            String collectionModifier = "";
            if (param.getValue().startsWith("List")) collectionModifier = "List";
            if (param.getValue().startsWith("Map")) collectionModifier = "Map";

//            Map DTOs using model mapper
            String varName = generateRequestParamMapping(getGetterName(param.getKey(), collectionModifier), param, methodBody, proto);
            methodCallExpr.addArgument(new NameExpr(varName));
        }
        if (endpoint.getParams().get("genResponse").equals("void")){
//            Handle void return type in method
            methodBody.addStatement(methodCallExpr);
        } else {
//            Handle non-void return type in method
            ClassOrInterfaceType returnType = StaticJavaParser.parseClassOrInterfaceType(translateToObjectName(endpoint.getParams().get("genResponse")));
            VariableDeclarationExpr variableDeclExpr = new VariableDeclarationExpr(
                    new VariableDeclarator(returnType, "methodResponse", methodCallExpr)
            );
            methodBody.addStatement(variableDeclExpr);
        }

//        Generate response setter by data type
        String responseType = "setResponse";
        if (endpoint.getParams().containsKey("genResponse")) {
            if (endpoint.getParams().get("genResponse").startsWith("List") ||
                    endpoint.getParams().get("genResponse").startsWith("Set") ||
                    endpoint.getParams().get("genResponse").startsWith("Collection")) {
                responseType = "addAllResponse";
            } else if (endpoint.getParams().get("genResponse").startsWith("Map")) {
                responseType = "putAllResponse";
            }
        }

//        Response mapping to Grpc data types
        String responseValue = "methodResponse";
        ClassOrInterfaceType grpcType = StaticJavaParser.parseClassOrInterfaceType(endpoint.getResponse().getName());
        MethodCallExpr builderCall = new MethodCallExpr(new NameExpr(endpoint.getResponse().getName()), "newBuilder");
        MethodCallExpr setResponse;
        if (endpoint.getParams().get("genResponse").equals("void")){
//            Handle void return type
            setResponse = builderCall;
        } else {
//            Handle non-void return type. Recursively mapping response objects
            responseValue = mapDtoOrEnum(endpoint.getParams().get("genResponse"), proto, methodBody, responseValue, new ArrayList<>());
            setResponse = new MethodCallExpr(builderCall, responseType).addArgument(responseValue);
        }

//        Create return statement
        MethodCallExpr buildCall = new MethodCallExpr(setResponse, "build");
        VariableDeclarationExpr grpcVariableDeclExpr = new VariableDeclarationExpr(
                new VariableDeclarator(grpcType, endpoint.getResponse().getName() + "Gen", buildCall)
        );
        methodBody.addStatement(grpcVariableDeclExpr);

//        Create response observer finalize statements
        MethodCallExpr onNextCall = new MethodCallExpr(new NameExpr("responseObserver"), "onNext").addArgument(new NameExpr(endpoint.getResponse().getName() + "Gen"));
        methodBody.addStatement(onNextCall);

        MethodCallExpr onCompletedCall = new MethodCallExpr(new NameExpr("responseObserver"), "onCompleted");
        methodBody.addStatement(onCompletedCall);

//        Exception handling for Grpc status
        BlockStmt catchBlock = new BlockStmt();
        if (enablePrintException) {
            NodeList<Expression> arguments = NodeList.nodeList(new MethodCallExpr(new NameExpr("ex"), "getMessage"), new NameExpr("ex"));
            catchBlock.addStatement(new MethodCallExpr(new NameExpr("log"), "error", arguments));
        }
        catchBlock.addStatement(new ExpressionStmt(
                new MethodCallExpr( new NameExpr("responseObserver"), "onError",
                        NodeList.nodeList(new MethodCallExpr(new MethodCallExpr(new NameExpr("ExceptionToGrpcStatus"), "translateExceptionToStatus").addArgument(new NameExpr("ex")), "asRuntimeException"))
                )
        ));

        TryStmt tryStmt = new TryStmt();
        tryStmt.setTryBlock(methodBody);
        CatchClause catchClause = new CatchClause();
        catchClause.setParameter(new com.github.javaparser.ast.body.Parameter(StaticJavaParser.parseType("Exception"), "ex"));
        catchClause.setBody(catchBlock);
        tryStmt.getCatchClauses().add(catchClause);

        BlockStmt blockStmt = new BlockStmt();
        blockStmt.addStatement(tryStmt);
        method.setBody(blockStmt);
    }

    /**
     * Generates code to map a request parameter from the gRPC request object to a local variable.
     *
     * This method handles the mapping of a single request parameter. If the parameter type is a DTO,
     * it uses ModelMapper to map the corresponding object from the gRPC request to a local variable
     * of the DTO type. Otherwise, it simply uses the provided getter expression to access the parameter value.
     *
     * @param getterName  The expression to get the parameter value from the gRPC request object.
     * @param param      The parameter information, including its name and data type.
     * @param methodBody The method body where the generated code will be added.
     * @param proto     The {@link ProtoObject} containing the service definition and message/enum mappings.
     * @return The name of the local variable holding the mapped parameter value.
     */
    private String generateRequestParamMapping(String getterName, Map.Entry<String, String> param, BlockStmt methodBody, ProtoObject proto) {
        if (proto.getDtoMap().containsKey(param.getValue())) {
            String varName = param.getKey() + "RequestDto";

//            Generate model mapper statement
            ClassOrInterfaceType dataType = StaticJavaParser.parseClassOrInterfaceType(param.getValue());
            MethodCallExpr methodCallExpr = new MethodCallExpr();
            methodCallExpr.setScope(new NameExpr("modelMapper"));
            methodCallExpr.setName("map");
            methodCallExpr.addArgument(new NameExpr(getterName));
            methodCallExpr.addArgument(new ClassExpr(dataType));

            VariableDeclarationExpr variableDecl = new VariableDeclarationExpr(
                    new VariableDeclarator(dataType, varName, methodCallExpr)
            );
            methodBody.addStatement(new ExpressionStmt(variableDecl));
            return varName;
        }
        return getterName;
    }

    /**
     * Recursively maps a Java data type to its corresponding gRPC message or enum representation.
     *
     * This method handles the mapping of complex data types, including nested DTOs, enums, and collections (Lists).
     * It recursively traverses the object graph, generating the necessary code to map each field to its gRPC equivalent.
     *
     * @param dataType      The Java data type to map (e.g., "Student", "List<Student>", "StudentType").
     * @param proto        The {@link ProtoObject} containing the service definition and message/enum mappings.
     * @param methodBody    The {@link BlockStmt} representing the method body where the mapping code will be added.
     * @param responseValue The current value being mapped (could be a variable name or a more complex expression).
     * @param parents      A list of parent data types to keep track of the recursion depth and handle nested objects.
     * @return The updated response value after mapping the given data type.
     * @throws FileNotFoundException If a Java source file for a DTO or enum cannot be found.
     */
    private String mapDtoOrEnum(String dataType, ProtoObject proto, BlockStmt methodBody, String responseValue, List<String> parents) throws FileNotFoundException {
        if (proto.getDtoMap().containsKey(dataType)){
//            Mapping DTOs recursively
            parents.add(dataType);
            NameMapper nm = NameMapper.getInstance(project, proto.getDtoMap());
            CompilationUnit cu = StaticJavaParser.parse(new File(nm.mapFQN(dataType)));
            Optional<ClassOrInterfaceDeclaration> dtoClassDecl = cu.findFirst(ClassOrInterfaceDeclaration.class);
            Optional<EnumDeclaration> enumDeclaration = cu.findFirst(EnumDeclaration.class);

            if (dtoClassDecl.isPresent()) {
//                Process DTO class
                ClassOrInterfaceType grpcType = StaticJavaParser.parseClassOrInterfaceType(dataType + "Dto");
                MethodCallExpr builderCall = new MethodCallExpr(new NameExpr(grpcType.getName()), "newBuilder");

                MethodCallExpr setResponse = builderCall;
//                Process each fields in the DTO
                for (FieldDeclaration field : dtoClassDecl.get().getFields()) {
                    String innerResponseValue = responseValue;
                    String name = field.getVariables().get(0).getNameAsString();
                    String fieldDataType = field.getVariable(0).getType().toString();

//                    When field is a DTO recursively map it
                    innerResponseValue = mapDtoOrEnum(fieldDataType, proto, methodBody, innerResponseValue, parents);

                    if (proto.getDtoMap().containsKey(fieldDataType)) {
//                        Map value for DTO
                        setResponse = new MethodCallExpr(setResponse, NameMapper.setterName(name, ""))
                                .addArgument(innerResponseValue);
                    } else {
//                        Map value from inner objects
                        String parentGetters = parents.stream().skip(1)
                                .map(e -> NameMapper.getterName(e, "()"))
                                .collect(Collectors.joining("."));
                        if (!parentGetters.isEmpty()) parentGetters = "." + parentGetters;

                        setResponse = new MethodCallExpr(setResponse, NameMapper.setterName(name, ""))
                                .addArgument(innerResponseValue + parentGetters + "." + NameMapper.getterName(name, "()"));
                    }
                }

//                Generate statement
                responseValue = dataType + "DtoGen";
                MethodCallExpr buildCall = new MethodCallExpr(setResponse, "build");
                VariableDeclarationExpr grpcVariableDeclExpr = new VariableDeclarationExpr(
                        new VariableDeclarator(grpcType, responseValue, buildCall)
                );
                methodBody.addStatement(grpcVariableDeclExpr);
            }

            if (enumDeclaration.isPresent()){
//                Process enum class
                String enumName = dataType + "Enum";
                String parentDtoName = parents.get(parents.size() - 2) + "Dto";
                Message message = proto.getMessages().stream().filter(e -> e.getName().equals(parentDtoName)).findFirst().orElseThrow();
                String fieldName = NameMapper.extractWordAfter(enumName, message.getFields());

//                Getting value form parent DTO
                String responseCatcher = parents.size() > 2 ? parentDtoName + "Gen" : responseValue;
                ClassOrInterfaceType enumType = StaticJavaParser.parseClassOrInterfaceType(enumName);
                NameExpr methodResponseExpr = new NameExpr(responseCatcher);
                MethodCallExpr getTypeExpr = new MethodCallExpr(methodResponseExpr, NameMapper.getterName(fieldName, ""));

//                Generate statement
                MethodCallExpr getNameExpr = new MethodCallExpr(getTypeExpr, "name");

                MethodCallExpr valueOfExpr = new MethodCallExpr(new NameExpr(String.valueOf(enumType)), "valueOf");
                valueOfExpr.addArgument(getNameExpr);

                responseValue = dataType + "EnumGen";
                VariableDeclarationExpr variableDecl = new VariableDeclarationExpr(
                        new VariableDeclarator(enumType, responseValue, valueOfExpr)
                );

                methodBody.addStatement(new ExpressionStmt(variableDecl));
            }

            parents.remove(parents.size()-1);
        } else if (dataType.startsWith("List")) {
//            Mapping List of DTOs
            Pattern genericPattern = Pattern.compile("<(.*?)>");
            Matcher matcher = genericPattern.matcher(dataType);
            if (matcher.find()) {
                dataType = matcher.group(1);
                String dtoName = dataType+"Dto";
                parents.add(dataType.toLowerCase());

//                Create list to collect objects
                VariableDeclarationExpr declaration = new VariableDeclarationExpr(
                        new VariableDeclarator(
                                StaticJavaParser.parseType("List<"+dtoName+">"),
                                dtoName+"List",
                                new ObjectCreationExpr(null, StaticJavaParser.parseClassOrInterfaceType("ArrayList"), NodeList.nodeList())
                        )
                );
                methodBody.addStatement(declaration);

//                Generate for loop for iterate each object
                ForEachStmt forEachStmt = new ForEachStmt();
                VariableDeclarationExpr variableDecl = new VariableDeclarationExpr(
                        new VariableDeclarator(StaticJavaParser.parseType(dataType), dataType.toLowerCase())
                );
                forEachStmt.setVariable(variableDecl);
                forEachStmt.setIterable(new NameExpr(responseValue));

                BlockStmt loopBody = new BlockStmt();
                responseValue = mapDtoOrEnum(dataType, proto, loopBody, dataType.toLowerCase(), new ArrayList<>());

//                Generate statement
                MethodCallExpr addCall = new MethodCallExpr(new NameExpr(dtoName+"List"), "add")
                        .addArgument(new NameExpr(responseValue));
                loopBody.addStatement(new ExpressionStmt(addCall));
                forEachStmt.setBody(loopBody);

                responseValue = dtoName+"List";
                methodBody.addStatement(forEachStmt);
                parents.remove(parents.size()-1);
            }
        }
        return responseValue;
    }

    /**
     * Translates a Java primitive data type to its corresponding wrapper class name.
     *
     * This method takes a Java primitive data type (e.g., "int", "long", "boolean")
     * and returns the name of its corresponding wrapper class (e.g., "Integer", "Long", "Boolean").
     * For non-primitive data types, the input string is returned unchanged.
     *
     * @param javaDataType The Java data type to translate.
     * @return The name of the corresponding wrapper class, or the input string if it's not a primitive type.
     */
    public static String translateToObjectName(String javaDataType) {
        return switch (javaDataType) {
            case "int", "Integer" -> "Integer";
            case "long", "Long" -> "Long";
            case "float", "Float" -> "Float";
            case "double", "Double" -> "Double";
            case "boolean", "Boolean" -> "Boolean";
            default -> javaDataType;
        };
    }

    /**
     * Generates the name of a getter method for a field, optionally considering a collection modifier.
     *
     * This method takes a field name and a collection modifier (e.g., "List", "Map", or an empty string).
     * It constructs and returns the name of a getter method for the field, capitalizing the first letter
     * of the field name and appending the collection modifier if provided.
     *
     * @param fieldName          The name of the field for which to generate the getter name.
     * @param collectionModifier The collection modifier to append to the getter name (e.g., "List", "Map", "").
     * @return The generated getter method name.
     */
    public String getGetterName(String fieldName, String collectionModifier) {
        return "request.get" + capitalizeFirstLetter(fieldName) + collectionModifier + "()";
    }

    /**
     * Generates a GRPC reflection service class for the given proto object.
     *
     * This method creates a new Java class that extends the `ProtoReflectionService`
     * and annotates it with `@GRpcService`. This service is responsible for providing
     * reflection information about the defined GRPC services.
     *
     * The generated class includes a constructor that takes a `ServerBuilder`
     * and registers the `ProtoReflectionService` with it.
     *
     * @param proto The proto object containing the service definitions.
     */
    private void genGrpcReflection(ProtoObject proto) {
        CompilationUnit cu = new CompilationUnit();
        cu.setPackageDeclaration(proto.getPackageName());
        cu.addImport("io.grpc.protobuf.services.ProtoReflectionService");
        cu.addImport("org.lognet.springboot.grpc.GRpcService");

        ClassOrInterfaceDeclaration classDeclaration = cu.addClass("GrpcReflectionService")
                .setModifiers(Modifier.Keyword.PUBLIC);
        classDeclaration.addAnnotation(new MarkerAnnotationExpr("GRpcService"));

        ConstructorDeclaration constructor = classDeclaration.addConstructor(Modifier.Keyword.PUBLIC);
        Type serverBuilderType = StaticJavaParser.parseClassOrInterfaceType("io.grpc.ServerBuilder<?>");
        com.github.javaparser.ast.body.Parameter parameter = new com.github.javaparser.ast.body.Parameter(serverBuilderType, "serverBuilder");
        constructor.addParameter(parameter);

        BlockStmt constructorBody = new BlockStmt();
        MethodCallExpr newInstanceCall = new MethodCallExpr(new NameExpr("ProtoReflectionService"), "newInstance");
        NodeList<Expression> arguments = NodeList.nodeList(newInstanceCall);
        MethodCallExpr addServiceCall = new MethodCallExpr(new NameExpr("serverBuilder"), "addService", arguments);

        constructorBody.addStatement(new ExpressionStmt(addServiceCall));
        constructor.setBody(constructorBody);
        storeClassFile(cu, "GrpcReflectionService.java");
    }

    /**
     * Writes a Java class file to the generated sources directory.
     *
     * This method takes a {@link CompilationUnit} representing a Java class and a file name,
     * and writes the class definition to a file with the given name in the generated sources directory.
     * The directory structure for the package of the class is created if it doesn't exist.
     *
     * @param cu       The {@link CompilationUnit} representing the Java class to write.
     * @param fileName The name of the file to create, including the .java extension.
     * @throws RuntimeException If an I/O error occurs while writing the file.
     */
    public void storeClassFile(CompilationUnit cu, String fileName) {
        File outputDirectory = new File(project.getBasedir() + "/target/generated-sources/protosvc/" +
                cu.getPackageDeclaration().orElseThrow().getNameAsString() + "/");

        if (!outputDirectory.exists()) {
            outputDirectory.mkdirs();
        }
        File outputFile = new File(outputDirectory, fileName);

        try (FileWriter fileWriter = new FileWriter(outputFile)) {
            fileWriter.write(cu.toString());
            getLog().info("File saved to: " + outputFile.getAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
