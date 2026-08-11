package com.sqlgen.km.mb2dsl.transform;

import com.sqlgen.km.mb2dsl.model.StatementIR;

import java.util.regex.Pattern;

/**
 * Resolves the sqlgen :mode directive from return type and annotations.
 */
public class ModeResolver {

    /**
     * Refine the mode based on additional context from the mapper interface.
     * The Introspector gives a best-guess default; this method applies overrides
     * based on the Java method signature.
     *
     * @param ir       the statement IR
     * @param javaReturnType  the Java method return type string (e.g., "List<User>", "long", "void")
     */
    public static void refine(StatementIR ir, String javaReturnType) {
        if (javaReturnType == null) return;

        String rt = javaReturnType.trim();

        if (rt.equals("void") || rt.equals("Void")) {
            ir.setMode(":exec");
            ir.setReturnEntity(null);
            ir.setReturnDslType(null);
            return;
        }

        // Collection types → :many
        if (rt.startsWith("List<") || rt.startsWith("Collection<")
                || rt.startsWith("Set<") || rt.startsWith("ArrayList<")) {
            ir.setMode(":many");
            // Extract entity type from generic
            String entity = rt.replaceAll(".*<(.+)>.*", "$1");
            if (!entity.equals(rt) && ir.getReturnEntity() == null) {
                ir.setReturnEntity(entity);
                ir.setReturnDslType(entity);
            }
            return;
        }

        // Optional → :one
        if (rt.startsWith("Optional<")) {
            ir.setMode(":one");
            String entity = rt.replaceAll(".*<(.+)>.*", "$1");
            if (!entity.equals(rt)) {
                ir.setReturnEntity(entity);
                ir.setReturnDslType(entity);
            }
            return;
        }

        // Scalar return types
        if (rt.equals("int") || rt.equals("long") || rt.equals("boolean")
                || rt.equals("Integer") || rt.equals("Long") || rt.equals("Boolean")) {
            // INSERT with RETURNING (useGeneratedKeys) → :one
            if (ir.getType() == StatementIR.StatementType.INSERT && ir.isHasReturning()) {
                ir.setMode(":one");
                ir.setReturnEntity(null);
                ir.setReturnDslType(TypeMapper.toDslType(rt));
                return;
            }
            if (ir.getType() != StatementIR.StatementType.SELECT) {
                ir.setMode(":execrows");
            } else {
                ir.setMode(":one");
            }
            ir.setReturnEntity(null);
            ir.setReturnDslType(TypeMapper.toDslType(rt));
            return;
        }

        // Entity return type (single object)
        if (ir.getType() == StatementIR.StatementType.SELECT) {
            ir.setMode(":one");
            if (ir.getReturnEntity() == null) {
                ir.setReturnEntity(rt);
                ir.setReturnDslType(rt);
            }
        }
    }
}
