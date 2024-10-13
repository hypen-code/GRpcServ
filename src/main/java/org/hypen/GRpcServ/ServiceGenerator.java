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
import org.hypen.GRpcServ.models.ProtoObject;
import org.springframework.util.CollectionUtils;

import java.io.*;
import java.util.List;
import java.util.Map;

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

        if (!CollectionUtils.isEmpty(protoObjects))genGrpcReflection(protoObjects.get(0));

        protoObjects.forEach(this::genGrpcService);
    }

    private void genGrpcService(ProtoObject proto) {
        getLog().info("GRpcServ generating service: " + proto.getServiceName());
        CompilationUnit cu = new CompilationUnit();
        String defaultPackage = proto.getPackageName()+"."+proto.getServiceName()+"Gen";
        cu.setPackageDeclaration(defaultPackage);

        proto.getMessages().forEach(message -> cu.addImport(defaultPackage+"."+message.getName()));
        if (proto.getMessages().stream().anyMatch(msg -> msg.getFields().contains("repeated"))) cu.addImport("java.util.List");
        if (proto.getMessages().stream().anyMatch(msg -> msg.getFields().contains("map"))) cu.addImport("java.util.Map");
        cu.addImport("io.grpc.Status");
        cu.addImport("io.grpc.stub.StreamObserver");
        cu.addImport("org.lognet.springboot.grpc.GRpcService");
        cu.addImport("org.springframework.beans.factory.annotation.Autowired");

        ClassOrInterfaceDeclaration classDeclaration = cu.addClass(proto.getServiceName()+"Gen").setModifiers(Modifier.Keyword.PUBLIC);
        classDeclaration.addAnnotation(new MarkerAnnotationExpr("GRpcService"));
        ClassOrInterfaceType superclass = StaticJavaParser.parseClassOrInterfaceType(proto.getServiceName() +"Grpc."+proto.getServiceName()+"ImplBase");
        cu.addImport(defaultPackage+"."+proto.getServiceName()+"Grpc");
        classDeclaration.setExtendedTypes(NodeList.nodeList(superclass));

        FieldDeclaration field = classDeclaration.addField(StaticJavaParser.parseType(proto.getServiceName()), proto.getServiceName());
        field.addAnnotation(new MarkerAnnotationExpr("Autowired"));
        cu.addImport(proto.getPackageName()+"."+proto.getServiceName());

        proto.getEndpoints().forEach(endpoint ->generateMethods(classDeclaration, endpoint, proto));

        storeClassFile(cu, proto.getServiceName() + "Gen.java");
    }

    private void generateMethods(ClassOrInterfaceDeclaration classDeclaration, Endpoint endpoint, ProtoObject proto) {
        getLog().info("Generating method: " + endpoint.getName());
        MethodDeclaration method = classDeclaration.addMethod(endpoint.getName(), Modifier.Keyword.PUBLIC);
        method.addAnnotation(new MarkerAnnotationExpr("Override"));

        NodeList<com.github.javaparser.ast.body.Parameter> params = new NodeList<>(
                new com.github.javaparser.ast.body.Parameter(StaticJavaParser.parseType(endpoint.getRequest().getName()), "request"),
                new com.github.javaparser.ast.body.Parameter(StaticJavaParser.parseType("StreamObserver<"+endpoint.getResponse().getName()+">"), "responseObserver"));
        method.setParameters(params);

        BlockStmt methodBody = new BlockStmt();

        ClassOrInterfaceType returnType = StaticJavaParser.parseClassOrInterfaceType(translateToObjectName(endpoint.getParams().get("genResponse")));
        MethodCallExpr methodCallExpr = new MethodCallExpr(new NameExpr(proto.getServiceName()), endpoint.getName());
        for (Map.Entry<String, String> param : endpoint.getParams().entrySet()) {
            if (param.getKey().equals("genResponse")) continue;

            String collectionModifier = "";
            if (param.getValue().startsWith("List")) collectionModifier = "List";
            if (param.getValue().startsWith("Map")) collectionModifier = "Map";
            methodCallExpr.addArgument(new NameExpr(getGetterName(param.getKey(), collectionModifier)));
        }
        VariableDeclarationExpr variableDeclExpr = new VariableDeclarationExpr(
                new VariableDeclarator(returnType, "methodResponse", methodCallExpr)
        );
        methodBody.addStatement(variableDeclExpr);

        String responseType = "setResponse";
        if (endpoint.getParams().containsKey("genResponse") && endpoint.getParams().get("genResponse").startsWith("List")) responseType = "addAllResponse";
        if (endpoint.getParams().containsKey("genResponse") && endpoint.getParams().get("genResponse").startsWith("Map")) responseType = "putAllResponse";

        ClassOrInterfaceType grpcUserType = StaticJavaParser.parseClassOrInterfaceType(endpoint.getResponse().getName());
        MethodCallExpr builderCall = new MethodCallExpr(new NameExpr(endpoint.getResponse().getName()), "newBuilder");
        MethodCallExpr setResponse = new MethodCallExpr(builderCall, responseType).addArgument("methodResponse");
        MethodCallExpr buildCall = new MethodCallExpr(setResponse, "build");
        VariableDeclarationExpr grpcVariableDeclExpr = new VariableDeclarationExpr(
                new VariableDeclarator(grpcUserType, endpoint.getResponse().getName()+"Gen", buildCall)
        );
        methodBody.addStatement(grpcVariableDeclExpr);

        MethodCallExpr onNextCall = new MethodCallExpr(new NameExpr("responseObserver"), "onNext").addArgument(new NameExpr(endpoint.getResponse().getName()+"Gen"));
        methodBody.addStatement(onNextCall);

        MethodCallExpr onCompletedCall = new MethodCallExpr(new NameExpr("responseObserver"), "onCompleted");
        methodBody.addStatement(onCompletedCall);

        method.setBody(methodBody);
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
