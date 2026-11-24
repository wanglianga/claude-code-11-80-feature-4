package com.flood.statemigrate.web;

import com.flood.statemigrate.domain.RecoveryDrill;
import com.flood.statemigrate.service.DrillService;
import com.flood.statemigrate.web.dto.DrillReq;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "可恢复性演练")
@RestController
@RequestMapping("/api/streams/{streamId}/recovery-drills")
public class DrillController {

    private final DrillService drillService;

    public DrillController(DrillService drillService) {
        this.drillService = drillService;
    }

    @Operation(summary = "在隔离环境从指定检查点恢复并核对输出摘要（摘要不一致则冻结该快照及派生压缩包）")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RecoveryDrill drill(@PathVariable Long streamId, @Valid @RequestBody DrillReq req) {
        return drillService.drill(streamId, req.checkpointId(), req.isolatedEnv());
    }

    @Operation(summary = "演练记录列表")
    @GetMapping
    public List<RecoveryDrill> list(@PathVariable Long streamId) {
        return drillService.list(streamId);
    }

    @Operation(summary = "演练记录详情")
    @GetMapping("/{drillId}")
    public RecoveryDrill get(@PathVariable Long streamId, @PathVariable Long drillId) {
        return drillService.get(drillId);
    }
}
