package com.flood.statemigrate.web.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** 压缩归档请求：仅对满足可恢复性证明与完整谱系的快照生效 */
public record CompressionReq(
        @NotEmpty List<Long> checkpointIds,
        String derivedFromPackageRef,
        String note
) {
}
