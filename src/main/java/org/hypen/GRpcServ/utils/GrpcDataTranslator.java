package org.hypen.GRpcServ.utils;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.hypen.GRpcServ.models.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class GrpcDataTranslator {

    private static final Logger log = LoggerFactory.getLogger(GrpcDataTranslator.class);

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    public static List<String> JAVA_DATA_TYPES = Arrays.asList("int", "Integer", "long", "Long", "float", "Float", "double", "Double", "boolean", "Boolean", "String", "byte[]", "Date", "Instant", "Duration");

    /**
     * Translates a Java data type to its equivalent gRPC data type.
     *
     * @param javaDataType The Java data type to translate.
     * @return The equivalent gRPC data type.
     */
    public static String translateToGrpcDataType(String javaDataType, Set<Message> msgList) {
        // Pattern to extract inner types from generics
        Pattern genericPattern = Pattern.compile("<(.*?)>");
        long closeCount = javaDataType.chars().filter(c -> c == '>').count();

        if (javaDataType.startsWith("List")) {
            // Handle List types
            System.out.println("List: " + javaDataType);
            Matcher matcher = genericPattern.matcher(javaDataType);
            if (matcher.find()) {
                String elementType = translateToGrpcDataType(matcher.group(1) + String.valueOf('>').repeat((int) (closeCount - 1)), msgList);
                return "repeated " + elementType;
            } else {
                return "repeated <unknown>";
            }
        } else if (javaDataType.startsWith("Map")) {
            // Handle Map types
            System.out.println("Map: " + javaDataType);
            Matcher matcher = genericPattern.matcher(javaDataType);
            if (matcher.find()) {
                String[] types = matcher.group(1).split(",");
                if (types.length == 2) {
                    String keyType = translateToGrpcDataType(types[0].trim(), msgList);
                    String valueType = translateToGrpcDataType(types[1].trim() + String.valueOf('>').repeat((int) (closeCount - 1)), msgList);
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
                case "Date", "Instant" -> "google.protobuf.Timestamp";
                case "Duration" -> "google.protobuf.Duration";
                default -> translateClass(javaDataType, msgList);
            };
        }
    }

    /**
     * Translates a Java class or enum type to its equivalent gRPC message or enum definition.
     *
     * @param javaDataType The fully qualified name of the Java class or enum to translate.
     * @return The gRPC message or enum definition as a string.
     */
    public static String translateClass(String javaDataType, Set<Message> msgList) {
        try {
            log.info("\t\t\tObject FQN: {}", javaDataType);
            CompilationUnit cu = StaticJavaParser.parse(new File(javaDataType));

            // Check if it's an enum
            Optional<EnumDeclaration> enumDeclaration = cu.findFirst(EnumDeclaration.class);
            if (enumDeclaration.isPresent()) {
                return translateEnumToGrpc(enumDeclaration.get(), msgList);
            }

            // Otherwise, treat it as a class and create a message
            Optional<ClassOrInterfaceDeclaration> classDeclaration = cu.findFirst(ClassOrInterfaceDeclaration.class);
            if (classDeclaration.isPresent()) {
                return translateClassToGrpcMessage(classDeclaration.get(), msgList);
            }

        } catch (IOException e) {
            System.err.println("Error translating class: " + e.getMessage());
        }
        return null;
    }

    /**
     * Translates a Java enum to a gRPC enum definition.
     *
     * @param enumDeclaration The JavaParser EnumDeclaration object.
     * @return The gRPC enum definition as a string.
     */
    private static String translateEnumToGrpc(EnumDeclaration enumDeclaration, Set<Message> msgList) {
        String fields = IntStream.range(0, enumDeclaration.getEntries().size())
                .mapToObj(i -> String.format("\t%s = %d;",
                        enumDeclaration.getEntry(i).asEnumConstantDeclaration().getNameAsString(), i))
                .collect(Collectors.joining("\n"));

        Message msg = new Message(
                Message.Type.GRpcEnum,
                enumDeclaration.getNameAsString() + "Enum",
                fields
        );
        msgList.add(msg);
        return msg.getName();
    }

    /**
     * Translates a Java class to a gRPC message definition.
     *
     * @param classDeclaration The JavaParser ClassOrInterfaceDeclaration object.
     * @return The gRPC message definition as a string.
     */
    private static String translateClassToGrpcMessage(ClassOrInterfaceDeclaration classDeclaration, Set<Message> msgList) {
        String fields = IntStream.range(0, classDeclaration.getFields().size())
                .mapToObj(i -> String.format("\t%s %s = %d;",
                        GrpcDataTranslator.translateToGrpcDataType(classDeclaration.getFields().get(i).getVariable(0).getType().toString(), msgList),
                        classDeclaration.getFields().get(i).getVariable(0).getNameAsString(), i + 1))
                .collect(Collectors.joining("\n"));

        Message msg = new Message(
                Message.Type.GRpcMessage,
                classDeclaration.getNameAsString() + "Dto",
                fields
        );

        msgList.add(msg);
        return msg.getName();
    }
}
