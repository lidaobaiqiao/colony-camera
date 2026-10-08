package dev.colony.camera.worker;

import dev.colony.camera.config.WorkerConfig;
import java.math.BigInteger;
import java.util.Objects;
import javax.annotation.ParametersAreNonnullByDefault;

/** Independent mappings: hit points, food, armour, movement, time and inventory are different units. */
@ParametersAreNonnullByDefault
public final class WorkerEffects {
    private WorkerEffects() {}
    private static double extra(WorkerState state, WorkerAttribute attribute) {
        double points = state.points(attribute).subtract(BigInteger.ONE).doubleValue();
        return Math.min(1e12, points);
    }
    public static double health(WorkerState state) { return Math.min(1024, (20 + Math.sqrt(extra(state, WorkerAttribute.HEALTH)) * WorkerConfig.coefficient(WorkerAttribute.HEALTH)) * WorkerConfig.baseBonus(state, WorkerAttribute.HEALTH)); }
    public static double hunger(WorkerState state) { return (20 + Math.sqrt(extra(state, WorkerAttribute.HUNGER)) * WorkerConfig.coefficient(WorkerAttribute.HUNGER)) * WorkerConfig.baseBonus(state, WorkerAttribute.HUNGER); }
    public static double defense(WorkerState state) { return Math.min(30, Math.sqrt(extra(state, WorkerAttribute.DEFENSE)) * WorkerConfig.coefficient(WorkerAttribute.DEFENSE) + (WorkerConfig.baseBonus(state, WorkerAttribute.DEFENSE) - 1) * 20); }
    public static double movement(WorkerState state) { return Math.min(1, (0.3 + Math.log1p(extra(state, WorkerAttribute.MOVEMENT)) * WorkerConfig.coefficient(WorkerAttribute.MOVEMENT)) * state.trait(WorkerAttribute.MOVEMENT)); }
    public static double attack(WorkerState state) { return Math.min(2048, (2 + extra(state, WorkerAttribute.ATTACK) * WorkerConfig.coefficient(WorkerAttribute.ATTACK)) * WorkerConfig.baseBonus(state, WorkerAttribute.ATTACK)); }
    public static double mining(WorkerState state, long time) {
        return Math.min(10000, (1 + extra(state, WorkerAttribute.MINING) * WorkerConfig.coefficient(WorkerAttribute.MINING))
                * state.trait(WorkerAttribute.MINING) * WorkerConfig.baseBonus(state, WorkerAttribute.MINING) * WorkerConfig.morale(state, WorkerAttribute.MINING, time));
    }
    public static int buildTicks(WorkerState state, long time) {
        double speed = (1 + Math.sqrt(extra(state, WorkerAttribute.BUILDING)) * WorkerConfig.coefficient(WorkerAttribute.BUILDING))
                * state.trait(WorkerAttribute.BUILDING) * WorkerConfig.baseBonus(state, WorkerAttribute.BUILDING) * WorkerConfig.morale(state, WorkerAttribute.BUILDING, time);
        return Math.max(1, (int) Math.ceil(10 / speed));
    }
    public static int haulTicks(WorkerState state) { return Math.max(1, (int) Math.ceil(5 / ((1 + Math.log1p(extra(state, WorkerAttribute.HAULING)) * WorkerConfig.coefficient(WorkerAttribute.HAULING)) * WorkerConfig.baseBonus(state, WorkerAttribute.HAULING)))); }
    public static int capacity(WorkerState state) { return (int) Math.min(Integer.MAX_VALUE, 16 + extra(state, WorkerAttribute.CAPACITY) * WorkerConfig.coefficient(WorkerAttribute.CAPACITY)); }
    public static double tolerance(WorkerState state) { return (1 + Math.sqrt(extra(state, WorkerAttribute.RESILIENCE)) * WorkerConfig.coefficient(WorkerAttribute.RESILIENCE)) * state.trait(WorkerAttribute.RESILIENCE) * WorkerConfig.baseBonus(state, WorkerAttribute.RESILIENCE); }
    public static double recovery(WorkerState state) { return WorkerConfig.RECOVERY_PER_TICK.get() * (1 + Math.log1p(extra(state, WorkerAttribute.RESILIENCE)) * WorkerConfig.coefficient(WorkerAttribute.RESILIENCE)) * state.trait(WorkerAttribute.RESILIENCE) * WorkerConfig.baseBonus(state, WorkerAttribute.RESILIENCE); }
    public static double manufacturing(WorkerState state, long time) {
        return Math.min(10000, (1 + Math.log1p(extra(state, WorkerAttribute.MANUFACTURING)) * WorkerConfig.coefficient(WorkerAttribute.MANUFACTURING))
                * state.trait(WorkerAttribute.MANUFACTURING) * WorkerConfig.baseBonus(state, WorkerAttribute.MANUFACTURING) * WorkerConfig.morale(state, WorkerAttribute.MANUFACTURING, time));
    }
    public static double perception(WorkerState state) { return Math.min(128, (32 + Math.sqrt(extra(state, WorkerAttribute.PERCEPTION)) * WorkerConfig.coefficient(WorkerAttribute.PERCEPTION)) * WorkerConfig.baseBonus(state, WorkerAttribute.PERCEPTION)); }
    public static int searchRange(WorkerState state) { return Math.min(96, (int) Math.floor(perception(state) * 0.75)); }
    public static int navigationRange(WorkerState state) { return Math.min(128, (int) Math.floor(perception(state) * 2)); }
    public static double mobility(WorkerState state) { return Math.min(1, (0.6 + Math.log1p(extra(state, WorkerAttribute.MOBILITY)) * WorkerConfig.coefficient(WorkerAttribute.MOBILITY)) * WorkerConfig.baseBonus(state, WorkerAttribute.MOBILITY)); }
    public static double climbing(WorkerState state) { return Math.min(2, mobility(state) / 0.6); }
    public static double energy(WorkerState state) { return Math.min(10, (1 + Math.log1p(extra(state, WorkerAttribute.ENERGY)) * WorkerConfig.coefficient(WorkerAttribute.ENERGY)) * WorkerConfig.baseBonus(state, WorkerAttribute.ENERGY)); }
    public static double learning(WorkerState state) { return Math.min(10000, (1 + Math.sqrt(extra(state, WorkerAttribute.LEARNING)) * WorkerConfig.coefficient(WorkerAttribute.LEARNING)) * WorkerConfig.baseBonus(state, WorkerAttribute.LEARNING)); }
    public static double environmentResistance(WorkerState state) { return Math.min(4, (1 + Math.sqrt(extra(state, WorkerAttribute.ENVIRONMENT_RESISTANCE)) * WorkerConfig.coefficient(WorkerAttribute.ENVIRONMENT_RESISTANCE)) * WorkerConfig.baseBonus(state, WorkerAttribute.ENVIRONMENT_RESISTANCE)); }
    public static double precision(WorkerState state) { return Math.min(4, (1 + Math.sqrt(extra(state, WorkerAttribute.PRECISION)) * WorkerConfig.coefficient(WorkerAttribute.PRECISION)) * WorkerConfig.baseBonus(state, WorkerAttribute.PRECISION)); }
    public static double operation(WorkerState state) { return Math.min(10000, (1 + Math.log1p(extra(state, WorkerAttribute.OPERATION)) * WorkerConfig.coefficient(WorkerAttribute.OPERATION)) * WorkerConfig.baseBonus(state, WorkerAttribute.OPERATION)); }
    /** Both the observer's detection range and a worker target's conspicuousness matter; walls still block sight. */
    public static double witnessRange(WorkerState observer, net.minecraft.world.entity.LivingEntity target) {
        double visible = target instanceof WorkerEntity worker ? perception(worker.state()) : 32;
        return (perception(observer) + visible) / 2;
    }
    /** A deterministic quality tier becomes a real vanilla durability enchantment, preserving recipe enchantments. */
    public static void finishCraft(WorkerState state, net.minecraft.world.item.ItemStack output) {
        if (!output.isDamageableItem()) return;
        int tier = Math.min(3, (int) Math.floor((precision(state) - 1) * 10 + 1e-9) + (state.hasAbility(WorkerProfile.Ability.FINE_CRAFT) ? 1 : 0));
        if (tier <= 0) return;
        var enchantments = Objects.requireNonNull(net.minecraft.world.item.enchantment.EnchantmentHelper.getEnchantments(output));
        var durability = Objects.requireNonNull(net.minecraft.world.item.enchantment.Enchantments.UNBREAKING);
        enchantments.put(durability, Math.max(tier, Objects.requireNonNull(enchantments.getOrDefault(durability, 0))));
        net.minecraft.world.item.enchantment.EnchantmentHelper.setEnchantments(enchantments, output);
        output.getOrCreateTag().putInt("ColonyQuality", tier);
    }
}
