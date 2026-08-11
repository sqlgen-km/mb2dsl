# Changelog

## v1.1.0 (2026-08-11)

### 新增

- **Maven 插件**：`mb2dsl-maven-plugin`，通过 `generate-dsl` goal 集成到 Maven 构建
- 项目重构为多模块：`mb2dsl-parent` + `mb2dsl-core` + `mb2dsl-maven-plugin`

### 改进

- `<include>` 嵌套引用支持（递归解析 `sqlFragments`）
- 动态 SQL `<if>` 改写降级说明（空字符串检查/复杂表达式）
- README 补充 Maven 使用方式和不支持项

## v1.0.0 (2026-08-11)

### 核心功能

- **XML Mapper 解析**：直接从 XML 提取 SQL，不需要 Entity 类（`XmlDirectParser`）
- **纯注解 Mapper 解析**：`@Select/@Insert/@Update/@Delete` 通过 JavaParser 源码解析
- **动态 SQL 自动改写**：
  - `<if test="name != null">` → `AND (name = @name OR @name IS NULL)`
  - `<foreach>` IN 子句 → `= ANY(@ids)`
  - `<choose>/<when>` → 取第一个分支
  - `<where>/<set>/<trim>/<bind>` → 剥离标签，保留内容
- **`<selectKey>` 处理**：MySQL `LAST_INSERT_ID()` / Oracle `seq.NEXTVAL` → `INSERT ... RETURNING id`
- **`<include>` 嵌套展开**：支持多级嵌套引用（递归解析 `sqlFragments`）
- **`<resultMap>` → model 块**：从 XML resultMap 提取列名+字段映射，自动生成 `-- model:` 块
- **Entity 源码解析**：从 Java 字段提取类型+列映射
- **模式自动检测**：`List<Entity>`→`:many`、`void`→`:exec`、`int`→`:execrows`

### SQL 格式

- `<include>` 展开后自动补空格
- SQL 空白规范化（多余空格/换行清理）
- `#{}` → `@param` 占位符转换
- `resultType="map"` → 自动跳过 model 行

### 输出

- **`.sql` DSL 文件**：`UserMapper` → `user.sql`，`package=userMapper`
- **`sqlg.yaml`**：自动生成 sqlgen 配置文件
- **`_parsing_report.md`**：解析统计汇报（概览/类型/模式/特性/Mapper 明细）
- **`_parsing_log.md`**：逐语句详细日志（名称/类型/模式/特殊处理）
- **`_manual_review.md`**：需人工处理的项

### 命令参数

```
-s, --src           Java 源码根目录（必需）
-r, --resources     Mapper XML 目录
-o, --output        输出目录（必需）
-p, --base-package  Java 基础包名
-c, --classpath     编译后的 classes 目录（可选）
--mapper-package    Mapper 包名
--model-package     Entity 包名
--engines           数据库引擎（默认 pg）
```

### 边界

- 支持：XML Mapper + 纯注解 Mapper
- 不支持：`@SelectProvider/@InsertProvider`、存储过程、`${}` 动态占位符
- 动态 SQL `<if>` 仅识别 `!= null` 表达式
- `resultType="map"` 不生成类型安全的 model
- `Collection<?>` 参数泛型不展开
