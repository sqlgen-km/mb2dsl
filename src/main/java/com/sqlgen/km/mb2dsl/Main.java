package com.sqlgen.km.mb2dsl;

import com.sqlgen.km.mb2dsl.engine.*;
import com.sqlgen.km.mb2dsl.generator.DslGenerator;
import com.sqlgen.km.mb2dsl.model.EntityIR;
import com.sqlgen.km.mb2dsl.model.StatementIR;
import com.sqlgen.km.mb2dsl.report.ReviewReport;
import com.sqlgen.km.mb2dsl.transform.ModeResolver;
import org.apache.ibatis.session.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.*;

/**
 * Main entry point for mb2dsl.
 */
public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        CliOptions opts = new CliOptions();
        CommandLine cmd = new CommandLine(opts);
        cmd.parseArgs(args);

        if (cmd.isUsageHelpRequested()) {
            cmd.usage(System.out);
            return;
        }
        if (cmd.isVersionHelpRequested()) {
            cmd.printVersionHelp(System.out);
            return;
        }

        try {
            new Main().run(opts);
        } catch (Exception e) {
            log.error("Fatal error: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    public void run(CliOptions opts) throws Exception {
        log.info("mb2dsl — MyBatis → sqlgen DSL converter");
        log.info("Source:      {}", opts.getSrcDir());
        log.info("Output:      {}", opts.getOutputDir());

        // ---- Step 1: Scan files ----
        List<Path> xmlMapperFiles = MapperScanner.scanXmlMappers(opts.getResourcesDir());
        List<Path> mapperJavaFiles = MapperScanner.scanMapperJavaFiles(opts.getSrcDir());

        // ---- Step 2: Parse entities using JavaParser ----
        List<EntityIR> entities;
        List<Path> entityFiles = EntityScanner.scanEntityFiles(opts.getSrcDir(), List.of());
        entities = EntityScanner.parseAll(entityFiles);

        // ---- Step 3: Parse mapper interfaces for mode refinement (JavaParser, no classpath needed) ----
        Map<String, String> methodReturnTypes = MapperInterfaceParser.parseMethodReturnTypes(mapperJavaFiles);

        // ---- Step 4: Parse mappers ----
        // Try MyBatis Configuration first (needs entity classes); fall back to direct XML parsing
        List<StatementIR> statements = new ArrayList<>();
        if (opts.getClasspathDir() != null) {
            // Use MyBatis Configuration for everything (handles XML + annotations)
            ConfigurationBuilder cb = new ConfigurationBuilder();
            cb.classLoader(ConfigurationBuilder.createProjectClassLoader(opts.getClasspathDir()));
            for (Path xml : xmlMapperFiles) {
                cb.addXmlMapper(xml);
            }
            for (Path javaFile : mapperJavaFiles) {
                try {
                    String className = MapperScanner.extractClassName(javaFile, opts.getSrcDir());
                    cb.addMapperClass(className);
                } catch (Exception e) {
                    log.warn("Failed to add mapper class {}: {}", javaFile, e.getMessage());
                }
            }
            Configuration config = cb.build();
            Introspector introspector = new Introspector();
            statements = introspector.extractStatements(config);
        } else {
            // No classpath: use direct XML parsing (no entity classes needed)
            for (Path xml : xmlMapperFiles) {
                String mapperName = extractMapperNameFromXml(xml);
                List<StatementIR> stmts = XmlDirectParser.parse(xml, mapperName);
                statements.addAll(stmts);
            }
            log.info("Parsed {} statements from XML (direct mode, no entity classes)", statements.size());
        }

        // ---- Step 5: Refine modes from parsed Mapper interface return types ----
        if (!methodReturnTypes.isEmpty()) {
            for (StatementIR stmt : statements) {
                // Build lookup key: "UserMapper.findById"
                String mapperSimple = extractMapperSimpleName(stmt.getId());
                String key = mapperSimple + "." + stmt.getName();
                String returnType = methodReturnTypes.get(key);
                if (returnType != null) {
                    ModeResolver.refine(stmt, returnType);
                    log.debug("Refined {} → {} (return type: {})", key, stmt.getMode(), returnType);
                }
            }
        }

        // ---- Step 6: Generate DSL ----
        String mapperPackage = opts.getMapperPackage() != null ? opts.getMapperPackage()
                : opts.getBasePackage() + ".mapper";
        String modelPackage = opts.getModelPackage() != null ? opts.getModelPackage()
                : opts.getBasePackage() + ".entity";

        DslGenerator generator = new DslGenerator(
                opts.getOutputDir(),
                opts.getBasePackage(),
                mapperPackage,
                modelPackage
        );
        ReviewReport report = generator.generate(statements, entities);

        // ---- Step 7: Summary ----
        int totalStmts = statements.size();
        int reviewCount = report.getReviewCount();
        int cleanCount = totalStmts - reviewCount;

        log.info("");
        log.info("========================================");
        log.info("  Conversion complete!");
        log.info("  Total statements:  {}", totalStmts);
        log.info("  Clean conversions: {} ", cleanCount);
        if (reviewCount > 0) {
            log.info("  Need manual review: {} (see _manual_review.md)", reviewCount);
        }
        log.info("  Output: {}/", opts.getOutputDir());
        log.info("========================================");
    }

    /**
     * Extract mapper interface simple name from a statement id.
     * "com.example.mapper.UserMapper.findById" → "UserMapper"
     */
    private static String extractMapperSimpleName(String statementId) {
        if (statementId == null) return "UnknownMapper";
        String[] parts = statementId.split("\\.");
        if (parts.length >= 2) {
            return parts[parts.length - 2];
        }
        return "UnknownMapper";
    }

    /**
     * Extract the mapper namespace from an XML file.
     * Reads the <mapper namespace="..."> attribute.
     */
    private static String extractMapperNameFromXml(Path xmlFile) {
        try {
            String content = java.nio.file.Files.readString(xmlFile);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("<mapper[^>]*namespace\\s*=\\s*\"([^\"]+)\"")
                    .matcher(content);
            if (m.find()) {
                return m.group(1);
            }
            // Fallback: derive from filename
            return MapperScanner.getMapperSimpleName(xmlFile);
        } catch (Exception e) {
            return MapperScanner.getMapperSimpleName(xmlFile);
        }
    }
}
