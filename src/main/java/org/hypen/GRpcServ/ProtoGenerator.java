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

//        Process one by one source directories
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

    /**
     * Generates proto files for all Java classes annotated with `@GRpcServ` in the given source directory.
     * <p>
     * This method iterates through all files in the source directory and its subdirectories.
     * For each Java file encountered, it parses the file and extracts all methods annotated with `@GRpcServ`.
     * For each annotated method, it generates a corresponding proto file containing the service definition,
     * message definitions for the request and response objects, and any necessary enum definitions.
     *
     * @param sourceDirectory The path to the source directory containing the Java files to process.
     * @throws TemplateException If an error occurs while processing the Freemarker template for the proto file.
     * @throws IOException       If an error occurs while reading or writing files.
     */
    private void generateProtoFiles(String sourceDirectory) throws TemplateException, IOException {
        getLog().info("Source directory: " + sourceDirectory);
        File sourceDir = new File(sourceDirectory);
        if (sourceDir.exists() && sourceDir.isDirectory()) {
            for (File file : Objects.requireNonNull(sourceDir.listFiles())) {
                if (file.isFile() && file.getName().endsWith(".java")) {
                    getLog().info("\tProcessing source file: " + file.getName());
                    try {
                        parseMethodsByAnnotation(file.getAbsolutePath());
                    } catch (RuntimeException runtimeException) {
                        getLog().warn(runtimeException.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Parses a Java source file for methods annotated with `@GRpcServ` and generates a {@link ProtoObject} representing the service.
     * <p>
     * This method reads the Java source file located at the given `sourceDir`, parses it using JavaParser,
     * and extracts all methods annotated with `@GRpcServ`. For each annotated method, it creates
     * corresponding request and response messages, and adds them to a {@link ProtoObject} along with
     * the endpoint definition. The generated {@link ProtoObject} is then added to the `protoObjects` list.
     *
     * @param sourceDir The path to the Java source file to parse.
     * @throws IOException       If an I/O error occurs while reading the source file.
     * @throws TemplateException If an error occurs while processing the Freemarker template for generating the proto file.
     */
    private void parseMethodsByAnnotation(String sourceDir) throws IOException, TemplateException {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        CompilationUnit cu = StaticJavaParser.parse(new FileInputStream(sourceDir));

        ProtoObject protoObject = new ProtoObject();
        protoObject.setPackageName(cu.getPackageDeclaration().orElseThrow().getNameAsString());
        protoObject.setServiceName(cu.findFirst(ClassOrInterfaceDeclaration.class)
                .orElseThrow(() -> new RuntimeException("No class found in the file: " + sourceDir))
                .getNameAsString());

//        Extract methods which annotated with @GRpcServ
        List<MethodDeclaration> methods = cu.findAll(MethodDeclaration.class,
                m -> m.getAnnotationByName("GRpcServ").isPresent());

        Map<String, String> dtoMap = generateImportMap(cu);
        dtoMap.put("package", cu.getPackageDeclaration().orElseThrow().getNameAsString());
        protoObject.setDtoMap(dtoMap);
        NameMapper nm = NameMapper.getInstance(project, dtoMap);

//        Process each method one by one
        for (MethodDeclaration method : methods) {
            getLog().info("\t\tParsing method: " + method.getNameAsString());
            List<com.github.javaparser.ast.body.Parameter> params = method.getParameters();
//            Extract parameters from method
            Message request = new Message(
                    Message.Type.GRpcMessage,
                    method.getNameAsString() + "Request",
                    IntStream.range(0, params.size())
                            .mapToObj(i -> String.format("\t%s %s = %d;",
                                    GrpcDataTranslator.translateToGrpcDataType(nm.mapFQN(params.get(i).getTypeAsString()), protoObject),
                                    params.get(i).getNameAsString(), i + 1))
                            .collect(Collectors.joining("\n"))
            );

//            Extract return type of method
            Message response = new Message(
                    Message.Type.GRpcMessage,
                    method.getNameAsString() + "Response",
                    method.getTypeAsString().equals("void") ? "" :
                            String.format("\t%s %s = 1;", GrpcDataTranslator.translateToGrpcDataType(nm.mapFQN(method.getTypeAsString()), protoObject), "response")
            );

//            Identify data types used in method and added into metaParams map with parameter name
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

//        Generate proto file if there are any methods
        if (!methods.isEmpty()) {
            String outputDir = project.getBasedir() + "/target/generated-sources/proto";
            generateProtoFiles(protoObject, outputDir);
            protoObjects.add(protoObject);
        }
    }

    /**
     * Generates a map of class simple names to their fully qualified names from the imports of a CompilationUnit.
     * <p>
     * This method iterates through the imports of a given CompilationUnit and extracts the simple name
     * and fully qualified name of each imported class. It handles both single class imports and wildcard imports.
     * For wildcard imports, it currently logs a warning as they are not fully supported.
     *
     * @param cu The CompilationUnit to extract imports from.
     * @return A map where keys are class simple names and values are their corresponding fully qualified names.
     */
    public static Map<String, String> generateImportMap(CompilationUnit cu) {
        Map<String, String> dtoMap = new HashMap<>();
        cu.getImports().forEach(importDecl -> {
//            Process each import
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

    /**
     * Generates a .proto file from a {@link ProtoObject}.
     * <p>
     * This method takes a {@link ProtoObject} and an output directory, and generates a .proto file
     * representing the service defined in the {@link ProtoObject}. The generated file includes the
     * service definition, message definitions, and enum definitions.
     *
     * @param protoObject     The {@link ProtoObject} containing the service definition to generate.
     * @param outputDirectory The directory where the generated .proto file should be written.
     * @throws IOException       If an I/O error occurs while writing the .proto file.
     * @throws TemplateException If an error occurs while processing the Freemarker template.
     */
    private void generateProtoFiles(@NotNull ProtoObject protoObject, @NotNull String outputDirectory)
            throws IOException, TemplateException {
        getLog().info("\tGenerating service proto file: " + protoObject.getServiceName());
//        Generate proto file using freemarker template
        Map<String, Object> data = new HashMap<>();
        data.put("packageName", protoObject.getPackageName());
        data.put("serviceName", protoObject.getServiceName());
        data.put("javaMultipleFiles", protoObject.getJavaMultipleFiles());

        data.put("imports", protoObject.getImports());

//        Generate service declarations
        List<Map<String, String>> endpoints = new ArrayList<>();
        protoObject.getEndpoints().forEach(endpoint -> {
            endpoints.add(Map.of(
                    "name", endpoint.getName(),
                    "request", endpoint.getRequest().getName(),
                    "response", endpoint.getResponse().getName()));
        });
        data.put("endpoints", endpoints);

//        Generate message declarations for DTOs
        List<Map<String, String>> messages = new ArrayList<>();
        protoObject.getMessages().stream()
                .filter(message -> message.getType() == Message.Type.GRpcMessage)
                .forEach(message -> {
                    messages.add(Map.of(
                            "name", message.getName(),
                            "fields", message.getFields()));
                });
        data.put("messages", messages);

//        Generate enum declaration
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

//        Write proto file on disk
        try (FileWriter fileWriter = new FileWriter(
                Paths.get(outputDirectory, protoObject.getServiceName() + ".proto").toString())) {
            fileWriter.write(writer.toString());
            getLog().info(String.format("\tProto file generated: %s/%s%s", outputDirectory, protoObject.getServiceName(), ".proto"));
        }
    }
}
