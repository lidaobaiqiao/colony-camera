package dev.colony.camera.worker;

import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Life needs and actual shared time, independent of professional attributes and player loyalty. */
@ParametersAreNonnullByDefault
public final class WorkerNeeds {
    public static final int MAX_RELATIONS = 32;
    public record Relation(long togetherTicks) {
        public Relation { if (togetherTicks < 0 || togetherTicks > 200000) throw new IllegalArgumentException("relation"); }
        public int affinity() { return (int) Math.min(100, togetherTicks / 2000); }
    }
    private double hydration = 100, social = 80, waterQuality, temperature = 20;
    private long drinks, meals, lastCareDay = -1;
    @Nonnull private final Map<UUID, Relation> relations = new LinkedHashMap<>();
    public double hydration() { return hydration; }
    public double social() { return social; }
    public double waterQuality() { return waterQuality; }
    public double temperature() { return temperature; }
    public long drinks() { return drinks; }
    public long meals() { return meals; }
    public long lastCareDay() { return lastCareDay; }
    /** The caller must have supplied a real meal, water and a valid home before claiming this event. */
    public boolean claimCare(long day) {
        if (day < 0 || day <= lastCareDay) return false; lastCareDay = day; return true;
    }
    @Nonnull public Map<UUID, Relation> relations() { return Objects.requireNonNull(Map.copyOf(relations)); }
    public void elapsed(long ticks, boolean working, double waterUse, double socialUse) {
        if (ticks < 0 || !Double.isFinite(waterUse + socialUse) || waterUse < 0 || socialUse < 0) throw new IllegalArgumentException("needs");
        hydration = Math.max(0, hydration - ticks * waterUse * (working ? 1.25 : 1));
        social = Math.max(0, social - ticks * socialUse);
    }
    public void drink(double quality) {
        if (!Double.isFinite(quality) || quality < 0 || quality > 2) throw new IllegalArgumentException("water");
        hydration = 100; waterQuality = quality; drinks = Math.min(Long.MAX_VALUE - 1, drinks) + 1;
    }
    public void meal() { meals = Math.min(Long.MAX_VALUE - 1, meals) + 1; }
    public void temperature(double value) {
        if (!Double.isFinite(value) || value < -100 || value > 200) throw new IllegalArgumentException("temperature");
        temperature = value;
    }
    public boolean canConverse(UUID person) { return relations.containsKey(person) || relations.size() < MAX_RELATIONS; }
    public void converse(UUID person, long ticks) {
        if (ticks < 1 || ticks > 20) throw new IllegalArgumentException("conversation");
        Relation previous = relations.get(person);
        if (!canConverse(person)) return;
        long old = previous == null ? 0 : previous.togetherTicks();
        relations.put(person, new Relation(Math.min(200000, old + ticks)));
        social = Math.min(100, social + ticks * 0.15);
    }
    public double thermalDiscomfort(double resistance) {
        double margin = Math.min(15, Math.max(0, resistance - 1) * 3);
        return Math.max(0, 8 - margin - temperature) + Math.max(0, temperature - 32 - margin);
    }
    public double workSpeed(double resistance) {
        return (hydration < 15 ? 0.8 : 1) * Math.max(0.75, 1 - thermalDiscomfort(resistance) / 80);
    }
    public void restore(double water, double company, double quality, double heat, long drunk, long eaten, long careDay, Map<UUID, Relation> friends) {
        if (!Double.isFinite(water + company + quality + heat) || water < 0 || water > 100 || company < 0 || company > 100
                || quality < 0 || quality > 2 || heat < -100 || heat > 200 || drunk < 0 || eaten < 0 || careDay < -1 || friends.size() > MAX_RELATIONS)
            throw new IllegalArgumentException("needs");
        hydration = water; social = company; waterQuality = quality; temperature = heat; drinks = drunk; meals = eaten; lastCareDay = careDay;
        relations.clear(); relations.putAll(friends);
    }
}
