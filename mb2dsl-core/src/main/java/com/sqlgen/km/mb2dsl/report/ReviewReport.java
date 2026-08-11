package com.sqlgen.km.mb2dsl.report;

import com.sqlgen.km.mb2dsl.model.EntityIR;
import com.sqlgen.km.mb2dsl.model.StatementIR;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Collects parsing statistics and items that need manual review.
 */
public class ReviewReport {
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static class Item {
        public final String sqlFile;
        public final String statementName;
        public final List<String> tags;
        public final String rawSql;

        Item(String sqlFile, String statementName, List<String> tags, String rawSql) {
            this.sqlFile = sqlFile;
            this.statementName = statementName;
            this.tags = tags;
            this.rawSql = rawSql;
        }
    }

    private final List<Item> items = new ArrayList<>();
    private int totalStatements = 0;
    private int totalMappers = 0;
    private int totalEntities = 0;
    private int annotationStmts = 0;
    private int xmlStmts = 0;
    private int returningCount = 0;
    private int dynamicCount = 0;
    private int selectCount = 0;
    private int insertCount = 0;
    private int updateCount = 0;
    private int deleteCount = 0;
    private int modeOne = 0;
    private int modeMany = 0;
    private int modeExec = 0;
    private int modeExecrows = 0;

    // Per-mapper detail: mapperName → {sqlFile, stmtCount}
    private final List<String[]> mapperDetails = new ArrayList<>();

    public void addItem(String sqlFile, String statementName, List<String> tags, String rawSql) {
        items.add(new Item(sqlFile, statementName, tags, rawSql));
    }

    public void addMapperDetail(String mapperName, String sqlFile, int stmtCount) {
        mapperDetails.add(new String[]{mapperName, sqlFile, String.valueOf(stmtCount)});
    }

    public void collectStats(List<StatementIR> statements, List<EntityIR> entities,
                              int annotationCount, int mappersWithStatements) {
        this.totalStatements = statements.size();
        this.annotationStmts = annotationCount;
        this.xmlStmts = totalStatements - annotationCount;
        this.totalEntities = entities.size();
        this.totalMappers = mappersWithStatements;

        for (StatementIR stmt : statements) {
            if (stmt.isHasReturning()) returningCount++;
            if (!stmt.getReviewTags().isEmpty()) dynamicCount++;
            switch (stmt.getType()) {
                case SELECT -> selectCount++;
                case INSERT -> insertCount++;
                case UPDATE -> updateCount++;
                case DELETE -> deleteCount++;
            }
            if (stmt.getMode() != null) {
                switch (stmt.getMode()) {
                    case ":one" -> modeOne++;
                    case ":many" -> modeMany++;
                    case ":exec" -> modeExec++;
                    case ":execrows" -> modeExecrows++;
                }
            }
        }
    }

    public int getReviewCount() { return items.size(); }

