package com.sqlgen.km.mb2dsl.transform;

import java.util.Map;

/**
 * Maps Java types to sqlgen DSL types.
 */
public class TypeMapper {

    private static final Map<String, String> JAVA_TO_DSL = Map.ofEntries(
            Map.entry("long", "int64"),
            Map.entry("Long", "int64"),
            Map.entry("int", "int32"),
            Map.entry("Integer", "int32"),
            Map.entry("short", "int16"),
            Map.entry("Short", "int16"),
            Map.entry("double", "float64"),
            Map.entry("Double", "float64"),
            Map.entry("float", "float32"),
            Map.entry("Float", "float32"),
            Map.entry("boolean", "bool"),
            Map.entry("Boolean", "bool"),
            Map.entry("String", "string"),
            Map.entry("BigDecimal", "float64"),
            Map.entry("LocalDateTime", "time.Time"),
            Map.entry("LocalDate", "time.Time"),
            Map.entry("Date", "time.Time"),
            Map.entry("Timestamp", "time.Time"),
            Map.entry("byte[]", "[]byte"),
            Map.entry("Object", "string")
    );

    /**
     * Convert a Java type name to its DSL equivalent.
     */
    public static String toDslType(String javaType) {
        if (javaType == null) return "string";

        // Strip generic: List<Long> → Long
        String baseType = javaType.replaceAll("<.*>", "");

        // Strip package: java.lang.String → String
        if (baseType.startsWith("java.lang.")) {
            baseType = baseType.substring(10);
        }
        if (baseType.startsWith("java.math.")) {
            baseType = baseType.substring(10);
        }
        if (baseType.startsWith("java.time.")) {
            baseType = baseType.substring(10);
        }
        if (baseType.startsWith("java.util.")) {
            baseType = baseType.substring(10);
        }

        return JAVA_TO_DSL.getOrDefault(baseType, baseType);
    }

    /**
     * Convert CamelCase to snake_case.
     * displayName  → display_name
     * UserMapper   → user_mapper
     * ID           → id
     */
    public static String camelToSnake(String camel) {
        if (camel == null || camel.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0 && Character.isLowerCase(camel.charAt(i - 1))) {
                    sb.append('_');
                }
                // Handle consecutive uppercase (e.g., "XMLParser" → "xml_parser")
                if (i > 0 && i + 1 < camel.length()
                        && Character.isUpperCase(camel.charAt(i - 1))
                        && Character.isLowerCase(camel.charAt(i + 1))) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Convert Mapper interface name to DSL package name (lowerCamelCase).
     * UserMapper → userMapper
     * AimsConfigMapper → aimsConfigMapper
     */
    public static String toDslPackage(String mapperName) {
        if (mapperName == null || mapperName.isEmpty()) return "";
        return Character.toLowerCase(mapperName.charAt(0)) + mapperName.substring(1);
    }
}
