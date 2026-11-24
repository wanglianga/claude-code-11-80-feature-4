package com.flood.statemigrate.web;

import com.flood.statemigrate.domain.CompressionPackage;
import com.flood.statemigrate.service.CompressionEligibilityService;
import com.flood.statemigrate.service.CompressionService;
import com.flood.statemigrate.web.dto.CompressionReq;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "快照压缩归档")
@RestController
@RequestMapping("/api/streams/{streamId}")
public class CompressionController {

    private final CompressionEligibilityService eligibilityService;
    private final CompressionService compressionService;

    public CompressionController(CompressionEligibilityService eligibilityService,
                                 CompressionService compressionService) {
        this.eligibilityService = eligibilityService;
        this.compressionService = compressionService;
    }

    @Operation(summary = "单快照压缩资格判定（演练通过且谱系完整才允许）")
    @GetMapping("/checkpoints/{checkpointId}/compression-eligibility")
    public Map<String, Object> eligibility(@PathVariable Long streamId, @PathVariable Long checkpointId) {
        return eligibilityService.evaluate(streamId, checkpointId);
    }

    @Operation(summary = "流内全部快照压缩资格清单")
    @GetMapping("/compression-eligibility")
    public List<Map<String, Object>> eligibilityList(@PathVariable Long streamId) {
        return eligibilityService.evaluateStream(streamId);
    }

    @Operation(summary = "压缩归档（仅收录满足资格的检查点，可清理原文；已冻结包禁止派生）")
    @PostMapping("/compression-packages")
    @ResponseStatus(HttpStatus.CREATED)
    public CompressionPackage compress(@PathVariable Long streamId,
                                       @RequestParam(defaultValue = "true") boolean purgeOriginal,
                                       @Valid @RequestBody CompressionReq req) {
        return compressionService.compress(streamId, req.checkpointIds(),
                req.derivedFromPackageRef(), purgeOriginal, req.note());
    }

    @Operation(summary = "压缩包列表")
    @GetMapping("/compression-packages")
    public List<CompressionPackage> list(@PathVariable Long streamId) {
        return compressionService.list(streamId);
    }

    @Operation(summary = "压缩包详情")
    @GetMapping("/compression-packages/{packageRef}")
    public CompressionPackage get(@PathVariable Long streamId, @PathVariable String packageRef) {
        return compressionService.get(streamId, packageRef);
    }
}
