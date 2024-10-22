package org.hypen.GRpcServ;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
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
import java.util.stream.Collectors;

import static org.apache.maven.shared.utils.StringUtils.capitalizeFirstLetter;

@Mojo(name = "svc-gen", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
public class ServiceGenerator extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

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

    private void genGrpcService(ProtoObject proto) {
        getLog().info("GRpcServ generating service: " + proto.getServiceName());
        CompilationUnit cu = new CompilationUnit();
        String defaultPackage = proto.getPackageName() + "." + proto.getServiceName() + "Gen";
        cu.setPackageDeclaration(defaultPackage);

        generateImports(proto, cu, defaultPackage);

        ClassOrInterfaceDeclaration classDeclaration = cu.addClass(proto.getServiceName() + "Gen").setModifiers(Modifier.Keyword.PUBLIC);
        classDeclaration.addAnnotation(new MarkerAnnotationExpr("GRpcService"));
        ClassOrInterfaceType superclass = StaticJavaParser.parseClassOrInterfaceType(proto.getServiceName() + "Grpc." + proto.getServiceName() + "ImplBase");
        cu.addImport(defaultPackage + "." + proto.getServiceName() + "Grpc");
        classDeclaration.setExtendedTypes(NodeList.nodeList(superclass));

        FieldDeclaration field = classDeclaration.addField(StaticJavaParser.parseType(proto.getServiceName()), proto.getServiceName());
        field.setModifiers(Modifier.Keyword.PRIVATE);
        field.addAnnotation(new MarkerAnnotationExpr("Autowired"));
        cu.addImport(proto.getPackageName() + "." + proto.getServiceName());

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

        proto.getEndpoints().forEach(endpoint -> {
            try {
                generateMethods(classDeclaration, endpoint, proto);
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            }
        });

        storeClassFile(cu, proto.getServiceName() + "Gen.java");
    }

    private void generateImports(ProtoObject proto, CompilationUnit cu, String defaultPackage) {
        proto.getMessages().forEach(message -> cu.addImport(defaultPackage + "." + message.getName()));
        cu.addImport("io.grpc.Status");
        cu.addImport("io.grpc.stub.StreamObserver");
        cu.addImport("org.lognet.springboot.grpc.GRpcService");
        cu.addImport("org.springframework.beans.factory.annotation.Autowired");
        cu.addImport("org.modelmapper.ModelMapper");

        List<String> paramDTs = new ArrayList<>();
        proto.getEndpoints().forEach(e->paramDTs.addAll(e.getParams().values().stream().toList()));
        if (NameMapper.anyStartWithStr("List", paramDTs)) cu.addImport("java.util.List");
        if (NameMapper.anyStartWithStr("Map", paramDTs)) cu.addImport("java.util.Map");
        if (NameMapper.anyStartWithStr("Set", paramDTs)) cu.addImport("java.util.Set");
        if (NameMapper.anyStartWithStr("Collection", paramDTs)) cu.addImport("java.util.Collection");

        proto.getDtoMap().keySet().forEach(key -> {
            if (NameMapper.anyStartWithStr(key, paramDTs)) cu.addImport(proto.getDtoMap().get(key));
        });
    }

    private void generateMethods(ClassOrInterfaceDeclaration classDeclaration, Endpoint endpoint, ProtoObject proto) throws FileNotFoundException {
        getLog().info("Generating method: " + endpoint.getName());
        MethodDeclaration method = classDeclaration.addMethod(endpoint.getName(), Modifier.Keyword.PUBLIC);
        method.addAnnotation(new MarkerAnnotationExpr("Override"));

        NodeList<com.github.javaparser.ast.body.Parameter> params = new NodeList<>(
                new com.github.javaparser.ast.body.Parameter(StaticJavaParser.parseType(endpoint.getRequest().getName()), "request"),
                new com.github.javaparser.ast.body.Parameter(StaticJavaParser.parseType("StreamObserver<" + endpoint.getResponse().getName() + ">"), "responseObserver"));
        method.setParameters(params);

        BlockStmt methodBody = new BlockStmt();

        ClassOrInterfaceType returnType = StaticJavaParser.parseClassOrInterfaceType(translateToObjectName(endpoint.getParams().get("genResponse")));
        MethodCallExpr methodCallExpr = new MethodCallExpr(new NameExpr(proto.getServiceName()), endpoint.getName());
        for (Map.Entry<String, String> param : endpoint.getParams().entrySet()) {
            if (param.getKey().equals("genResponse")) continue;

            String collectionModifier = "";
            if (param.getValue().startsWith("List")) collectionModifier = "List";
            if (param.getValue().startsWith("Map")) collectionModifier = "Map";

            String varName = generateRequestParamMapping(getGetterName(param.getKey(), collectionModifier), param, methodBody, proto);
            methodCallExpr.addArgument(new NameExpr(varName));
        }
        VariableDeclarationExpr variableDeclExpr = new VariableDeclarationExpr(
                new VariableDeclarator(returnType, "methodResponse", methodCallExpr)
        );
        methodBody.addStatement(variableDeclExpr);

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

        String responseValue = "methodResponse";
        responseValue = mapDtoOrEnum(endpoint.getParams().get("genResponse"), proto, methodBody, responseValue, new ArrayList<>());

        ClassOrInterfaceType grpcType = StaticJavaParser.parseClassOrInterfaceType(endpoint.getResponse().getName());
        MethodCallExpr builderCall = new MethodCallExpr(new NameExpr(endpoint.getResponse().getName()), "newBuilder");
        MethodCallExpr setResponse = new MethodCallExpr(builderCall, responseType).addArgument(responseValue);
        MethodCallExpr buildCall = new MethodCallExpr(setResponse, "build");
        VariableDeclarationExpr grpcVariableDeclExpr = new VariableDeclarationExpr(
                new VariableDeclarator(grpcType, endpoint.getResponse().getName() + "Gen", buildCall)
        );
        methodBody.addStatement(grpcVariableDeclExpr);

        MethodCallExpr onNextCall = new MethodCallExpr(new NameExpr("responseObserver"), "onNext").addArgument(new NameExpr(endpoint.getResponse().getName() + "Gen"));
        methodBody.addStatement(onNextCall);

        MethodCallExpr onCompletedCall = new MethodCallExpr(new NameExpr("responseObserver"), "onCompleted");
        methodBody.addStatement(onCompletedCall);

        method.setBody(methodBody);
    }

    private String generateRequestParamMapping(String getterName, Map.Entry<String, String> param, BlockStmt methodBody, ProtoObject proto) {
        if (proto.getDtoMap().containsKey(param.getValue())) {
            String varName = param.getKey() + "RequestDto";

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

    private String mapDtoOrEnum(String dataType, ProtoObject proto, BlockStmt methodBody, String responseValue, List<String> parents) throws FileNotFoundException {
        if (proto.getDtoMap().containsKey(dataType)){
            parents.add(dataType);
            NameMapper nm = NameMapper.getInstance(project, proto.getDtoMap());
            CompilationUnit cu = StaticJavaParser.parse(new File(nm.mapFQN(dataType)));
            Optional<ClassOrInterfaceDeclaration> dtoClassDecl = cu.findFirst(ClassOrInterfaceDeclaration.class);
            Optional<EnumDeclaration> enumDeclaration = cu.findFirst(EnumDeclaration.class);

            if (dtoClassDecl.isPresent()) {
                ClassOrInterfaceType grpcType = StaticJavaParser.parseClassOrInterfaceType(dataType + "Dto");
                MethodCallExpr builderCall = new MethodCallExpr(new NameExpr(grpcType.getName()), "newBuilder");

                MethodCallExpr setResponse = builderCall;
                for (FieldDeclaration field : dtoClassDecl.get().getFields()) {
                    String innerResponseValue = responseValue;
                    String name = field.getVariables().get(0).getNameAsString();
                    String fieldDataType = field.getVariable(0).getType().toString();

                    innerResponseValue = mapDtoOrEnum(fieldDataType, proto, methodBody, innerResponseValue, parents);

                    if (proto.getDtoMap().containsKey(fieldDataType)) {
                        setResponse = new MethodCallExpr(setResponse, NameMapper.setterName(name, ""))
                                .addArgument(innerResponseValue);
                    } else {
                        String parentGetters = parents.stream().skip(1)
                                .map(e -> NameMapper.getterName(e, "()"))
                                .collect(Collectors.joining("."));
                        if (!parentGetters.isEmpty()) parentGetters = "." + parentGetters;

                        setResponse = new MethodCallExpr(setResponse, NameMapper.setterName(name, ""))
                                .addArgument(innerResponseValue + parentGetters + "." + NameMapper.getterName(name, "()"));
                    }
                }

                responseValue = dataType + "DtoGen";
                MethodCallExpr buildCall = new MethodCallExpr(setResponse, "build");
                VariableDeclarationExpr grpcVariableDeclExpr = new VariableDeclarationExpr(
                        new VariableDeclarator(grpcType, responseValue, buildCall)
                );
                methodBody.addStatement(grpcVariableDeclExpr);
            }

            if (enumDeclaration.isPresent()){
                String enumName = dataType + "Enum";
                String parentDtoName = parents.get(parents.size() - 2) + "Dto";
                Message message = proto.getMessages().stream().filter(e -> e.getName().equals(parentDtoName)).findFirst().orElseThrow();
                String fieldName = NameMapper.extractWordAfter(enumName, message.getFields());

                String responseCatcher = parents.size() > 2 ? parentDtoName + "Gen" : "methodResponse";
                ClassOrInterfaceType enumType = StaticJavaParser.parseClassOrInterfaceType(enumName);
                NameExpr methodResponseExpr = new NameExpr(responseCatcher);
                MethodCallExpr getTypeExpr = new MethodCallExpr(methodResponseExpr, NameMapper.getterName(fieldName, ""));

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
        }
        return responseValue;
    }

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

    public String getGetterName(String fieldName, String collectionModifier) {
        return "request.get" + capitalizeFirstLetter(fieldName) + collectionModifier + "()";
    }

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
