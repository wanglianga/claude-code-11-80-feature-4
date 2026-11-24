package com.flood.statemigrate.domain;

/** ARCHIVED=可恢复归档；FROZEN=因演练摘要不一致冻结，禁止继续压缩/派生 */
public enum CompressionStatus {
    ARCHIVED,
    FROZEN
}
