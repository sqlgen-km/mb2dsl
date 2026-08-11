package com.sqlgen.km.mb2dsl.generator;

import com.sqlgen.km.mb2dsl.model.EntityIR;
import com.sqlgen.km.mb2dsl.model.StatementIR;
import com.sqlgen.km.mb2dsl.report.ReviewReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Top-level DSL generator. Orchestrates grouping, writing, and report generation.
 */
public class DslGenerator {
    private static final Logger log = LoggerFactory.getLogger(DslGenerator.class);

    private final Path outputDir;
    private final String javaBasePackage;
    private final String mapperPackage;
    private final String modelPackage;
    private final List<String> sqlFiles = new ArrayList<>();

    public DslGenerator(Path outputDir, String javaBasePackage, String mapperPackage, String modelPackage) {
        this.outputDir = outputDir;
        this.javaBasePackage = javaBasePackage;
        this.mapperPackage = mapperPackage;
        this.modelPackage = modelPackage;
    }

    /**
     * Generate all .sql DSL files and sqlg.yaml.
     */
    public ReviewReport generate(List<StatementIR> statements, List<EntityIR> entities) throws IOException {
        Files.createDirectories(outputDir);
        ReviewReport report = new ReviewReport();

        // Group statements by mapper interface
        Map<String, List<StatementIR>> groups = FileGrouper.byMapperInterface(statements);

        for (Map.Entry<String, List<StatementIR>> entry : groups.entrySet()) {
            String mapperName = entry.getKey();
            List<StatementIR> stmts = entry.getValue();

            // Find referenced entities
            List<EntityIR> groupEntities = FileGrouper.entitiesForGroup(stmts, entities);

            // Build DSL content
            String dsl = buildDslFileContent(mapperName, groupEntities, stmts);

            // Write .sql file
            String fileName = FileGrouper.toSqlFileName(mapperName);
            Path outputFile = outputDir.resolve(fileName);
            Files.writeString(outputFile, dsl);
            sqlFiles.add(fileName);
            log.info("Generated: {}", fileName);

            // Collect review items + mapper detail
            for (StatementIR stmt : stmts) {
                if (!stmt.getReviewTags().isEmpty()) {
                    report.addItem(fileName, stmt.getName(), stmt.getReviewTags(), stmt.getRawSql());
                }
            }
            report.addMapperDetail(mapperName, fileName, stmts.size());
        }

        // Generate sqlg.yaml
        String yaml = SqlgYamlWriter.render(javaBasePackage, mapperPackage, modelPackage, sqlFiles);
        Files.writeString(outputDir.resolve("sqlg.yaml"), yaml);
        log.info("Generated: sqlg.yaml");

        // Collect stats and write reports
        int mapperCount = groups.size();
        report.collectStats(statements, entities, 0, mapperCount);
        report.setEntities(entities);
        report.writeSummary(outputDir);
        report.writeDetailedLog(outputDir, statements);

        return report;
    }

    private String buildDslFileContent(String mapperName, List<EntityIR> entities, List<StatementIR> statements) {
        StringBuilder sb = new StringBuilder();

        // -- package: lowerCamelCase of mapper name
        String pkg = com.sqlgen.km.mb2dsl.transform.TypeMapper.toDslPackage(mapperName);
        sb.append("-- package: ").append(pkg).append("\n\n");

        // Models
        for (EntityIR entity : entities) {
            sb.append(ModelWriter.render(entity));
            sb.append("\n");
        }

        // Statements
        for (StatementIR stmt : statements) {
            sb.append(StatementWriter.render(stmt));
            sb.append("\n");
        }

        return sb.toString();
    }
}
