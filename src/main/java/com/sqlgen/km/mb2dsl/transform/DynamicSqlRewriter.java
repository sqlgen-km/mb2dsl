package com.sqlgen.km.mb2dsl.transform;

import org.apache.ibatis.scripting.xmltags.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts MyBatis dynamic SQL (SqlNode tree) to static SQL with review tags.
 * All MyBatis SqlNode internal fields are accessed via reflection since they're private.
 */
public class DynamicSqlRewriter {
    private static final Logger log = LoggerFactory.getLogger(DynamicSqlRewriter.class);

    // ---- Reflective field accessors ----

    private static String getTextField(StaticTextSqlNode node) {
        return (String) getField(node, "text");
    }

    private static String getTextField(TextSqlNode node) {
        return (String) getField(node, "text");
    }

    private static String getIfTest(IfSqlNode node) {
        return (String) getField(node, "test");
    }

    private static SqlNode getIfContents(IfSqlNode node) {
        return (SqlNode) getField(node, "contents");
    }

    @SuppressWarnings("unchecked")
    private static List<SqlNode> getMixedContents(MixedSqlNode node) {
        return (List<SqlNode>) getField(node, "contents");
    }

    private static String getForEachCollection(ForEachSqlNode node) {
        return (String) getField(node, "collectionExpression");
    }

    private static String getForEachItem(ForEachSqlNode node) {
        return (String) getField(node, "item");
    }

    private static String getForEachOpen(ForEachSqlNode node) {
        return (String) getField(node, "open");
    }

    private static String getForEachClose(ForEachSqlNode node) {
        return (String) getField(node, "close");
    }

    private static SqlNode getForEachContents(ForEachSqlNode node) {
        return (SqlNode) getField(node, "contents");
    }

