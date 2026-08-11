package com.sqlgen.km.mb2dsl.generator;

import com.sqlgen.km.mb2dsl.model.EntityIR;
import com.sqlgen.km.mb2dsl.model.StatementIR;
import com.sqlgen.km.mb2dsl.transform.TypeMapper;

import java.util.*;

/**
 * Groups statements by their mapper interface.
 * Each Mapper → one .sql file, package = mapperName.
 */
public class FileGrouper {

    /**
     * Group statements by mapper interface name.
     * Key: mapper simple name (e.g., "AimsConfigMapper")
     * Value: list of statements belonging to that mapper
     */
    public static Map<String, List<StatementIR>> byMapperInterface(List<StatementIR> statements) {
        Map<String, List<StatementIR>> groups = new LinkedHashMap<>();
        for (StatementIR stmt : statements) {
            String mapperName = extractMapperSimpleName(stmt);
            groups.computeIfAbsent(mapperName, k -> new ArrayList<>()).add(stmt);
        }
        return groups;
    }

    /**
     * Find the entity(ies) referenced by statements in a group.
     */
    public static List<EntityIR> entitiesForGroup(List<StatementIR> stmts, List<EntityIR> allEntities) {
        Set<String> entityNames = new LinkedHashSet<>();
        for (StatementIR stmt : stmts) {
            if (stmt.getReturnEntity() != null) {
                entityNames.add(stmt.getReturnEntity());
            }
        }

        List<EntityIR> result = new ArrayList<>();
        for (EntityIR entity : allEntities) {
            if (entityNames.contains(entity.getClassName())) {
                result.add(entity);
            }
        }
        return result;
    }

    /**
     * Generate the output .sql file name from mapper name.
     * AimsConfigMapper → aims_config.sql
     */
    public static String toSqlFileName(String mapperSimpleName) {
        String stem = mapperSimpleName;
        // Remove "Mapper" suffix if present
        if (stem.endsWith("Mapper")) {
            stem = stem.substring(0, stem.length() - 6);
        }
        return TypeMapper.camelToSnake(stem) + ".sql";
    }

    /**
     * Extract mapper simple name from statement id.
     * "com.example.mapper.AimsConfigMapper.findById" → "AimsConfigMapper"
     */
    private static String extractMapperSimpleName(StatementIR stmt) {
        String id = stmt.getId();
        if (id == null) return "UnknownMapper";
        String[] parts = id.split("\\.");
        if (parts.length >= 2) {
            return parts[parts.length - 2];  // second-to-last = Mapper interface name
        }
        return "UnknownMapper";
    }
}
