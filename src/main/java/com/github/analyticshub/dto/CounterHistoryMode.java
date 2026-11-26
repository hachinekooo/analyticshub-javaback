package com.github.analyticshub.dto;

/**
 * 决定重算事件计数时从哪条时间边界开始纳入事件。
 */
public enum CounterHistoryMode {
    /** 纳入项目已保存且符合规则的全部历史事件。 */
    INCLUDE_EXISTING,

    /** 只纳入启用该模式后入库的事件。 */
    START_FROM_NOW
}
