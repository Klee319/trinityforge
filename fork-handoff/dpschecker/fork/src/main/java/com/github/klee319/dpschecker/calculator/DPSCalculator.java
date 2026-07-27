package com.github.klee319.dpschecker.calculator;

import com.github.klee319.dpschecker.dummy.DamageRecord;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;

import java.util.*;
import java.util.stream.Collectors;

public final class DPSCalculator {

    private DPSCalculator() {}

    public static double calculateDPS(List<DamageRecord> records, int windowSeconds) {
        if (records.isEmpty() || windowSeconds <= 0) return 0.0;

        long now = System.currentTimeMillis();
        long windowMs = windowSeconds * 1000L;

        double totalDamage = records.stream()
                .filter(r -> (now - r.timestamp()) <= windowMs)
                .mapToDouble(DamageRecord::finalDamage)
                .sum();

        return totalDamage / windowSeconds;
    }

    public static double calculateAverage(List<DamageRecord> records) {
        if (records.isEmpty()) return 0.0;
        return records.stream()
                .mapToDouble(DamageRecord::finalDamage)
                .average()
                .orElse(0.0);
    }

    public static double calculateMax(List<DamageRecord> records) {
        return records.stream()
                .mapToDouble(DamageRecord::finalDamage)
                .max()
                .orElse(0.0);
    }

    public static double calculateMin(List<DamageRecord> records) {
        return records.stream()
                .mapToDouble(DamageRecord::finalDamage)
                .min()
                .orElse(0.0);
    }

    public static double calculateTotal(List<DamageRecord> records) {
        return records.stream()
                .mapToDouble(DamageRecord::finalDamage)
                .sum();
    }

    public static Map<DamageCause, List<DamageRecord>> groupByType(List<DamageRecord> records) {
        return records.stream()
                .collect(Collectors.groupingBy(DamageRecord::cause));
    }

    public static DamageStats buildStatsFromRecords(List<DamageRecord> filtered, DamageCause cause, int windowSeconds) {
        return new DamageStats(
                cause,
                calculateDPS(filtered, windowSeconds),
                calculateAverage(filtered),
                calculateMax(filtered),
                calculateMin(filtered),
                calculateTotal(filtered),
                filtered.size()
        );
    }

    public static DamageStats buildTotalStats(List<DamageRecord> records, int windowSeconds) {
        return new DamageStats(
                null,
                calculateDPS(records, windowSeconds),
                calculateAverage(records),
                calculateMax(records),
                calculateMin(records),
                calculateTotal(records),
                records.size()
        );
    }

    public static List<DamageStats> buildAllStats(List<DamageRecord> records, int windowSeconds) {
        Map<DamageCause, List<DamageRecord>> grouped = groupByType(records);
        List<DamageStats> statsList = new ArrayList<>();

        for (Map.Entry<DamageCause, List<DamageRecord>> entry : grouped.entrySet()) {
            // Use already-grouped records directly instead of re-filtering
            statsList.add(buildStatsFromRecords(entry.getValue(), entry.getKey(), windowSeconds));
        }

        statsList.sort(Comparator.comparingDouble(DamageStats::total).reversed());
        return statsList;
    }
}
