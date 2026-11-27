package com.pollen.rag.controller;

import com.pollen.rag.common.ApiResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/stats")
public class StatsController {

    private final JdbcTemplate jdbc;

    public StatsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview(
            @RequestParam(required = false) Long kbId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        StringBuilder sql = new StringBuilder("""
                SELECT count(*) AS calls,
                       coalesce(sum(total_tokens), 0) AS tokens,
                       coalesce(avg(latency_ms), 0) AS avg_latency,
                       coalesce(percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms), 0) AS p95_latency,
                       coalesce(sum(CASE WHEN refused THEN 1 ELSE 0 END), 0) AS refused
                  FROM retrieval_log
                 WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (kbId != null) {
            sql.append(" AND kb_id = ?");
            args.add(kbId);
        }
        if (from != null && !from.isBlank()) {
            sql.append(" AND created_at >= CAST(? AS TIMESTAMPTZ)");
            args.add(from);
        }
        if (to != null && !to.isBlank()) {
            sql.append(" AND created_at <= CAST(? AS TIMESTAMPTZ)");
            args.add(to);
        }
        Map<String, Object> row = jdbc.queryForMap(sql.toString(), args.toArray());
        long calls = number(row.get("calls")).longValue();
        long tokens = number(row.get("tokens")).longValue();
        long refused = number(row.get("refused")).longValue();
        double avgLatency = number(row.get("avg_latency")).doubleValue();
        double p95Latency = number(row.get("p95_latency")).doubleValue();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("calls", calls);
        result.put("tokens", tokens);
        result.put("avgLatencyMs", Math.round(avgLatency * 10.0) / 10.0);
        result.put("p95LatencyMs", Math.round(p95Latency * 10.0) / 10.0);
        result.put("refused", refused);
        result.put("refusalRate", calls == 0 ? 0 : Math.round(refused * 10000.0 / calls) / 10000.0);
        return ApiResponse.ok(result);
    }

    @GetMapping("/top-missed")
    public ApiResponse<List<Map<String, Object>>> topMissed(@RequestParam(required = false) Long kbId) {
        StringBuilder sql = new StringBuilder("""
                SELECT question, count(*) AS cnt
                  FROM retrieval_log
                 WHERE fused_hits = 0 OR refused = TRUE
                """);
        List<Object> args = new ArrayList<>();
        if (kbId != null) {
            sql.append(" AND kb_id = ?");
            args.add(kbId);
        }
        sql.append(" GROUP BY question ORDER BY cnt DESC LIMIT 10");
        return ApiResponse.ok(jdbc.queryForList(sql.toString(), args.toArray()));
    }

    private Number number(Object value) {
        return value instanceof Number n ? n : 0;
    }
}
