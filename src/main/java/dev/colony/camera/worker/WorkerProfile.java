package dev.colony.camera.worker;

import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Objects;

@ParametersAreNonnullByDefault
public final class WorkerProfile {
    public enum Interest { NONE, EXPLORER, ARCHITECT, ORGANIZER, CRAFTSMAN, SCIENTIST, ARTIST, DEFENDER, CAREGIVER }
    public enum Temperament { NONE, IMPATIENT, STEADY, CAREFUL }
    public enum Profession { NONE, MINER, LUMBERJACK, FARMER, BUILDER, ENGINEER, ARTISAN, COOK,
        MUSICIAN, SCULPTOR, MASTER_OF_CEREMONIES, GUARD, HUNTER, CAPTAIN, DOCTOR, RESEARCHER, TEACHER }
    public enum ProfessionLayer { GATHERING, BUILDING, PRODUCTION, ART, COMBAT, SUPPORT }
    public enum Ability { NONE, ORE_SURVEY, WHOLE_TREE, AGRONOMY, SCAFFOLDING, ADVANCED_MACHINES, FINE_CRAFT,
        MASS_MEALS, PERFORMANCE, MONUMENT, CEREMONY, ANCHORED_DEFENSE, PATROL_HUNT, COMMAND, MEDICINE, RESEARCH, TEACHING }
    public enum StressCause { HUNGER, POOR_FOOD, HOMELESS, BAD_ENVIRONMENT, FATIGUE, OVERWORK,
        UNSUITED_WORK, FORCED_STUDY, DOUBLE_DEGREE, REFUSED_ORDER, WITNESSED_EXECUTION, HEARD_EXECUTION,
        THIRST, COLD, HEAT, LONELINESS }
    private WorkerProfile() {}
    @Nonnull public static WorkerAttribute focus(Profession profession) {
        return switch (profession) {
            case MINER -> WorkerAttribute.MINING;
            case LUMBERJACK -> WorkerAttribute.MINING;
            case FARMER -> WorkerAttribute.MANUFACTURING;
            case BUILDER -> WorkerAttribute.BUILDING;
            case ENGINEER -> WorkerAttribute.OPERATION;
            case ARTISAN -> WorkerAttribute.MANUFACTURING;
            case COOK -> WorkerAttribute.MANUFACTURING;
            case MUSICIAN, SCULPTOR, MASTER_OF_CEREMONIES -> WorkerAttribute.RESILIENCE;
            case GUARD -> WorkerAttribute.DEFENSE;
            case HUNTER, CAPTAIN -> WorkerAttribute.ATTACK;
            case DOCTOR -> WorkerAttribute.HEALTH;
            case RESEARCHER, TEACHER -> WorkerAttribute.LEARNING;
            case NONE -> WorkerAttribute.RESILIENCE;
        };
    }
    @Nonnull public static WorkerAttribute secondaryFocus(Profession profession) {
        return switch (profession) {
            case MINER -> WorkerAttribute.PERCEPTION;
            case LUMBERJACK, BUILDER -> WorkerAttribute.MOBILITY;
            case FARMER, ARTISAN -> WorkerAttribute.PRECISION;
            case ENGINEER -> WorkerAttribute.HAULING;
            case COOK, RESEARCHER -> WorkerAttribute.OPERATION;
            case MUSICIAN, SCULPTOR, MASTER_OF_CEREMONIES -> WorkerAttribute.ENERGY;
            case GUARD -> WorkerAttribute.ENVIRONMENT_RESISTANCE;
            case HUNTER -> WorkerAttribute.PERCEPTION;
            case CAPTAIN -> WorkerAttribute.DEFENSE;
            case DOCTOR -> WorkerAttribute.RESILIENCE;
            case TEACHER -> WorkerAttribute.OPERATION;
            case NONE -> WorkerAttribute.RESILIENCE;
        };
    }
    public static boolean specializes(Profession profession, WorkerAttribute attribute) {
        return profession != Profession.NONE && (focus(profession) == attribute || secondaryFocus(profession) == attribute);
    }
    @Nonnull public static Ability ability(Profession profession) {
        return switch (profession) {
            case NONE -> Ability.NONE; case MINER -> Ability.ORE_SURVEY; case LUMBERJACK -> Ability.WHOLE_TREE;
            case FARMER -> Ability.AGRONOMY; case BUILDER -> Ability.SCAFFOLDING; case ENGINEER -> Ability.ADVANCED_MACHINES;
            case ARTISAN -> Ability.FINE_CRAFT; case COOK -> Ability.MASS_MEALS; case MUSICIAN -> Ability.PERFORMANCE;
            case SCULPTOR -> Ability.MONUMENT; case MASTER_OF_CEREMONIES -> Ability.CEREMONY; case GUARD -> Ability.ANCHORED_DEFENSE;
            case HUNTER -> Ability.PATROL_HUNT; case CAPTAIN -> Ability.COMMAND; case DOCTOR -> Ability.MEDICINE;
            case RESEARCHER -> Ability.RESEARCH; case TEACHER -> Ability.TEACHING;
        };
    }
    /** Capability routing is separate from attribute-based scheduling; independent AI modules consume this contract. */
    public static boolean abilityImplemented(Ability value) { return value == Ability.FINE_CRAFT || value == Ability.RESEARCH || value == Ability.TEACHING; }
    @Nonnull public static ProfessionLayer layer(Profession profession) {
        return switch (profession) {
            case MINER, LUMBERJACK, FARMER -> ProfessionLayer.GATHERING;
            case BUILDER, ENGINEER -> ProfessionLayer.BUILDING;
            case ARTISAN, COOK -> ProfessionLayer.PRODUCTION;
            case MUSICIAN, SCULPTOR, MASTER_OF_CEREMONIES -> ProfessionLayer.ART;
            case GUARD, HUNTER, CAPTAIN -> ProfessionLayer.COMBAT;
            case DOCTOR, RESEARCHER, TEACHER, NONE -> ProfessionLayer.SUPPORT;
        };
    }
    public static int schoolLevel(Profession profession) {
        return switch (profession) {
            case ENGINEER, ARTISAN, SCULPTOR, GUARD, DOCTOR, RESEARCHER, TEACHER -> 2;
            case CAPTAIN, MASTER_OF_CEREMONIES -> 3;
            default -> 1;
        };
    }
    public static boolean interested(Interest interest, Profession profession) {
        return switch (profession) {
            case MINER, LUMBERJACK -> interest == Interest.EXPLORER;
            case FARMER -> interest == Interest.ORGANIZER || interest == Interest.CAREGIVER;
            case BUILDER -> interest == Interest.ARCHITECT;
            case ENGINEER -> interest == Interest.ARCHITECT || interest == Interest.ORGANIZER || interest == Interest.SCIENTIST;
            case ARTISAN -> interest == Interest.CRAFTSMAN || interest == Interest.SCIENTIST;
            case COOK -> interest == Interest.CAREGIVER || interest == Interest.CRAFTSMAN;
            case MUSICIAN, SCULPTOR, MASTER_OF_CEREMONIES -> interest == Interest.ARTIST;
            case GUARD, HUNTER, CAPTAIN -> interest == Interest.DEFENDER;
            case DOCTOR -> interest == Interest.CAREGIVER || interest == Interest.SCIENTIST;
            case RESEARCHER, TEACHER -> interest == Interest.SCIENTIST;
            case NONE -> true;
        };
    }
    public static boolean compatible(Interest interest, Temperament temperament) {
        return interest != Interest.SCIENTIST || temperament != Temperament.IMPATIENT;
    }
    public static int birthBonus(Interest interest, WorkerAttribute attribute) {
        return switch (interest) {
            case EXPLORER -> attribute == WorkerAttribute.MINING || attribute == WorkerAttribute.MOVEMENT ? 1 : 0;
            case ARCHITECT -> attribute == WorkerAttribute.BUILDING || attribute == WorkerAttribute.DEFENSE ? 1 : 0;
            case ORGANIZER -> attribute == WorkerAttribute.HAULING || attribute == WorkerAttribute.CAPACITY ? 1 : 0;
            case CRAFTSMAN -> attribute == WorkerAttribute.MANUFACTURING || attribute == WorkerAttribute.ATTACK ? 1 : 0;
            case SCIENTIST -> attribute == WorkerAttribute.MANUFACTURING || attribute == WorkerAttribute.RESILIENCE ? 1 : 0;
            case ARTIST -> attribute == WorkerAttribute.RESILIENCE || attribute == WorkerAttribute.ENERGY ? 1 : 0;
            case DEFENDER -> attribute == WorkerAttribute.ATTACK || attribute == WorkerAttribute.DEFENSE ? 1 : 0;
            case CAREGIVER -> attribute == WorkerAttribute.HEALTH || attribute == WorkerAttribute.LEARNING ? 1 : 0;
            case NONE -> 0;
        };
    }
    public static double temperamentEffect(Temperament temperament, WorkerAttribute attribute) {
        return switch (temperament) {
            case IMPATIENT -> attribute == WorkerAttribute.MOVEMENT ? 1.05 : attribute == WorkerAttribute.MANUFACTURING ? 0.95 : 1;
            case STEADY -> attribute == WorkerAttribute.RESILIENCE ? 1.05 : attribute == WorkerAttribute.MOVEMENT ? 0.95 : 1;
            case CAREFUL -> attribute == WorkerAttribute.BUILDING ? 1.05 : attribute == WorkerAttribute.MINING ? 0.95 : 1;
            case NONE -> 1;
        };
    }
    @Nonnull public static String key(Enum<?> value) { return "colonycamera.worker." + value.getDeclaringClass().getSimpleName().toLowerCase(java.util.Locale.ROOT) + "." + value.name().toLowerCase(java.util.Locale.ROOT); }
    @Nonnull public static <T extends Enum<T>> T parse(Class<T> type, String value) { return Objects.requireNonNull(Enum.valueOf(type, value)); }
}
