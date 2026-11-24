package com.flood.statemigrate;

import com.flood.statemigrate.domain.*;
import com.flood.statemigrate.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/** 事故复原工作台：跨模式组合、不可还原空档、最小必要分区 */
class IncidentRestoreTest extends BaseIntegrationTest {

    @Autowired
    private IncidentRestoreService incidentService;
    @Autowired
    private LineageService lineageService;

    @Test
    void crossSchemaWindowCombinesAdjacentSnapshotsAndReportsGap() {
        StreamDefinition stream = scenario.stream("T-INCIDENT", 2, 48);
        Instant now = Instant.now();
        Instant winStart = now.minusSeconds(6 * 3600);
        Instant winEnd = now.minusSeconds(600);

        // 分区 0：旧模式快照（早段） + 新模式快照（晚段），中间留 30 分钟空档
        scenario.checkpointDetailed(stream.getId(), 0, 5, "v1.0.0", StateSchema.GRID_WINDOW_V1,
                now.minusSeconds(6 * 3600), now.minusSeconds(4 * 3600), "MEDIUM");
        scenario.checkpointDetailed(stream.getId(), 0, 12, "v2.0.0", StateSchema.PUMP_REACH_ROAD_V2,
                now.minusSeconds(1800), now.minusSeconds(600), "HIGH");
        // 分区 1：完全无快照
        lineageService.rebuild(stream.getId());

        IncidentRestore record = incidentService.restore(stream.getId(), winStart, winEnd, null);
        Map<String, Object> plan = readPlan(record);

        assertThat(plan.get("schemaTransition")).isEqualTo(true);
        assertThat(plan.get("status")).isEqualTo("PARTIAL");
        assertThat((List<?>) plan.get("segments")).hasSize(2);
        // 最小必要分区只包含有可恢复快照的分区 0
        assertThat((List<Integer>) plan.get("minimalPartitions")).containsExactly(0);
        List<Map<String, Object>> gaps = (List<Map<String, Object>>) plan.get("gaps");
        // 至少包含：相邻快照空档、分区1全窗空档、尾部空档
        assertThat(gaps).isNotEmpty();
        assertThat(gaps.stream().anyMatch(g -> ((Number) g.get("partitionNo")).intValue() == 0)).isTrue();
        assertThat(gaps.stream().anyMatch(g -> ((Number) g.get("partitionNo")).intValue() == 1)).isTrue();
        List<Map<String, Object>> order = (List<Map<String, Object>>) plan.get("restoreOrder");
        assertThat(order).hasSize(2);
        assertThat(order.get(0).get("stateSchema").toString()).isEqualTo("GRID_WINDOW_V1");
        assertThat(order.get(1).get("stateSchema").toString()).isEqualTo("PUMP_REACH_ROAD_V2");
        // 跨模式须显式说明组合语义，且空档不得外推填充
        assertThat(plan.get("notes").toString()).contains("跨越状态模式").contains("不得以相邻状态外推填充");
        assertThat(gaps).allMatch(g -> g.containsKey("gapStart") && g.containsKey("gapEnd"));
    }

    @Test
    void executeMinimalRestoreReturnsRecoveredStateAndSkipsGaps() {
        StreamDefinition stream = scenario.stream("T-INCIDENT-EXEC", 1, 48);
        Instant now = Instant.now();
        scenario.checkpointDetailed(stream.getId(), 0, 5, "v1.0.0", StateSchema.GRID_WINDOW_V1,
                now.minusSeconds(3600), now.minusSeconds(1800), "MEDIUM");
        lineageService.rebuild(stream.getId());

        IncidentRestore record = incidentService.restore(stream.getId(),
                now.minusSeconds(4000), now.minusSeconds(600), null);
        Map<String, Object> result = incidentService.executeRestore(stream.getId(), record.getId());

        assertThat(result.get("status")).isEqualTo("PARTIALLY_RESTORED");
        assertThat((List<?>) result.get("restoredSegments")).hasSize(1);
        Map<String, Object> restored = (Map<String, Object>) ((List<?>) result.get("restoredSegments")).get(0);
        assertThat(restored.get("stateSchema").toString()).isEqualTo("GRID_WINDOW_V1");
        assertThat(restored.get("recoveredState")).isNotNull();
        assertThat((List<?>) result.get("skippedSegments")).isNotEmpty();
    }

    @Test
    void noCoverageWhenNoSnapshots() {
        StreamDefinition stream = scenario.stream("T-INCIDENT-NONE", 1, 48);
        Instant now = Instant.now();
        IncidentRestore record = incidentService.restore(stream.getId(),
                now.minusSeconds(4000), now.minusSeconds(600), null);
        assertThat(record.getStatus()).isEqualTo("NO_COVERAGE");
    }

    @Test
    void frozenSnapshotBecomesExplicitGap() {
        StreamDefinition stream = scenario.stream("T-INCIDENT-FROZEN", 1, 48);
        Instant now = Instant.now();
        CheckpointManifest cp = scenario.checkpointDetailed(stream.getId(), 0, 5, "v1.0.0",
                StateSchema.GRID_WINDOW_V1, now.minusSeconds(3600), now.minusSeconds(900), "MEDIUM");
        lineageService.rebuild(stream.getId());
        SnapshotLineage l = lineageRepo.findByCheckpointId(cp.getId()).orElseThrow();
        l.setFrozen(true);
        l.setFrozenReason("演练摘要不一致");
        lineageRepo.save(l);

        IncidentRestore record = incidentService.restore(stream.getId(),
                now.minusSeconds(4000), now.minusSeconds(600), null);
        Map<String, Object> plan = readPlan(record);
        assertThat(record.getStatus()).isEqualTo("NO_COVERAGE");
        assertThat((List<?>) plan.get("gaps")).isNotEmpty();
    }

    private Map<String, Object> readPlan(IncidentRestore record) {
        try {
            return tools.jackson.databind.json.JsonMapper.builder().build()
                    .readValue(record.getResultJson(), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
