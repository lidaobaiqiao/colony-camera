package dev.colony.camera.worker;

import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.math.BigInteger;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import static dev.colony.camera.worker.WorkerProfile.*;

/** Pure state and growth. Save validation and physical actions live in adapters. */
@ParametersAreNonnullByDefault
public final class WorkerState {
    public static final long TICKS_PER_POINT = 7L * 24000;
    @Nonnull private static final BigInteger ONE = Objects.requireNonNull(BigInteger.ONE);
    @Nonnull private static final BigInteger ZERO = Objects.requireNonNull(BigInteger.ZERO);
    @Nonnull private static final BigInteger GROWTH_UNIT = Objects.requireNonNull(BigInteger.valueOf(TICKS_PER_POINT * 1000));
    @Nonnull private final EnumMap<WorkerAttribute, BigInteger> births = new EnumMap<>(WorkerAttribute.class);
    @Nonnull private final EnumMap<WorkerAttribute, BigInteger> growth = new EnumMap<>(WorkerAttribute.class);
    @Nonnull private final EnumMap<StressCause, Double> pressure = new EnumMap<>(StressCause.class);
    @Nonnull private Interest interest = Interest.NONE;
    @Nonnull private Temperament temperament = Temperament.NONE;
    @Nonnull private Profession primary = Profession.NONE, secondary = Profession.NONE;
    private boolean striking;
    private double loyalty, hunger = 20, rest = 1, foodQuality = 1, lastQuality = 2;
    private long continuousWork;
    @Nonnull private BigInteger minedBlocks = ZERO;
    private long moraleUntil;
    private boolean moraleSuccess;
    public WorkerState() {
        for (WorkerAttribute value : WorkerAttribute.values()) { births.put(Objects.requireNonNull(value), ONE); growth.put(value, ZERO); }
    }
    public void birth(Interest newInterest, Temperament newTemperament) {
        if (!compatible(newInterest, newTemperament)) throw new IllegalArgumentException("profile");
        interest = newInterest; temperament = newTemperament;
        for (WorkerAttribute value : WorkerAttribute.values()) births.put(Objects.requireNonNull(value), Objects.requireNonNull(BigInteger.valueOf(1 + birthBonus(newInterest, value))));
    }
    @Nonnull public BigInteger points(WorkerAttribute attribute) { return Objects.requireNonNull(Objects.requireNonNull(births.get(attribute)).add(Objects.requireNonNull(growth.get(attribute)).divide(GROWTH_UNIT))); }
    @Nonnull public BigInteger credit(WorkerAttribute attribute) { return Objects.requireNonNull(growth.get(attribute)); }
    @Nonnull public BigInteger birthPoints(WorkerAttribute attribute) { return Objects.requireNonNull(births.get(attribute)); }
    public double progress(WorkerAttribute attribute) { return credit(attribute).remainder(GROWTH_UNIT).doubleValue() / GROWTH_UNIT.doubleValue(); }
    public void practice(WorkerAttribute attribute, long ticks, double professionMultiplier) {
        if (ticks < 0 || !Double.isFinite(professionMultiplier) || professionMultiplier < 0) throw new IllegalArgumentException("growth");
        double rate = professionFocus(attribute) ? professionMultiplier : 1;
        long perTick = Math.max(1, Math.round(rate * 1000));
        growth.put(attribute, credit(attribute).add(BigInteger.valueOf(ticks).multiply(BigInteger.valueOf(perTick))));
    }
    public void study(WorkerAttribute attribute, long ticks, double speed, double professionMultiplier) {
        if (ticks < 0 || !Double.isFinite(speed) || speed <= 0 || !Double.isFinite(professionMultiplier) || professionMultiplier < 0) throw new IllegalArgumentException("study");
        double rate = speed * (professionFocus(attribute) ? professionMultiplier : 1);
        long perTick = Math.max(1, Math.round(rate * 1000));
        growth.put(attribute, credit(attribute).add(BigInteger.valueOf(ticks).multiply(BigInteger.valueOf(perTick))));
    }
    public boolean professionFocus(WorkerAttribute attribute) {
        return specializes(primary, attribute) || specializes(secondary, attribute);
    }
    public boolean hasProfession(Profession value) { return primary == value || secondary == value; }
    public boolean hasAbility(Ability value) { return value != Ability.NONE && (ability(primary) == value || ability(secondary) == value); }
    public void profession(Profession profession, boolean additional) {
        if (profession == Profession.NONE || profession == primary || profession == secondary) throw new IllegalArgumentException("profession");
        if (additional) {
            if (primary == Profession.NONE || secondary != Profession.NONE) throw new IllegalArgumentException("profession");
            secondary = profession;
        } else { primary = profession; if (secondary == profession) secondary = Profession.NONE; }
    }
    @Nonnull public Interest interest() { return interest; }
    @Nonnull public Temperament temperament() { return temperament; }
    @Nonnull public Profession primary() { return primary; }
    @Nonnull public Profession secondary() { return secondary; }
    public double trait(WorkerAttribute attribute) { return temperamentEffect(temperament, attribute); }
    public boolean suited(Profession profession) { return interested(interest, profession); }
    public void successfulMining() { minedBlocks = Objects.requireNonNull(minedBlocks.add(ONE)); }
    @Nonnull public BigInteger minedBlocks() { return minedBlocks; }
    public void addPressure(StressCause source, double amount) {
        if (!Double.isFinite(amount) || amount < 0) throw new IllegalArgumentException("pressure");
        pressure.put(source, Math.min(1e12, Objects.requireNonNull(pressure.getOrDefault(source, 0.0)) + amount));
    }
    public double stress(double tolerance) { return Math.min(100, rawPressure() / Math.max(1, tolerance)); }
    public double rawPressure() { return pressure.values().stream().mapToDouble(value -> Objects.requireNonNull(value)).sum(); }
    @Nonnull public Map<StressCause, Double> pressureSources() { return Objects.requireNonNull(Map.copyOf(pressure)); }
    public void recover(double amount) {
        if (!Double.isFinite(amount) || amount < 0) throw new IllegalArgumentException("recovery");
        double total = rawPressure(), factor = total <= amount || total == 0 ? 0 : (total - amount) / total;
        pressure.replaceAll((key, value) -> Objects.requireNonNull(value) * factor); pressure.values().removeIf(value -> Objects.requireNonNull(value) < 0.001);
    }
    public void mood(double tolerance, double crisisLoss) {
        if (!Double.isFinite(crisisLoss) || crisisLoss < 0) throw new IllegalArgumentException("loyalty");
        if (stress(tolerance) >= 100) { striking = true; loyalty = Math.max(0, loyalty - crisisLoss); }
        else if (striking && stress(tolerance) <= 40) striking = false;
    }
    public boolean striking() { return striking; }
    public double loyalty() { return loyalty; }
    public void receivedCare(double reward) {
        if (!Double.isFinite(reward) || reward < 0) throw new IllegalArgumentException("care"); loyalty = Math.min(1e12, loyalty + reward);
    }
    public void lifeImproved(double quality, double reward) {
        if (!Double.isFinite(quality) || quality < 0 || !Double.isFinite(reward) || reward < 0) throw new IllegalArgumentException("quality");
        if (quality > lastQuality) loyalty += (quality - lastQuality) * reward;
        // Repeating deprivation and recovery must not generate an endless loyalty reward.
        lastQuality = Math.max(lastQuality, quality);
    }
    public double hunger() { return hunger; }
    public double rest() { return rest; }
    public double foodQuality() { return foodQuality; }
    public long continuousWork() { return continuousWork; }
    public void metabolism(long ticks, boolean working, double hungerUse, double restUse) {
        if (ticks < 0 || hungerUse < 0 || restUse < 0) throw new IllegalArgumentException("metabolism");
        hunger = Math.max(0, hunger - hungerUse * ticks * (working ? 1.25 : 1));
        if (working) { continuousWork = Math.min(Long.MAX_VALUE - ticks, continuousWork) + ticks; rest = Math.max(0, rest - restUse * ticks); }
        else continuousWork = Math.max(0, continuousWork - ticks * 3);
    }
    public void eat(double nutrition, double maximum, double quality) { hunger = Math.min(maximum, hunger + nutrition); foodQuality = quality; }
    public void rest(long ticks, boolean atHome) { rest(ticks, atHome, 1); }
    public void rest(long ticks, boolean atHome, double recovery) {
        if (ticks < 0 || !Double.isFinite(recovery) || recovery <= 0) throw new IllegalArgumentException("rest");
        rest = Math.min(1, rest + ticks * (atHome ? 0.0005 : 0.0001) * recovery);
    }
    public void morale(long until, boolean success) { moraleUntil = until; moraleSuccess = success; }
    public boolean moraleActive(long time) { return moraleUntil > time; }
    public boolean moraleSuccess() { return moraleSuccess; }
    public long moraleUntil() { return moraleUntil; }
    public void restoreAttribute(WorkerAttribute attribute, BigInteger birth, BigInteger practice) {
        if (birth.signum() <= 0 || practice.signum() < 0) throw new IllegalArgumentException("attribute");
        births.put(attribute, birth); growth.put(attribute, practice);
    }
    public void restoreIdentity(Interest identity, Temperament trait, Profession first, Profession second) {
        if (!compatible(identity, trait) || first == Profession.NONE && second != Profession.NONE || first != Profession.NONE && first == second) throw new IllegalArgumentException("identity");
        interest = identity; temperament = trait; primary = first; secondary = second;
    }
    public void restoreLife(double food, double sleep, double quality, double previousQuality, double trust, boolean strike,
            long continuous, BigInteger mined, long morale, boolean success) {
        if (!Double.isFinite(food + sleep + quality + previousQuality + trust) || food < 0 || sleep < 0 || sleep > 1 || quality < 0
                || previousQuality < 0 || trust < 0 || continuous < 0 || mined.signum() < 0) throw new IllegalArgumentException("life");
        hunger = food; rest = sleep; foodQuality = quality; lastQuality = previousQuality; loyalty = trust; striking = strike;
        continuousWork = continuous; minedBlocks = mined; moraleUntil = morale; moraleSuccess = success;
    }
    public double lastQuality() { return lastQuality; }
    @Nonnull public static WorkerState migrate(int legacyXp) {
        WorkerState state = new WorkerState(); int xp = Math.max(0, legacyXp), points = 1;
        for (int threshold : new int[]{16, 48, 112, 240}) if (xp >= threshold) points++;
        // Preserve the old miner's attained ability; the old XP curve exists only in this migration.
        state.restoreAttribute(WorkerAttribute.MINING, Objects.requireNonNull(BigInteger.valueOf(points)), ZERO);
        state.minedBlocks = Objects.requireNonNull(BigInteger.valueOf(xp)); return state;
    }
}
