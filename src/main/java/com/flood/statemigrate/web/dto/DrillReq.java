package com.flood.statemigrate.web.dto;

import jakarta.validation.constraints.NotNull;

/** 可恢复性演练请求：在隔离环境从指定检查点恢复并核对输出摘要 */
public record DrillReq(
        @NotNull Long checkpointId,
        String isolatedEnv
) {
}
