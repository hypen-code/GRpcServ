package org.hypen.GRpcServ;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
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
import org.hypen.GRpcServ.models.ProtoObject;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

@Mojo(name = "client-gen", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
public class ClientGenerator extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    @Parameter(property = "feignDirectories")
    private String feignDirectories;

    List<ProtoObject> protoObjects;

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
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        CompilationUnit cu = StaticJavaParser.parse(new FileInputStream(absolutePath));
        String interfaceName = cu.findFirst(ClassOrInterfaceDeclaration.class)
                .orElseThrow(() -> new RuntimeException("No interface found in the file: " + absolutePath))
                .getNameAsString();
        List<MethodDeclaration> methods = cu.findAll(MethodDeclaration.class,
                m -> m.getAnnotationByName("GRpcServClient").isPresent());

        CompilationUnit implCu = new CompilationUnit();
        ClassOrInterfaceDeclaration implClass = implCu.addClass(interfaceName+ "GRpcImpl");
        implClass.setAbstract(true);
        ClassOrInterfaceDeclaration feignInterface = cu.getInterfaceByName(interfaceName).get();
        implClass.addImplementedType(new ClassOrInterfaceType(null, feignInterface.getNameAsString()));

        for (MethodDeclaration method : methods) {
            MethodDeclaration newMethod = implClass.addMethod(method.getNameAsString(), method.getType(), method.getModifiers());
            BlockStmt block = newMethod.createBody();

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

        // Save the new class to a file
        Files.write(Paths.get(feignDirectory+ "/" + interfaceName+"GRpcImpl.java"), implCu.toString().getBytes());
    }


}
