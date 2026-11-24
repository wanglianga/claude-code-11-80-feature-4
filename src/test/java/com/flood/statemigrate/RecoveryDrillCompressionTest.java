package com.flood.statemigrate;

import com.flood.statemigrate.domain.*;
import com.flood.statemigrate.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/** 演练器 → 压缩资格 → 压缩冻结 的完整可恢复性证明链 */
class RecoveryDrillCompressionTest extends BaseIntegrationTest {

    @Autowired
    private DrillService drillService;
    @Autowired
    private CompressionEligibilityService eligibilityService;
    @Autowired
    private CompressionService compressionService;
    @Autowired
    private LineageService lineageService;
    @Autowired
    private RetentionPolicyService retentionPolicyService;

    @Test
    void drillPassesThenCompressionEligible() {
        StreamDefinition stream = scenario.stream("T-DRILL-OK", 1, 48);
        scenario.events(stream.getId(), 0, 10, 2.6, 2.7, true, null);
        Instant now = Instant.now();
        CheckpointManifest older = scenario.checkpointDetailed(stream.getId(), 0, 5, "v1.0.0",
                StateSchema.GRID_WINDOW_V1, now.minusSeconds(125 * 60), now.minusSeconds(5 * 60), "LOW");
        CheckpointManifest latest = scenario.checkpointDetailed(stream.getId(), 0, 9, "v1.0.0",
                StateSchema.GRID_WINDOW_V1, now.minusSeconds(125 * 60), now.minusSeconds(4 * 60), "LOW");
        lineageService.rebuild(stream.getId());

        // 未演练 → 不可压缩
        Map<String, Object> before = eligibilityService.evaluate(stream.getId(), older.getId());
        assertThat(before.get("eligible")).isEqualTo(false);

        RecoveryDrill drill = drillService.drill(stream.getId(), older.getId(), "iso-a");
        assertThat(drill.getStatus()).isEqualTo(DrillStatus.PASSED);
        assertThat(drill.isDigestMatch()).isTrue();
        assertThat(drill.getExpectedDigest()).isEqualTo(drill.getActualDigest());

        Map<String, Object> after = eligibilityService.evaluate(stream.getId(), older.getId());
        assertThat(after.get("eligible")).isEqualTo(true);
        assertThat(after.get("passedDrills")).isEqualTo(1L);

        drillService.drill(stream.getId(), latest.getId(), "iso-a");
        CompressionPackage pkg = compressionService.compress(stream.getId(),
                List.of(older.getId(), latest.getId()), null, true, "首个可恢复归档");
        assertThat(pkg.getStatus()).isEqualTo(CompressionStatus.ARCHIVED);
        assertThat(pkg.getCompressedBytes()).isLessThan(pkg.getOriginalBytes());

        // 较早检查点原文被清理，再次演练须从归档解压核对
        assertThat(checkpointRepo.findById(older.getId()).orElseThrow().getStateSnapshotJson()).isNull();
        assertThat(checkpointRepo.findById(latest.getId()).orElseThrow().getStateSnapshotJson()).isNotNull();
        RecoveryDrill archivedDrill = drillService.drill(stream.getId(), older.getId(), "iso-b");
        assertThat(archivedDrill.getStatus()).isEqualTo(DrillStatus.PASSED);
        assertThat(archivedDrill.getRestoreSource()).isEqualTo("ARCHIVE");
        RecoveryDrill latestDrill = drillService.drill(stream.getId(), latest.getId(), "iso-b");
        assertThat(latestDrill.getRestoreSource()).isEqualTo("STATE_SNAPSHOT");
    }

    @Test
    void digestMismatchFreezesSnapshotAndDerivedPackage() {
        StreamDefinition stream = scenario.stream("T-DRILL-BAD", 1, 48);
        scenario.events(stream.getId(), 0, 10, 2.6, 2.7, true, null);
        Instant now = Instant.now();
        CheckpointManifest good = scenario.checkpointDetailed(stream.getId(), 0, 5, "v1.0.0",
                StateSchema.GRID_WINDOW_V1, now.minusSeconds(125 * 60), now.minusSeconds(5 * 60), "LOW");
        // 第二个检查点故意把降雨累计改错 → 演练摘要不一致
        String badJson = scenario.corruptSnapshot(scenario.snapshot(StateSchema.GRID_WINDOW_V1,
                now.minusSeconds(125 * 60), now.minusSeconds(4 * 60), 30.0, 30, 0.9, "LOW"));
        CheckpointManifest bad = scenario.checkpointDetailed(stream.getId(), 0, 9, "v1.0.0",
                StateSchema.GRID_WINDOW_V1, now.minusSeconds(125 * 60), now.minusSeconds(4 * 60), "LOW");
        bad.setStateSnapshotJson(badJson);
        checkpointRepo.save(bad);
        lineageService.rebuild(stream.getId());

        // 先给好快照建一个归档包
        drillService.drill(stream.getId(), good.getId(), "iso");
        CompressionPackage pkg = compressionService.compress(stream.getId(), List.of(good.getId()),
                null, false, "好快照归档");

        RecoveryDrill failed = drillService.drill(stream.getId(), bad.getId(), "iso");
        assertThat(failed.getStatus()).isEqualTo(DrillStatus.FAILED);
        assertThat(failed.getExpectedDigest()).isNotEqualTo(failed.getActualDigest());

        SnapshotLineage badLineage = lineageRepo.findByCheckpointId(bad.getId()).orElseThrow();
        assertThat(badLineage.isFrozen()).isTrue();
        assertThat(eligibilityService.evaluate(stream.getId(), bad.getId()).get("eligible")).isEqualTo(false);
    }

