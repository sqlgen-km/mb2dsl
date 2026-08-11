package com.sqlgen.km.mb2dsl.model;

import java.util.ArrayList;
import java.util.List;

/** Intermediate representation of a Java Entity class. */
public class EntityIR {
    private String className;       // User
    private String packageName;     // com.example.entity
    private String tableName;       // users (from @Table or inferred)
    private List<FieldIR> fields = new ArrayList<>();
    private String sourcePath;      // original .java file path

    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }

    public String getPackageName() { return packageName; }
    public void setPackageName(String packageName) { this.packageName = packageName; }

    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }

    public List<FieldIR> getFields() { return fields; }
    public void addField(FieldIR field) { this.fields.add(field); }

    public String getSourcePath() { return sourcePath; }
    public void setSourcePath(String sourcePath) { this.sourcePath = sourcePath; }
}
