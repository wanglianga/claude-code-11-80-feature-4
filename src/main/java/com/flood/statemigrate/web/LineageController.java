package com.flood.statemigrate.web;

import com.flood.statemigrate.domain.SnapshotLineage;
import com.flood.statemigrate.service.LineageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "快照谱系索引")
@RestController
@RequestMapping("/api/streams/{streamId}/lineage")
public class LineageController {

    private final LineageService lineageService;

    public LineageController(LineageService lineageService) {
        this.lineageService = lineageService;
    }

    @Operation(summary = "谱系检索：按分区/状态模式/时间窗过滤，附恢复分区映射")
    @GetMapping
    public List<Map<String, Object>> search(@PathVariable Long streamId,
                                            @RequestParam(required = false) Integer partitionNo,
                                            @RequestParam(required = false)
                                            com.flood.statemigrate.domain.StateSchema stateSchema,
                                            @RequestParam(required = false) java.time.Instant from,
                                            @RequestParam(required = false) java.time.Instant to) {
        return lineageService.search(streamId, partitionNo, stateSchema, from, to);
    }

    @Operation(summary = "历史检查点补建谱系索引")
    @PostMapping("/rebuild")
    public Map<String, Object> rebuild(@PathVariable Long streamId) {
        int built = lineageService.rebuild(streamId);
        return Map.of("streamId", streamId, "lineageIndexed", built);
    }

    @Operation(summary = "单检查点谱系详情（含绕过规则与演练记录）")
    @GetMapping("/{checkpointId}")
    public Map<String, Object> detail(@PathVariable Long streamId, @PathVariable Long checkpointId) {
        return lineageService.detail(streamId, checkpointId);
    }
}
