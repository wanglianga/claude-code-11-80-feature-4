package com.flood.statemigrate.web;

import com.flood.statemigrate.domain.IncidentRestore;
import com.flood.statemigrate.service.IncidentRestoreService;
import com.flood.statemigrate.web.dto.IncidentReq;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "事故复原工作台")
@RestController
@RequestMapping("/api/streams/{streamId}/incident-restores")
public class IncidentRestoreController {

    private final IncidentRestoreService incidentService;

    public IncidentRestoreController(IncidentRestoreService incidentService) {
        this.incidentService = incidentService;
    }

    @Operation(summary = "事故调查最小必要分区恢复：组合相邻快照，跨模式显式标注并给出不可还原空档")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IncidentRestore restore(@PathVariable Long streamId, @Valid @RequestBody IncidentReq req) {
        return incidentService.restore(streamId, req.windowStart(), req.windowEnd(), req.partitions());
    }

    @Operation(summary = "事故复原记录列表")
    @GetMapping
    public List<IncidentRestore> list(@PathVariable Long streamId) {
        return incidentService.list(streamId);
    }

    @Operation(summary = "事故复原记录详情")
    @GetMapping("/{id}")
    public IncidentRestore get(@PathVariable Long streamId, @PathVariable Long id) {
        return incidentService.get(streamId, id);
    }

    @Operation(summary = "执行最小必要分区恢复：仅取回可还原分段，其余空档显式跳过")
    @PostMapping("/{id}/restore")
    public java.util.Map<String, Object> restore(@PathVariable Long streamId, @PathVariable Long id) {
        return incidentService.executeRestore(streamId, id);
    }
}
