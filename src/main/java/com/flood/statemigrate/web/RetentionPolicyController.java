package com.flood.statemigrate.web;

import com.flood.statemigrate.domain.RetentionPolicy;
import com.flood.statemigrate.error.ApiException;
import com.flood.statemigrate.service.RetentionPolicyService;
import com.flood.statemigrate.web.dto.RetentionPolicyReq;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "检查点保留策略")
@RestController
@RequestMapping("/api/streams/{streamId}/retention-policies")
public class RetentionPolicyController {

    private final RetentionPolicyService policyService;

    public RetentionPolicyController(RetentionPolicyService policyService) {
        this.policyService = policyService;
    }

    @Operation(summary = "登记/更新预警等级保留策略（基线天数 + 规则变更/高影响输出延长）")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RetentionPolicy upsert(@PathVariable Long streamId, @Valid @RequestBody RetentionPolicyReq req) {
        RetentionPolicy policy = new RetentionPolicy();
        policy.setWarningLevel(req.warningLevel());
        policy.setRetentionDays(req.retentionDays());
        policy.setRequiredDrills(req.requiredDrills());
        policy.setRuleChangeExtraDays(req.ruleChangeExtraDays());
        policy.setHighImpactExtraDays(req.highImpactExtraDays());
        return policyService.upsert(streamId, policy);
    }

    @Operation(summary = "保留策略列表")
    @GetMapping
    public List<RetentionPolicy> list(@PathVariable Long streamId) {
        return policyService.list(streamId);
    }

    @Operation(summary = "逐快照保留期评估（预警等级 + 规则变更/高影响输出延长）")
    @GetMapping("/evaluate")
    public Map<String, Object> evaluate(@PathVariable Long streamId) {
        return policyService.evaluate(streamId);
    }
}
