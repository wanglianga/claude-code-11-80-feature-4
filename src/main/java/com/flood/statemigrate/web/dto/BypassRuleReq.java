package com.flood.statemigrate.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/** 临时绕过规则登记 */
public record BypassRuleReq(
        @NotBlank String ruleKey,
        Integer partitionNo,
        @NotNull Instant effectiveFrom,
        Instant effectiveTo,
        String reason
) {
}
