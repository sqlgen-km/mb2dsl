package com.sqlgen.km.mb2dsl.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Scans a project directory for MyBatis mapper files.
 */
public class MapperScanner {
    private static final Logger log = LoggerFactory.getLogger(MapperScanner.class);

    /**
     * Scan for XML mapper files (*Mapper.xml) under the given resources directory.
     */
    public static List<Path> scanXmlMappers(Path resourcesDir) throws IOException {
        List<Path> result = new ArrayList<>();
        if (!Files.isDirectory(resourcesDir)) {
            log.warn("Resources directory not found: {}", resourcesDir);
            return result;
        }
        try (Stream<Path> stream = Files.walk(resourcesDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith("Mapper.xml"))
                    .forEach(result::add);
        }
        log.info("Found {} XML mapper files", result.size());
        return result;
    }

    /**
     * Scan for Java mapper interface files (*Mapper.java) under the given source directory.
     * Returns the file paths.
     */
    public static List<Path> scanMapperJavaFiles(Path sourceDir) throws IOException {
        List<Path> result = new ArrayList<>();
        if (!Files.isDirectory(sourceDir)) {
            log.warn("Source directory not found: {}", sourceDir);
            return result;
        }
        try (Stream<Path> stream = Files.walk(sourceDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith("Mapper.java"))
                    .forEach(result::add);
        }
        log.info("Found {} Mapper Java files", result.size());
        return result;
    }

    /**
     * Extract the fully qualified class name from a Java source file path.
     * e.g. src/main/java/com/example/mapper/UserMapper.java
     *   → com.example.mapper.UserMapper
     */
    public static String extractClassName(Path javaFile, Path sourceRoot) {
        Path relative = sourceRoot.relativize(javaFile);
        String path = relative.toString().replace(javaFile.getFileSystem().getSeparator(), ".");
        // Remove .java suffix
        return path.substring(0, path.length() - 5);
    }

    /**
     * Get the Mapper interface simple name (e.g., "UserMapper").
     */
    public static String getMapperSimpleName(Path javaFile) {
        String name = javaFile.getFileName().toString();
        return name.substring(0, name.length() - 5); // strip ".java"
    }
}