    /**
     * Write the comprehensive parsing report.
     */
    public void writeSummary(Path outputDir) {
        StringBuilder sb = new StringBuilder();
        sb.append("# mb2dsl 解析汇报\n\n");
        sb.append("生成时间: ").append(LocalDateTime.now().format(DTF)).append("\n\n");

        // ---- Summary ----
        sb.append("## 概览\n\n");
        sb.append("| 指标 | 数量 |\n");
        sb.append("|------|------|\n");
        sb.append("| Mapper 文件 | ").append(totalMappers).append(" |\n");
        sb.append("| SQL 语句总数 | ").append(totalStatements).append(" |\n");
        if (totalEntities > 0) {
            sb.append("| Entity 定义 | ").append(totalEntities).append(" |\n");
        }
        sb.append("| 输出 .sql 文件 | ").append(mapperDetails.size()).append(" |\n");
        sb.append("\n");

        // ---- Source breakdown ----
        sb.append("## 来源\n\n");
        sb.append("| 来源 | 数量 |\n");
        sb.append("|------|------|\n");
        sb.append("| XML Mapper | ").append(xmlStmts).append(" |\n");
        sb.append("| 注解 Mapper | ").append(annotationStmts).append(" |\n");
        sb.append("\n");

        // ---- Statement types ----
        sb.append("## 语句类型\n\n");
        sb.append("| 类型 | 数量 |\n");
        sb.append("|------|------|\n");
        sb.append("| SELECT | ").append(selectCount).append(" |\n");
        sb.append("| INSERT | ").append(insertCount).append(" |\n");
        sb.append("| UPDATE | ").append(updateCount).append(" |\n");
        sb.append("| DELETE | ").append(deleteCount).append(" |\n");
        sb.append("\n");

        // ---- Modes ----
        sb.append("## 执行模式\n\n");
        sb.append("| 模式 | 数量 | 说明 |\n");
        sb.append("|------|------|------|\n");
        sb.append("| `:one` | ").append(modeOne).append(" | 单行/标量查询 |\n");
        sb.append("| `:many` | ").append(modeMany).append(" | 多行查询 |\n");
        sb.append("| `:exec` | ").append(modeExec).append(" | 执行无返回 |\n");
        sb.append("| `:execrows` | ").append(modeExecrows).append(" | 返回影响行数 |\n");
        sb.append("\n");

        // ---- Special features ----
        sb.append("## 特性检测\n\n");
        sb.append("| 特性 | 数量 |\n");
        sb.append("|------|------|\n");
        sb.append("| INSERT RETURNING / selectKey | ").append(returningCount).append(" |\n");
        sb.append("| 动态 SQL 标注 | ").append(dynamicCount).append(" |\n");
        sb.append("| 需人工处理 | ").append(items.size()).append(" |\n");
        sb.append("\n");

        // ---- Per-mapper detail ----
        sb.append("## Mapper 明细\n\n");
        sb.append("| Mapper | 输出文件 | 语句数 |\n");
        sb.append("|--------|----------|--------|\n");
        for (String[] row : mapperDetails) {
            sb.append("| ").append(row[0])
              .append(" | `").append(row[1]).append("`")
              .append(" | ").append(row[2])
              .append(" |\n");
        }
        sb.append("\n");

        // ---- Entities ----
        if (totalEntities > 0) {
            sb.append("## Entity 定义\n\n");
            for (EntityIR e : entities) {
                sb.append("- **").append(e.getClassName()).append("** — ")
                  .append(e.getFields().size()).append(" 字段");
                if (e.getTableName() != null) {
                    sb.append(", 表 `").append(e.getTableName()).append("`");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        // ---- Review items ----
        if (!items.isEmpty()) {
            sb.append("## 需人工处理\n\n");
            Map<String, List<Item>> byFile = new LinkedHashMap<>();
            for (Item item : items) {
                byFile.computeIfAbsent(item.sqlFile, k -> new ArrayList<>()).add(item);
            }
            for (var entry : byFile.entrySet()) {
                sb.append("### ").append(entry.getKey()).append("\n\n");
                for (Item item : entry.getValue()) {
                    sb.append("- **").append(item.statementName).append("**");
                    for (String tag : item.tags) {
                        sb.append("  \n  ").append(tag);
                    }
                    sb.append("\n");
                }
                sb.append("\n");
            }
        }

        try {
            Files.writeString(outputDir.resolve("_parsing_report.md"), sb.toString());
        } catch (Exception ignored) { }
    }

    /**
     * Write the manual review items (backward compatible).
     */
    public void writeTo(Path outputDir) {
        if (!items.isEmpty()) {
            writeSummary(outputDir); // comprehensive report includes review items
        }
    }

    // Internal store for entities
    private List<EntityIR> entities = List.of();

    /**
     * Write a detailed per-mapper log showing every statement and its handling.
     */
    public void writeDetailedLog(Path outputDir, List<StatementIR> allStatements) {
        StringBuilder sb = new StringBuilder();
        sb.append("# mb2dsl 解析详细日志\n\n");
        sb.append("生成时间: ").append(LocalDateTime.now().format(DTF)).append("\n\n");

        // Group by mapper name
        Map<String, List<StatementIR>> groups = new LinkedHashMap<>();
        for (StatementIR stmt : allStatements) {
            String mapper = extractMapperSimpleName(stmt.getId());
            groups.computeIfAbsent(mapper, k -> new ArrayList<>()).add(stmt);
        }

        for (var entry : groups.entrySet()) {
            String mapper = entry.getKey();
            List<StatementIR> stmts = entry.getValue();

            sb.append("## ").append(mapper).append("\n\n");
            sb.append("| 方法 | 类型 | 模式 | 特殊处理 |\n");
            sb.append("|------|------|------|----------|\n");

            for (StatementIR s : stmts) {
                String type = s.getType().name();
                String mode = s.getMode() != null ? s.getMode() : "-";
                String notes = s.getNotes().isEmpty() ? "-"
                        : String.join("; ", s.getNotes());
                String reviews = s.getReviewTags().isEmpty() ? ""
                        : " ⚠️ " + s.getReviewTags().size() + " review";

                sb.append("| `").append(s.getName()).append("`")
                  .append(" | ").append(type)
                  .append(" | ").append(mode)
                  .append(" | ").append(notes).append(reviews)
                  .append(" |\n");
            }
            sb.append("\n");
        }

        try {
            Files.writeString(outputDir.resolve("_parsing_log.md"), sb.toString());
        } catch (Exception ignored) { }
    }

    private static String extractMapperSimpleName(String statementId) {
        if (statementId == null) return "Unknown";
        String[] parts = statementId.split("\\.");
        return parts.length >= 2 ? parts[parts.length - 2] : "Unknown";
    }

    public void setEntities(List<EntityIR> entities) {
        this.entities = entities;
    }
}
