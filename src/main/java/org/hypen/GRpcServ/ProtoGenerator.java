package org.hypen.GRpcServ;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.nodeTypes.NodeWithType;
import com.github.javaparser.quality.NotNull;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.utils.StringUtils;
import org.hypen.GRpcServ.models.Endpoint;
import org.hypen.GRpcServ.models.Message;
import org.hypen.GRpcServ.models.ProtoObject;
import org.hypen.GRpcServ.utils.GrpcDataTranslator;
import org.hypen.GRpcServ.utils.NameMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Mojo(name = "proto-gen", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
public class ProtoGenerator extends AbstractMojo {
    private static final Logger log = LoggerFactory.getLogger(ProtoGenerator.class);
    List<ProtoObject> protoObjects = new ArrayList<>(1);

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    @Parameter(property = "sourceDirectories")
    private String sourceDirectories;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        File outputDir = new File(project.getBasedir() + "/target/generated-sources/proto");
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        if (StringUtils.isEmpty(sourceDirectories)) {
            getLog().error("Plugin 'sourceDirectories' not configured");
            return;
        }

        Arrays.stream(sourceDirectories.split(","))
                .map(String::trim)
                .forEach(dir -> {
                    try {
                        getLog().info("Processing source directory: " + dir);
                        generateProtoFiles(project.getBasedir() + "/" + dir);
                    } catch (Exception e) {
                        getLog().error("Error processing source directory: " + dir, e);
                        throw new RuntimeException(e);
                    }
                });

        try (ObjectOutputStream oos = new ObjectOutputStream(
                new FileOutputStream(outputDir.getAbsolutePath() + "/proto-metadata.dat"))) {
            oos.writeObject(protoObjects);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void generateProtoFiles(String sourceDirectory) throws TemplateException, IOException {
        getLog().info("Source directory: " + sourceDirectory);
        File sourceDir = new File(sourceDirectory);
        if (sourceDir.exists() && sourceDir.isDirectory()) {
            for (File file : Objects.requireNonNull(sourceDir.listFiles())) {
                if (file.isFile() && file.getName().endsWith(".java")) {
                    getLog().info("\tProcessing source file: " + file.getName());
                    try {
                        parseMethodsByAnnotation(file.getAbsolutePath());
                    } catch (RuntimeException runtimeException){
                        getLog().warn(runtimeException.getMessage());
                    }
                }
            }
        }
    }

    private void parseMethodsByAnnotation(String sourceDir) throws IOException, TemplateException {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        CompilationUnit cu = StaticJavaParser.parse(new FileInputStream(sourceDir));

        ProtoObject protoObject = new ProtoObject();
        protoObject.setPackageName(cu.getPackageDeclaration().orElseThrow().getNameAsString());
        protoObject.setServiceName(cu.findFirst(ClassOrInterfaceDeclaration.class)
                .orElseThrow(() -> new RuntimeException("No class found in the file: " + sourceDir))
                .getNameAsString());

        List<MethodDeclaration> methods = cu.findAll(MethodDeclaration.class,
                m -> m.getAnnotationByName("GRpcServ").isPresent());

        Map<String, String> dtoMap = generateImportMap(cu);
        dtoMap.put("package", cu.getPackageDeclaration().orElseThrow().getNameAsString());
        NameMapper nm = NameMapper.getInstance(project, dtoMap);

        for (MethodDeclaration method : methods) {
            getLog().info("\t\tParsing method: " + method.getNameAsString());
            List<com.github.javaparser.ast.body.Parameter> params = method.getParameters();
            Message request = new Message(
                    Message.Type.GRpcMessage,
                    method.getNameAsString() + "Request",
                    IntStream.range(0, params.size())
                            .mapToObj(i -> String.format("\t%s %s = %d;",
                                    GrpcDataTranslator.translateToGrpcDataType(nm.mapFQN(params.get(i).getTypeAsString()), protoObject),
                                    params.get(i).getNameAsString(), i + 1))
                            .collect(Collectors.joining("\n"))
            );

            Message response = new Message(
                    Message.Type.GRpcMessage,
                    method.getNameAsString() + "Response",
                    String.format("\t%s %s = 1;", GrpcDataTranslator.translateToGrpcDataType(nm.mapFQN(method.getTypeAsString()), protoObject), "response")
            );

            Map<String, String> metaParams = method.getParameters().stream()
                    .collect(Collectors.toMap(
                            NodeWithSimpleName::getNameAsString,
                            NodeWithType::getTypeAsString,
                            (v1, v2) -> v1,
                            LinkedHashMap::new
                    ));
            metaParams.put("genResponse", method.getTypeAsString());

            Endpoint endpoint = new Endpoint(method.getNameAsString(), request, response, metaParams);
            protoObject.getEndpoints().add(endpoint);
            protoObject.getMessages().add(request);
            protoObject.getMessages().add(response);
        }

        if (!methods.isEmpty()) {
            String outputDir = project.getBasedir() + "/target/generated-sources/proto";
            generateProtoFiles(protoObject, outputDir);
            protoObjects.add(protoObject);
        }
    }
//
//    private String mapFQN(String s, Map<String, String> dtoMap) {
//        if (GrpcDataTranslator.JAVA_DATA_TYPES.contains(s)) return s;
//        if (dtoMap.containsKey(s)) {
//            return project.getBasedir() + "/src/main/java/" + dtoMap.get(s).replace('.', '/') + ".java";
//        } else {
////            Assume Classes in same package
//            return project.getBasedir() + "/src/main/java/" + dtoMap.get("package").replace('.', '/') + "/" + s + ".java";
//        }
//    }

    public static Map<String, String> generateImportMap(CompilationUnit cu) {
        Map<String, String> dtoMap = new HashMap<>();
        cu.getImports().forEach(importDecl -> {
            String importStr = importDecl.toString().replace("import ", "");
            importStr = importStr.replace(";", "").trim();

            String[] importArr = importStr.split("\\.");
            int lastElement = importArr.length - 1;
            if (importArr[lastElement].equals("*")) {
//                TODO Handle * imports
                log.warn("Current version not supporting * imports");
            } else {
                dtoMap.put(importArr[lastElement].trim(), importStr);
            }
        });
        return dtoMap;
    }

    private void generateProtoFiles(@NotNull ProtoObject protoObject, @NotNull String outputDirectory)
            throws IOException, TemplateException {
        getLog().info("\tGenerating service proto file: " + protoObject.getServiceName());
        Map<String, Object> data = new HashMap<>();
        data.put("packageName", protoObject.getPackageName());
        data.put("serviceName", protoObject.getServiceName());
        data.put("javaMultipleFiles", protoObject.getJavaMultipleFiles());

        data.put("imports", protoObject.getImports());

        List<Map<String, String>> endpoints = new ArrayList<>();
        protoObject.getEndpoints().forEach(endpoint -> {
            endpoints.add(Map.of(
                    "name", endpoint.getName(),
                    "request", endpoint.getRequest().getName(),
                    "response", endpoint.getResponse().getName()));
        });
        data.put("endpoints", endpoints);

        List<Map<String, String>> messages = new ArrayList<>();
        protoObject.getMessages().stream()
                .filter(message -> message.getType() == Message.Type.GRpcMessage)
                .forEach(message -> {
                    messages.add(Map.of(
                            "name", message.getName(),
                            "fields", message.getFields()));
                });
        data.put("messages", messages);

        List<Map<String, String>> enums = new ArrayList<>();
        protoObject.getMessages().stream()
                .filter(message -> message.getType() == Message.Type.GRpcEnum)
                .forEach(message -> {
                    enums.add(Map.of(
                            "name", message.getName(),
                            "fields", message.getFields()));
                });
        data.put("enums", enums);

        Configuration cfg = new Configuration(Configuration.VERSION_2_3_31);
        cfg.setClassForTemplateLoading(this.getClass(), "/");
        cfg.setDefaultEncoding("UTF-8");
        Template template = cfg.getTemplate("proto_template.ftl");
        StringWriter writer = new StringWriter();
        template.process(data, writer);

        try (FileWriter fileWriter = new FileWriter(
                Paths.get(outputDirectory, protoObject.getServiceName() + ".proto").toString())) {
            fileWriter.write(writer.toString());
            getLog().info(String.format("\tProto file generated: %s/%s%s", outputDirectory, protoObject.getServiceName(), ".proto"));
        }
    }
}
