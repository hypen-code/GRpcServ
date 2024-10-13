package org.hypen.GRpcServ.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GrpcDataTranslator {

    /**
     * Translates a Java data type to its equivalent gRPC data type.
     *
     * @param javaDataType The Java data type to translate.
     * @return The equivalent gRPC data type.
     */
    public static String translateToGrpcDataType(String javaDataType) {
        // Pattern to extract inner types from generics
        Pattern genericPattern = Pattern.compile("<(.*?)>");
        long closeCount = javaDataType.chars().filter(c -> c == '>').count();

        if (javaDataType.startsWith("List")) {
            // Handle List types
            System.out.println("List: "+ javaDataType);
            Matcher matcher = genericPattern.matcher(javaDataType);
            if (matcher.find()) {
                String elementType = translateToGrpcDataType(matcher.group(1) + String.valueOf('>').repeat((int) (closeCount-1)));
                return "repeated " + elementType;
            } else {
                return "repeated <unknown>";
            }
        } else if (javaDataType.startsWith("Map")) {
            // Handle Map types
            System.out.println("Map: "+ javaDataType);
            Matcher matcher = genericPattern.matcher(javaDataType);
            if (matcher.find()) {
                String[] types = matcher.group(1).split(",");
                if (types.length == 2) {
                    String keyType = translateToGrpcDataType(types[0].trim());
                    String valueType = translateToGrpcDataType(types[1].trim() + String.valueOf('>').repeat((int) (closeCount-1)));
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
                default -> "unknown";
            };
        }
    }
}
