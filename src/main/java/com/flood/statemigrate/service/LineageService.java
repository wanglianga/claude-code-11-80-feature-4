package com.flood.statemigrate.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.flood.statemigrate.domain.*;
import com.flood.statemigrate.error.ApiException;
import com.flood.statemigrate.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * 快照谱系索引：把检查点快照与状态模式、输入位点、当时生效的规则包版本与临时绕过规则关联，
 * 并在扩缩容发生时计算"旧分区号 → 当前分区"的恢复映射。禁止假设旧分区号可直接恢复。
 */
@Service
public class LineageService {

    private final SnapshotLineageRepo lineageRepo;
    private final CheckpointManifestRepo checkpointRepo;
    private final StreamEventRepo eventRepo;
    private final RulePackageRepo rulePackageRepo;
    private final BypassRuleRepo bypassRepo;
    private final PartitionScaleEventRepo scaleRepo;
    private final RecoveryDrillRepo drillRepo;
    private final StreamService streamService;
    private final SnapshotDigest digest;
    private final ObjectMapper om;

    public LineageService(SnapshotLineageRepo lineageRepo, CheckpointManifestRepo checkpointRepo,
                          StreamEventRepo eventRepo, RulePackageRepo rulePackageRepo,
                          BypassRuleRepo bypassRepo, PartitionScaleEventRepo scaleRepo,
                          RecoveryDrillRepo drillRepo, StreamService streamService,
                          SnapshotDigest digest, ObjectMapper om) {
        this.lineageRepo = lineageRepo;
        this.checkpointRepo = checkpointRepo;
        this.eventRepo = eventRepo;
        this.rulePackageRepo = rulePackageRepo;
        this.bypassRepo = bypassRepo;
        this.scaleRepo = scaleRepo;
        this.drillRepo = drillRepo;
        this.streamService = streamService;
        this.digest = digest;
        this.om = om;
    }

    /** 登记检查点时建立/刷新谱系索引（幂等） */
    @Transactional
    public SnapshotLineage ensureLineage(StreamDefinition stream, CheckpointManifest cp) {
        Optional<SnapshotLineage> existing = lineageRepo.findByCheckpointId(cp.getId());
        if (existing.isPresent()) {
            return existing.get();
        }
        JsonNode snapshot = digest.read(cp.getStateSnapshotJson());
        Instant windowEnd = digest.windowEnd(snapshot);
        Instant windowStart = digest.windowStart(snapshot);
        if (windowEnd == null) windowEnd = cp.getWindowWatermark();
        if (windowStart == null && windowEnd != null) windowStart = windowEnd.minusSeconds(7200);

        long from = cp.getConsumeOffset();
        long to = cp.getConsumeOffset();
        if (windowStart != null && windowEnd != null) {
            List<StreamEvent> events = eventRepo
                    .findByStreamIdAndPartitionNoAndEventTimeBetweenOrderByEventTimeAsc(
                            stream.getId(), cp.getPartitionNo(), windowStart, windowEnd);
            if (!events.isEmpty()) {
                from = events.get(0).getEventOffset();
                to = events.get(events.size() - 1).getEventOffset();
            }
        }

        String risk = digest.riskLevel(snapshot);
        WarningLevel level = warningFromRisk(risk);

        // 当时生效的临时绕过规则
        Instant at = cp.getCreatedAt() != null ? cp.getCreatedAt() : Instant.now();
        List<Map<String, Object>> bypass = new ArrayList<>();
        for (BypassRule b : bypassRepo.findByStreamIdOrderByIdAsc(stream.getId())) {
            if (b.activeAt(at) && (b.getPartitionNo() == null
                    || b.getPartitionNo() == cp.getPartitionNo())) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", b.getId());
                row.put("ruleKey", b.getRuleKey());
                row.put("partitionNo", b.getPartitionNo());
                row.put("effectiveFrom", b.getEffectiveFrom());
                row.put("effectiveTo", b.getEffectiveTo());
                row.put("reason", b.getReason());
                bypass.add(row);
            }
        }

        // 同分区上一条快照（按窗口起点排序取最近一条）
        Long previousId = null;
        for (SnapshotLineage l : lineageRepo.findByStreamIdAndPartitionNoOrderByInputOffsetToAsc(
                stream.getId(), cp.getPartitionNo())) {
            if (l.getCheckpointId().equals(cp.getId())) continue;
            if (previousId == null || l.getCreatedAt().isBefore(at)
                    || l.getCreatedAt().equals(at)) {
                previousId = l.getCheckpointId();
            }
        }

        boolean ruleKnown = cp.getRulePackageVersion() != null
                && rulePackageRepo.findByStreamIdAndVersion(stream.getId(), cp.getRulePackageVersion()).isPresent();

