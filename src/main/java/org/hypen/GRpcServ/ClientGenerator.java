package org.hypen.GRpcServ;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.utils.StringUtils;
import org.hypen.GRpcServ.models.Endpoint;
import org.hypen.GRpcServ.models.ProtoObject;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

@Mojo(name = "client-gen", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
public class ClientGenerator extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    @Parameter(property = "feignDirectories")
    private String feignDirectories;

    List<ProtoObject> protoObjects;
    ProtoObject currentProto;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(
                project.getBasedir() + "/target/generated-sources/proto/proto-metadata.dat"))) {
            protoObjects = (List<ProtoObject>) ois.readObject();
        } catch (Exception e) {
            getLog().error("Error reading proto-metadata.dat\n" + e.getMessage());
            return;
        }

        if (StringUtils.isEmpty(feignDirectories)) {
            getLog().error("Plugin 'feignDirectories' not configured");
            return;
        }

        Arrays.stream(feignDirectories.split(","))
                .map(String::trim)
                .forEach(dir -> {
                    try {
                        getLog().info("Processing feign directory: " + dir);
                        processFeignDir(project.getBasedir() + "/" + dir);
                    } catch (Exception e) {
                        getLog().error("Error processing feign directory: " + dir, e);
                        throw new RuntimeException(e);
                    }
                });
    }

    private void processFeignDir(String feignDirectory) {
        getLog().info("Feign directory: " + feignDirectory);
        File sourceDir = new File(feignDirectory);
        if (sourceDir.exists() && sourceDir.isDirectory()) {
            for (File file : Objects.requireNonNull(sourceDir.listFiles())) {
                if (file.isFile() && file.getName().endsWith(".java")) {
                    getLog().info("\tProcessing feign file: " + file.getName());
                    try {
                        generateClientImpl(file.getAbsolutePath(), feignDirectory);
                    } catch (RuntimeException | IOException runtimeException) {
                        getLog().warn(runtimeException.getMessage());
                    }
                }
            }
        }
    }

    private void generateClientImpl(String absolutePath, String feignDirectory) throws IOException {
        String packageLocation = feignDirectory.replace(project.getBasedir() + "/src/main/java/", "");
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        CompilationUnit cu = StaticJavaParser.parse(new FileInputStream(absolutePath));
        String interfaceName = cu.findFirst(ClassOrInterfaceDeclaration.class)
                .orElseThrow(() -> new RuntimeException("No interface found in the file: " + absolutePath))
                .getNameAsString();
        List<MethodDeclaration> methods = cu.findAll(MethodDeclaration.class,
                m -> m.getAnnotationByName("GRpcServClient").isPresent());

        CompilationUnit implCu = new CompilationUnit();
        implCu.setPackageDeclaration(packageLocation.replace('/', '.'));
        ClassOrInterfaceDeclaration implClass = implCu.addClass(interfaceName+ "GRpcImpl");
        implClass.setAbstract(true);
        ClassOrInterfaceDeclaration feignInterface = cu.getInterfaceByName(interfaceName).get();
        implClass.addImplementedType(new ClassOrInterfaceType(null, feignInterface.getNameAsString()));

        for (MethodDeclaration method : methods) {
            MethodDeclaration newMethod = implClass.addMethod(method.getNameAsString());
            newMethod.addAnnotation("Override");
            newMethod.setPublic(true);
            newMethod.setType(method.getType());
            newMethod.setParameters(method.getParameters());

            BlockStmt block = newMethod.createBody();
            generateMethodBody(block, method);
        }
        makeImports(implCu);

        // Save the new class to a file
        storeClassFile(implCu, interfaceName + "GRpcImpl.java");
    }

    private void makeImports(CompilationUnit implCu) {
        implCu.addImport("io.grpc.ManagedChannel");
        implCu.addImport("io.grpc.ManagedChannelBuilder");

        if (currentProto != null)
            implCu.addImport(currentProto.getPackageName()+"."+currentProto.getServiceName()+"Gen.*");
    }

    private void generateMethodBody(BlockStmt block, MethodDeclaration method) {
//        Get annotation's attributes as pairs
        NormalAnnotationExpr normalAnnotationExpr = method.getAnnotationByName("GRpcServClient")
                .orElseThrow().asNormalAnnotationExpr();
        Map<SimpleName, Expression> pairs = normalAnnotationExpr.getPairs().stream()
                .collect(Collectors.toMap(MemberValuePair::getName, MemberValuePair::getValue));

        ProtoObject proto = protoObjects.stream()
                .filter(e -> e.getServiceName().equals(pairs.get(new SimpleName("service")).asStringLiteralExpr().getValue()))
                .findFirst().orElseThrow();
        currentProto = proto;

        String endpointName = pairs.get(new SimpleName("endpoint")).asStringLiteralExpr().getValue();
        Endpoint endpoint = proto.getEndpoints().stream()
                .filter(e -> e.getName().equals(endpointName.isBlank() ? method.getNameAsString() : endpointName))
                .findFirst().orElseThrow();

        ExpressionStmt channelDecl = new ExpressionStmt(
                new AssignExpr( new NameExpr("channel"), new MethodCallExpr( new MethodCallExpr( new MethodCallExpr(
                        new NameExpr("ManagedChannelBuilder"), "forAddress",
                        new NodeList<>(
                                new StringLiteralExpr(pairs.get(new SimpleName("host")).asStringLiteralExpr().getValue()),
                                new IntegerLiteralExpr(pairs.get(new SimpleName("port")).asIntegerLiteralExpr().getValue())
                        ) ), "usePlaintext" ), "build" ),
                AssignExpr.Operator.ASSIGN));
        channelDecl.getExpression().asAssignExpr().setTarget(new VariableDeclarationExpr(new ClassOrInterfaceType(null, "ManagedChannel"), "channel"));
        block.addStatement(channelDecl);

        ExpressionStmt stubDecl = new ExpressionStmt( new AssignExpr(
                new NameExpr("stub"), new MethodCallExpr(
                        new NameExpr(proto.getServiceName()+"Grpc"), "newBlockingStub",
                        new NodeList<>(new NameExpr("channel"))
                ), AssignExpr.Operator.ASSIGN));
        stubDecl.getExpression().asAssignExpr().setTarget(new VariableDeclarationExpr(new ClassOrInterfaceType(null, "DtosGrpc.DtosBlockingStub"), "stub"));
        block.addStatement(stubDecl);

        ExpressionStmt requestDecl = new ExpressionStmt( new AssignExpr(
                new NameExpr("request"), new MethodCallExpr(
                        new MethodCallExpr(
                                new MethodCallExpr(
                                        new NameExpr(endpoint.getRequest().getName()),
                                        "newBuilder"
                                ), "setStu",
                                new NodeList<>(new StringLiteralExpr("student1"))
                        ), "build"
                ), AssignExpr.Operator.ASSIGN));
        requestDecl.getExpression().asAssignExpr().setTarget(new VariableDeclarationExpr(new ClassOrInterfaceType(null, endpoint.getRequest().getName()), "request"));
        block.addStatement(requestDecl);

        ExpressionStmt responseDecl = new ExpressionStmt( new AssignExpr(
                        new NameExpr("response"), new MethodCallExpr(
                                new NameExpr("stub"), endpoint.getName(),
                                new NodeList<>(new NameExpr("request"))
                        ), AssignExpr.Operator.ASSIGN));
        responseDecl.getExpression().asAssignExpr().setTarget(new VariableDeclarationExpr(new ClassOrInterfaceType(null, endpoint.getResponse().getName()), "response"));
        block.addStatement(responseDecl);

        ExpressionStmt shutdownStmt = new ExpressionStmt(new MethodCallExpr(new NameExpr("channel"), "shutdown"));
        block.addStatement(shutdownStmt);

        // Add a default implementation (e.g., return a default value)
        ReturnStmt returnStmt = new ReturnStmt();
        if (method.getType().isVoidType()) {
            returnStmt.setExpression(null);
        } else if (method.getType().isPrimitiveType()) {
            returnStmt.setExpression(new NameExpr(method.getType().asPrimitiveType().asString() + "Value"));
        } else {
            returnStmt.setExpression(new NameExpr("null"));
        }
        block.addStatement(returnStmt);
    }

    public void storeClassFile(CompilationUnit cu, String fileName) {
        File outputDirectory = new File(project.getBasedir() + "/target/generated-sources/protoclient/" +
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
