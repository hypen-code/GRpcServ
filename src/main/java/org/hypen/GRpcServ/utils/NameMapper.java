package org.hypen.GRpcServ.utils;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.maven.project.MavenProject;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.apache.maven.shared.utils.StringUtils.capitalizeFirstLetter;

@Slf4j
public class NameMapper {
    private static volatile NameMapper instance;

    private final MavenProject project;
    @Getter
    private Map<String, String> dtoMap;

    private NameMapper(MavenProject project, Map<String, String> dtoMap) {
        this.project = project;
        this.dtoMap = dtoMap;
    }

    public static NameMapper getInstance(MavenProject project, Map<String, String> dtoMap) {
        if (instance == null) {
            synchronized (NameMapper.class) {
                if (instance == null) {
                    instance = new NameMapper(project, dtoMap);
                }
            }
        } else {
            instance.dtoMap = dtoMap;
        }
        return instance;
    }

    public static NameMapper getInstance() {
        if (instance == null) throw new RuntimeException("NameMapper instance not initialized");
        return instance;
    }

    /**
     * Maps a simple class name or data type to its fully qualified name or file path.
     * <p>
     * This method takes a string representing a class simple name or a Java data type.
     * It first checks if the input is a known Java data type or a collection type.
     * If so, it returns the input string as is.
     * <p>
     * If the input is a class simple name present in the `dtoMap`, it searches for the corresponding
     * Java file in the project's source roots using the fully qualified name from the `dtoMap`.
     * If the file is found, it returns the absolute path to the file.
     * <p>
     * If the input is not found in the `dtoMap`, it assumes the class is in the same package as the
     * classes defined in the `dtoMap` and searches for the corresponding Java file in the project's
     * source roots. If the file is found, it returns the absolute path to the file.
     * <p>
     * If the file is not found in any of the source roots, it throws a RuntimeException.
     *
     * @param s The simple class name or Java data type to map.
     * @return The fully qualified name or file path of the class or data type.
     * @throws RuntimeException If the corresponding Java file is not found in the project's source roots.
     */
    public String mapFQN(String s) {
//        Check known data type
        if (GrpcDataTranslator.JAVA_DATA_TYPES.contains(s)) return s;
        if (startsWithAny(s, GrpcDataTranslator.JAVA_DATA_COLLECTIONS)) return s;

        List<String> sourceRoots = project.getCompileSourceRoots();
        if (dtoMap.containsKey(s)) {
//            Includes in dtoMap
            for (String sourceRoot : sourceRoots) {
                String path = sourceRoot + "/" + dtoMap.get(s).replace('.', '/') + ".java";
                if (fileExists(path)) return path;
            }
            log.warn("File not found - source dirs: {}", String.join(",", sourceRoots));
            throw new RuntimeException("File not found: " + s);
        } else {
//            Assume Class in same package
            for (String sourceRoot : sourceRoots) {
                for (String pkg : dtoMap.get("package").split(",")) {
                    String path = sourceRoot + "/" + pkg.replace('.', '/') + "/" + s + ".java";
                    if (fileExists(path)) return path;
                }
            }
            log.warn("File not found - source dirs: {}", String.join(",", sourceRoots));
            log.warn("File not found - packages: {}", dtoMap.get("package"));
            throw new RuntimeException("File not found in same package: " + s);
        }
    }

    /**
     * Checks if the given string starts with any of the prefixes in the provided list.
     *
     * @param str      The string to check.
     * @param prefixes The list of prefixes to compare against.
     * @return True if the string starts with any of the prefixes, false otherwise.
     */
    public static boolean startsWithAny(String str, List<String> prefixes) {
        return prefixes.stream().anyMatch(str::startsWith);
    }

    /**
     * Checks if any string in the given list starts with the specified prefix.
     *
     * @param prefix  The prefix to check for.
     * @param strings The list of strings to search within.
     * @return True if any string in the list starts with the prefix, false otherwise.
     */
    public static boolean anyStartWithStr(String prefix, List<String> strings) {
        return strings.stream().anyMatch(str -> str.startsWith(prefix));
    }

    /**
     * Checks if a file exists at the specified file path.
     *
     * @param filePath The path to the file to check.
     * @return True if a file exists at the given path, false otherwise.
     */
    public static boolean fileExists(String filePath) {
        return Files.exists(Paths.get(filePath));
    }

    /**
     * Generates the name of a getter method for a field, optionally appending a suffix.
     * <p>
     * This method takes a field name and a suffix (e.g., "List", "Dto", or an empty string).
     * It constructs and returns the name of a getter method for the field, capitalizing the first letter
     * of the field name and appending the suffix if provided.
     *
     * @param fieldName The name of the field for which to generate the getter name.
     * @param suffix    The suffix to append to the getter name (e.g., "List", "Dto", "").
     * @return The generated getter method name.
     */
    public static String getterName(String fieldName, String suffix) {
        return "get" + capitalizeFirstLetter(fieldName) + suffix;
    }

    /**
     * Generates the name of a setter method for a field, optionally appending a suffix.
     * <p>
     * This method takes a field name and a suffix (e.g., "List", "Dto", or an empty string).
     * It constructs and returns the name of a setter method for the field, capitalizing the first letter
     * of the field name and appending the suffix if provided.
     *
     * @param fieldName The name of the field for which to generate the setter name.
     * @param suffix    The suffix to append to the setter name (e.g., "List", "Dto", "").
     * @return The generated setter method name.
     */
    public static String setterName(String fieldName, String suffix) {
        return "set" + capitalizeFirstLetter(fieldName) + suffix;
    }

    /**
     * Extracts the word immediately following a target word in a given input string.
     * <p>
     * This method uses regular expressions to search for the target word followed by a space and another word.
     * If a match is found, it returns the word that follows the target word. Otherwise, it returns null.
     *
     * @param targetWord The word to search for in the input string.
     * @param input      The input string to search within.
     * @return The word immediately following the target word, or null if no match is found.
     */
    public static String extractWordAfter(String targetWord, String input) {
        Pattern pattern = Pattern.compile("\\b" + targetWord + "\\s+(\\w+)\\b");
        Matcher matcher = pattern.matcher(input);

        if (matcher.find()) {
            return matcher.group(1);
        } else {
            return null;
        }
    }
}