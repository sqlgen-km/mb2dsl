package com.sqlgen.km.mb2dsl.generator;

import com.sqlgen.km.mb2dsl.model.EntityIR;
import com.sqlgen.km.mb2dsl.model.FieldIR;

import java.util.List;

/**
 * Generates the -- model: DSL block from an EntityIR.
 */
public class ModelWriter {

    /**
     * Render the model definition.
     */
    public static String render(EntityIR entity) {
        StringBuilder sb = new StringBuilder();
        sb.append("-- model: ").append(entity.getClassName()).append(" {\n");

        List<FieldIR> fields = entity.getFields();
        int maxNameLen = fields.stream()
                .mapToInt(f -> f.getColumnName().length())
                .max().orElse(10);
        int maxTypeLen = fields.stream()
                .mapToInt(f -> f.getDslType().length())
                .max().orElse(8);

        for (FieldIR field : fields) {
            sb.append("    ");
            sb.append(padRight(field.getColumnName(), maxNameLen + 2));
            sb.append(padRight(field.getDslType(), maxTypeLen + 2));
            // Comment: Java type + annotations
            String comment = field.getJavaType();
            if (field.isPrimaryKey()) comment += ", PK";
            if (field.isNullable()) comment += ", nullable";
            sb.append("  -- ").append(comment);
            sb.append("\n");
        }

        sb.append("}\n");
        return sb.toString();
    }

    private static String padRight(String s, int len) {
        if (s.length() >= len) return s;
        return s + " ".repeat(len - s.length());
    }
}
