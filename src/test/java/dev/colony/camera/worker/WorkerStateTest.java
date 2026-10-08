package dev.colony.camera.worker;

import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.Objects;
import static org.junit.jupiter.api.Assertions.*;
import static dev.colony.camera.worker.WorkerProfile.*;

final class WorkerStateTest {
    @Test void allEighteenStartAtOneAndAWeekOfMatchingWorkGrowsOnlyThatAttribute() {
        WorkerState state = new WorkerState();
        assertEquals(18, WorkerAttribute.values().length);
        for (WorkerAttribute attribute : WorkerAttribute.values()) assertEquals(BigInteger.ONE, state.points(Objects.requireNonNull(attribute)));
        state.practice(WorkerAttribute.MINING, WorkerState.TICKS_PER_POINT - 1, 2);
        assertEquals(BigInteger.ONE, state.points(WorkerAttribute.MINING));
        state.practice(WorkerAttribute.MINING, 1, 2);
        assertEquals(BigInteger.TWO, state.points(WorkerAttribute.MINING));
        assertEquals(BigInteger.ONE, state.points(WorkerAttribute.BUILDING));
    }
    @Test void growthContinuesBeyondIntegerLimitsWithoutOverflow() {
        WorkerState state = new WorkerState(); BigInteger huge = new BigInteger("1000000000000000000000000");
        state.restoreAttribute(WorkerAttribute.MINING, huge, Objects.requireNonNull(BigInteger.ZERO));
        state.practice(WorkerAttribute.MINING, WorkerState.TICKS_PER_POINT, 1);
        assertEquals(huge.add(BigInteger.ONE), state.points(WorkerAttribute.MINING));
    }
    @Test void professionChangesSlopeAndSwitchingPreservesBothKindsOfGrowth() {
        WorkerState state = new WorkerState(); state.profession(Profession.MINER, false);
        state.practice(WorkerAttribute.MINING, WorkerState.TICKS_PER_POINT / 2, 2);
        state.practice(WorkerAttribute.BUILDING, WorkerState.TICKS_PER_POINT / 2, 2);
        assertEquals(BigInteger.TWO, state.points(WorkerAttribute.MINING)); assertEquals(BigInteger.ONE, state.points(WorkerAttribute.BUILDING));
        state.profession(Profession.BUILDER, false); state.practice(WorkerAttribute.BUILDING, WorkerState.TICKS_PER_POINT / 4, 2);
        assertEquals(BigInteger.TWO, state.points(WorkerAttribute.BUILDING)); assertEquals(BigInteger.TWO, state.points(WorkerAttribute.MINING));
    }
    @Test void birthBonusDoesNotChangeGrowthAndTemperamentNeverChangesPoints() {
        WorkerState plain = new WorkerState(), scientist = new WorkerState(); scientist.birth(Interest.SCIENTIST, Temperament.STEADY);
        plain.practice(WorkerAttribute.MANUFACTURING, 1000, 2); scientist.practice(WorkerAttribute.MANUFACTURING, 1000, 2);
        assertEquals(plain.credit(WorkerAttribute.MANUFACTURING), scientist.credit(WorkerAttribute.MANUFACTURING));
        assertEquals(BigInteger.TWO, scientist.points(WorkerAttribute.RESILIENCE));
        assertEquals(1.05, scientist.trait(WorkerAttribute.RESILIENCE));
        assertThrows(IllegalArgumentException.class, () -> scientist.birth(Interest.SCIENTIST, Temperament.IMPATIENT));
    }
    @Test void legacySkillCurveExistsOnlyAsMigrationAndNewGrowthDoesNotUseMinedCount() {
        WorkerState migrated = WorkerState.migrate(48);
        assertEquals(BigInteger.valueOf(3), migrated.points(WorkerAttribute.MINING));
        assertEquals(BigInteger.valueOf(48), migrated.minedBlocks());
        migrated.successfulMining(); assertEquals(BigInteger.valueOf(49), migrated.minedBlocks());
        assertEquals(BigInteger.valueOf(3), migrated.points(WorkerAttribute.MINING));
    }
    @Test void schoolIsASecondMatchingGrowthPathAndCannotGrantAnotherAttribute() {
        WorkerState state = new WorkerState(); state.study(WorkerAttribute.MANUFACTURING, WorkerState.TICKS_PER_POINT / 2, 2, 1);
        assertEquals(BigInteger.TWO, state.points(WorkerAttribute.MANUFACTURING)); assertEquals(BigInteger.ONE, state.points(WorkerAttribute.MINING));
    }
    @Test void stressHasVisibleSourcesToleranceAndRecoverableStrikeHysteresis() {
        WorkerState state = new WorkerState(); state.addPressure(StressCause.OVERWORK, 75); state.addPressure(StressCause.HUNGER, 30);
        assertEquals(2, state.pressureSources().size()); assertEquals(52.5, state.stress(2));
        state.mood(1, 1); assertTrue(state.striking());
        state.recover(10); state.mood(1, 1); assertTrue(state.striking());
        state.recover(70); state.mood(1, 1); assertFalse(state.striking());
    }
    @Test void LoyaltyGrowsOnlyWithImprovementAndFallsOnlyAtFullStress() {
        WorkerState state = new WorkerState(); assertEquals(0, state.loyalty());
        state.lifeImproved(2, 2); assertEquals(0, state.loyalty());
        state.lifeImproved(3, 2); assertEquals(2, state.loyalty());
        state.lifeImproved(3, 2); state.lifeImproved(2.5, 2); assertEquals(2, state.loyalty());
        state.addPressure(StressCause.HUNGER, 99); state.mood(1, 1); assertEquals(2, state.loyalty());
        state.addPressure(StressCause.OVERWORK, 1); state.mood(1, 1); assertEquals(1, state.loyalty());
    }
    @Test void invalidRestoreIsRejectedBeforeChangingAttribute() {
        WorkerState state = new WorkerState();
        assertThrows(IllegalArgumentException.class, () -> state.restoreAttribute(WorkerAttribute.MINING, Objects.requireNonNull(BigInteger.ZERO), Objects.requireNonNull(BigInteger.ZERO)));
        assertThrows(IllegalArgumentException.class, () -> state.restoreAttribute(WorkerAttribute.MINING, Objects.requireNonNull(BigInteger.ONE), Objects.requireNonNull(BigInteger.valueOf(-1))));
        assertEquals(BigInteger.ONE, state.points(WorkerAttribute.MINING));
    }
    @Test void fractionalLearningAccumulatesWithoutPerTickRoundingInflation() {
        WorkerState split = new WorkerState(), together = new WorkerState();
        split.study(WorkerAttribute.LEARNING, 1, 0.5, 1); split.study(WorkerAttribute.LEARNING, 1, 0.5, 1);
        together.study(WorkerAttribute.LEARNING, 2, 0.5, 1);
        assertEquals(together.credit(WorkerAttribute.LEARNING), split.credit(WorkerAttribute.LEARNING));
        assertEquals(BigInteger.valueOf(1000), split.credit(WorkerAttribute.LEARNING));
    }
    @Test void professionFrameworkRoutesUniqueAbilitiesAcrossSixLayersWithoutGrowthLocks() {
        var layers = java.util.EnumSet.noneOf(ProfessionLayer.class); var abilities = java.util.EnumSet.noneOf(Ability.class);
        for (Profession entry : Profession.values()) {
            Profession profession = Objects.requireNonNull(entry); if (profession == Profession.NONE) continue;
            layers.add(layer(profession)); assertTrue(abilities.add(ability(profession)));
            WorkerState worker = new WorkerState(); worker.profession(profession, false);
            WorkerAttribute focus = focus(profession); worker.practice(focus, WorkerState.TICKS_PER_POINT / 2, 2);
            assertEquals(BigInteger.TWO, worker.points(focus));
            worker.practice(WorkerAttribute.CAPACITY, WorkerState.TICKS_PER_POINT, 2);
            assertEquals(BigInteger.TWO, worker.points(WorkerAttribute.CAPACITY));
            assertTrue(worker.hasAbility(ability(profession)));
        }
        assertEquals(6, layers.size()); assertEquals(16, abilities.size());
    }
}
