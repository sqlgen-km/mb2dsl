package com.sqlgen.km.mb2dsl.model;

/** Intermediate representation of an Entity field. */
public class FieldIR {
    private String name;            // displayName
    private String javaType;        // String / Long / java.math.BigDecimal
    private String columnName;      // display_name (from @Column or inferred)
    private String dslType;         // string / int64 / float64 / time.Time
    private boolean nullable;       // from @Column(nullable=true) or type wrapper
    private boolean primaryKey;     // @Id

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getJavaType() { return javaType; }
    public void setJavaType(String javaType) { this.javaType = javaType; }

    public String getColumnName() { return columnName; }
    public void setColumnName(String columnName) { this.columnName = columnName; }

    public String getDslType() { return dslType; }
    public void setDslType(String dslType) { this.dslType = dslType; }

    public boolean isNullable() { return nullable; }
    public void setNullable(boolean nullable) { this.nullable = nullable; }

    public boolean isPrimaryKey() { return primaryKey; }
    public void setPrimaryKey(boolean primaryKey) { this.primaryKey = primaryKey; }
}