        SnapshotLineage lineage = new SnapshotLineage();
        lineage.setStreamId(stream.getId());
        lineage.setCheckpointId(cp.getId());
        lineage.setPartitionNo(cp.getPartitionNo());
        lineage.setRoadId(roadId(stream, cp.getPartitionNo()));
        lineage.setStateSchema(cp.getStateSchema());
        lineage.setRulePackageVersion(cp.getRulePackageVersion());
        lineage.setWindowStart(windowStart);
        lineage.setWindowEnd(windowEnd);
        lineage.setInputOffsetFrom(from);
        lineage.setInputOffsetTo(to);
        lineage.setPreviousCheckpointId(previousId);
        lineage.setBypassRulesJson(writeJson(bypass));
        lineage.setWarningLevel(level);
        lineage.setFrozen(false);
        boolean complete = cp.getStateSchema() != null && windowStart != null && windowEnd != null
                && cp.getStateSnapshotJson() != null && ruleKnown;
        lineage.setLineageComplete(complete);
        return lineageRepo.save(lineage);
    }

    /** 对已登记检查点补建谱系（用于历史数据或迁移产生的检查点） */
    @Transactional
    public int rebuild(Long streamId) {
        StreamDefinition stream = streamService.get(streamId);
        int built = 0;
        for (CheckpointManifest cp : checkpointRepo.findByStreamIdOrderByPartitionNoAscCreatedAtDesc(streamId)) {
            if (lineageRepo.findByCheckpointId(cp.getId()).isEmpty()) {
                ensureLineage(stream, cp);
                built++;
            }
        }
        return built;
    }

    /** 谱系检索：按分区/模式/时间窗过滤，并逐条给出恢复分区映射 */
    public List<Map<String, Object>> search(Long streamId, Integer partitionNo, StateSchema schema,
                                            Instant from, Instant to) {
        StreamDefinition stream = streamService.get(streamId);
        List<SnapshotLineage> lines = lineageRepo.findByStreamIdOrderByWindowEndAscIdAsc(streamId);
        List<PartitionScaleEvent> scales = scaleRepo.findByStreamIdOrderByOccurredAtAscIdAsc(streamId);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SnapshotLineage l : lines) {
            if (partitionNo != null && l.getPartitionNo() != partitionNo) continue;
            if (schema != null && l.getStateSchema() != schema) continue;
            if (to != null && l.getWindowStart() != null && l.getWindowStart().isAfter(to)) continue;
            if (from != null && l.getWindowEnd() != null && l.getWindowEnd().isBefore(from)) continue;
            rows.add(row(stream, l, scales));
        }
        return rows;
    }

    public Map<String, Object> detail(Long streamId, Long checkpointId) {
        StreamDefinition stream = streamService.get(streamId);
        SnapshotLineage l = lineageRepo.findByCheckpointId(checkpointId)
                .orElseThrow(() -> ApiException.notFound("检查点无谱系索引: " + checkpointId));
        List<PartitionScaleEvent> scales = scaleRepo.findByStreamIdOrderByOccurredAtAscIdAsc(streamId);
        Map<String, Object> row = row(stream, l, scales);
        row.put("previousCheckpointId", l.getPreviousCheckpointId());
        row.put("bypassRules", readJson(l.getBypassRulesJson()));
        row.put("drills", drillRepo.findByStreamIdAndCheckpointIdOrderByIdDesc(streamId, checkpointId)
                .stream().map(d -> Map.of(
                        "id", d.getId(), "status", d.getStatus(), "digestMatch", d.isDigestMatch(),
                        "runAt", d.getRunAt(), "restoreSource", d.getRestoreSource() == null ? "" : d.getRestoreSource()))
                .toList());
        return row;
    }

    private Map<String, Object> row(StreamDefinition stream, SnapshotLineage l,
                                    List<PartitionScaleEvent> scales) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("checkpointId", l.getCheckpointId());
        row.put("partitionNo", l.getPartitionNo());
        row.put("roadId", l.getRoadId());
        row.put("stateSchema", l.getStateSchema());
        row.put("rulePackageVersion", l.getRulePackageVersion());
        row.put("windowStart", l.getWindowStart());
        row.put("windowEnd", l.getWindowEnd());
        row.put("inputOffsetFrom", l.getInputOffsetFrom());
        row.put("inputOffsetTo", l.getInputOffsetTo());
        row.put("warningLevel", l.getWarningLevel());
        row.put("lineageComplete", l.isLineageComplete());
        row.put("frozen", l.isFrozen());
        row.put("frozenReason", l.getFrozenReason());
        row.put("archivedPackageRef", l.getArchivedPackageRef());
        row.put("bypassRuleCount", readJson(l.getBypassRulesJson()) instanceof List<?> list ? list.size() : 0);
        Map<String, Object> mapping = restoreMapping(l, scales);
        row.put("restoreMapping", mapping.get("mapping"));
        row.put("directRestoreSafe", mapping.get("directRestoreSafe"));
        row.put("mappingNotes", mapping.get("notes"));
        return row;
    }

    /**
     * 计算从该快照恢复时旧分区号到当前分区的映射。
     * 若快照创建之后发生过扩缩容，则逐次应用映射；直连恢复不再安全。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> restoreMapping(SnapshotLineage l, List<PartitionScaleEvent> scales) {
        Instant created = l.getCreatedAt();
        List<PartitionScaleEvent> later = scales.stream()
                .filter(s -> created == null || !s.getOccurredAt().isBefore(created))
                .toList();
        List<Map<String, Object>> applied = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Set<Integer> current = new LinkedHashSet<>(List.of(l.getPartitionNo()));
        boolean safe = later.isEmpty();
        boolean split = false;
        for (PartitionScaleEvent s : later) {
            List<Map<String, Object>> mappings;
            try {
                mappings = om.readValue(s.getMappingJson(), new TypeReference<>() {});
            } catch (Exception e) {
                throw ApiException.badRequest("扩缩容映射无法解析: " + s.getMappingJson());
            }
            Set<Integer> next = new LinkedHashSet<>();
            for (int p : current) {
                boolean matched = false;
                for (Map<String, Object> m : mappings) {
                    int fromP = ((Number) m.get("from")).intValue();
                    int toP = ((Number) m.get("to")).intValue();
                    if (fromP == p) {
                        next.add(toP);
                        matched = true;
                        if (Boolean.TRUE.equals(m.get("split"))) split = true;
                    }
                }
                if (!matched) next.add(p); // 未出现在映射中的分区沿用原编号
            }
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("scaleEventId", s.getId());
            step.put("occurredAt", s.getOccurredAt());
            step.put("fromPartitions", s.getFromPartitions());
            step.put("toPartitions", s.getToPartitions());
            step.put("mappedTo", new ArrayList<>(next));
            applied.add(step);
            notes.add("检查点创建后发生扩缩容（" + s.getFromPartitions() + "→" + s.getToPartitions()
                    + "），旧分区 " + l.getPartitionNo() + " 在本次变更后映射为 " + next);
            current = next;
        }
        if (!later.isEmpty()) {
            notes.add("旧分区号不可直接恢复：须按 restoreMapping 重写分区号"
                    + (split ? "；该分区在扩容中被拆分，恢复须合并拆分后的多个分区或回放" : ""));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mapping", applied);
        result.put("directRestoreSafe", safe);
        result.put("notes", notes);
        result.put("currentPartitions", new ArrayList<>(current));
        result.put("split", split);
        return result;
    }

    /** 当前分区号在快照时刻对应的旧分区（逆映射，用于事故窗查询） */
    public Integer legacyPartitionAt(Long streamId, int currentPartition, Instant moment) {
        StreamDefinition stream = streamService.get(streamId);
        if (streamService.partitions(stream).stream().noneMatch(p -> p.partitionNo() == currentPartition)) {
            return null;
        }
        int candidate = currentPartition;
        for (PartitionScaleEvent s : scaleRepo.findByStreamIdOrderByOccurredAtAscIdAsc(streamId)) {
            if (moment != null && s.getOccurredAt().isAfter(moment)) break;
            try {
                List<Map<String, Object>> mappings = om.readValue(s.getMappingJson(), new TypeReference<>() {});
                Integer source = null;
                for (Map<String, Object> m : mappings) {
                    if (((Number) m.get("to")).intValue() == candidate) {
                        source = ((Number) m.get("from")).intValue();
                        break;
                    }
                }
                if (source != null) candidate = source;
            } catch (Exception ignored) {
                // 映射非法时保留当前编号，由检索结果显式提示
            }
        }
        return candidate;
    }

    private WarningLevel warningFromRisk(String risk) {
        if (risk == null) return WarningLevel.YELLOW;
        try {
            return WarningLevel.fromRisk(RiskLevel.valueOf(risk));
        } catch (IllegalArgumentException e) {
            return WarningLevel.YELLOW;
        }
    }

    private String roadId(StreamDefinition stream, int partitionNo) {
        return streamService.partitions(stream).stream()
                .filter(p -> p.partitionNo() == partitionNo)
                .map(PartitionConfig::roadId)
                .findFirst().orElse(null);
    }

    public List<PartitionScaleEvent> scaleEvents(Long streamId) {
        return scaleRepo.findByStreamIdOrderByOccurredAtAscIdAsc(streamId);
    }

    public List<BypassRule> bypassRules(Long streamId) {
        return bypassRepo.findByStreamIdOrderByIdAsc(streamId);
    }

    @Transactional
    public PartitionScaleEvent registerScaleEvent(Long streamId, PartitionScaleEvent event) {
        streamService.get(streamId);
        event.setStreamId(streamId);
        return scaleRepo.save(event);
    }

    @Transactional
    public BypassRule registerBypassRule(Long streamId, BypassRule rule) {
        streamService.get(streamId);
        rule.setStreamId(streamId);
        return bypassRepo.save(rule);
    }

    private Object readJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return om.readValue(json, Object.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return om.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
