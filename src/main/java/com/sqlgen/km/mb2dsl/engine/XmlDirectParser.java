package com.sqlgen.km.mb2dsl.engine;

import com.sqlgen.km.mb2dsl.model.StatementIR;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Direct XML parser for MyBatis mapper files.
 * Extracts SQL statements, result maps, and entity definitions
 * WITHOUT needing entity classes on the classpath.
 */
public class XmlDirectParser {
    private static final Logger log = LoggerFactory.getLogger(XmlDirectParser.class);

    /**
     * Parse a mapper XML file and extract statements.
     * Returns empty list if parsing fails (caller should fall back to MyBatis Configuration).
     */
    public static List<StatementIR> parse(Path xmlFile, String mapperInterfaceName) {
        try (InputStream is = Files.newInputStream(xmlFile)) {
            Document doc = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder().parse(is);
            doc.getDocumentElement().normalize();

            // Build sql fragment map
            Map<String, String> sqlFragments = buildSqlFragments(doc);

            // Build result maps
            Map<String, List<FieldDef>> resultMaps = buildResultMaps(doc);

            // Extract statements
            List<StatementIR> result = new ArrayList<>();
            result.addAll(extractStatements(doc, "select", StatementIR.StatementType.SELECT,
                    sqlFragments, mapperInterfaceName));
            result.addAll(extractStatements(doc, "insert", StatementIR.StatementType.INSERT,
                    sqlFragments, mapperInterfaceName));
            result.addAll(extractStatements(doc, "update", StatementIR.StatementType.UPDATE,
                    sqlFragments, mapperInterfaceName));
            result.addAll(extractStatements(doc, "delete", StatementIR.StatementType.DELETE,
                    sqlFragments, mapperInterfaceName));

            log.debug("Direct-parsed {} statements from {}", result.size(), xmlFile.getFileName());
            return result;
        } catch (Exception e) {
            log.debug("Direct XML parse failed for {}: {}", xmlFile.getFileName(), e.getMessage());
            return List.of();
        }
    }

    /**
     * Build map of <sql id="..."> fragments.
     */
    private static Map<String, String> buildSqlFragments(Document doc) {
        Map<String, String> fragments = new HashMap<>();
        NodeList sqlNodes = doc.getElementsByTagName("sql");
        for (int i = 0; i < sqlNodes.getLength(); i++) {
            Element sqlEl = (Element) sqlNodes.item(i);
            String id = sqlEl.getAttribute("id");
            String text = sqlEl.getTextContent().trim();
            fragments.put(id, text);
        }
        return fragments;
    }

