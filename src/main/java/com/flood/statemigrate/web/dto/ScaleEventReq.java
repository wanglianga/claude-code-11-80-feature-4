package com.flood.statemigrate.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/** 分区扩缩容事件登记 */
public record ScaleEventReq(
        @Min(1) int fromPartitions,
        @Min(1) int toPartitions,
        @NotBlank String mappingJson,
        @NotNull Instant occurredAt,
        String reason
) {
}
