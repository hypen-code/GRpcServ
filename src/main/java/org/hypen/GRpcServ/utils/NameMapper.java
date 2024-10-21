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

    public String mapFQN(String s) {
        if (GrpcDataTranslator.JAVA_DATA_TYPES.contains(s)) return s;
        if (startsWithAny(s, GrpcDataTranslator.JAVA_DATA_COLLECTIONS)) return s;

        List<String> sourceRoots = project.getCompileSourceRoots();
        if (dtoMap.containsKey(s)) {
            for (String sourceRoot : sourceRoots){
                String path = sourceRoot + "/" + dtoMap.get(s).replace('.', '/') + ".java";
                if (fileExists(path)) return path;
            }
            log.warn("File not found - source dirs: {}", String.join(",", sourceRoots));
            throw new RuntimeException("File not found: " + s);
        } else {
//            Assume Class in same package
            for (String sourceRoot : sourceRoots){
                for (String pkg: dtoMap.get("package").split(",")){
                    String path = sourceRoot + "/" + pkg.replace('.', '/') + "/" + s + ".java";
                    if (fileExists(path)) return path;
                }
            }
            log.warn("File not found - source dirs: {}", String.join(",", sourceRoots));
            log.warn("File not found - packages: {}", dtoMap.get("package"));
            throw new RuntimeException("File not found in same package: " + s);
        }
    }

    public static boolean startsWithAny(String str, List<String> prefixes) {
        return prefixes.stream().anyMatch(str::startsWith);
    }

    public static boolean anyStartWithStr(String prefix, List<String> strings) {
        return strings.stream().anyMatch(str -> str.startsWith(prefix));
    }

    public static boolean fileExists(String filePath) {
        return Files.exists(Paths.get(filePath));
    }

    public static String getterName(String fieldName, String suffix){
        return "get" + capitalizeFirstLetter(fieldName) + suffix;
    }

    public static String setterName(String fieldName, String suffix){
        return "set" + capitalizeFirstLetter(fieldName) + suffix;
    }

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