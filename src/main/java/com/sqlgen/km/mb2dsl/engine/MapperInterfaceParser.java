package com.sqlgen.km.mb2dsl.engine;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Parses Mapper Java interface source files to extract method return types.
 * Used to refine statement modes (:one vs :many) without needing compiled classes.
 */
public class MapperInterfaceParser {
    private static final Logger log = LoggerFactory.getLogger(MapperInterfaceParser.class);

    /**
     * Parse all Mapper Java files and collect method return types.
     *
     * @param mapperFiles list of Mapper.java file paths
     * @return map of "MapperSimpleName.methodName" → return type string (e.g., "List<User>", "long", "void")
     */
    public static Map<String, String> parseMethodReturnTypes(List<Path> mapperFiles) {
        Map<String, String> result = new HashMap<>();
        for (Path file : mapperFiles) {
            try {
                String mapperName = MapperScanner.getMapperSimpleName(file);
                Map<String, String> methods = parseOne(file);
                for (Map.Entry<String, String> entry : methods.entrySet()) {
                    result.put(mapperName + "." + entry.getKey(), entry.getValue());
                }
                log.debug("Parsed {}: {} methods", mapperName, methods.size());
            } catch (Exception e) {
                log.warn("Failed to parse mapper interface {}: {}", file, e.getMessage());
            }
        }
        log.info("Parsed {} method return types from {} mapper files", result.size(), mapperFiles.size());
        return result;
    }

    /**
     * Parse a single Mapper interface file.
     * Returns method name → return type string.
     */
    static Map<String, String> parseOne(Path javaFile) throws IOException {
        Map<String, String> result = new HashMap<>();
        CompilationUnit cu = StaticJavaParser.parse(javaFile);

        List<ClassOrInterfaceDeclaration> interfaces = cu.findAll(ClassOrInterfaceDeclaration.class);
        for (ClassOrInterfaceDeclaration iface : interfaces) {
            for (MethodDeclaration method : iface.getMethods()) {
                String methodName = method.getNameAsString();
                String returnType = typeToString(method.getType());
                result.put(methodName, returnType);
            }
        }
        return result;
    }

    /**
     * Convert a JavaParser Type to a string like "List<User>", "long", "void".
     */
    private static String typeToString(Type type) {
        if (type.isVoidType()) {
            return "void";
        }
        if (type.isClassOrInterfaceType()) {
            ClassOrInterfaceType cit = type.asClassOrInterfaceType();
            String name = cit.getNameAsString();
            if (cit.getTypeArguments().isPresent() && !cit.getTypeArguments().get().isEmpty()) {
                String args = cit.getTypeArguments().get().stream()
                        .map(Type::asString)
                        .collect(Collectors.joining(", "));
                return name + "<" + args + ">";
            }
            return name;
        }
        return type.asString();
    }
}
