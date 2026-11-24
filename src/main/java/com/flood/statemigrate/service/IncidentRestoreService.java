package com.flood.statemigrate.service;

import tools.jackson.databind.ObjectMapper;
import com.flood.statemigrate.domain.*;
import com.flood.statemigrate.error.ApiException;
import com.flood.statemigrate.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * 事故复原工作台：按调查时间窗组合相邻快照，输出最小必要分区、分段恢复计划
 * 与不可还原的空档。跨状态模式时明确标注须组合两种模式的相邻快照。
 * 冻结、谱系不完整或原文缺失的快照只作为空档呈现，不得参与恢复。
 */
@Service
public class IncidentRestoreService {

    private final IncidentRestoreRepo incidentRepo;
    private final SnapshotLineageRepo lineageRepo;
    private final PartitionScaleEventRepo scaleRepo;
    private final CheckpointManifestRepo checkpointRepo;
    private final CompressionPackageRepo compressionPackageRepo;
    private final StreamService streamService;
    private final LineageService lineageService;
    private final ObjectMapper om;

    public IncidentRestoreService(IncidentRestoreRepo incidentRepo, SnapshotLineageRepo lineageRepo,
                                  PartitionScaleEventRepo scaleRepo, CheckpointManifestRepo checkpointRepo,
                                  CompressionPackageRepo compressionPackageRepo,
                                  StreamService streamService, LineageService lineageService,
                                  ObjectMapper om) {
        this.incidentRepo = incidentRepo;
        this.lineageRepo = lineageRepo;
        this.scaleRepo = scaleRepo;
        this.checkpointRepo = checkpointRepo;
        this.compressionPackageRepo = compressionPackageRepo;
        this.streamService = streamService;
        this.lineageService = lineageService;
        this.om = om;
    }

