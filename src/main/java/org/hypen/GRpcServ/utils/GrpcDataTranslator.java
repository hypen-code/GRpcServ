package org.hypen.GRpcServ.utils;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.hypen.GRpcServ.ProtoGenerator;
import org.hypen.GRpcServ.models.Message;
import org.hypen.GRpcServ.models.ProtoObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class GrpcDataTranslator {

    private static final Logger log = LoggerFactory.getLogger(GrpcDataTranslator.class);

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    public static List<String> JAVA_DATA_TYPES = Arrays.asList("int", "Integer", "long", "Long", "float", "Float", "double", "Double", "boolean", "Boolean", "String", "byte[]", "Date", "Instant", "Duration");
    public static List<String> JAVA_DATA_COLLECTIONS = Arrays.asList("List", "Map", "Set", "Collection");

    /**
     * Translates a Java data type to its equivalent gRPC data type.
     * <p>
     * This method takes a Java data type as a string and a {@link ProtoObject}.
     * It handles simple types, collections (List, Map, Set, Collection), and custom classes.
     * For custom classes, it recursively translates them to gRPC messages or enums.
     *
     * @param javaDataType The Java data type to translate.
     * @param protoObject  The {@link ProtoObject} representing the current protobuf definition, used for adding imports.
     * @return The equivalent gRPC data type.
     */
    public static String translateToGrpcDataType(String javaDataType, ProtoObject protoObject) {
        // Pattern to extract inner types from generics
        NameMapper nm = NameMapper.getInstance();
        Pattern genericPattern = Pattern.compile("<(.*?)>");
        long closeCount = javaDataType.chars().filter(c -> c == '>').count();

        if (javaDataType.startsWith("List") || javaDataType.startsWith("Set") || javaDataType.startsWith("Collection")) {
            // Handle List types
            Matcher matcher = genericPattern.matcher(javaDataType);
            if (matcher.find()) {
                String elementType = translateToGrpcDataType(nm.mapFQN(matcher.group(1) + String.valueOf('>').repeat((int) (closeCount - 1))), protoObject);
                return "repeated " + elementType;
            } else {
                return "repeated <unknown>";
            }
        } else if (javaDataType.startsWith("Map")) {
            // Handle Map types
            Matcher matcher = genericPattern.matcher(javaDataType);
            if (matcher.find()) {
                String[] types = matcher.group(1).split(",");
                if (types.length == 2) {
                    String keyType = translateToGrpcDataType(nm.mapFQN(types[0].trim()), protoObject);
                    String valueType = translateToGrpcDataType(nm.mapFQN(types[1].trim() + String.valueOf('>').repeat((int) (closeCount - 1))), protoObject);
                    return "map<" + keyType + ", " + valueType + ">";
                } else {
                    return "map<unknown, unknown>";
                }
            } else {
                return "map<unknown, unknown>";
            }
        } else {
            // Handle simple types using the switch statement (same as before)
            return switch (javaDataType) {
                case "int", "Integer" -> "int32";
                case "long", "Long" -> "int64";
                case "float", "Float" -> "float";
                case "double", "Double" -> "double";
                case "boolean", "Boolean" -> "bool";
                case "String" -> "string";
                case "byte[]" -> "bytes";
                case "Date", "Instant" -> {
                    protoObject.getImports().add("google/protobuf/timestamp.proto");
                    yield "google.protobuf.Timestamp";
                }
                case "Duration" -> {
                    protoObject.getImports().add("google/protobuf/duration.proto");
                    yield "google.protobuf.Duration";
                }
                default -> translateClass(javaDataType, protoObject);
            };
        }
    }

    /**
     * Translates a Java class or enum type to its equivalent gRPC message or enum definition.
     * <p>
     * This method takes the fully qualified name of a Java class or enum and a {@link ProtoObject}.
     * It parses the Java file, extracts necessary information for the `dtoMap`, and then
     * delegates the translation to either {@link #translateEnumToGrpc} for enums or
     * {@link #translateClassToGrpcMessage} for classes.
     *
     * @param javaDataType The fully qualified name of the Java class or enum to translate.
     * @param protoObject  The {@link ProtoObject} representing the current protobuf definition,
     *                     used for adding the generated message or enum.
     * @return The name of the generated gRPC message or enum, or null if an error occurs.
     */
    public static String translateClass(String javaDataType, ProtoObject protoObject) {
        try {
            log.info("\t\t\tObject FQN: {}", javaDataType);
            CompilationUnit cu = StaticJavaParser.parse(new File(javaDataType));
            NameMapper.getInstance().getDtoMap().putAll(ProtoGenerator.generateImportMap(cu));
            NameMapper.getInstance().getDtoMap().put("package",
                    NameMapper.getInstance().getDtoMap().get("package") + "," +
                            cu.getPackageDeclaration().orElseThrow().getNameAsString());

            // Check if it's an enum
            Optional<EnumDeclaration> enumDeclaration = cu.findFirst(EnumDeclaration.class);
            if (enumDeclaration.isPresent()) {
                return translateEnumToGrpc(enumDeclaration.get(), protoObject);
            }

            // Otherwise, treat it as a class and create a message
            Optional<ClassOrInterfaceDeclaration> classDeclaration = cu.findFirst(ClassOrInterfaceDeclaration.class);
            if (classDeclaration.isPresent()) {
                return translateClassToGrpcMessage(classDeclaration.get(), protoObject);
            }

        } catch (IOException e) {
            System.err.println("Error translating class: " + e.getMessage());
        }
        return null;
    }

    /**
     * Translates a Java enum to a gRPC enum definition.
     * <p>
     * This method takes a JavaParser {@link EnumDeclaration} representing a Java enum
     * and a {@link ProtoObject}. It iterates through the enum constants, assigns them
     * sequential numerical values starting from 0, and constructs a gRPC enum definition
     * string with constant names and their corresponding values.
     * <p>
     * The generated enum definition is then wrapped in a {@link Message} object and added to the
     * `messages` list of the provided {@link ProtoObject}.
     *
     * @param enumDeclaration The JavaParser {@link EnumDeclaration} representing the Java enum to translate.
     * @param protoObject     The {@link ProtoObject} representing the current protobuf definition, used for adding the generated enum.
     * @return The name of the generated gRPC enum.
     */
    private static String translateEnumToGrpc(EnumDeclaration enumDeclaration, ProtoObject protoObject) {
        String fields = IntStream.range(0, enumDeclaration.getEntries().size())
                .mapToObj(i -> String.format("\t%s = %d;",
                        enumDeclaration.getEntry(i).asEnumConstantDeclaration().getNameAsString(), i))
                .collect(Collectors.joining("\n"));

        Message msg = new Message(
                Message.Type.GRpcEnum,
                enumDeclaration.getNameAsString() + "Enum",
                fields
        );
        protoObject.getMessages().add(msg);
        return msg.getName();
    }

    /**
     * Translates a Java class to a gRPC message definition.
     * <p>
     * This method takes a JavaParser {@link ClassOrInterfaceDeclaration} representing a Java class
     * and a {@link ProtoObject}. It iterates through the fields of the class, translates each field's
     * data type to its equivalent gRPC data type using {@link GrpcDataTranslator#translateToGrpcDataType},
     * and constructs a gRPC message definition string with field names and their corresponding types.
     * <p>
     * The generated message definition is then wrapped in a {@link Message} object and added to the
     * `messages` list of the provided {@link ProtoObject}.
     *
     * @param classDeclaration The JavaParser {@link ClassOrInterfaceDeclaration} representing the Java class to translate.
     * @param protoObject      The {@link ProtoObject} representing the current protobuf definition, used for adding the generated message.
     * @return The name of the generated gRPC message.
     */
    private static String translateClassToGrpcMessage(ClassOrInterfaceDeclaration classDeclaration, ProtoObject protoObject) {
        NameMapper nm = NameMapper.getInstance();
        String fields = IntStream.range(0, classDeclaration.getFields().size())
                .mapToObj(i -> String.format("\t%s %s = %d;",
                        GrpcDataTranslator.translateToGrpcDataType(nm.mapFQN(classDeclaration.getFields().get(i).getVariable(0).getType().toString()), protoObject),
                        classDeclaration.getFields().get(i).getVariable(0).getNameAsString(), i + 1))
                .collect(Collectors.joining("\n"));

        Message msg = new Message(
                Message.Type.GRpcMessage,
                classDeclaration.getNameAsString() + "Dto",
                fields
        );

        protoObject.getMessages().add(msg);
        return msg.getName();
    }
}