    private static Object getField(Object obj, String fieldName) {
        try {
            Field f = obj.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.get(obj);
        } catch (Exception e) {
            log.debug("Failed to get field {} on {}: {}", fieldName, obj.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    /**
     * Walk the SqlNode tree and produce a best-effort static SQL string.
     */
    public static String toStaticSql(SqlNode root, List<String> reviewTags) {
        StringBuilder sb = new StringBuilder();
        walk(root, sb, reviewTags);
        return sb.toString().trim();
    }

    private static void walk(SqlNode node, StringBuilder sb, List<String> reviewTags) {
        if (node == null) return;

        if (node instanceof StaticTextSqlNode) {
            String text = getTextField((StaticTextSqlNode) node);
            if (text != null) sb.append(text);

        } else if (node instanceof TextSqlNode) {
            String text = getTextField((TextSqlNode) node);
            if (text != null) {
                if (text.contains("${")) {
                    reviewTags.add("REVIEW: dynamic placeholder ${} in SQL — check if table/column name is dynamic");
                }
                sb.append(text);
            }

        } else if (node instanceof IfSqlNode) {
            IfSqlNode ifNode = (IfSqlNode) node;
            String test = getIfTest(ifNode);
            SqlNode contents = getIfContents(ifNode);
            String body = collectStatic(contents);
            if (body != null && !body.isEmpty()) {
                String rewritten = rewriteIfToOptional(body, test);
                sb.append(rewritten);
            }
            if (test != null) {
                reviewTags.add("REVIEW: 原为动态SQL <if test=\"" + test + "\"> 已降级为静态SQL");
            }

        } else if (node instanceof ForEachSqlNode) {
            ForEachSqlNode forEach = (ForEachSqlNode) node;
            String rewritten = rewriteForEach(forEach);
            sb.append(rewritten);
            reviewTags.add("REVIEW: 原为 <foreach> 动态SQL，已尝试转为数组参数 — 请验证");

        } else if (node instanceof WhereSqlNode || node instanceof SetSqlNode
                || node instanceof TrimSqlNode) {
            // For WHERE/SET/TRIM: walk children recursively via reflection
            walkChildrenByReflection(node, sb, reviewTags);

        } else if (node instanceof MixedSqlNode) {
            List<SqlNode> contents = getMixedContents((MixedSqlNode) node);
            if (contents != null) {
                for (SqlNode child : contents) {
                    walk(child, sb, reviewTags);
                }
            }

        } else if (node instanceof ChooseSqlNode) {
            // <choose>/<when>/<otherwise> — take first <when>
            ChooseSqlNode choose = (ChooseSqlNode) node;
            SqlNode defaultBranch = (SqlNode) getField(choose, "otherwise");
            @SuppressWarnings("unchecked")
            List<SqlNode> whenNodes = (List<SqlNode>) getField(choose, "ifSqlNodes");
            if (whenNodes != null && !whenNodes.isEmpty()) {
                SqlNode firstWhen = whenNodes.get(0);
                if (firstWhen instanceof IfSqlNode) {
                    IfSqlNode ifNode = (IfSqlNode) firstWhen;
                    String test = getIfTest(ifNode);
                    SqlNode contents = getIfContents(ifNode);
                    String body = collectStatic(contents);
                    if (body != null) {
                        sb.append(rewriteIfToOptional(body, test));
                    }
                }
            } else if (defaultBranch != null) {
                sb.append(collectStatic(defaultBranch));
            }
            reviewTags.add("REVIEW: 原为 <choose>/<when> 动态SQL，已取第一个分支降级");

        } else {
            // Unknown node type — try reflection-based children
            log.debug("Unknown SqlNode type: {}", node.getClass().getSimpleName());
            walkChildrenByReflection(node, sb, reviewTags);
        }
    }

    /**
     * Collect static SQL text from a SqlNode, ignoring dynamic parts.
     */
    private static String collectStatic(SqlNode node) {
        if (node == null) return "";
        if (node instanceof StaticTextSqlNode) {
            String t = getTextField((StaticTextSqlNode) node);
            return t != null ? t : "";
        }
        if (node instanceof TextSqlNode) {
            String t = getTextField((TextSqlNode) node);
            return t != null ? t : "";
        }
        if (node instanceof MixedSqlNode) {
            StringBuilder sb = new StringBuilder();
            List<SqlNode> contents = getMixedContents((MixedSqlNode) node);
            if (contents != null) {
                for (SqlNode child : contents) {
                    sb.append(collectStatic(child));
                }
            }
            return sb.toString();
        }
        return "";
    }

    /**
     * Rewrite {@code <if test="param != null">body</if>}
     * to {@code AND (body OR @param IS NULL)}.
     */
    private static String rewriteIfToOptional(String body, String test) {
        if (body == null || body.isEmpty()) return "";
        if (test == null) return " " + body;

        Matcher m = Pattern.compile("(\\w+)\\s*!=\\s*null").matcher(test);
        if (m.find()) {
            String paramName = m.group(1);
            body = body.replaceAll("^(?i)\\s*(AND|OR)\\s+", "");
            return " AND (" + body.trim() + " OR @" + paramName + " IS NULL)";
        }
        return " " + body;
    }

    /**
     * Rewrite {@code <foreach collection="ids" item="id" open="(" close=")" separator=",">}
     * to {@code = ANY(@ids)}.
     */
    private static String rewriteForEach(ForEachSqlNode forEach) {
        String collection = getForEachCollection(forEach);
        String item = getForEachItem(forEach);
        String open = getForEachOpen(forEach);
        String close = getForEachClose(forEach);
        SqlNode contents = getForEachContents(forEach);
        String body = collectStatic(contents);

        // Detect batch INSERT
        if (body != null && body.toUpperCase().contains("INSERT")) {
            return "/* REVIEW: <foreach> batch INSERT not supported */\n";
        }

        // Standard IN clause
        if (open != null && open.equals("(") && close != null && close.equals(")")) {
            return "= ANY(@" + (collection != null ? collection : "ids") + ")";
        }
        return "/* @" + (collection != null ? collection : "ids") + " */";
    }

    /**
     * Walk children of a SqlNode using reflection to find a 'contents' field.
     */
    private static void walkChildrenByReflection(SqlNode node, StringBuilder sb, List<String> reviewTags) {
        @SuppressWarnings("unchecked")
        List<SqlNode> contents = (List<SqlNode>) getField(node, "contents");
        if (contents != null) {
            for (SqlNode child : contents) {
                walk(child, sb, reviewTags);
            }
        }
    }
}
