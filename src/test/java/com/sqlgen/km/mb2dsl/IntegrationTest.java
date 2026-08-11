package com.sqlgen.km.mb2dsl;

import com.sqlgen.km.mb2dsl.engine.*;
import com.sqlgen.km.mb2dsl.generator.DslGenerator;
import com.sqlgen.km.mb2dsl.model.EntityIR;
import com.sqlgen.km.mb2dsl.model.StatementIR;
import com.sqlgen.km.mb2dsl.report.ReviewReport;
import com.sqlgen.km.mb2dsl.transform.ModeResolver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class IntegrationTest {

    private static final Path FIXTURES = Paths.get("src/test/fixtures");
    private static Path compiledClassesDir;
    private static ClassLoader fixturesClassLoader;

    @BeforeAll
    static void compileFixtures() throws Exception {
        // Compile fixture Java files to a temp directory
        compiledClassesDir = Files.createTempDirectory("mb2dsl-fixtures-");

        // Collect all .java files from fixtures
        List<Path> javaFiles = new ArrayList<>();
        Path javaSrc = FIXTURES.resolve("src/main/java");
        try (Stream<Path> stream = Files.walk(javaSrc)) {
            stream.filter(p -> p.toString().endsWith(".java"))
                    .forEach(javaFiles::add);
        }

        // Compile
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        List<String> args = new ArrayList<>();
        args.add("-d");
        args.add(compiledClassesDir.toString());
        for (Path f : javaFiles) {
            args.add(f.toString());
        }
        int result = compiler.run(null, null, null, args.toArray(new String[0]));
        if (result != 0) {
            throw new RuntimeException("Failed to compile fixtures");
        }

        // Create classloader
        fixturesClassLoader = new URLClassLoader(
                new URL[]{compiledClassesDir.toUri().toURL()},
                IntegrationTest.class.getClassLoader()
        );

        System.out.println("Compiled fixtures to: " + compiledClassesDir);
    }

    @Test
    void testXmlMapperParsing() throws Exception {
        Path resourcesDir = FIXTURES.resolve("src/main/resources");

        // Scan
        List<Path> xmlFiles = MapperScanner.scanXmlMappers(resourcesDir);
        assertThat(xmlFiles).isNotEmpty();
        assertThat(xmlFiles).anyMatch(p -> p.toString().contains("UserMapper.xml"));
        assertThat(xmlFiles).anyMatch(p -> p.toString().contains("ItemMapper.xml"));

        // Build Configuration with fixture classloader
        ConfigurationBuilder cb = new ConfigurationBuilder();
        cb.classLoader(fixturesClassLoader);
        for (Path xml : xmlFiles) {
            cb.addXmlMapper(xml);
        }
        Configuration config = cb.build();

        // Extract statements
        Introspector introspector = new Introspector();
        List<StatementIR> statements = introspector.extractStatements(config);
        assertThat(statements).isNotEmpty();

        // Check UserMapper statements
        StatementIR findById = findStatement(statements, "findById");
        assertThat(findById).isNotNull();
        assertThat(findById.getType()).isEqualTo(StatementIR.StatementType.SELECT);
        assertThat(findById.getSql()).contains("@id");

        StatementIR insertUser = findStatement(statements, "insertUser");
        assertThat(insertUser).isNotNull();
        assertThat(insertUser.isHasReturning()).isTrue();

        // Check ItemMapper (raw extraction, no mode refinement)
        StatementIR findAll = findStatement(statements, "findAll");
        assertThat(findAll).isNotNull();

        StatementIR countItems = findStatement(statements, "countItems");
        assertThat(countItems).isNotNull();
        assertThat(countItems.getReturnDslType()).isNotNull();
    }

    @Test
    void testEntityScanning() throws Exception {
        Path sourceDir = FIXTURES.resolve("src/main/java");
        List<Path> entityFiles = EntityScanner.scanEntityFiles(sourceDir, List.of());
        List<EntityIR> entities = EntityScanner.parseAll(entityFiles);

        assertThat(entities).hasSizeGreaterThanOrEqualTo(2);

        EntityIR user = entities.stream()
                .filter(e -> e.getClassName().equals("User"))
                .findFirst().orElse(null);
        assertThat(user).isNotNull();
        assertThat(user.getFields()).isNotEmpty();
        assertThat(user.getFields()).anyMatch(f -> f.getName().equals("displayName")
                && f.getColumnName().equals("display_name"));
    }

    @Test
    void testModeRefinementFromInterface() throws Exception {
        // This tests the MapperInterfaceParser + ModeResolver chain
        Path sourceDir = FIXTURES.resolve("src/main/java");

        List<Path> mapperFiles = MapperScanner.scanMapperJavaFiles(sourceDir);
        assertThat(mapperFiles).isNotEmpty();

        Map<String, String> returnTypes = MapperInterfaceParser.parseMethodReturnTypes(mapperFiles);
        assertThat(returnTypes).containsKeys("UserMapper.findById", "ItemMapper.findAll");

        // List<Item> → should contain "List"
        assertThat(returnTypes.get("ItemMapper.findAll")).contains("List");
        // void → should be "void"
        assertThat(returnTypes.get("UserMapper.deleteUser")).isEqualTo("void");
    }

    @Test
    void testFullPipeline(@TempDir Path tempDir) throws Exception {
        Path resourcesDir = FIXTURES.resolve("src/main/resources");
        Path sourceDir = FIXTURES.resolve("src/main/java");

        // Scan + parse entities (using JavaParser, no classpath needed)
        List<Path> entityFiles = EntityScanner.scanEntityFiles(sourceDir, List.of());
        List<EntityIR> entities = EntityScanner.parseAll(entityFiles);

        // Scan + parse mappers (need compiled classes)
        List<Path> xmlFiles = MapperScanner.scanXmlMappers(resourcesDir);
        ConfigurationBuilder cb = new ConfigurationBuilder();
        cb.classLoader(fixturesClassLoader);
        for (Path xml : xmlFiles) {
            cb.addXmlMapper(xml);
        }
        Configuration config = cb.build();
        Introspector introspector = new Introspector();
        List<StatementIR> statements = introspector.extractStatements(config);

        // Refine modes from mapper interface return types
        List<Path> mapperFiles = MapperScanner.scanMapperJavaFiles(sourceDir);
        Map<String, String> returnTypes = MapperInterfaceParser.parseMethodReturnTypes(mapperFiles);
        for (StatementIR stmt : statements) {
            String[] idParts = stmt.getId().split("\\.");
            String mapperSimple = idParts.length >= 2 ? idParts[idParts.length - 2] : "Unknown";
            String key = mapperSimple + "." + stmt.getName();
            String returnType = returnTypes.get(key);
            if (returnType != null) {
                ModeResolver.refine(stmt, returnType);
            }
        }

        // Generate DSL
        DslGenerator gen = new DslGenerator(tempDir, "com.example",
                "com.example.mapper", "com.example.entity");
        ReviewReport report = gen.generate(statements, entities);

        // Verify output files
        Path userSql = tempDir.resolve("user.sql");
        assertThat(Files.exists(userSql)).isTrue();
        String userContent = Files.readString(userSql);
        assertThat(userContent).contains("-- package: userMapper");
        assertThat(userContent).contains("-- model: User");
        assertThat(userContent).contains("-- name: findById :one");
        assertThat(userContent).contains("-- name: findByGender :many");  // List<User> → :many
        assertThat(userContent).contains("-- name: insertUser");
        assertThat(userContent).contains("-- name: deleteUser :exec");    // void → :exec

        Path itemSql = tempDir.resolve("item.sql");
        assertThat(Files.exists(itemSql)).isTrue();
        String itemContent = Files.readString(itemSql);
        assertThat(itemContent).contains("-- package: itemMapper");
        assertThat(itemContent).contains("-- model: Item");
        assertThat(itemContent).contains("countItems");
        assertThat(itemContent).contains("findAll :many");   // List<Item> → :many

        // Verify sqlg.yaml
        Path yamlFile = tempDir.resolve("sqlg.yaml");
        assertThat(Files.exists(yamlFile)).isTrue();
        String yaml = Files.readString(yamlFile);
        assertThat(yaml).contains("user.sql");
        assertThat(yaml).contains("item.sql");

        System.out.println("=== Generated user.sql ===");
        System.out.println(userContent);
        System.out.println("=== Generated sqlg.yaml ===");
        System.out.println(yaml);
    }

    private StatementIR findStatement(List<StatementIR> statements, String name) {
        return statements.stream()
                .filter(s -> s.getName().equals(name))
                .findFirst().orElse(null);
    }
}
