package com.sqlgen.km.mb2dsl.transform;

import com.sqlgen.km.mb2dsl.model.ParamIR;
import com.sqlgen.km.mb2dsl.model.StatementIR;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves and deduplicates parameters for DSL output.
 */
public class ParamResolver {

    /**
     * Build the -- param: directive string for a statement.
     */
    public static String buildParamDirective(StatementIR ir) {
        List<ParamIR> params = ir.getParams();
        if (params.isEmpty()) return null;

        // Group object params: collect fields under each object
        Map<String, List<ParamIR>> objectParams = new LinkedHashMap<>();
        List<ParamIR> scalarParams = new ArrayList<>();

        for (ParamIR p : params) {
            String name = p.getName();
            if (name == null) continue;

            if (p.isObjectParam() && name.contains(".")) {
                String objName = name.substring(0, name.indexOf('.'));
                objectParams.computeIfAbsent(objName, k -> new ArrayList<>()).add(p);
            } else {
                // Deduplicate by name
                if (scalarParams.stream().noneMatch(sp -> sp.getName().equals(name))) {
                    scalarParams.add(p);
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        // Object params first
        for (Map.Entry<String, List<ParamIR>> entry : objectParams.entrySet()) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(entry.getKey()).append(" ").append(toPascal(entry.getKey()));
        }
        // Scalar params
        for (ParamIR p : scalarParams) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(p.getName()).append(" ").append(p.getDslType());
        }

        return sb.isEmpty() ? null : "-- param: " + sb.toString();
    }

    private static String toPascal(String name) {
        if (name == null || name.isEmpty()) return name;
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
