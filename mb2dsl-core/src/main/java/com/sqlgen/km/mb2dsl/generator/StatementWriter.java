package com.sqlgen.km.mb2dsl.generator;

import com.sqlgen.km.mb2dsl.model.StatementIR;
import com.sqlgen.km.mb2dsl.transform.ParamResolver;

import java.util.List;

/**
 * Generates the -- name: query block from a StatementIR.
 */
public class StatementWriter {

    /**
     * Render a single statement as DSL.
     */
    public static String render(StatementIR stmt) {
        StringBuilder sb = new StringBuilder();

        // REVIEW tags as comments
        for (String tag : stmt.getReviewTags()) {
            sb.append("-- ").append(tag).append("\n");
        }

        // -- param: directive
        String paramDirective = ParamResolver.buildParamDirective(stmt);
        if (paramDirective != null) {
            sb.append(paramDirective).append("\n");
        }

        // -- name: directive
        sb.append("-- name: ").append(stmt.getName()).append(" ").append(stmt.getMode()).append("\n");

        // -- model: directive
        if (stmt.getReturnDslType() != null) {
            sb.append("-- model");
            if (stmt.getReturnEntity() != null) {
                sb.append(": ").append(stmt.getReturnEntity());
            } else {
                sb.append(" ").append(stmt.getReturnDslType());
            }
            sb.append("\n");
        }

        // SQL
        sb.append(stmt.getSql()).append("\n");

        return sb.toString();
    }
}
