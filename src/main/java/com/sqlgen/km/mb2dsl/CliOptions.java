package com.sqlgen.km.mb2dsl;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;

/**
 * CLI options for mb2dsl.
 */
@Command(
        name = "mb2dsl",
        mixinStandardHelpOptions = true,
        version = "mb2dsl 1.0.1-SNAPSHOT",
        description = "MyBatis → sqlgen DSL reverse engineering tool"
)
public class CliOptions implements Runnable {

    @Option(names = {"-s", "--src"}, required = true,
            description = "Java source root directory (e.g., src/main/java)")
    private Path srcDir;

    @Option(names = {"-r", "--resources"},
            description = "Resources directory containing Mapper XML files (e.g., src/main/resources)")
    private Path resourcesDir;

    @Option(names = {"-o", "--output"}, required = true,
            description = "Output directory for generated DSL files")
    private Path outputDir;

    @Option(names = {"-c", "--classpath"},
            description = "Compiled classes directory for loading Mapper interfaces (e.g., target/classes)")
    private Path classpathDir;

    @Option(names = {"-p", "--base-package"},
            description = "Java base package name (e.g., com.example)", defaultValue = "com.example")
    private String basePackage;

    @Option(names = {"--mapper-package"},
            description = "Mapper interface package (e.g., com.example.mapper)")
    private String mapperPackage;

    @Option(names = {"--model-package"},
            description = "Entity/model package (e.g., com.example.entity)")
    private String modelPackage;

    @Option(names = {"--engines"},
            description = "Database engines for sqlg.yaml", defaultValue = "pg")
    private String engines;

    // --- getters ---

    public Path getSrcDir() { return srcDir; }
    public Path getResourcesDir() { return resourcesDir; }
    public Path getOutputDir() { return outputDir; }
    public Path getClasspathDir() { return classpathDir; }
    public String getBasePackage() { return basePackage; }
    public String getMapperPackage() { return mapperPackage; }
    public String getModelPackage() { return modelPackage; }
    public String getEngines() { return engines; }

    @Override
    public void run() {
        // Handled by Main
    }
}