    @Transactional
    public IncidentRestore restore(Long streamId, Instant windowStart, Instant windowEnd,
                                   List<Integer> requestedPartitions) {
        StreamDefinition stream = streamService.get(streamId);
        if (!windowEnd.isAfter(windowStart)) {
            throw ApiException.badRequest("事故调查时间窗非法: windowEnd 须晚于 windowStart");
        }
        List<Integer> partitions = requestedPartitions == null || requestedPartitions.isEmpty()
                ? streamService.partitions(stream).stream().map(PartitionConfig::partitionNo).toList()
                : requestedPartitions;
        for (int p : partitions) {
            streamService.partition(stream, p);
        }
        List<PartitionScaleEvent> scales = scaleRepo.findByStreamIdOrderByOccurredAtAscIdAsc(streamId);
        List<SnapshotLineage> all = lineageRepo.findByStreamIdOrderByWindowEndAscIdAsc(streamId);

        List<Map<String, Object>> segments = new ArrayList<>();
        List<Map<String, Object>> gaps = new ArrayList<>();
        Set<StateSchema> schemas = new LinkedHashSet<>();
        Set<Integer> minimalPartitions = new TreeSet<>();

        for (int p : partitions) {
            List<SnapshotLineage> candidates = all.stream()
                    .filter(l -> currentPartitions(l, scales).contains(p))
                    .filter(l -> overlaps(l, windowStart, windowEnd))
                    .sorted(Comparator.comparing(l -> l.getWindowStart() == null
                            ? Instant.MIN : l.getWindowStart()))
                    .toList();
            if (candidates.isEmpty()) {
                gaps.add(gap(p, windowStart, windowEnd, "该分区无覆盖调查窗的快照谱系，此段状态不可还原"));
                continue;
            }
            Instant coveredTo = windowStart;
            int restorable = 0;
            for (SnapshotLineage l : candidates) {
                if (l.getWindowStart() == null || l.getWindowEnd() == null) {
                    continue;
                }
                Instant segStart = l.getWindowStart();
                Instant segEnd = l.getWindowEnd();
                String source = restorableSource(l);
                if (segStart.isAfter(coveredTo) && coveredTo.isBefore(windowEnd)) {
                    gaps.add(gap(p, coveredTo, min(segStart, windowEnd),
                            "相邻快照之间无覆盖，状态边界不可还原"));
                }
                if (!source.startsWith("STATE_SNAPSHOT") && !source.startsWith("ARCHIVE")) {
                    gaps.add(gap(p, max(segStart, windowStart), min(segEnd, windowEnd),
                            l.isFrozen() ? "快照演练摘要不一致被冻结，禁止用于事故复原"
                                    : "快照谱系不完整或原文缺失，无法恢复"));
                } else {
                    Map<String, Object> seg = new LinkedHashMap<>();
                    seg.put("partitionNo", p);
                    seg.put("checkpointId", l.getCheckpointId());
                    seg.put("stateSchema", l.getStateSchema());
                    seg.put("rulePackageVersion", l.getRulePackageVersion());
                    seg.put("windowStart", segStart);
                    seg.put("windowEnd", segEnd);
                    seg.put("restorableFrom", source);
                    seg.put("frozen", l.isFrozen());
                    seg.put("bypassRules", readJson(l.getBypassRulesJson()));
                    Map<String, Object> mapping = lineageService.restoreMapping(l, scales);
                    seg.put("restoreMapping", mapping.get("mapping"));
                    seg.put("directRestoreSafe", mapping.get("directRestoreSafe"));
                    seg.put("mappingNotes", mapping.get("notes"));
                    segments.add(seg);
                    restorable++;
                    schemas.add(l.getStateSchema());
                }
                if (segEnd.isAfter(coveredTo)) coveredTo = segEnd;
            }
            if (coveredTo.isBefore(windowEnd)) {
                gaps.add(gap(p, coveredTo, windowEnd, "时间窗尾部超出快照覆盖范围，此段状态不可还原"));
            }
            if (restorable > 0) {
                minimalPartitions.add(p);
            }
        }

        boolean schemaTransition = schemas.size() > 1;
        String status;
        if (segments.isEmpty()) {
            status = "NO_COVERAGE";
        } else if (gaps.isEmpty()) {
            status = "READY";
        } else {
            status = "PARTIAL";
        }

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("streamId", streamId);
        plan.put("streamCode", stream.getStreamCode());
        plan.put("windowStart", windowStart);
        plan.put("windowEnd", windowEnd);
        plan.put("requestedPartitions", partitions);
        plan.put("minimalPartitions", new ArrayList<>(minimalPartitions));
        plan.put("schemaTransition", schemaTransition);
        plan.put("schemasInvolved", schemas);
        plan.put("status", status);
        plan.put("segments", segments);
        plan.put("gaps", gaps);
        plan.put("restoreOrder", restoreOrder(segments));
        plan.put("notes", notes(schemaTransition, schemas, gaps, segments));

        IncidentRestore record = new IncidentRestore();
        record.setStreamId(streamId);
        record.setWindowStart(windowStart);
        record.setWindowEnd(windowEnd);
        record.setRequestedPartitionsJson(writeJson(partitions));
        record.setMinimalPartitionsJson(writeJson(new ArrayList<>(minimalPartitions)));
        record.setSchemaTransition(schemaTransition);
        record.setStatus(status);
        record.setResultJson(writeJson(plan));
        record.setReason(status.equals("READY") ? "调查窗可由相邻快照完整覆盖"
                : (status.equals("PARTIAL") ? "调查窗部分覆盖，存在不可还原空档"
                : "调查窗无任何快照覆盖"));
        return incidentRepo.save(record);
    }

    /** 快照旧分区号在经历后续扩缩容后对应的当前分区集合 */
    private Set<Integer> currentPartitions(SnapshotLineage l, List<PartitionScaleEvent> scales) {
        Map<String, Object> mapping = lineageService.restoreMapping(l, scales);
        Object current = mapping.get("currentPartitions");
        if (current instanceof List<?> list) {
            Set<Integer> result = new TreeSet<>();
            for (Object o : list) result.add(((Number) o).intValue());
            return result;
        }
        return Set.of(l.getPartitionNo());
    }

    private boolean overlaps(SnapshotLineage l, Instant from, Instant to) {
        Instant s = l.getWindowStart();
        Instant e = l.getWindowEnd();
        if (s == null || e == null) return false;
        return !e.isBefore(from) && !s.isAfter(to);
    }

    private String restorableSource(SnapshotLineage l) {
        if (l.isFrozen()) return "FROZEN_UNAVAILABLE";
        if (l.getArchivedPackageRef() != null) return "ARCHIVE:" + l.getArchivedPackageRef();
        CheckpointManifest cp = checkpointRepo.findById(l.getCheckpointId()).orElse(null);
        if (cp == null || cp.getStateSnapshotJson() == null) return "MISSING";
        if (!l.isLineageComplete()) return "INCOMPLETE_LINEAGE";
        return "STATE_SNAPSHOT";
    }

