package com.flood.statemigrate;

import com.flood.statemigrate.domain.*;
import com.flood.statemigrate.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/** 谱系索引：扩缩容映射与临时绕过规则关联 */
class SnapshotLineageTest extends BaseIntegrationTest {

    @Autowired
    private LineageService lineageService;

    @Test
    void lineageIndexOfPartitionAndBypassRules() {
        StreamDefinition stream = scenario.stream("T-LINEAGE", 2, 48);
        Instant now = Instant.now();
        BypassRule rule = new BypassRule();
        rule.setRuleKey("CREDIBILITY_FLOOR");
        rule.setPartitionNo(0);
        rule.setEffectiveFrom(now.minusSeconds(3 * 3600));
        rule.setStatus(BypassRuleStatus.ACTIVE);
        rule.setReason("暴雨期传感器校准");
        lineageService.registerBypassRule(stream.getId(), rule);

        CheckpointManifest cp = scenario.checkpointDetailed(stream.getId(), 0, 5, "v1.0.0",
                StateSchema.GRID_WINDOW_V1, now.minusSeconds(3600), now.minusSeconds(60), "MEDIUM");
        lineageService.rebuild(stream.getId());

        Map<String, Object> detail = lineageService.detail(stream.getId(), cp.getId());
        assertThat(detail.get("stateSchema")).isEqualTo(StateSchema.GRID_WINDOW_V1);
        assertThat(detail.get("rulePackageVersion")).isEqualTo("v1.0.0");
        assertThat((List<?>) detail.get("bypassRules")).hasSize(1);
        assertThat(detail.get("warningLevel")).isEqualTo(WarningLevel.ORANGE);
        assertThat(detail.get("lineageComplete")).isEqualTo(true);
        assertThat(detail.get("directRestoreSafe")).isEqualTo(true);
    }

    @Test
    void scaleEventForcesMappingAndMarksUnsafeDirectRestore() {
        StreamDefinition stream = scenario.stream("T-LINEAGE-SCALE", 2, 48);
        Instant now = Instant.now();
        CheckpointManifest cp = scenario.checkpointDetailed(stream.getId(), 1, 5, "v1.0.0",
                StateSchema.GRID_WINDOW_V1, now.minusSeconds(3600), now.minusSeconds(60), "MEDIUM");

        PartitionScaleEvent scale = new PartitionScaleEvent();
        scale.setFromPartitions(2);
        scale.setToPartitions(3);
        // 分区 1 拆分出分区 2
        scale.setMappingJson("[{\"from\":0,\"to\":0},{\"from\":1,\"to\":1},{\"from\":1,\"to\":2,\"split\":true}]");
        scale.setOccurredAt(now.plusSeconds(60));
        scale.setReason("入汛扩容");
        lineageService.registerScaleEvent(stream.getId(), scale);
        lineageService.rebuild(stream.getId());

        Map<String, Object> detail = lineageService.detail(stream.getId(), cp.getId());
        assertThat(detail.get("directRestoreSafe")).isEqualTo(false);
        assertThat((List<?>) detail.get("restoreMapping")).hasSize(1);
        Map<String, Object> step = (Map<String, Object>) ((List<?>) detail.get("restoreMapping")).get(0);
        assertThat((List<Integer>) step.get("mappedTo")).containsExactly(1, 2);
        assertThat(detail.get("mappingNotes").toString()).contains("不可直接恢复");
    }

    @Test
    void searchFiltersByPartitionAndSchema() {
        StreamDefinition stream = scenario.stream("T-LINEAGE-SEARCH", 2, 48);
        Instant now = Instant.now();
        scenario.checkpointDetailed(stream.getId(), 0, 5, "v1.0.0", StateSchema.GRID_WINDOW_V1,
                now.minusSeconds(7200), now.minusSeconds(3600), "LOW");
        scenario.checkpointDetailed(stream.getId(), 1, 6, "v1.0.0", StateSchema.GRID_WINDOW_V1,
                now.minusSeconds(3600), now.minusSeconds(600), "LOW");
        lineageService.rebuild(stream.getId());

        assertThat(lineageService.search(stream.getId(), 0, null, null, null)).hasSize(1);
        assertThat(lineageService.search(stream.getId(), null, StateSchema.PUMP_REACH_ROAD_V2, null, null))
                .isEmpty();
        assertThat(lineageService.search(stream.getId(), null, null,
                now.minusSeconds(8000), now.minusSeconds(7000))).hasSize(1);
    }
}