    @Test
    void frozenPackageRejectsFurtherDerivation() {
        StreamDefinition stream = scenario.stream("T-DRILL-DERIVE", 1, 48);
        scenario.events(stream.getId(), 0, 10, 2.6, 2.7, true, null);
        Instant now = Instant.now();
        CheckpointManifest a = scenario.checkpointDetailed(stream.getId(), 0, 5, "v1.0.0",
                StateSchema.GRID_WINDOW_V1, now.minusSeconds(125 * 60), now.minusSeconds(5 * 60), "LOW");
        lineageService.rebuild(stream.getId());
        drillService.drill(stream.getId(), a.getId(), "iso");
        CompressionPackage pkg = compressionService.compress(stream.getId(), List.of(a.getId()),
                null, false, "归档");
        // 人为冻结（模拟后续成员演练不一致）
        pkg.setFrozen(true);
        pkg.setStatus(CompressionStatus.FROZEN);
        compressionPackageRepo.save(pkg);

        assertThatThrownBy(() -> compressionService.compress(stream.getId(), List.of(a.getId()),
                pkg.getPackageRef(), false, "派生"))
                .hasMessageContaining("已冻结");
    }

    @Test
    void freezePropagatesToDerivedPackages() {
        StreamDefinition stream = scenario.stream("T-DRILL-CHAIN", 1, 48);
        scenario.events(stream.getId(), 0, 10, 2.6, 2.7, true, null);
        Instant now = Instant.now();
        CheckpointManifest older = scenario.checkpointDetailed(stream.getId(), 0, 5, "v1.0.0",
                StateSchema.GRID_WINDOW_V1, now.minusSeconds(125 * 60), now.minusSeconds(5 * 60), "LOW");
        String badJson = scenario.corruptSnapshot(scenario.snapshot(StateSchema.GRID_WINDOW_V1,
                now.minusSeconds(125 * 60), now.minusSeconds(4 * 60), 30.0, 30, 0.9, "LOW"));
        CheckpointManifest bad = scenario.checkpointDetailed(stream.getId(), 0, 9, "v1.0.0",
                StateSchema.GRID_WINDOW_V1, now.minusSeconds(125 * 60), now.minusSeconds(4 * 60), "LOW");
        bad.setStateSnapshotJson(badJson);
        checkpointRepo.save(bad);
        lineageService.rebuild(stream.getId());

        drillService.drill(stream.getId(), older.getId(), "iso");
        drillService.drill(stream.getId(), bad.getId(), "iso"); // FAILED，冻结 bad 及其归档
        // 即便 bad 已被冻结，先手工建基础包再验证派生的传递性
        CompressionPackage base = compressionService.compress(stream.getId(), List.of(older.getId()),
                null, false, "基础归档");
        // 人为构造派生链：base ← child（用另一流内包模拟派生来源）
        CompressionPackage child = new CompressionPackage();
        child.setStreamId(stream.getId());
        child.setPackageRef(base.getPackageRef() + "-child");
        child.setCheckpointIdsJson("[]");
        child.setSchemasJson("[]");
        child.setStatus(CompressionStatus.ARCHIVED);
        child.setDerivedFromPackageRef(base.getPackageRef());
        child.setPayloadsJson("{}");
        compressionPackageRepo.save(child);

        // 冻结 base → 派生包 child 一并冻结
        drillService.freeze(older.getId(),
                lineageRepo.findByCheckpointId(older.getId()).orElseThrow(), "演练摘要不一致（测试）");
        assertThat(compressionPackageRepo.findByPackageRef(base.getPackageRef()).orElseThrow().isFrozen())
                .isTrue();
        assertThat(compressionPackageRepo.findByPackageRef(child.getPackageRef()).orElseThrow().isFrozen())
                .isTrue();
        assertThatThrownBy(() -> compressionService.compress(stream.getId(), List.of(older.getId()),
                base.getPackageRef(), false, "冻结后派生"))
                .hasMessageContaining("冻结");
    }

    @Test
    void retentionDrillRequirementBlocksEligibility() {
        StreamDefinition stream = scenario.stream("T-DRILL-REQ", 1, 48);
        scenario.events(stream.getId(), 0, 10, 2.6, 2.7, true, null);
        Instant now = Instant.now();
        CheckpointManifest cp = scenario.checkpointDetailed(stream.getId(), 0, 5, "v1.0.0",
                StateSchema.GRID_WINDOW_V1, now.minusSeconds(125 * 60), now.minusSeconds(5 * 60), "HIGH");
        lineageService.rebuild(stream.getId());

        RetentionPolicy red = new RetentionPolicy();
        red.setWarningLevel(WarningLevel.RED);
        red.setRetentionDays(120);
        red.setRequiredDrills(2);
        retentionPolicyService.upsert(stream.getId(), red);

        drillService.drill(stream.getId(), cp.getId(), "iso-1");
        Map<String, Object> oneDrill = eligibilityService.evaluate(stream.getId(), cp.getId());
        assertThat(oneDrill.get("eligible")).isEqualTo(false);
        assertThat(oneDrill.get("reason").toString()).contains("未达");

        drillService.drill(stream.getId(), cp.getId(), "iso-2");
        assertThat(eligibilityService.evaluate(stream.getId(), cp.getId()).get("eligible")).isEqualTo(true);
    }
}
