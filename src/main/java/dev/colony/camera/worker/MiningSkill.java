package dev.colony.camera.worker;

/** Experience is independent of equipment: tools still have to be physically available. */
public final class MiningSkill {
    public static final int MAX_EXPERIENCE = 1_000_000;
    private static final int[] THRESHOLDS = {0, 16, 48, 112, 240};
    private MiningSkill() {}

    public static int level(int experience) {
        int level = 1;
        for (int index = 1; index < THRESHOLDS.length; index++) {
            if (experience < THRESHOLDS[index]) break;
            level = index + 1;
        }
        return level;
    }

    public static int gain(int experience, int amount) {
        return (int) Math.min(MAX_EXPERIENCE, (long) Math.max(0, experience) + Math.max(0, amount));
    }
}
