package com.sqlgen.km.mb2dsl.model;

/** Intermediate representation of a Mapper method (statement). */
public class ParamIR {
    private String name;            // @param 名称 (id / filter.gender)
    private String javaType;        // Long / String / Filter
    private String dslType;         // int64 / string / Filter
    private boolean isObjectParam;  // 是否对象参数 (需展开为 filter.field)

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getJavaType() { return javaType; }
    public void setJavaType(String javaType) { this.javaType = javaType; }

    public String getDslType() { return dslType; }
    public void setDslType(String dslType) { this.dslType = dslType; }

    public boolean isObjectParam() { return isObjectParam; }
    public void setObjectParam(boolean objectParam) { isObjectParam = objectParam; }
}
