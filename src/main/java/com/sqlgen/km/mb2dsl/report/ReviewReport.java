package com.sqlgen.km.mb2dsl.report;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * Collects items that need manual review after conversion.
 */
public class ReviewReport {

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
    private int successCount = 0;

    public void addItem(String sqlFile, String statementName, List<String> tags, String rawSql) {
        items.add(new Item(sqlFile, statementName, tags, rawSql));
    }

    public void setSuccessCount(int count) {
        this.successCount = count;
    }

    public int getReviewCount() {
        return items.size();
    }

    public boolean hasItems() {
        return !items.isEmpty();
    }

    /**
     * Write the review report as markdown.
     */
    public void writeTo(Path outputDir) {
        if (!hasItems()) return;

        StringBuilder sb = new StringBuilder();
        sb.append("# MyBatis → sqlgen DSL 转换报告\n\n");
        sb.append("生成时间: ").append(LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n\n");

        sb.append("## 统计\n\n");
        sb.append("- 成功转换: ").append(successCount).append(" 个方法\n");
        sb.append("- 需人工处理: ").append(items.size()).append(" 个方法\n\n");

        sb.append("## 需人工处理\n\n");

        // Group by file
        Map<String, List<Item>> byFile = new LinkedHashMap<>();
        for (Item item : items) {
            byFile.computeIfAbsent(item.sqlFile, k -> new ArrayList<>()).add(item);
        }

        for (Map.Entry<String, List<Item>> entry : byFile.entrySet()) {
            for (Item item : entry.getValue()) {
                sb.append("### ").append(item.statementName).append("\n\n");
                sb.append("- **文件**: `").append(item.sqlFile).append("`\n");
                for (String tag : item.tags) {
                    sb.append("- ").append(tag).append("\n");
                }
                if (item.rawSql != null && !item.rawSql.isEmpty()) {
                    sb.append("\n原始 SQL:\n```sql\n").append(item.rawSql).append("\n```\n");
                }
                sb.append("\n---\n\n");
            }
        }

        try {
            Files.writeString(outputDir.resolve("_manual_review.md"), sb.toString());
        } catch (Exception e) {
            // Silently fail — report is non-critical
        }
    }
}
