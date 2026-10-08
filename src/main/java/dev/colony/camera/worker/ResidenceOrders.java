package dev.colony.camera.worker;

import com.google.gson.JsonObject;
import dev.colony.camera.content.ColonyContent;
import dev.colony.camera.content.MachineBlockEntity;
import dev.colony.camera.resources.GroundResources;
import dev.colony.camera.work.ColonyWorkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public final class ResidenceOrders {
    private ResidenceOrders() {}
    public static void request(ServerPlayer player, JsonObject data) {
        var level = Objects.requireNonNull(player.serverLevel()); BlockPos pos = Objects.requireNonNull(BlockPos.of(data.get("pos").getAsLong()));
        if (player.isSpectator() || !player.getAbilities().mayBuild || !GroundResources.loaded(level, pos) || !level.mayInteract(player, pos)
                || player.distanceToSqr(GroundResources.center(pos)) > 128 * 128 || !(level.getBlockEntity(pos) instanceof MachineBlockEntity machine)
                || machine.kind() != ColonyContent.Kind.RESIDENCE || !machine.permits(Objects.requireNonNull(player.getUUID()))) throw new IllegalArgumentException("denied");
        if (machine.facilityReadOnly()) throw new IllegalArgumentException("data");
        switch (data.get("operation").getAsString()) {
            case "name" -> {
                String name = Objects.requireNonNull(data.get("name").getAsString()).strip();
                if (name.length() > 64 || name.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("invalid");
                machine.customName = name.isEmpty() ? null : Component.literal(name); machine.setChanged();
            }
            case "automatic" -> machine.automaticHousing(data.get("enabled").getAsBoolean());
            case "assign" -> {
                UUID id = Objects.requireNonNull(UUID.fromString(data.get("worker").getAsString()));
                if (!(level.getEntity(id) instanceof WorkerEntity worker) || !worker.isAlive() || !player.getUUID().equals(worker.owner())
                        || worker.distanceToSqr(player) > 128 * 128 || !level.mayInteract(player, Objects.requireNonNull(worker.blockPosition())) || !Residences.assign(level, worker, pos)) throw new IllegalArgumentException("home");
            }
            case "release" -> {
                UUID id = machine.resident();
                if (id != null) {
                    // An unloaded resident still owns this claim; do not strand its persisted home reference.
                    if (!(level.getEntity(id) instanceof WorkerEntity worker) || worker.stateReadOnly() || !player.getUUID().equals(worker.owner())) throw new IllegalArgumentException("home");
                    if (pos.equals(worker.home())) { Residences.release(level, worker); worker.home(null); }
                    else machine.resident(null);
                }
                machine.automaticHousing(false);
            }
            default -> throw new IllegalArgumentException("invalid");
        }
        ColonyWorkManager.get(level).redispatch();
    }
}
