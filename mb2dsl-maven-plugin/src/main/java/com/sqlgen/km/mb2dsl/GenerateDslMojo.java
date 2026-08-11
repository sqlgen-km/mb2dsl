package com.sqlgen.km.mb2dsl;

import com.sqlgen.km.mb2dsl.engine.*;
import com.sqlgen.km.mb2dsl.generator.DslGenerator;
import com.sqlgen.km.mb2dsl.model.EntityIR;
import com.sqlgen.km.mb2dsl.model.StatementIR;
import com.sqlgen.km.mb2dsl.report.ReviewReport;
import com.sqlgen.km.mb2dsl.transform.ModeResolver;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Maven Mojo that runs mb2dsl DSL generation.
 *
 * <pre>
 * mvn com.sqlgen.km:mb2dsl-maven-plugin:1.0.0:generate-dsl
 * </pre>
 */
@Mojo(name = "generate-dsl", requiresProject = false)
public class GenerateDslMojo extends AbstractMojo {

    @Parameter(property = "mb2dsl.srcDir", defaultValue = "${project.basedir}/src/main/java")
    private String srcDir;

    @Parameter(property = "mb2dsl.resourcesDir", defaultValue = "${project.basedir}/src/main/resources")
    private String resourcesDir;

    @Parameter(property = "mb2dsl.outputDir", defaultValue = "${project.basedir}/sqlgen-dsl")
    private String outputDir;

    @Parameter(property = "mb2dsl.basePackage", defaultValue = "com.example")
    private String basePackage;

    @Parameter(property = "mb2dsl.engines", defaultValue = "pg")
    private String engines;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            Path src = Paths.get(srcDir);
            Path res = Paths.get(resourcesDir);
            Path out = Paths.get(outputDir);

            getLog().info("mb2dsl - MyBatis to sqlgen DSL converter");
            getLog().info("Source:    " + src);
            getLog().info("Resources: " + res);
            getLog().info("Output:    " + out);

            Files.createDirectories(out);

            // Scan
            List<Path> xmlMapperFiles = MapperScanner.scanXmlMappers(res);
            List<Path> mapperJavaFiles = MapperScanner.scanMapperJavaFiles(src);

            // Entities from source + resultMaps
            List<Path> entityFiles = EntityScanner.scanEntityFiles(src, List.of());
            List<EntityIR> entities = EntityScanner.parseAll(entityFiles);
            List<EntityIR> xmlEntities = XmlDirectParser.parseResultMapEntities(xmlMapperFiles);
            Map<String, EntityIR> merged = new LinkedHashMap<>();
            for (EntityIR e : entities) merged.put(e.getClassName(), e);
            for (EntityIR e : xmlEntities) {
                EntityIR existing = merged.get(e.getClassName());
                if (existing != null) {
                    for (var f : e.getFields()) {
                        if (existing.getFields().stream().noneMatch(ef -> ef.getColumnName().equals(f.getColumnName()))) {
                            existing.addField(f);
                        }
                    }
                } else {
                    merged.put(e.getClassName(), e);
                }
            }
            entities = new ArrayList<>(merged.values());

            // Mode refinement from mapper interfaces
            Map<String, String> returnTypes = MapperInterfaceParser.parseMethodReturnTypes(mapperJavaFiles);

            // Parse statements
            List<StatementIR> statements = new ArrayList<>();
            for (Path xml : xmlMapperFiles) {
                String mapperName = extractMapperNameFromXml(xml);
                statements.addAll(XmlDirectParser.parse(xml, mapperName));
            }
            List<StatementIR> annotationStmts = MapperInterfaceParser.parseAnnotationStatements(mapperJavaFiles);
            Set<String> xmlIds = new HashSet<>();
            for (StatementIR s : statements) xmlIds.add(s.getName());
            for (StatementIR as : annotationStmts) {
                if (!xmlIds.contains(as.getName())) statements.add(as);
            }

            // Refine modes
            for (StatementIR stmt : statements) {
                String mapperSimple = extractMapperSimpleName(stmt.getId());
                String key = mapperSimple + "." + stmt.getName();
                String returnType = returnTypes.get(key);
                if (returnType != null) {
                    String oldMode = stmt.getMode();
                    ModeResolver.refine(stmt, returnType);
                    if (!oldMode.equals(stmt.getMode())) {
                        stmt.addNote("mode refined: " + returnType + " → " + stmt.getMode());
                    }
                }
            }

            // Generate
            DslGenerator gen = new DslGenerator(out, basePackage,
                    basePackage + ".mapper", basePackage + ".entity");
            gen.generate(statements, entities);

            getLog().info("Generated " + statements.size() + " statements to " + out);
        } catch (Exception e) {
            throw new MojoExecutionException("mb2dsl generation failed", e);
        }
    }

    private static String extractMapperSimpleName(String statementId) {
        if (statementId == null) return "UnknownMapper";
        String[] parts = statementId.split("\\.");
        return parts.length >= 2 ? parts[parts.length - 2] : "UnknownMapper";
    }

    private static String extractMapperNameFromXml(Path xmlFile) {
        try {
            String content = Files.readString(xmlFile);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("<mapper[^>]*namespace\\s*=\\s*\"([^\"]+)\"")
                    .matcher(content);
            if (m.find()) return m.group(1);
            return MapperScanner.getMapperSimpleName(xmlFile);
        } catch (Exception e) {
            return MapperScanner.getMapperSimpleName(xmlFile);
        }
    }
}
