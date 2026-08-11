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
            // Walk child nodes to preserve <include> tags as text
            String text = buildInnerText(sqlEl);
            fragments.put(id, text.trim());
        }
        // Resolve nested <include> references (max 10 levels)
        for (int level = 0; level < 10; level++) {
            boolean changed = false;
            for (Map.Entry<String, String> entry : fragments.entrySet()) {
                String resolved = resolveNestedIncludes(entry.getValue(), fragments);
                if (!resolved.equals(entry.getValue())) {
                    entry.setValue(resolved);
                    changed = true;
                }
            }
            if (!changed) break;
        }
        return fragments;
    }

    /** Build inner text preserving child element tags as text. */
    private static String buildInnerText(Element el) {
        StringBuilder sb = new StringBuilder();
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE) {
                sb.append(child.getTextContent());
            } else if (child.getNodeType() == Node.ELEMENT_NODE) {
                Element childEl = (Element) child;
                sb.append("<").append(childEl.getTagName());
                // Append attributes
                NamedNodeMap attrs = childEl.getAttributes();
                for (int j = 0; j < attrs.getLength(); j++) {
                    Attr attr = (Attr) attrs.item(j);
                    sb.append(" ").append(attr.getName()).append("=\"").append(attr.getValue()).append("\"");
                }
                sb.append("/>");
            }
        }
        return sb.toString();
    }

    private static String resolveNestedIncludes(String text, Map<String, String> fragments) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<include\\s+refid\\s*=\\s*\"([^\"]+)\"\\s*/?>")
                .matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String refid = m.group(1);
            String replacement = fragments.getOrDefault(refid, m.group(0));
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
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
            String type = rm.getAttribute("type");
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
                String kp = selectKeyEl.getAttribute("keyProperty");
                ir.addNote("selectKey → RETURNING " + (kp != null ? kp : "id"));
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
                ir.addNote("useGeneratedKeys");
            }

            // Parameter type
            String paramType = el.getAttribute("parameterType");
            if (paramType != null && !paramType.isEmpty()) {
                ir.addNote("parameterType: " + lastSegment(paramType));
            }

            // Result type
            String resultMap = el.getAttribute("resultMap");
            String resultType = el.getAttribute("resultType");
            if (resultMap != null && !resultMap.isEmpty()) {
                ir.setResultMapId(resultMap);
                // Type comes from resultMap — not easily extractable without classes
            }
            if (resultType != null && !resultType.isEmpty()) {
                // Skip "map" resultType — no model in DSL
                if ("map".equalsIgnoreCase(resultType) || "hashmap".equalsIgnoreCase(resultType)) {
                    ir.setReturnEntity(null);
                    ir.setReturnDslType(null);
                } else if (isScalarTypeName(resultType)) {
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
     * Build SQL text from DOM child nodes.
     * Skips <selectKey>, expands <include>, rewrites dynamic SQL.
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
        if (!hasElements) return null;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE) {
                sb.append(child.getTextContent());
            } else if (child.getNodeType() == Node.ELEMENT_NODE) {
                Element childEl = (Element) child;
                String tag = childEl.getTagName();
                switch (tag) {
                    case "selectKey" -> {} // skip, RETURNING appended separately
                    case "include" -> {
                        String refid = childEl.getAttribute("refid");
                        if (refid != null && sqlFragments.containsKey(refid)) {
                            sb.append(" ").append(sqlFragments.get(refid)).append(" ");
                        }
                    }
                    case "if" -> rewriteIfNode(childEl, sb);
                    case "foreach" -> rewriteForEachNode(childEl, sb);
                    case "where", "set", "trim", "bind" ->
                        sb.append(childEl.getTextContent()); // strip tag, keep body
                    case "choose" -> rewriteChooseNode(childEl, sb);
                    default -> sb.append(childEl.getTextContent());
                }
            }
        }
        // Clean whitespace: collapse multiple spaces/newlines, trim
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    /** Rewrite <if test="param != null">body</if> → AND (body OR @param IS NULL) */
    private static void rewriteIfNode(Element ifEl, StringBuilder sb) {
        String test = ifEl.getAttribute("test");
        String body = ifEl.getTextContent().replaceAll("\\s+", " ").trim();
        // Extract param name from test expression
        String param = null;
        if (test != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(\\w+)\\s*!=\\s*null").matcher(test);
            if (m.find()) param = m.group(1);
        }
        if (param != null) {
            body = body.replaceAll("^(?i)\\s*(AND|OR)\\s+", "").trim();
            sb.append(" AND (").append(body).append(" OR @").append(param).append(" IS NULL)");
        } else {
            sb.append(" ").append(body);
        }
    }

    /** Rewrite <foreach> IN clause → = ANY(@ids) */
    private static void rewriteForEachNode(Element forEach, StringBuilder sb) {
        String collection = forEach.getAttribute("collection");
        String open = forEach.getAttribute("open");
        String close = forEach.getAttribute("close");
        String body = forEach.getTextContent();

        if (body != null && body.toUpperCase().contains("INSERT")) {
            sb.append(" /* <foreach> batch INSERT — manual rewrite needed */ ");
        } else if ("(".equals(open) && ")".equals(close)) {
            sb.append(" = ANY(@").append(collection != null ? collection : "ids").append(")");
        } else {
            sb.append(" /* @").append(collection != null ? collection : "ids").append(" */ ");
        }
    }

    /** Rewrite <choose> → take first <when>, fallback to <otherwise> */
    private static void rewriteChooseNode(Element choose, StringBuilder sb) {
        NodeList whens = choose.getElementsByTagName("when");
        if (whens.getLength() > 0) {
            Element first = (Element) whens.item(0);
            String body = first.getTextContent().replaceAll("\\s+", " ").trim();
            sb.append(" ").append(body);
        } else {
            NodeList other = choose.getElementsByTagName("otherwise");
            if (other.getLength() > 0) {
                sb.append(" ").append(other.item(0).getTextContent().replaceAll("\\s+", " ").trim());
            }
        }
        sb.append(" /* <choose> — first branch taken */ ");
    }

    /** Simple field definition from resultMap. */
    public static class FieldDef {
        public String column;
        public String property;
        public String javaType;
    }

    /**
     * Extract entity definitions from <resultMap> elements in XML files.
     */
    public static List<com.sqlgen.km.mb2dsl.model.EntityIR> parseResultMapEntities(List<Path> xmlFiles) {
        Map<String, com.sqlgen.km.mb2dsl.model.EntityIR> entityMap = new LinkedHashMap<>();
        for (Path xmlFile : xmlFiles) {
            try (InputStream is = Files.newInputStream(xmlFile)) {
                Document doc = DocumentBuilderFactory.newInstance()
                        .newDocumentBuilder().parse(is);
                doc.getDocumentElement().normalize();

                NodeList rmNodes = doc.getElementsByTagName("resultMap");
                for (int i = 0; i < rmNodes.getLength(); i++) {
                    Element rm = (Element) rmNodes.item(i);
                    String type = rm.getAttribute("type");
                    if (type == null || type.isEmpty()) continue;

                    String className = type.substring(type.lastIndexOf('.') + 1);
                    com.sqlgen.km.mb2dsl.model.EntityIR entity = entityMap
                            .computeIfAbsent(className, k -> new com.sqlgen.km.mb2dsl.model.EntityIR());
                    entity.setClassName(className);
                    // Use first resultMap's table as reference
                    if (entity.getTableName() == null) {
                        entity.setTableName(com.sqlgen.km.mb2dsl.transform.TypeMapper.camelToSnake(className));
                    }

                    NodeList children = rm.getChildNodes();
                    for (int j = 0; j < children.getLength(); j++) {
                        Node child = children.item(j);
                        if (child.getNodeType() != Node.ELEMENT_NODE) continue;
                        Element el = (Element) child;
                        String tag = el.getTagName();
                        if ("id".equals(tag) || "result".equals(tag)) {
                            String col = el.getAttribute("column");
                            String prop = el.getAttribute("property");
                            String jt = el.hasAttribute("javaType") ? el.getAttribute("javaType") : null;

                            // Skip if already present
                            if (entity.getFields().stream().anyMatch(f -> f.getColumnName().equals(col))) {
                                continue;
                            }

                            com.sqlgen.km.mb2dsl.model.FieldIR f = new com.sqlgen.km.mb2dsl.model.FieldIR();
                            f.setName(prop);
                            f.setColumnName(col);
                            f.setJavaType(jt != null ? jt : "String");
                            f.setDslType(com.sqlgen.km.mb2dsl.transform.TypeMapper.toDslType(
                                    jt != null ? jt : "String"));
                            f.setPrimaryKey("id".equals(tag));
                            f.setNullable(false);
                            entity.addField(f);
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to extract resultMaps from {}: {}", xmlFile.getFileName(), e.getMessage());
            }
        }
        return new ArrayList<>(entityMap.values());
    }

    // Type field added to FieldDef for external use
    public String type;
}
