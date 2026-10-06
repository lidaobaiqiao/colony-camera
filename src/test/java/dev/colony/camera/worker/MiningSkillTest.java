package dev.colony.camera.worker;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class MiningSkillTest {
    @Test void thresholdCrossingsKeepToolsIndependentOfExperience() {
        assertEquals(1, MiningSkill.level(0));
        assertEquals(1, MiningSkill.level(15));
        assertEquals(2, MiningSkill.level(16));
        assertEquals(2, MiningSkill.level(47));
        assertEquals(3, MiningSkill.level(48));
        assertEquals(4, MiningSkill.level(112));
        assertEquals(5, MiningSkill.level(240));
    }

    @Test void malformedOrOverflowingExperienceCannotWrapAround() {
        assertEquals(0, MiningSkill.gain(-8, -4));
        assertEquals(16, MiningSkill.gain(15, 1));
        assertEquals(MiningSkill.MAX_EXPERIENCE, MiningSkill.gain(Integer.MAX_VALUE, Integer.MAX_VALUE));
    }
}
