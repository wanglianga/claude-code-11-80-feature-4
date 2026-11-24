package com.flood.statemigrate;

import com.flood.statemigrate.domain.*;
import com.flood.statemigrate.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/** 保留策略器：预警等级基线 + 规则变更/高影响输出延长 */
class RetentionPolicyTest extends BaseIntegrationTest {

    @Autowired
    private RetentionPolicyService policyService;

    @Test
    void warningLevelAndRuleChangeExtendRetention() {
        StreamDefinition stream = scenario.stream("T-RETAIN", 1, 48);
        scenario.events(stream.getId(), 0, 10, 2.6, 2.7, true, null);
        // 同一分区两个检查点，规则包版本不同（跨越规则变更边界）
        scenario.checkpointDetailed(stream.getId(), 0, 5, "v1.0.0", StateSchema.GRID_WINDOW_V1,
                java.time.Instant.now().minusSeconds(7200), java.time.Instant.now().minusSeconds(600), "LOW");
        CheckpointManifest second = scenario.checkpointDetailed(stream.getId(), 0, 9, "v2.0.0",
                StateSchema.GRID_WINDOW_V1,
                java.time.Instant.now().minusSeconds(3600), java.time.Instant.now().minusSeconds(120), "HIGH");

        RetentionPolicy red = new RetentionPolicy();
        red.setWarningLevel(WarningLevel.RED);
        red.setRetentionDays(180);
        red.setRequiredDrills(2);
        red.setRuleChangeExtraDays(30);
        red.setHighImpactExtraDays(45);
        policyService.upsert(stream.getId(), red);
        lineageService.rebuild(stream.getId());

        Map<String, Object> evaluation = policyService.evaluate(stream.getId());
        var rows = (java.util.List<Map<String, Object>>) evaluation.get("snapshots");
        assertThat(rows).hasSize(2);

        // 高风险快照：基线 180 + 规则变更 30 + 高影响 45 = 255
        Map<String, Object> row = rows.stream()
                .filter(r -> ((Number) r.get("checkpointId")).longValue() ==
                        checkpointIdOf(stream.getId(), second))
                .findFirst().orElseThrow();
        assertThat(row.get("warningLevel")).isEqualTo(WarningLevel.RED);
        assertThat(row.get("effectiveDays")).isEqualTo(255);
        assertThat((java.util.List<String>) row.get("extensionTriggers"))
                .containsExactlyInAnyOrder("RULE_CHANGE", "HIGH_IMPACT_OUTPUT");
    }

    @Test
    void unconfiguredLevelUsesDefaults() {
        StreamDefinition stream = scenario.stream("T-RETAIN-DEF", 1, 48);
        scenario.checkpointDetailed(stream.getId(), 0, 5, "v1.0.0", StateSchema.GRID_WINDOW_V1,
                java.time.Instant.now().minusSeconds(7200), java.time.Instant.now().minusSeconds(600), "MEDIUM");
        lineaged(stream.getId());

        Map<String, Object> evaluation = policyService.evaluate(stream.getId());
        var rows = (java.util.List<Map<String, Object>>) evaluation.get("snapshots");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("warningLevel")).isEqualTo(WarningLevel.ORANGE);
        assertThat(rows.get(0).get("baseDays")).isEqualTo(WarningLevel.ORANGE.defaultRetentionDays());
        assertThat(rows.get(0).get("policySource")).isEqualTo("DEFAULT");
    }

    @Autowired
    private LineageService lineageService;

    private void lineaged(Long streamId) {
        lineageService.rebuild(streamId);
    }

    private long checkpointIdOf(Long streamId, CheckpointManifest cp) {
        return cp.getId();
    }
}
