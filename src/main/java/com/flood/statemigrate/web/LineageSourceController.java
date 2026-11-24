package com.flood.statemigrate.web;

import com.flood.statemigrate.domain.BypassRule;
import com.flood.statemigrate.domain.BypassRuleStatus;
import com.flood.statemigrate.domain.PartitionScaleEvent;
import com.flood.statemigrate.service.LineageService;
import com.flood.statemigrate.web.dto.BypassRuleReq;
import com.flood.statemigrate.web.dto.ScaleEventReq;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "谱系来源登记")
@RestController
@RequestMapping("/api/streams/{streamId}")
public class LineageSourceController {

    private final LineageService lineageService;

    public LineageSourceController(LineageService lineageService) {
        this.lineageService = lineageService;
    }

    @Operation(summary = "登记分区扩缩容事件（检查点之间的拓扑变化，含映射）")
    @PostMapping("/scale-events")
    @ResponseStatus(HttpStatus.CREATED)
    public PartitionScaleEvent registerScaleEvent(@PathVariable Long streamId,
                                                  @Valid @RequestBody ScaleEventReq req) {
        PartitionScaleEvent event = new PartitionScaleEvent();
        event.setFromPartitions(req.fromPartitions());
        event.setToPartitions(req.toPartitions());
        event.setMappingJson(req.mappingJson());
        event.setOccurredAt(req.occurredAt());
        event.setReason(req.reason());
        return lineageService.registerScaleEvent(streamId, event);
    }

    @Operation(summary = "分区扩缩容事件列表")
    @GetMapping("/scale-events")
    public List<PartitionScaleEvent> scaleEvents(@PathVariable Long streamId) {
        return lineageService.scaleEvents(streamId);
    }

    @Operation(summary = "登记临时绕过规则（谱系索引记录检查点时刻生效的规则）")
    @PostMapping("/bypass-rules")
    @ResponseStatus(HttpStatus.CREATED)
    public BypassRule registerBypassRule(@PathVariable Long streamId,
                                         @Valid @RequestBody BypassRuleReq req) {
        BypassRule rule = new BypassRule();
        rule.setRuleKey(req.ruleKey());
        rule.setPartitionNo(req.partitionNo());
        rule.setEffectiveFrom(req.effectiveFrom());
        rule.setEffectiveTo(req.effectiveTo());
        rule.setReason(req.reason());
        rule.setStatus(BypassRuleStatus.ACTIVE);
        return lineageService.registerBypassRule(streamId, rule);
    }

    @Operation(summary = "临时绕过规则列表")
    @GetMapping("/bypass-rules")
    public List<BypassRule> bypassRules(@PathVariable Long streamId) {
        return lineageService.bypassRules(streamId);
    }
}
