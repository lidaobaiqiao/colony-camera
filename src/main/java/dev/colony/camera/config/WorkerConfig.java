package dev.colony.camera.config;

import dev.colony.camera.worker.WorkerAttribute;
import net.minecraftforge.common.ForgeConfigSpec;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.EnumMap;
import java.util.Objects;

/** Server-side balance. Each attribute has its own coefficient and physical units. */
@ParametersAreNonnullByDefault
public final class WorkerConfig {
    @Nonnull private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    @Nonnull private static final EnumMap<WorkerAttribute, ForgeConfigSpec.DoubleValue> EFFECTS = new EnumMap<>(WorkerAttribute.class);
    @Nonnull private static final EnumMap<dev.colony.camera.worker.WorkerProfile.Profession, ForgeConfigSpec.DoubleValue> GROWTH = new EnumMap<>(dev.colony.camera.worker.WorkerProfile.Profession.class);
    @Nonnull private static final EnumMap<dev.colony.camera.worker.WorkerProfile.Profession, ForgeConfigSpec.DoubleValue> BASE_BONUS = new EnumMap<>(dev.colony.camera.worker.WorkerProfile.Profession.class);
    @Nonnull private static final EnumMap<WorkerAttribute, ForgeConfigSpec.DoubleValue> MORALE_SUCCESS = new EnumMap<>(WorkerAttribute.class), MORALE_FAILURE = new EnumMap<>(WorkerAttribute.class);
    @Nonnull public static final ForgeConfigSpec.IntValue PROFESSION_TICKS = integer("learning.professionTicks", 2400, 200, 1680000);
    @Nonnull public static final ForgeConfigSpec.IntValue POPULATION_LIMIT = integer("life.populationLimit", 32, 1, 128);
    @Nonnull public static final ForgeConfigSpec.DoubleValue HUNGER_PER_TICK = number("life.hungerPerTick", 20.0 / 48000, 0, 0.01);
    @Nonnull public static final ForgeConfigSpec.DoubleValue FATIGUE_PER_TICK = number("life.fatiguePerTick", 1.0 / 48000, 0, 0.001);
    @Nonnull public static final ForgeConfigSpec.DoubleValue STRESS_PER_DAY = number("life.workStressPerDay", 12, 0, 100);
    @Nonnull public static final ForgeConfigSpec.DoubleValue WATER_PER_TICK = number("life.waterPerTick", 100.0 / 72000, 0, 0.1);
    @Nonnull public static final ForgeConfigSpec.DoubleValue SOCIAL_PER_TICK = number("life.socialPerTick", 20.0 / 24000, 0, 0.1);
    @Nonnull public static final ForgeConfigSpec.DoubleValue THIRST_STRESS = number("life.thirstStressPerDay", 8, 0, 100);
    @Nonnull public static final ForgeConfigSpec.DoubleValue LONELY_STRESS = number("life.lonelyStressPerDay", 4, 0, 100);
    @Nonnull public static final ForgeConfigSpec.DoubleValue THERMAL_STRESS = number("life.thermalStressPerDegreeDay", 0.5, 0, 10);
    @Nonnull public static final ForgeConfigSpec.DoubleValue RECOVERY_PER_TICK = number("life.recoveryPerTick", 0.02, 0.001, 1);
    @Nonnull public static final ForgeConfigSpec.DoubleValue LOYALTY_IMPROVEMENT = number("loyalty.improvementReward", 2, 0, 20);
    @Nonnull public static final ForgeConfigSpec.DoubleValue LOYALTY_CARE = number("loyalty.actualCareRewardPerDay", 3, 0, 20);
    @Nonnull public static final ForgeConfigSpec.DoubleValue LOYALTY_CRISIS = number("loyalty.crisisLossPerSecond", 0.01, 0, 10);
    @Nonnull public static final ForgeConfigSpec.DoubleValue ORDER_LOYALTY = number("orders.executionLoyalty", 50, 1, 10000);
    @Nonnull public static final ForgeConfigSpec.DoubleValue SACRIFICE_LOYALTY = number("orders.sacrificeLoyalty", 80, 1, 10000);
    @Nonnull public static final ForgeConfigSpec.DoubleValue REFUSAL_STRESS = number("orders.refusalStress", 5, 0.01, 100);
    @Nonnull public static final ForgeConfigSpec.DoubleValue EXECUTION_STRESS = number("orders.witnessStress", 5, 0, 100);
    @Nonnull public static final ForgeConfigSpec.IntValue MORALE_TICKS = integer("orders.moraleTicks", 1200, 20, 24000);
    @Nonnull public static final ForgeConfigSpec SPEC;
    static {
        // Coefficients are separate, even when two initial placeholder values happen to match.
        for (WorkerAttribute attribute : WorkerAttribute.values()) EFFECTS.put(Objects.requireNonNull(attribute),
                number("effects." + attribute.name().toLowerCase(java.util.Locale.ROOT), defaultCoefficient(attribute), 0, 100));
        for (var profession : dev.colony.camera.worker.WorkerProfile.Profession.values()) if (profession != dev.colony.camera.worker.WorkerProfile.Profession.NONE) {
            GROWTH.put(Objects.requireNonNull(profession), number("learning." + profession.name().toLowerCase(java.util.Locale.ROOT) + "Growth", 2, 1, 20));
            BASE_BONUS.put(profession, number("professions." + profession.name().toLowerCase(java.util.Locale.ROOT) + "BaseBonus", profession == dev.colony.camera.worker.WorkerProfile.Profession.BUILDER ? 1.12 : 1.05, 1, 2));
        }
        for (var attribute : new WorkerAttribute[]{WorkerAttribute.MINING, WorkerAttribute.BUILDING, WorkerAttribute.MANUFACTURING}) {
            String key = attribute.name().toLowerCase(java.util.Locale.ROOT);
            double success = attribute == WorkerAttribute.MINING ? 1.05 : attribute == WorkerAttribute.BUILDING ? 1.03 : 1.04;
            double failure = attribute == WorkerAttribute.MINING ? 1.02 : attribute == WorkerAttribute.BUILDING ? 1.01 : 1.015;
            MORALE_SUCCESS.put(attribute, number("orders.morale." + key + "Success", success, 1, 2));
            MORALE_FAILURE.put(attribute, number("orders.morale." + key + "Failure", failure, 1, 2));
        }
        SPEC = Objects.requireNonNull(BUILDER.build());
    }
    private WorkerConfig() {}
    public static double coefficient(WorkerAttribute attribute) { return Objects.requireNonNull(EFFECTS.get(attribute)).get(); }
    public static double growth(dev.colony.camera.worker.WorkerState state, WorkerAttribute attribute) {
        double rate = 1;
        for (var entry : new dev.colony.camera.worker.WorkerProfile.Profession[]{state.primary(), state.secondary()}) {
            var profession = Objects.requireNonNull(entry);
            if (profession == dev.colony.camera.worker.WorkerProfile.Profession.NONE) continue;
            if (dev.colony.camera.worker.WorkerProfile.specializes(profession, attribute))
                rate = Math.max(rate, Objects.requireNonNull(GROWTH.get(profession)).get());
        }
        return rate;
    }
    public static double morale(dev.colony.camera.worker.WorkerState state, WorkerAttribute attribute, long time) {
        if (!state.moraleActive(time)) return 1;
        var mapping = state.moraleSuccess() ? MORALE_SUCCESS : MORALE_FAILURE;
        var value = mapping.get(attribute); return value == null ? 1 : value.get();
    }
    public static double baseBonus(dev.colony.camera.worker.WorkerState state, WorkerAttribute attribute) {
        double bonus = 1;
        for (var entry : new dev.colony.camera.worker.WorkerProfile.Profession[]{state.primary(), state.secondary()}) {
            var profession = Objects.requireNonNull(entry);
            if (dev.colony.camera.worker.WorkerProfile.specializes(profession, attribute)) bonus = Math.max(bonus, Objects.requireNonNull(BASE_BONUS.get(profession)).get());
        }
        return bonus;
    }
    private static double defaultCoefficient(WorkerAttribute attribute) {
        return switch (attribute) {
            case HEALTH -> 1; case HUNGER, DEFENSE, PERCEPTION -> 0.5; case MOVEMENT -> 0.003; case ATTACK -> 0.25;
            case MOBILITY, MINING, OPERATION -> 0.01; case ENERGY, MANUFACTURING, ENVIRONMENT_RESISTANCE -> 0.02;
            case BUILDING -> 0.015; case HAULING, RESILIENCE -> 0.025; case CAPACITY -> 8; case LEARNING -> 0.03; case PRECISION -> 0.1;
        };
    }
    @Nonnull private static ForgeConfigSpec.DoubleValue number(String key, double value, double min, double max) { return Objects.requireNonNull(BUILDER.defineInRange(key, value, min, max)); }
    @Nonnull private static ForgeConfigSpec.IntValue integer(String key, int value, int min, int max) { return Objects.requireNonNull(BUILDER.defineInRange(key, value, min, max)); }
}
