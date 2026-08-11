package com.sqlgen.km.mb2dsl.model;

import java.util.ArrayList;
import java.util.List;

/** Intermediate representation of a single Mapper method / XML statement. */
public class StatementIR {
    private String id;                      // com.example.mapper.UserMapper.findById
    private String name;                    // findById
    private String sql;                     // 转换后的 SQL (含 @param 占位符)
    private String rawSql;                  // 原始 SQL (含 #{} 占位符)
    private StatementType type;             // SELECT / INSERT / UPDATE / DELETE
    private String mode;                    // :one / :many / :exec / :execrows
    private String returnEntity;            // User (null if scalar)
    private String returnDslType;           // 返回的 DSL 类型: User / int64 / string
    private List<ParamIR> params = new ArrayList<>();
    private boolean hasReturning;           // INSERT RETURNING
    private List<String> keyColumns;        // RETURNING 列
    private List<String> reviewTags = new ArrayList<>();  // 人工审核标记
    private List<String> notes = new ArrayList<>();        // 特殊处理备注 (selectKey→RETURNING等)
    private String resultMapId;             // XML resultMap 引用
    private String sourceFile;              // 原始来源文件 (Mapper.java 或 Mapper.xml)
    private String mapperInterfaceName;     // 所属 Mapper 接口全名

    public enum StatementType { SELECT, INSERT, UPDATE, DELETE }

    // --- getters/setters ---

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getSql() { return sql; }
    public void setSql(String sql) { this.sql = sql; }

    public String getRawSql() { return rawSql; }
    public void setRawSql(String rawSql) { this.rawSql = rawSql; }

    public StatementType getType() { return type; }
    public void setType(StatementType type) { this.type = type; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public String getReturnEntity() { return returnEntity; }
    public void setReturnEntity(String returnEntity) { this.returnEntity = returnEntity; }

    public String getReturnDslType() { return returnDslType; }
    public void setReturnDslType(String returnDslType) { this.returnDslType = returnDslType; }

    public List<ParamIR> getParams() { return params; }
    public void addParam(ParamIR param) { this.params.add(param); }

    public boolean isHasReturning() { return hasReturning; }
    public void setHasReturning(boolean hasReturning) { this.hasReturning = hasReturning; }

    public List<String> getKeyColumns() { return keyColumns; }
    public void setKeyColumns(List<String> keyColumns) { this.keyColumns = keyColumns; }

    public List<String> getNotes() { return notes; }
    public void addNote(String note) { this.notes.add(note); }

    public List<String> getReviewTags() { return reviewTags; }
    public void addReviewTag(String tag) { this.reviewTags.add(tag); }

    public String getResultMapId() { return resultMapId; }
    public void setResultMapId(String resultMapId) { this.resultMapId = resultMapId; }

    public String getSourceFile() { return sourceFile; }
    public void setSourceFile(String sourceFile) { this.sourceFile = sourceFile; }

    public String getMapperInterfaceName() { return mapperInterfaceName; }
    public void setMapperInterfaceName(String mapperInterfaceName) { this.mapperInterfaceName = mapperInterfaceName; }
}
