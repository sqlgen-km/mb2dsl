package com.sqlgen.km.mb2dsl.engine;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.sqlgen.km.mb2dsl.model.EntityIR;
import com.sqlgen.km.mb2dsl.model.FieldIR;
import com.sqlgen.km.mb2dsl.transform.TypeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Scans Entity/POJO classes using JavaParser (no classpath required).
 */
public class EntityScanner {
    private static final Logger log = LoggerFactory.getLogger(EntityScanner.class);

    /**
     * Scan for entity classes under the given directory.
     * Entity classes are identified by having @Table, @Entity, or being referenced by a Mapper.
     */
    public static List<Path> scanEntityFiles(Path sourceDir, List<String> entityPackages) throws IOException {
        List<Path> result = new ArrayList<>();
        if (!Files.isDirectory(sourceDir)) {
            return result;
        }
        try (Stream<Path> stream = Files.walk(sourceDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().endsWith("Mapper.java"))
                    .filter(p -> !p.toString().endsWith("Mapper.xml"))
                    .forEach(result::add);
        }
        log.info("Found {} potential entity files", result.size());
        return result;
    }

    /**
     * Parse a single Java entity file into EntityIR.
     */
    public static EntityIR parse(Path javaFile) throws IOException {
        CompilationUnit cu = StaticJavaParser.parse(javaFile);

        // Get the (first) class/interface declaration
        Optional<ClassOrInterfaceDeclaration> classOpt = cu.findFirst(ClassOrInterfaceDeclaration.class);
        if (classOpt.isEmpty()) {
            throw new IOException("No class found in " + javaFile);
        }

        ClassOrInterfaceDeclaration cls = classOpt.get();
        EntityIR ir = new EntityIR();
        ir.setClassName(cls.getNameAsString());
        ir.setPackageName(cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse(""));
        ir.setSourcePath(javaFile.toString());

        // Table name: @Table(name="xxx") or inferred from class name
        String tableName = null;
        Optional<AnnotationExpr> tableAnn = cls.getAnnotationByName("Table");
        if (tableAnn.isPresent() && tableAnn.get().isNormalAnnotationExpr()) {
            tableName = extractAnnotationValue(tableAnn.get(), "name");
        }
        if (tableName == null || tableName.isEmpty()) {
            tableName = TypeMapper.camelToSnake(cls.getNameAsString());
        }
        ir.setTableName(tableName);

        // Fields
        for (FieldDeclaration field : cls.getFields()) {
            // Skip static/final fields
            if (field.isStatic() || field.isFinal()) continue;

            field.getVariables().forEach(var -> {
                FieldIR f = new FieldIR();
                f.setName(var.getNameAsString());

                String javaType = field.getCommonType().asString();
                f.setJavaType(javaType);
                f.setDslType(TypeMapper.toDslType(javaType));

                // @Column(name="xxx")
                String colName = null;
                Optional<AnnotationExpr> colAnn = field.getAnnotationByName("Column");
                if (colAnn.isPresent() && colAnn.get().isNormalAnnotationExpr()) {
                    colName = extractAnnotationValue(colAnn.get(), "name");
                }
                if (colName == null || colName.isEmpty()) {
                    colName = TypeMapper.camelToSnake(var.getNameAsString());
                }
                f.setColumnName(colName);

                // @Id
                f.setPrimaryKey(field.getAnnotationByName("Id").isPresent()
                        || field.getAnnotationByName("GeneratedValue").isPresent());

                // Nullable: wrapper types (Long vs long)
                f.setNullable(isWrapperType(javaType));

                ir.addField(f);
            });
        }

        return ir;
    }

    /**
     * Parse all entity files, returning only those that look like entities
     * (have fields, not utility classes).
     */
    public static List<EntityIR> parseAll(List<Path> javaFiles) {
        List<EntityIR> result = new ArrayList<>();
        for (Path file : javaFiles) {
            try {
                EntityIR entity = parse(file);
                if (!entity.getFields().isEmpty()) {
                    result.add(entity);
                    log.debug("Parsed entity: {} ({} fields)", entity.getClassName(), entity.getFields().size());
                }
            } catch (Exception e) {
                log.debug("Skipping {}: {}", file.getFileName(), e.getMessage());
            }
        }
        log.info("Parsed {} entities with fields", result.size());
        return result;
    }

    private static String extractAnnotationValue(AnnotationExpr ann, String key) {
        if (ann.isNormalAnnotationExpr()) {
            for (MemberValuePair pair : ann.asNormalAnnotationExpr().getPairs()) {
                if (pair.getNameAsString().equals(key)) {
                    if (pair.getValue().isStringLiteralExpr()) {
                        return pair.getValue().asStringLiteralExpr().asString();
                    }
                }
            }
        }
        return null;
    }

    private static boolean isWrapperType(String javaType) {
        return javaType.startsWith("java.lang.")
                || javaType.equals("Long") || javaType.equals("Integer")
                || javaType.equals("Double") || javaType.equals("Float")
                || javaType.equals("Boolean") || javaType.equals("Short")
                || javaType.equals("Byte") || javaType.equals("Character");
    }
}
