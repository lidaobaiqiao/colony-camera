package dev.colony.camera.worker;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.colony.camera.config.WorkerConfig;
import dev.colony.camera.content.ColonyContent;
import dev.colony.camera.content.MachineBlockEntity;
import dev.colony.camera.work.WorkStations;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public final class WorkerSnapshots {
    private WorkerSnapshots() {}
    @Nonnull public static JsonObject person(WorkerEntity worker) {
        WorkerState state = worker.state(); long time = worker.level().getGameTime(); JsonObject value = new JsonObject();
        value.addProperty("readOnly", worker.stateReadOnly()); value.addProperty("interest", state.interest().name());
        value.addProperty("temperament", state.temperament().name()); value.addProperty("primary", state.primary().name()); value.addProperty("secondary", state.secondary().name());
        JsonArray abilities = new JsonArray();
        for (var entry : new WorkerProfile.Profession[]{state.primary(), state.secondary()}) {
            var profession = Objects.requireNonNull(entry); if (profession == WorkerProfile.Profession.NONE) continue;
            JsonObject ability = new JsonObject(); var capability = WorkerProfile.ability(profession);
            ability.addProperty("profession", profession.name()); ability.addProperty("id", capability.name());
            ability.addProperty("implemented", WorkerProfile.abilityImplemented(capability)); abilities.add(ability);
        }
        value.add("abilities", abilities);
        value.addProperty("stress", state.stress(WorkerEffects.tolerance(state))); value.addProperty("loyalty", state.loyalty());
        value.addProperty("hunger", state.hunger()); value.addProperty("rest", state.rest()); value.addProperty("health", worker.getHealth());
        value.addProperty("reason", worker.lifeReason()); value.addProperty("striking", state.striking());
        JsonObject needs = new JsonObject(); needs.addProperty("hydration", worker.needs().hydration()); needs.addProperty("social", worker.needs().social());
        needs.addProperty("temperature", worker.needs().temperature()); needs.addProperty("waterQuality", worker.needs().waterQuality());
        needs.addProperty("drinks", worker.needs().drinks()); needs.addProperty("meals", worker.needs().meals());
        needs.addProperty("workSpeed", worker.needs().workSpeed(WorkerEffects.environmentResistance(state))); value.add("needs", needs);
        JsonArray relationships = new JsonArray();
        worker.needs().relations().entrySet().stream().sorted(java.util.Comparator.<java.util.Map.Entry<java.util.UUID, WorkerNeeds.Relation>>comparingLong(entry -> entry.getValue().togetherTicks()).reversed())
                .forEach(entry -> {
                    JsonObject relation = new JsonObject(); var id = Objects.requireNonNull(entry.getKey()); var acquaintance = worker.level() instanceof net.minecraft.server.level.ServerLevel server ? server.getEntity(id) : null;
                    relation.addProperty("id", id.toString()); relation.addProperty("name", acquaintance == null ? id.toString().substring(0, 8) : acquaintance.getDisplayName().getString() + " " + id.toString().substring(0, 8));
                    relation.addProperty("loaded", acquaintance != null && acquaintance.isAlive()); relation.addProperty("affinity", entry.getValue().affinity());
                    relation.addProperty("seconds", entry.getValue().togetherTicks() / 20); relationships.add(relation);
                });
        value.add("relationships", relationships);
        JsonObject preferences = new JsonObject();
        preferences.addProperty("automaticTools", worker.preferences().automaticTools());
        for (var task : WorkerPreferences.Task.values()) preferences.addProperty(task.name(), worker.preferences().allows(Objects.requireNonNull(task)));
        value.add("preferences", preferences); JsonArray equipment = new JsonArray();
        for (var slot : net.minecraft.world.entity.EquipmentSlot.values()) if (slot != net.minecraft.world.entity.EquipmentSlot.MAINHAND) {
            JsonObject equipped = new JsonObject(); var stack = worker.getItemBySlot(Objects.requireNonNull(slot));
            equipped.addProperty("slot", slot.name()); equipped.addProperty("name", stack.isEmpty() ? "" : stack.getHoverName().getString());
            equipped.addProperty("item", String.valueOf(net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem())));
            equipped.addProperty("damage", stack.getDamageValue()); equipped.addProperty("maxDamage", stack.getMaxDamage()); equipment.add(equipped);
        }
        value.add("equipment", equipment);
        value.addProperty("homeValid", worker.level() instanceof net.minecraft.server.level.ServerLevel server && Residences.home(server, worker) != null);
        var home = worker.home(); if (home != null) value.addProperty("home", home.asLong());
        JsonArray attributes = new JsonArray();
        for (WorkerAttribute attribute : WorkerAttribute.values()) {
            JsonObject stat = new JsonObject(); stat.addProperty("id", attribute.name()); String points = state.points(Objects.requireNonNull(attribute)).toString();
            stat.addProperty("points", points.length() <= 64 ? points : points.substring(0, 64) + "…"); stat.addProperty("progress", state.progress(attribute));
            double effect = switch (attribute) {
                case HEALTH -> WorkerEffects.health(state); case HUNGER -> WorkerEffects.hunger(state); case DEFENSE -> WorkerEffects.defense(state);
                case MOVEMENT -> WorkerEffects.movement(state); case ATTACK -> WorkerEffects.attack(state); case MINING -> WorkerEffects.mining(state, time);
                case BUILDING -> WorkerEffects.buildTicks(state, time) / 20.0; case HAULING -> WorkerEffects.haulTicks(state) / 20.0;
                case CAPACITY -> WorkerEffects.capacity(state); case RESILIENCE -> WorkerEffects.tolerance(state); case MANUFACTURING -> WorkerEffects.manufacturing(state, time);
                case PERCEPTION -> WorkerEffects.perception(state); case MOBILITY -> WorkerEffects.mobility(state); case ENERGY -> WorkerEffects.energy(state);
                case LEARNING -> WorkerEffects.learning(state); case ENVIRONMENT_RESISTANCE -> WorkerEffects.environmentResistance(state);
                case PRECISION -> WorkerEffects.precision(state); case OPERATION -> WorkerEffects.operation(state);
            };
            stat.addProperty("effect", effect); attributes.add(stat);
        }
        value.add("attributes", attributes); JsonArray pressure = new JsonArray();
        state.pressureSources().forEach((source, amount) -> { JsonObject entry = new JsonObject(); entry.addProperty("id", source.name()); entry.addProperty("amount", Objects.requireNonNull(amount) / WorkerEffects.tolerance(state)); pressure.add(entry); });
        value.add("pressure", pressure);
        WorkerDirective directive = worker.directive();
        if (directive != null) {
            JsonObject intent = new JsonObject(); intent.addProperty("kind", directive.kind.name()); intent.addProperty("course", directive.course);
            intent.addProperty("additional", directive.additional); intent.addProperty("tier", directive.tier); intent.addProperty("progress", directive.progress);
            intent.addProperty("duration", directive.kind == WorkerDirective.Kind.STUDY_ATTRIBUTE ? WorkerState.TICKS_PER_POINT : WorkerConfig.PROFESSION_TICKS.get());
            if (directive.target != null) intent.addProperty("target", directive.target.toString()); value.add("directive", intent);
        }
        return value;
    }
    public static void colony(ServerPlayer player, JsonObject root) {
        var level = Objects.requireNonNull(player.serverLevel()); JsonArray facilities = new JsonArray();
        for (var pos : WorkStations.owned(level, Objects.requireNonNull(player.getUUID())).stream().limit(128).toList()) {
            if (!(level.getBlockEntity(Objects.requireNonNull(pos)) instanceof MachineBlockEntity machine)
                    || machine.kind() != ColonyContent.Kind.RESIDENCE && machine.kind() != ColonyContent.Kind.ACADEMY
                    && machine.kind() != ColonyContent.Kind.WATER_PUMP && machine.kind() != ColonyContent.Kind.MEETING_POINT) continue;
            var inspected = machine.kind() == ColonyContent.Kind.RESIDENCE ? Residences.details(level, pos) : null;
            JsonObject entry = new JsonObject(); entry.addProperty("pos", pos.asLong()); entry.addProperty("kind", machine.kind().name());
            entry.addProperty("level", machine.schoolLevel()); entry.addProperty("valid", !machine.facilityReadOnly() && (inspected == null || inspected.room() != null));
            if (machine.kind() == ColonyContent.Kind.WATER_PUMP || machine.kind() == ColonyContent.Kind.MEETING_POINT) {
                String status = WorkerSurvival.facilityStatus(level, machine); entry.addProperty("status", status); entry.addProperty("valid", status.equals("ready"));
            }
            entry.addProperty("occupied", machine.resident() != null); facilities.add(entry);
            entry.addProperty("name", machine.customName == null ? "" : machine.customName.getString());
            entry.addProperty("readOnly", machine.facilityReadOnly()); entry.addProperty("automatic", machine.automaticHousing());
            if (machine.resident() != null) entry.addProperty("resident", Objects.requireNonNull(machine.resident()).toString());
            if (inspected != null) {
                entry.addProperty("reason", inspected.reason()); entry.addProperty("area", inspected.area());
                var room = inspected.room(); if (room != null) entry.addProperty("bed", room.bed().asLong());
            }
        }
        root.add("livingFacilities", facilities); JsonArray targets = new JsonArray();
        level.getEntitiesOfClass(LivingEntity.class, Objects.requireNonNull(player.getBoundingBox().inflate(128))).stream().filter(entity -> Objects.requireNonNull(entity).isAlive())
                .sorted(java.util.Comparator.comparingDouble(player::distanceToSqr)).limit(64).forEach(entity -> {
                    JsonObject target = new JsonObject(); target.addProperty("id", entity.getUUID().toString()); target.addProperty("name", entity.getDisplayName().getString()); targets.add(target);
                });
        root.add("peopleTargets", targets); JsonObject rules = new JsonObject(); rules.addProperty("executeLoyalty", WorkerConfig.ORDER_LOYALTY.get());
        rules.addProperty("sacrificeLoyalty", WorkerConfig.SACRIFICE_LOYALTY.get()); rules.addProperty("populationLimit", WorkerConfig.POPULATION_LIMIT.get()); root.add("workerRules", rules);
        JsonArray professions = new JsonArray();
        for (var entry : WorkerProfile.Profession.values()) {
            var profession = Objects.requireNonNull(entry); if (profession == WorkerProfile.Profession.NONE) continue;
            JsonObject item = new JsonObject(); var ability = WorkerProfile.ability(profession);
            item.addProperty("id", profession.name()); item.addProperty("layer", WorkerProfile.layer(profession).name());
            item.addProperty("ability", ability.name()); item.addProperty("abilityImplemented", WorkerProfile.abilityImplemented(ability));
            item.addProperty("schoolLevel", WorkerProfile.schoolLevel(profession)); professions.add(item);
        }
        rules.add("professions", professions);
    }
}
