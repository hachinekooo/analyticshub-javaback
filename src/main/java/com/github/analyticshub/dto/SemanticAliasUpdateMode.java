package com.github.analyticshub.dto;

/**
 * 业务事件定义更新时，对原始事件名映射的写入方式。
 */
public enum SemanticAliasUpdateMode {
    /** 用提交的列表替换全部映射；空列表表示清空。 */
    REPLACE,
    /** 只更新定义，不修改已有映射。 */
    PRESERVE
}
