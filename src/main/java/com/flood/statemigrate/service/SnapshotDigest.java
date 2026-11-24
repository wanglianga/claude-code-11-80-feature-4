package com.flood.statemigrate.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 快照解析与摘要工具：提取窗口边界、预警等级与可比对状态字段，并生成稳定摘要。
 * 旧模式（GRID_WINDOW_V1）与新模式（PUMP_REACH_ROAD_V2）字段命名不同，
 * 但可比对的三项状态量一致：两小时降雨累计、泵站运行分钟数、传感器可信度。
 */
@Component
public class SnapshotDigest {

    private final ObjectMapper om;

    public SnapshotDigest(ObjectMapper om) {
        this.om = om;
    }

    public JsonNode read(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return om.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    public Instant windowStart(JsonNode snapshot) {
        return instant(snapshot, "windowStart");
    }

    public Instant windowEnd(JsonNode snapshot) {
        return instant(snapshot, "windowEnd");
    }

    private Instant instant(JsonNode snapshot, String field) {
        if (snapshot == null) return null;
        JsonNode n = snapshot.get(field);
        if (n == null || !n.isTextual()) return null;
        try {
            return Instant.parse(n.asText());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 从快照取风险等级（旧/新模式下均在顶层 riskLevel） */
    public String riskLevel(JsonNode snapshot) {
        if (snapshot == null) return null;
        JsonNode n = snapshot.get("riskLevel");
        if (n != null && n.isTextual()) return n.asText();
        JsonNode road = snapshot.get("road");
        if (road != null && road.get("riskLevel") != null && road.get("riskLevel").isTextual()) {
            return road.get("riskLevel").asText();
        }
        return null;
    }

    /**
     * 提取可比对状态字段：rain2h / pumpRuntimeMin / credibility。
     * 旧模式取顶层字段；新模式取 road.rain2hAcc、pump.runtimeMin、road.credibility。
     * 缺失字段记入 notComparable，不参与摘要，绝不以零值填充。
     */
    public Map<String, Double> comparableFields(JsonNode snapshot) {
        Map<String, Double> fields = new LinkedHashMap<>();
        if (snapshot == null) return fields;
        Double rain = number(snapshot, "rain2hAcc");
        Double runtime = number(snapshot, "pumpRuntimeMin");
        Double cred = number(snapshot, "deviceHealth");
        JsonNode road = snapshot.get("road");
        JsonNode pump = snapshot.get("pump");
        if (rain == null && road != null) rain = number(road, "rain2hAcc");
        if (runtime == null && pump != null) runtime = number(pump, "runtimeMin");
        if (cred == null) {
            if (road != null) cred = number(road, "credibility");
            if (cred == null && road != null) cred = number(road, "credibilityScore");
        }
        if (rain != null) fields.put("rain2h", rain);
        if (runtime != null) fields.put("pumpRuntimeMin", runtime);
        if (cred != null) fields.put("credibility", cred);
        return fields;
    }

    private Double number(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode n = node.get(field);
        return n != null && n.isNumber() ? n.asDouble() : null;
    }

    /** 规范化摘要：只含参与比对的三项状态量，按固定键序与精度生成 */
    public String digest(Map<String, Double> fields) {
        String canonical = "rain2h=" + round(fields.get("rain2h"), 2)
                + "|pumpRuntimeMin=" + round(fields.get("pumpRuntimeMin"), 2)
                + "|credibility=" + round(fields.get("credibility"), 3);
        return sha256(canonical);
    }

    public String canonical(Map<String, Double> fields) {
        return "rain2h=" + round(fields.get("rain2h"), 2)
                + "|pumpRuntimeMin=" + round(fields.get("pumpRuntimeMin"), 2)
                + "|credibility=" + round(fields.get("credibility"), 3);
    }

    private String round(Double v, int digits) {
        if (v == null) return "n/a";
        double scale = Math.pow(10, digits);
        return String.valueOf(Math.round(v * scale) / scale);
    }

    public static String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 窗口小时数（向上取整，最少 1 小时） */
    public static int windowHours(Instant start, Instant end) {
        long minutes = Duration.between(start, end).toMinutes();
        return (int) Math.max(1, (minutes + 59) / 60);
    }
}
