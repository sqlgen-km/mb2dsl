package com.sqlgen.km.mb2dsl.engine;

import com.sqlgen.km.mb2dsl.model.ParamIR;
import com.sqlgen.km.mb2dsl.model.StatementIR;
import org.apache.ibatis.mapping.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts intermediate representation (IR) from a MyBatis {@link org.apache.ibatis.session.Configuration}.
 */
public class Introspector {
    private static final Logger log = LoggerFactory.getLogger(Introspector.class);

    /**
     * Extract all MappedStatements as StatementIR list.
     */
    public List<StatementIR> extractStatements(org.apache.ibatis.session.Configuration config) {
        List<StatementIR> result = new ArrayList<>();
        java.util.Set<String> seenIds = new java.util.HashSet<>();
        for (MappedStatement ms : config.getMappedStatements()) {
            if (!seenIds.add(ms.getId())) {
                continue; // deduplicate
            }
            try {
                StatementIR ir = extractOne(ms);
                if (ir != null) {
                    result.add(ir);
                }
            } catch (Exception e) {
                log.warn("Failed to extract statement {}: {}", ms.getId(), e.getMessage());
            }
        }
        log.info("Extracted {} statements", result.size());
        return result;
    }

    private StatementIR extractOne(MappedStatement ms) {
        StatementIR ir = new StatementIR();
        ir.setId(ms.getId());

        // Name: last segment of id
        String[] parts = ms.getId().split("\\.");
        ir.setName(parts[parts.length - 1]);

        // Mapper interface name
        StringBuilder mapperName = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            if (i > 0) mapperName.append(".");
            mapperName.append(parts[i]);
        }
        ir.setMapperInterfaceName(mapperName.toString());

        // Statement type
        ir.setType(mapCommandType(ms.getSqlCommandType()));

        // Get parameter mappings
        List<ParameterMapping> paramMappings = getParamMappings(ms);

        // SQL extraction with @param reconstruction
        SqlSource sqlSource = ms.getSqlSource();
        String rawSql = extractSqlText(sqlSource, paramMappings);
        ir.setRawSql(rawSql);
        ir.setSql(rawSql);

        // Parameters
        extractParameters(ir, paramMappings);

        // Result type
        extractResultType(ms, ir);

        // RETURNING detection
        if (ms.getKeyProperties() != null && ms.getKeyProperties().length > 0) {
            ir.setHasReturning(true);
            ir.setKeyColumns(List.of(ms.getKeyProperties()));
        }
        if (!ir.isHasReturning() && rawSql.toUpperCase().contains("RETURNING")) {
            ir.setHasReturning(true);
        }

        // Mode inference
        ir.setMode(inferMode(ir));

        // Source file
        if (ms.getResource() != null) {
            ir.setSourceFile(ms.getResource());
        }

        return ir;
    }

    private List<ParameterMapping> getParamMappings(MappedStatement ms) {
        ParameterMap paramMap = ms.getParameterMap();
        if (paramMap != null && paramMap.getParameterMappings() != null) {
            return paramMap.getParameterMappings();
        }
        return List.of();
    }

    private String extractSqlText(SqlSource sqlSource, List<ParameterMapping> paramMappings) {
        BoundSql boundSql = sqlSource.getBoundSql(null);
        String sql = boundSql.getSql().trim();

        // Also check BoundSql's own parameter mappings (may be more complete)
        List<ParameterMapping> boundParams = boundSql.getParameterMappings();
        if (boundParams != null && !boundParams.isEmpty()) {
            for (ParameterMapping mapping : boundParams) {
                String prop = mapping.getProperty();
                if (prop != null && !prop.isEmpty()) {
                    sql = sql.replaceFirst("\\?", "@" + prop);
                }
            }
        } else if (paramMappings != null && !paramMappings.isEmpty()) {
            for (ParameterMapping mapping : paramMappings) {
                String prop = mapping.getProperty();
                if (prop != null && !prop.isEmpty()) {
                    sql = sql.replaceFirst("\\?", "@" + prop);
                }
            }
        }
        return sql;
    }

    private StatementIR.StatementType mapCommandType(SqlCommandType type) {
        return switch (type) {
            case SELECT -> StatementIR.StatementType.SELECT;
            case INSERT -> StatementIR.StatementType.INSERT;
            case UPDATE -> StatementIR.StatementType.UPDATE;
            case DELETE -> StatementIR.StatementType.DELETE;
            default -> StatementIR.StatementType.SELECT;
        };
    }

    private void extractParameters(StatementIR ir, List<ParameterMapping> mappings) {
        if (mappings == null) return;

        for (ParameterMapping mapping : mappings) {
            ParamIR param = new ParamIR();
            String propName = mapping.getProperty();
            param.setName(propName);

            if (mapping.getJavaType() != null) {
                String javaType = mapping.getJavaType().getName();
                if (javaType.startsWith("java.lang.")) {
                    javaType = javaType.substring(10);
                }
                param.setJavaType(javaType);
                param.setDslType(com.sqlgen.km.mb2dsl.transform.TypeMapper.toDslType(javaType));
            } else {
                param.setJavaType("String");
                param.setDslType("string");
            }

            param.setObjectParam(propName != null && propName.contains("."));
            ir.addParam(param);
        }
    }

    private void extractResultType(MappedStatement ms, StatementIR ir) {
        List<ResultMap> resultMaps = ms.getResultMaps();
        if (resultMaps == null || resultMaps.isEmpty()) return;

        ResultMap resultMap = resultMaps.get(0);
        if (resultMap == null) return;

        Class<?> type = resultMap.getType();
        if (type == null) return;

        String typeName = type.getSimpleName();

        if (isScalarType(type)) {
            ir.setReturnEntity(null);
            ir.setReturnDslType(com.sqlgen.km.mb2dsl.transform.TypeMapper.toDslType(typeName));
        } else {
            ir.setReturnEntity(typeName);
            ir.setReturnDslType(typeName);
        }
    }

    private boolean isScalarType(Class<?> type) {
        return type.isPrimitive()
                || Number.class.isAssignableFrom(type)
                || type == String.class
                || type == Boolean.class
                || type == java.util.Date.class
                || type == java.math.BigDecimal.class
                || type.getName().startsWith("java.time.");
    }

    /**
     * Infer the sqlgen :mode from statement type and return type.
     */
    private String inferMode(StatementIR ir) {
        if (ir.isHasReturning() && ir.getType() == StatementIR.StatementType.INSERT) {
            return ":one";
        }
        if (ir.getType() == StatementIR.StatementType.SELECT) {
            return ":one";
        }
        if (ir.getType() == StatementIR.StatementType.INSERT
                || ir.getType() == StatementIR.StatementType.UPDATE
                || ir.getType() == StatementIR.StatementType.DELETE) {
            // INSERT RETURNING is handled above; check if result type suggests :execrows
            String rt = ir.getReturnDslType();
            if (rt != null && (rt.equals("int") || rt.equals("long")
                    || rt.equals("int32") || rt.equals("int64"))) {
                return ":execrows";
            }
            return ":exec";
        }
        return ":one";
    }
}
