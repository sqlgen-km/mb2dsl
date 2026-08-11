package com.sqlgen.km.mb2dsl.engine;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.sqlgen.km.mb2dsl.model.ParamIR;
import com.sqlgen.km.mb2dsl.model.StatementIR;
import com.sqlgen.km.mb2dsl.transform.ModeResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Parses Mapper Java interface source files to extract:
 * 1. Method return types (for mode refinement)
 * 2. Annotation-based SQL statements (@Select/@Insert/@Update/@Delete)
 * All via JavaParser — no classpath or compilation needed.
 */
public class MapperInterfaceParser {
    private static final Logger log = LoggerFactory.getLogger(MapperInterfaceParser.class);

    /**
     * Parse all Mapper Java files and collect method return types.
     */
    public static Map<String, String> parseMethodReturnTypes(List<Path> mapperFiles) {
        Map<String, String> result = new HashMap<>();
        for (Path file : mapperFiles) {
            try {
                String mapperName = MapperScanner.getMapperSimpleName(file);
                Map<String, String> methods = parseOneReturnTypes(file);
                for (Map.Entry<String, String> entry : methods.entrySet()) {
                    result.put(mapperName + "." + entry.getKey(), entry.getValue());
                }
                log.debug("Parsed {} return types from {}", methods.size(), mapperName);
            } catch (Exception e) {
                log.warn("Failed to parse mapper interface {}: {}", file, e.getMessage());
            }
        }
        log.info("Parsed {} method return types from {} mapper files", result.size(), mapperFiles.size());
        return result;
    }

    /**
     * Parse annotation-based SQL statements from Mapper interface files.
     *
     * @param mapperFiles list of Mapper.java file paths
     * @return list of StatementIR from annotations
     */
    public static List<StatementIR> parseAnnotationStatements(List<Path> mapperFiles) {
        List<StatementIR> result = new ArrayList<>();
        for (Path file : mapperFiles) {
            try {
                String mapperName = MapperScanner.getMapperSimpleName(file);
                String fqMapper = extractFqMapperName(file);
                List<StatementIR> stmts = parseOneAnnotations(file, mapperName, fqMapper);
                result.addAll(stmts);
                log.debug("Parsed {} annotation statements from {}", stmts.size(), mapperName);
            } catch (Exception e) {
                log.warn("Failed to parse annotation mapper {}: {}", file, e.getMessage());
            }
        }
        log.info("Parsed {} annotation-based statements from {} mapper files", result.size(), mapperFiles.size());
        return result;
    }

    /**
     * Parse return types from a single Mapper interface.
     */
    static Map<String, String> parseOneReturnTypes(Path javaFile) throws IOException {
        Map<String, String> result = new HashMap<>();
        CompilationUnit cu = StaticJavaParser.parse(javaFile);
        for (ClassOrInterfaceDeclaration iface : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            for (MethodDeclaration method : iface.getMethods()) {
                result.put(method.getNameAsString(), typeToString(method.getType()));
            }
        }
        return result;
    }

    /**
     * Parse annotation SQL statements from a single Mapper interface.
     */
    private static List<StatementIR> parseOneAnnotations(Path javaFile, String mapperSimpleName,
                                                          String fqMapperName) throws IOException {
        List<StatementIR> result = new ArrayList<>();
        CompilationUnit cu = StaticJavaParser.parse(javaFile);

        for (ClassOrInterfaceDeclaration iface : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            for (MethodDeclaration method : iface.getMethods()) {
                StatementIR ir = extractAnnotationStatement(method, mapperSimpleName, fqMapperName);
                if (ir != null) {
                    result.add(ir);
                }
            }
        }
        return result;
    }