    /** 组合顺序：按窗口起点正序；跨模式时先恢复旧模式再恢复新模式 */
    private List<Map<String, Object>> restoreOrder(List<Map<String, Object>> segments) {
        List<Map<String, Object>> sorted = new ArrayList<>(segments);
        sorted.sort(Comparator
                .comparing((Map<String, Object> m) -> (Instant) m.get("windowStart"))
                .thenComparing(m -> ((StateSchema) m.get("stateSchema")).ordinal()));
        List<Map<String, Object>> order = new ArrayList<>();
        int seq = 1;
        for (Map<String, Object> seg : sorted) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("step", seq++);
            step.put("partitionNo", seg.get("partitionNo"));
            step.put("checkpointId", seg.get("checkpointId"));
            step.put("stateSchema", seg.get("stateSchema"));
            step.put("windowStart", seg.get("windowStart"));
            step.put("windowEnd", seg.get("windowEnd"));
            step.put("action", "从 " + seg.get("restorableFrom") + " 恢复并按 "
                    + seg.get("stateSchema") + " 重放窗口");
            order.add(step);
        }
        return order;
    }

    private List<String> notes(boolean schemaTransition, Set<StateSchema> schemas,
                               List<Map<String, Object>> gaps, List<Map<String, Object>> segments) {
        List<String> notes = new ArrayList<>();
        if (schemaTransition) {
            notes.add("调查窗跨越状态模式 " + schemas + "：须组合相邻快照，GRID_WINDOW_V1 快照还原网格窗口状态，"
                    + "PUMP_REACH_ROAD_V2 快照还原泵站—河段—道路三层状态；模式切换点无覆盖处标注为空档");
        }
        if (!gaps.isEmpty()) {
            notes.add("存在 " + gaps.size() + " 个不可还原空档，已在 gaps 中给出起止时刻，不得以相邻状态外推填充");
        }
        boolean mapped = segments.stream().anyMatch(s -> Boolean.FALSE.equals(s.get("directRestoreSafe")));
        if (mapped) {
            notes.add("部分快照创建后发生分区扩缩容，须按 restoreMapping 重写分区号，禁止按旧分区号直接恢复");
        }
        if (notes.isEmpty()) {
            notes.add("调查窗可由相邻快照直接覆盖");
        }
        return notes;
    }

    private Map<String, Object> gap(int partitionNo, Instant from, Instant to, String reason) {
        Map<String, Object> gap = new LinkedHashMap<>();
        gap.put("partitionNo", partitionNo);
        gap.put("gapStart", from);
        gap.put("gapEnd", to);
        gap.put("reason", reason);
        return gap;
    }

    private static Instant min(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
    private static Instant max(Instant a, Instant b) { return a.isAfter(b) ? a : b; }

    /**
     * 执行最小必要分区恢复：仅恢复调查窗内有可恢复快照的分区，
     * 逐段取回快照状态（原文或归档解压），返回恢复清单与未恢复空档。
     */
    @Transactional
    public Map<String, Object> executeRestore(Long streamId, Long incidentId) {
        IncidentRestore record = get(streamId, incidentId);
        List<PartitionScaleEvent> scales = scaleRepo.findByStreamIdOrderByOccurredAtAscIdAsc(streamId);
        List<SnapshotLineage> all = lineageRepo.findByStreamIdOrderByWindowEndAscIdAsc(streamId);
        List<Integer> requested = readIntList(record.getRequestedPartitionsJson());

        List<Map<String, Object>> restored = new ArrayList<>();
        List<Map<String, Object>> uncoveredGaps = new ArrayList<>();
        Set<StateSchema> schemas = new LinkedHashSet<>();

        for (int p : requested) {
            List<SnapshotLineage> candidates = all.stream()
                    .filter(l -> currentPartitions(l, scales).contains(p))
                    .filter(l -> overlaps(l, record.getWindowStart(), record.getWindowEnd()))
                    .sorted(Comparator.comparing(l -> l.getWindowStart() == null
                            ? Instant.MIN : l.getWindowStart()))
                    .toList();
            for (SnapshotLineage l : candidates) {
                String source = restorableSource(l);
                String stateJson = "STATE_SNAPSHOT".equals(source)
                        ? checkpointRepo.findById(l.getCheckpointId())
                            .map(CheckpointManifest::getStateSnapshotJson).orElse(null)
                        : (source.startsWith("ARCHIVE:") ? archivePayload(l, source) : null);
                if (stateJson == null) {
                    uncoveredGaps.add(gap(p, l.getWindowStart(), l.getWindowEnd(),
                            l.isFrozen() ? "快照已冻结，跳过恢复"
                                    : "快照状态不可取回（" + source + "），此段不可还原"));
                    continue;
                }
                Map<String, Object> mapping = lineageService.restoreMapping(l, scales);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("partitionNo", p);
                row.put("legacyPartitionNo", l.getPartitionNo());
                row.put("checkpointId", l.getCheckpointId());
                row.put("stateSchema", l.getStateSchema());
                row.put("windowStart", l.getWindowStart());
                row.put("windowEnd", l.getWindowEnd());
                row.put("restoreMapping", mapping.get("mapping"));
                row.put("bypassRules", readJson(l.getBypassRulesJson()));
                row.put("recoveredState", om.readValue(stateJson, Object.class));
                restored.add(row);
                schemas.add(l.getStateSchema());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("incidentId", incidentId);
        result.put("minimalPartitions", readIntList(record.getMinimalPartitionsJson()));
        result.put("restoredSegments", restored);

        // 计划中已识别的不可还原空档一并回传（无覆盖、冻结、尾部缺口）
        List<Map<String, Object>> skipped = new ArrayList<>(storedGaps(record));
        skipped.addAll(uncoveredGaps);
        result.put("skippedSegments", skipped);
        result.put("schemaTransition", schemas.size() > 1);
        result.put("schemasRestored", schemas);
        result.put("status", restored.isEmpty() ? "NOT_RESTORED"
                : ((skipped.isEmpty() && !"PARTIAL".equals(record.getStatus()))
                    ? "RESTORED" : "PARTIALLY_RESTORED"));
        result.put("notes", skipped.isEmpty()
                ? "已按最小必要分区恢复调查窗内全部可还原状态"
                : "仅恢复可还原分段，其余空档不得以相邻状态外推填充");
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> storedGaps(IncidentRestore record) {
        try {
            Map<String, Object> plan = om.readValue(record.getResultJson(), Map.class);
            Object gaps = plan.get("gaps");
            if (gaps instanceof List<?> list) {
                List<Map<String, Object>> result = new ArrayList<>();
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        m.forEach((k, v) -> row.put(String.valueOf(k), v));
                        row.put("skipped", true);
                        result.add(row);
                    }
                }
                return result;
            }
        } catch (Exception ignored) {
            // 解析失败时仅回传取回失败的分段
        }
        return List.of();
    }

    private String archivePayload(SnapshotLineage l, String source) {
        String ref = source.substring("ARCHIVE:".length());
        for (CompressionPackage pkg : compressionPackages(l.getStreamId())) {
            if (pkg.getPackageRef().equals(ref) && !pkg.isFrozen()) {
                try {
                    Map<String, String> payloads = om.readValue(pkg.getPayloadsJson(),
                            new tools.jackson.core.type.TypeReference<Map<String, String>>() {});
                    String b64 = payloads.get(String.valueOf(l.getCheckpointId()));
                    return b64 == null ? null : CompressionService.gunzip(Base64.getDecoder().decode(b64));
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }

    private List<CompressionPackage> compressionPackages(Long streamId) {
        return compressionPackageRepo.findByStreamIdOrderByIdDesc(streamId);
    }

    private List<Integer> readIntList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return om.readValue(json, new tools.jackson.core.type.TypeReference<List<Integer>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    public List<IncidentRestore> list(Long streamId) {
        return incidentRepo.findByStreamIdOrderByIdDesc(streamId);
    }

    public IncidentRestore get(Long streamId, Long id) {
        IncidentRestore r = incidentRepo.findById(id)
                .orElseThrow(() -> ApiException.notFound("事故复原记录不存在: " + id));
        if (!r.getStreamId().equals(streamId)) {
            throw ApiException.badRequest("记录不属于该流");
        }
        return r;
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