    /**
     * Build result maps from <resultMap> elements.
     */
    private static Map<String, List<FieldDef>> buildResultMaps(Document doc) {
        Map<String, List<FieldDef>> result = new HashMap<>();
        NodeList rmNodes = doc.getElementsByTagName("resultMap");
        for (int i = 0; i < rmNodes.getLength(); i++) {
            Element rm = (Element) rmNodes.item(i);
            String id = rm.getAttribute("id");
            List<FieldDef> fields = new ArrayList<>();

            NodeList children = rm.getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                Node child = children.item(j);
                if (child.getNodeType() != Node.ELEMENT_NODE) continue;
                Element el = (Element) child;
                String tag = el.getTagName();
                if ("id".equals(tag) || "result".equals(tag)) {
                    FieldDef f = new FieldDef();
                    f.column = el.getAttribute("column");
                    f.property = el.getAttribute("property");
                    f.javaType = el.hasAttribute("javaType") ? el.getAttribute("javaType") : null;
                    fields.add(f);
                }
            }
            result.put(id, fields);
        }
        return result;
    }

    /**
     * Extract <select>/<insert>/<update>/<delete> elements.
     */
    private static List<StatementIR> extractStatements(Document doc, String tag,
                                                        StatementIR.StatementType type,
                                                        Map<String, String> sqlFragments,
                                                        String mapperInterfaceName) {
        List<StatementIR> result = new ArrayList<>();
        NodeList nodes = doc.getElementsByTagName(tag);
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            String id = el.getAttribute("id");
            if (id == null || id.isEmpty()) continue;

            StatementIR ir = new StatementIR();
            ir.setId(mapperInterfaceName + "." + id);
            ir.setName(id);
            ir.setMapperInterfaceName(mapperInterfaceName);
            ir.setType(type);

            // Build SQL by walking DOM child nodes
            // Skip <selectKey> elements; expand <include> references
            Element selectKeyEl = null;
            String sql = buildSqlFromChildren(el, sqlFragments);
            if (sql == null) {
                // Fallback: plain text content
                sql = el.getTextContent().trim();
            }

            // Check if we skipped a <selectKey>
            NodeList skNodes = el.getElementsByTagName("selectKey");
            if (skNodes.getLength() > 0) {
                selectKeyEl = (Element) skNodes.item(0);
                ir.setHasReturning(true);
            }

            // Remove #{} → @param
            sql = convertPlaceholders(sql);
            // Append RETURNING if selectKey was present
            if (selectKeyEl != null) {
                sql = appendReturning(sql, selectKeyEl);
            }
            ir.setSql(sql);

            // Detect RETURNING in SQL text
            if (sql.toUpperCase().contains("RETURNING")) {
                ir.setHasReturning(true);
            }
            // Detect useGeneratedKeys
            String ugk = el.getAttribute("useGeneratedKeys");
            if ("true".equals(ugk)) {
                ir.setHasReturning(true);
            }

            // Parameter type
            String paramType = el.getAttribute("parameterType");
            if (paramType != null && !paramType.isEmpty()) {
                // Extract simple param from SQL placeholders
                extractSimpleParams(ir, sql);
            }

            // Result type
            String resultMap = el.getAttribute("resultMap");
            String resultType = el.getAttribute("resultType");
            if (resultMap != null && !resultMap.isEmpty()) {
                ir.setResultMapId(resultMap);
                // Type comes from resultMap — not easily extractable without classes
            }
            if (resultType != null && !resultType.isEmpty()) {
                if (isScalarTypeName(resultType)) {
                    ir.setReturnEntity(null);
                    ir.setReturnDslType(mapScalarType(resultType));
                } else {
                    ir.setReturnEntity(lastSegment(resultType));
                    ir.setReturnDslType(lastSegment(resultType));
                }
            }

            // Mode
            ir.setMode(inferMode(ir));

            // Dynamic SQL detection
            String rawText = el.getTextContent();
            if (rawText.contains("<if") || rawText.contains("<foreach")
                    || rawText.contains("<where") || rawText.contains("<choose")) {
                ir.addReviewTag("REVIEW: contains dynamic SQL (<if>/<foreach>/<where>)");
            }

            result.add(ir);
        }
        return result;
    }

    private static String expandIncludes(String sql, Element parent, Map<String, String> fragments) {
        NodeList includes = parent.getElementsByTagName("include");
        for (int i = includes.getLength() - 1; i >= 0; i--) {
            Element inc = (Element) includes.item(i);
            String refid = inc.getAttribute("refid");
            if (refid != null && fragments.containsKey(refid)) {
                // Replace the <include> placeholder with actual SQL
                // Approximation: replace <include refid="X"/> with fragment text
            }
        }
        // Simple approach: replace known include patterns
        for (Map.Entry<String, String> entry : fragments.entrySet()) {
            sql = sql.replaceAll(
                    "<include\\s+refid\\s*=\\s*\"" + entry.getKey() + "\"\\s*/?>",
                    entry.getValue());
        }
        return sql;
    }

    private static String convertPlaceholders(String sql) {
        // #{} → @param
        sql = sql.replaceAll("#\\{([^}]+)\\}", "@$1");
        // Mark ${}
        if (sql.contains("${")) {
            sql = "/* REVIEW: ${} dynamic */ " + sql;
        }
        return sql;
    }

    private static void extractSimpleParams(StatementIR ir, String sql) {
        // Extract @param references from converted SQL
        Matcher m = Pattern.compile("@(\\w+)").matcher(sql);
        while (m.find()) {
            String name = m.group(1);
            // Avoid duplicates
            if (ir.getParams().stream().noneMatch(p -> name.equals(p.getName()))) {
                com.sqlgen.km.mb2dsl.model.ParamIR param = new com.sqlgen.km.mb2dsl.model.ParamIR();
                param.setName(name);
                param.setJavaType("Object");
                param.setDslType("string");
                ir.addParam(param);
            }
        }
    }

    private static boolean isScalarTypeName(String type) {
        if (type == null) return true;
        return type.equals("int") || type.equals("long") || type.equals("boolean")
                || type.equals("Integer") || type.equals("Long") || type.equals("Boolean")
                || type.equals("String") || type.equals("string")
                || type.equals("void") || type.equals("double") || type.equals("float");
    }

    private static String mapScalarType(String type) {
        return com.sqlgen.km.mb2dsl.transform.TypeMapper.toDslType(type);
    }

    private static String lastSegment(String fqn) {
        if (fqn == null) return null;
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }

    private static String inferMode(StatementIR ir) {
        if (ir.getType() == StatementIR.StatementType.SELECT) {
            return ":one"; // will be refined by MapperInterfaceParser
        }
        if (ir.isHasReturning()) {
            return ":one";
        }
        if (ir.getType() == StatementIR.StatementType.INSERT
                || ir.getType() == StatementIR.StatementType.UPDATE
                || ir.getType() == StatementIR.StatementType.DELETE) {
            String rt = ir.getReturnDslType();
            if (rt != null && (rt.equals("int64") || rt.equals("int32"))) {
                return ":execrows";
            }
            return ":exec";
        }
        return ":one";
    }

    /**
     * Append RETURNING clause from <selectKey keyProperty="...">.
     */
    private static String appendReturning(String sql, Element selectKeyEl) {
        String keyProp = selectKeyEl.getAttribute("keyProperty");
        if (keyProp != null && !keyProp.isEmpty()
                && !sql.toUpperCase().contains("RETURNING")) {
            sql = sql.replaceAll(";?\\s*$", "") + " RETURNING " + keyProp;
        }
        return sql.replaceAll("\\s+", " ");
    }

    /**
     * Build SQL text from DOM child nodes, skipping <selectKey>.
     */
    private static String buildSqlFromChildren(Element el, Map<String, String> sqlFragments) {
        NodeList children = el.getChildNodes();
        if (children.getLength() == 0) return null;

        boolean hasElements = false;
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                hasElements = true;
                break;
            }
        }
        if (!hasElements) {
            // Pure text — use getTextContent() directly
            return null;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE) {
                sb.append(child.getTextContent());
            } else if (child.getNodeType() == Node.ELEMENT_NODE) {
                Element childEl = (Element) child;
                String tag = childEl.getTagName();
                if ("selectKey".equals(tag)) {
                    // Skip — RETURNING appended separately
                } else if ("include".equals(tag)) {
                    String refid = childEl.getAttribute("refid");
                    if (refid != null && sqlFragments.containsKey(refid)) {
                        sb.append(sqlFragments.get(refid));
                    }
                } else {
                    // <if>, <where>, <foreach>, etc. — extract text
                    sb.append(childEl.getTextContent());
                }
            }
        }
        return sb.toString().trim();
    }

    /** Simple field definition from resultMap. */
    public static class FieldDef {
        public String column;
        public String property;
        public String javaType;
    }
}
