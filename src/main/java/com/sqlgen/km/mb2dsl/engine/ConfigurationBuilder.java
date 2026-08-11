package com.sqlgen.km.mb2dsl.engine;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.builder.annotation.MapperAnnotationBuilder;
import org.apache.ibatis.session.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a MyBatis {@link Configuration} with zero database connection.
 * Parses XML mappers and annotation mappers from source files.
 */
public class ConfigurationBuilder {
    private static final Logger log = LoggerFactory.getLogger(ConfigurationBuilder.class);

    private final List<Path> xmlMapperFiles = new ArrayList<>();
    private final List<Path> mapperJavaFiles = new ArrayList<>();
    private final List<String> mapperClassNames = new ArrayList<>();
    private ClassLoader classLoader;

    public ConfigurationBuilder classLoader(ClassLoader cl) {
        this.classLoader = cl;
        return this;
    }

    public ConfigurationBuilder addXmlMapper(Path xmlFile) {
        this.xmlMapperFiles.add(xmlFile);
        return this;
    }

    public ConfigurationBuilder addMapperJava(Path javaFile) {
        this.mapperJavaFiles.add(javaFile);
        return this;
    }

    public ConfigurationBuilder addMapperClass(String className) {
        this.mapperClassNames.add(className);
        return this;
    }

    /**
     * Build the MyBatis Configuration. No database connection required.
     */
    public Configuration build() {
        // Use provided classloader or default
        ClassLoader cl = this.classLoader != null ? this.classLoader : getClass().getClassLoader();
        ClassLoader oldCcl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(cl);
        try {
            return doBuild();
        } finally {
            Thread.currentThread().setContextClassLoader(oldCcl);
        }
    }

    private Configuration doBuild() {
        Configuration config = new Configuration();
        // Prevent MyBatis from trying to load JDBC driver
        config.setDatabaseId("mb2dsl");

        // Register a fallback type alias so XML can still be parsed
        // when entity classes aren't on classpath
        config.getTypeAliasRegistry().registerAlias("__mb2dsl_fallback__", Object.class);

        // 1. Parse XML mapper files
        for (Path xmlFile : xmlMapperFiles) {
            try (InputStream is = new FileInputStream(xmlFile.toFile())) {
                String resource = xmlFile.getFileName().toString();
                XMLMapperBuilder xmlBuilder = new XMLMapperBuilder(
                        is, config, resource, config.getSqlFragments()
                );
                xmlBuilder.parse();
                log.info("Parsed XML mapper: {}", xmlFile);
            } catch (Exception e) {
                log.warn("Failed to parse XML mapper {}: {}", xmlFile, e.getMessage());
            }
        }

        // 2. Parse annotation mapper classes
        ClassLoader cl = this.classLoader != null ? this.classLoader : getClass().getClassLoader();
        for (String className : mapperClassNames) {
            try {
                Class<?> mapperClass = cl.loadClass(className);
                MapperAnnotationBuilder annBuilder = new MapperAnnotationBuilder(config, mapperClass);
                annBuilder.parse();
                log.info("Parsed annotation mapper: {}", className);
            } catch (Exception e) {
                log.warn("Failed to parse mapper class {}: {}", className, e.getMessage());
            }
        }

        return config;
    }

    /**
     * Try to build a ClassLoader from compiled classes directory.
     * Falls back to a lenient loader that returns Object.class for unknown classes,
     * allowing MyBatis XML parsing even when entity classes are missing.
     */
    public static ClassLoader createProjectClassLoader(Path classesDir) {
        try {
            List<URL> urls = new ArrayList<>();
            if (Files.isDirectory(classesDir)) {
                urls.add(classesDir.toUri().toURL());
            }
            URLClassLoader delegate = new URLClassLoader(urls.toArray(new URL[0]),
                    ConfigurationBuilder.class.getClassLoader());
            return new LenientClassLoader(delegate);
        } catch (Exception e) {
            log.warn("Failed to create project classloader: {}", e.getMessage());
            return new LenientClassLoader(ConfigurationBuilder.class.getClassLoader());
        }
    }

    /**
     * A ClassLoader that returns Object.class for any ClassNotFoundException,
     * allowing MyBatis to parse XML even without entity classes.
     */
    private static class LenientClassLoader extends ClassLoader {
        private final ClassLoader delegate;

        LenientClassLoader(ClassLoader delegate) {
            super(delegate);
            this.delegate = delegate;
        }

        @Override
        public Class<?> loadClass(String name) throws ClassNotFoundException {
            try {
                return super.loadClass(name);
            } catch (ClassNotFoundException e) {
                // Return Object.class as fallback
                log.debug("Lenient: using Object.class for {}", name);
                return Object.class;
            }
        }
    }
}
