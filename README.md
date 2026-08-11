# mb2dsl

MyBatis → [sqlgen](https://github.com/sqlgen-km/sqlgen) DSL 逆向工程工具。

扫描已有 Java 项目的 MyBatis XML Mapper + Mapper 接口 + Entity 类，生成 sqlgen 可用的 `.sql` DSL 文件和 `sqlg.yaml` 配置。

## 使用方式

### 命令行（独立 jar）

```bash
java -jar mb2dsl.jar \
  -s src/main/java \
  -r src/main/resources \
  -o ./sqlgen-dsl \
  -p com.example
```

### Maven 插件

```xml
<plugin>
    <groupId>com.sqlgen.km</groupId>
    <artifactId>mb2dsl-maven-plugin</artifactId>
    <version>1.0.0</version>
    <configuration>
        <basePackage>com.example</basePackage>
        <!-- 可选，以下为默认值 -->
        <!-- <srcDir>${project.basedir}/src/main/java</srcDir> -->
        <!-- <resourcesDir>${project.basedir}/src/main/resources</resourcesDir> -->
        <!-- <outputDir>${project.basedir}/sqlgen-dsl</outputDir> -->
        <!-- <engines>pg</engines> -->
    </configuration>
</plugin>
```

```bash
mvn com.sqlgen.km:mb2dsl-maven-plugin:1.0.0:generate-dsl
```

或通过命令行覆盖参数：

```bash
mvn com.sqlgen.km:mb2dsl-maven-plugin:1.0.0:generate-dsl \
  -Dmb2dsl.basePackage=com.example.hospital \
  -Dmb2dsl.outputDir=./output
```

## 命令参数

| 参数 | 必需 | 说明 |
|------|------|------|
| `-s, --src` | 是 | Java 源码根目录 |
| `-r, --resources` | 否 | Mapper XML 目录（默认同 `-s`） |
| `-o, --output` | 是 | 输出目录 |
| `-p, --base-package` | 否 | Java 基础包名（默认 `com.example`） |
| `-c, --classpath` | 否 | 编译后的 classes 目录（可选，提供时使用 MyBatis Configuration 解析，更准确） |
| `--mapper-package` | 否 | Mapper 包名（默认 `{base}.mapper`） |
| `--model-package` | 否 | Entity 包名（默认 `{base}.entity`） |

## Mapper → DSL 命名规则

```
AimsConfigMapper  →  aims_config.sql     -- package: aimsConfigMapper
UserMapper        →  user.sql            -- package: userMapper
OrderDetailMapper →  order_detail.sql    -- package: orderDetailMapper
RoleMapper        →  role.sql            -- package: roleMapper
```

## 工作原理

1. **扫描** 源码目录下的 `*Mapper.java` 和 `*Mapper.xml`
2. **解析** Mapper 接口方法签名 → 返回类型（`:one` / `:many` / `:exec` / `:execrows`）
3. **提取** XML 中的 SQL 语句 + 参数（`#{}→@param`）
4. **展开** `<include>` 引用、检测 `<if>/<foreach>/<where>` 动态 SQL
5. **生成** `.sql` DSL 文件 + `sqlg.yaml` + `_manual_review.md`

## 动态 SQL 处理

动态 SQL **自动改写**为 sqlgen DSL 兼容格式：

| 原始 | 转换 |
|------|------|
| `<if test="name != null"> AND name = #{name}</if>` | `AND (name = @name OR @name IS NULL)` |
| `<foreach collection="ids" item="id" open="(" close=")">#{id}</foreach>` | `= ANY(@ids)` |
| `<choose>/<when>/<otherwise>` | 取第一个 `<when>` 分支，标注 `/* <choose> */` |
| `<where>` / `<set>` / `<trim>` | 去掉外层标签，保留内部 SQL |
| `<include refid="BaseColumns"/>` | 直接展开，自动补空格 |
| `<bind>` | 去掉标签，保留内容 |
| `<foreach>` 批量 INSERT | **不支持**，标注 `/* manual rewrite needed */` |
| `${column}` 动态列名/表名 | **不支持**，标注 REVIEW |
| `${@Enum@VALUE}` | **不支持**，标注 REVIEW |

## 限制与已知问题

### 解析范围

| 支持 | 不支持 |
|------|--------|
| MyBatis XML Mapper (`<select>/<insert>/<update>/<delete>`) | — |
| 纯注解 Mapper (`@Select/@Insert/@Update/@Delete`) | `@SelectProvider/@InsertProvider` |
| `#{}` 占位符 → `@param` | `${}` 动态占位符 |
| `<include>` 展开（含嵌套引用，自动补空格） | — |
| `resultType` 基本类型/实体类名 | `resultType="map"` → 自动跳过 model |
| `useGeneratedKeys="true"` → `INSERT RETURNING` | — |
| `<selectKey>` → `INSERT RETURNING` | — |
| `<resultMap>` → `-- model:` 块（从 XML 提取字段） | — |
| `parameterType` 识别 → 记录在 notes | — |
| 单条 SQL 语句 | 存储过程 (`statementType="CALLABLE"`) |

### model 定义

- **有 Entity 源码时**：从 Java 字段提取字段列表
- **有 XML `<resultMap>` 时**：从 resultMap 提取列名+字段映射，生成 `-- model:` 块
- 两者同时存在时自动合并去重

### 类型与模式

| 场景 | 效果 |
|------|------|
| `List<Entity>` 返回 | 自动检测 `:many` |
| `void` 返回 | 自动检测 `:exec` |
| `int/long` 返回 (非 SELECT) | 自动检测 `:execrows` |
| `int/long` 返回 (SELECT) | 自动检测 `:one` |
| `INSERT + useGeneratedKeys/selectKey` | 自动检测 `INSERT RETURNING` |
| `Collection<?>` 参数 | 不展开泛型，标记为 `@collection` |
| 方法参数无 `@Param` 注解 | 取 Java 参数名（可能因编译优化不准确） |
| SELECT * | 不展开列名（需有 Entity 类或 resultMap 才知道列） |

### 动态 SQL 改写限制

| 场景 | 效果 |
|------|------|
| `<if test="name != null">` | ✅ 改写为 `OR @name IS NULL` |
| `<if test="name != null and name != ''">` | ⚠️ 只匹配 `!= null`，空字符串检查丢失 |
| `<if test="name != null && status == 1">` | ⚠️ 复杂表达式降级为保留 body |
| `<if test="list.size() > 0">` | ⚠️ 方法调用不识别，保留 body |

## 不提供 `-c` 时

- 从 XML + Mapper 注解提取 SQL，**不依赖 Entity 类**，不需要编译目标项目
- `<resultMap>` → `-- model:` 块自动生成
- 动态 SQL 自动改写（`<if>` → `OR @param IS NULL` 等）
- SQL 格式自动清理（空格/缩进规范化）
- 纯注解 Mapper 通过 JavaParser 源码解析

## 提供 `-c target/classes` 时

- 通过 MyBatis Configuration API 解析 XML + 注解 Mapper
- `<resultMap>` 字段映射更准确
- 需要 Entity 类在 classpath 上（完整编译目标项目）
- 目标项目与 mb2dsl 需使用相同 JDK 版本编译

## 输出文件

### .sql DSL 文件

```sql
-- package: roleMapper

-- name: selectById :one
-- model: Role
SELECT id, code, name FROM sys_role WHERE id = @id

-- name: selectByIds :many
-- model: Role
SELECT id, code, name FROM sys_role WHERE id IN (@ids)

-- name: insert :one
-- model int64
INSERT INTO sys_role (code, name) VALUES (@code, @name) RETURNING id

-- name: deleteById :exec
DELETE FROM sys_role WHERE id = @id

-- name: countList :one
-- model int64
SELECT COUNT(*) FROM sys_role WHERE name LIKE @keyword
```

### sqlg.yaml

```yaml
# sqlg.yaml — generated by mb2dsl
engines: [pg]

java:
  packages:
    - modelPackage: "com.example.entity"
      mapperPackage: "com.example.mapper"
      out: "src/main/java"
      files:
        - "user.sql"
        - "role.sql"
```

### _manual_review.md

列出需要人工检查的项（动态 SQL 降级、不支持的语法等）。

## 构建

```bash
git clone https://github.com/sqlgen-km/mb2dsl.git
cd mb2dsl
mvn package -DskipTests
# 产出: target/mb2dsl-1.0.0-SNAPSHOT.jar
```

## 发布

GitHub Releases 分发 uber-jar，直接 `java -jar` 运行，零外部依赖。

## License

Apache 2.0
