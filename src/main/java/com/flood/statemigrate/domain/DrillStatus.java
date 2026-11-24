package com.flood.statemigrate.domain;

/** 演练结论：通过 / 摘要不一致 / 无法演练（缺谱系、缺规则包、无事件等） */
public enum DrillStatus {
    PASSED,
    FAILED,
    BLOCKED
}