    /**
     * Extract a StatementIR from a method's MyBatis annotations.
     * Returns null if the method has no SQL annotation.
     */
    private static StatementIR extractAnnotationStatement(MethodDeclaration method,
                                                           String mapperSimpleName,
                                                           String fqMapperName) {
        String methodName = method.getNameAsString();

        // Check for @Select / @Insert / @Update / @Delete
        String sql = null;
        StatementIR.StatementType stmtType = null;

        for (AnnotationExpr ann : method.getAnnotations()) {
            String annName = ann.getNameAsString();
            String annSql = extractAnnotationValue(ann, "value");
            if (annSql == null) annSql = extractAnnotationValue(ann, ""); // single value

            if (annSql != null) {
                switch (annName) {
                    case "Select" -> { sql = annSql; stmtType = StatementIR.StatementType.SELECT; }
                    case "Insert" -> { sql = annSql; stmtType = StatementIR.StatementType.INSERT; }
                    case "Update" -> { sql = annSql; stmtType = StatementIR.StatementType.UPDATE; }
                    case "Delete" -> { sql = annSql; stmtType = StatementIR.StatementType.DELETE; }
                }
            }
        }

        if (sql == null || stmtType == null) return null;

        StatementIR ir = new StatementIR();
        ir.setId(fqMapperName + "." + methodName);
        ir.setName(methodName);
        ir.setMapperInterfaceName(fqMapperName);
        ir.setType(stmtType);

        // Convert #{} → @param
        String convertedSql = sql.replaceAll("#\\{([^}]+)\\}", "@$1");
        ir.setSql(convertedSql);
        ir.setRawSql(sql);

        // Extract parameters from method signature
        extractMethodParams(ir, method);

        // Check @Options for RETURNING
        for (AnnotationExpr ann : method.getAnnotations()) {
            if (ann.getNameAsString().equals("Options")) {
                String ugk = extractAnnotationValue(ann, "useGeneratedKeys");
                if ("true".equals(ugk)) {
                    ir.setHasReturning(true);
                    String keyProp = extractAnnotationValue(ann, "keyProperty");
                    if (keyProp != null) {
                        ir.setKeyColumns(List.of(keyProp));
                    }
                }
            }
        }

        // Check for RETURNING in SQL text
        if (convertedSql.toUpperCase().contains("RETURNING")) {
            ir.setHasReturning(true);
        }

        // Result type
        String returnType = typeToString(method.getType());
        if (isScalarReturn(returnType)) {
            ir.setReturnEntity(null);
            ir.setReturnDslType(com.sqlgen.km.mb2dsl.transform.TypeMapper.toDslType(returnType));
        } else if (!"void".equals(returnType)) {
            ir.setReturnEntity(returnType);
            ir.setReturnDslType(returnType);
        }

        // Mode
        ir.setMode(inferInitialMode(ir));
        // Then refine with return type
        ModeResolver.refine(ir, returnType);

        return ir;
    }

    /**
     * Extract @Param annotations from method parameters.
     */
    private static void extractMethodParams(StatementIR ir, MethodDeclaration method) {
        for (Parameter param : method.getParameters()) {
            ParamIR p = new ParamIR();

            // Check @Param("name")
            String paramName = null;
            for (AnnotationExpr ann : param.getAnnotations()) {
                if (ann.getNameAsString().equals("Param")) {
                    paramName = extractAnnotationValue(ann, "value");
                    if (paramName == null) paramName = extractAnnotationValue(ann, "");
                }
            }
            if (paramName == null) {
                paramName = param.getNameAsString();
            }
            p.setName(paramName);

            // Java type
            String javaType = typeToString(param.getType());
            p.setJavaType(javaType);
            p.setDslType(com.sqlgen.km.mb2dsl.transform.TypeMapper.toDslType(javaType));

            // Object param if type is not scalar
            p.setObjectParam(!isScalarReturn(javaType) && !"void".equals(javaType));

            ir.addParam(p);
        }
    }

    /**
     * Extract a string value from an annotation member.
     */
    private static String extractAnnotationValue(AnnotationExpr ann, String memberName) {
        if (ann.isSingleMemberAnnotationExpr()) {
            return unquote(ann.asSingleMemberAnnotationExpr().getMemberValue().toString());
        }
        if (ann.isNormalAnnotationExpr()) {
            for (MemberValuePair pair : ann.asNormalAnnotationExpr().getPairs()) {
                if (pair.getNameAsString().equals(memberName)) {
                    return unquote(pair.getValue().toString());
                }
            }
        }
        return null;
    }

    private static String unquote(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static boolean isScalarReturn(String rt) {
        if (rt == null) return true;
        return rt.equals("void") || rt.equals("int") || rt.equals("long") || rt.equals("boolean")
                || rt.equals("Integer") || rt.equals("Long") || rt.equals("Boolean")
                || rt.equals("String") || rt.equals("double") || rt.equals("float")
                || rt.equals("Double") || rt.equals("Float");
    }

    private static String inferInitialMode(StatementIR ir) {
        if (ir.isHasReturning()) return ":one";
        if (ir.getType() == StatementIR.StatementType.SELECT) return ":one";
        return ":exec";
    }

    /**
     * Extract fully qualified mapper name from file path.
     * src/main/java/com/example/mapper/UserMapper.java → com.example.mapper.UserMapper
     */
    private static String extractFqMapperName(Path javaFile) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(javaFile);
            String pkg = cu.getPackageDeclaration()
                    .map(pd -> pd.getNameAsString()).orElse("");
            String className = "";
            for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                className = cls.getNameAsString();
                break;
            }
            return pkg.isEmpty() ? className : pkg + "." + className;
        } catch (Exception e) {
            return MapperScanner.getMapperSimpleName(javaFile);
        }
    }

    private static String typeToString(Type type) {
        if (type.isVoidType()) return "void";
        if (type.isClassOrInterfaceType()) {
            ClassOrInterfaceType cit = type.asClassOrInterfaceType();
            String name = cit.getNameAsString();
            if (cit.getTypeArguments().isPresent() && !cit.getTypeArguments().get().isEmpty()) {
                String args = cit.getTypeArguments().get().stream()
                        .map(Type::asString)
                        .collect(Collectors.joining(", "));
                return name + "<" + args + ">";
            }
            return name;
        }
        return type.asString();
    }
}
