package dev.colony.camera.work;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.colony.camera.construction.ConstructionManager;
import dev.colony.camera.construction.ConstructionProject;
import dev.colony.camera.construction.ProjectOrder;
import dev.colony.camera.content.ColonyContent;
import dev.colony.camera.content.MachineBlockEntity;
import dev.colony.camera.content.PrintingCores;
import dev.colony.camera.crafting.CraftingService;
import dev.colony.camera.resources.GroundResources;
import dev.colony.camera.resources.ReservationLedger;
import dev.colony.camera.tech.TechProgress;
import dev.colony.camera.worker.WorkerEntity;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Additional jobs share ConstructionManager's sequence, worker pool and material reservation ledger. */
@ParametersAreNonnullByDefault
public final class ColonyWorkManager extends SavedData {
    private final Map<UUID, ColonyJob> jobs = new LinkedHashMap<>();
    @Nullable private CompoundTag rejected;
    private boolean changed = true;
    @Nonnull public static ColonyWorkManager get(ServerLevel level) {
        return Objects.requireNonNull(level.getDataStorage().computeIfAbsent(ColonyWorkManager::load, ColonyWorkManager::new, "colonycamera_work"));
    }
    public boolean readOnly() { return rejected != null; }
    public boolean needsDispatch() { boolean result = changed; changed = false; return result; }
    public void redispatch() { changed = true; setDirty(); }
    public long nextSequenceFloor() { return jobs.values().stream().mapToLong(job -> job.sequence + 1).max().orElse(0); }
    @Nonnull public List<ColonyJob> jobs(UUID owner) { return Objects.requireNonNull(jobs.values().stream().filter(job -> job.owner.equals(owner)).sorted(Comparator.comparing((ColonyJob checkedElement) -> Objects.requireNonNull(checkedElement).order(), ProjectOrder.COMPARATOR)).toList()); }
    @Nonnull public List<ColonyJob> all() { return Objects.requireNonNull(List.copyOf(jobs.values())); }
    private void validatePlayer(ServerPlayer player) {
        if (readOnly() || ConstructionManager.get(Objects.requireNonNull(player.serverLevel())).readOnly()) throw new IllegalArgumentException("data");
        if (player.isSpectator() || !player.getAbilities().mayBuild) throw new IllegalArgumentException("denied");
        if (jobs(Objects.requireNonNull(player.getUUID())).size() + ConstructionManager.get(Objects.requireNonNull(player.serverLevel())).projects(Objects.requireNonNull(player.getUUID())).size() >= ConstructionManager.MAX_PROJECTS) throw new IllegalArgumentException("limit");
    }
    private static void location(ServerPlayer player, BlockPos pos, List<BlockPos> origins) {
        ServerLevel level = Objects.requireNonNull(player.serverLevel());
        if (!GroundResources.loaded(level, pos) || level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos) || !level.mayInteract(player, pos)
                || player.distanceToSqr(GroundResources.center(pos)) > 128 * 128 && origins.stream().noneMatch(core -> core.distSqr(pos) <= 128 * 128)) throw new IllegalArgumentException("location");
    }
    private ColonyJob begin(ServerPlayer player, ColonyJob.Kind kind) {
        ConstructionManager manager = ConstructionManager.get(Objects.requireNonNull(player.serverLevel()));
        manager.sequenceFloor(nextSequenceFloor());
        return new ColonyJob(Objects.requireNonNull(UUID.randomUUID()), Objects.requireNonNull(player.getUUID()), Objects.requireNonNull(player.getGameProfile().getName()), kind, 5,
                ConstructionManager.get(Objects.requireNonNull(player.serverLevel())).allocateSequence());
    }
    private void add(ColonyJob job) { jobs.put(job.id, job); redispatch(); }
    public void mine(ServerPlayer player, List<BlockPos> selection) {
        validatePlayer(player);
        if (selection.isEmpty()) throw new IllegalArgumentException("empty");
        if (selection.size() > 8192 || selection.size() + jobs(Objects.requireNonNull(player.getUUID())).stream().filter(job -> job.kind == ColonyJob.Kind.MINE).mapToInt(job -> job.targets.size()).sum() > ConstructionManager.MAX_BLOCKS) throw new IllegalArgumentException("limit");
        var unique = new HashSet<BlockPos>(selection); if (unique.size() != selection.size()) throw new IllegalArgumentException("invalid");
        ServerLevel level = Objects.requireNonNull(player.serverLevel());
        var origins = Objects.requireNonNull(PrintingCores.owned(level, Objects.requireNonNull(player.getUUID())));
        for (BlockPos pos : selection) location(player, Objects.requireNonNull(pos), origins);
        for (var old : jobs.values()) if (old.kind == ColonyJob.Kind.MINE && !old.cancelled && !old.finished() && old.targets.stream().anyMatch(unique::contains)) throw new IllegalArgumentException("overlap");
        ColonyJob job = begin(player, ColonyJob.Kind.MINE);
        job.targets = Objects.requireNonNull(List.copyOf(selection));
        job.expectedStates = Objects.requireNonNull(selection.stream().map(pos -> BlockStateParser.serialize(Objects.requireNonNull(level.getBlockState(Objects.requireNonNull(pos))))).toList());
        add(job);
    }
    public void sweep(ServerPlayer player, BlockPos min, BlockPos max) {
        validatePlayer(player); validateBounds(min, max);
        var origins = Objects.requireNonNull(PrintingCores.owned(Objects.requireNonNull(player.serverLevel()), Objects.requireNonNull(player.getUUID())));
        location(player, min, origins); location(player, max, origins);
        for (int x = min.getX() >> 4; x <= max.getX() >> 4; x++) for (int z = min.getZ() >> 4; z <= max.getZ() >> 4; z++) {
            if (!player.serverLevel().getChunkSource().hasChunk(x, z)) throw new IllegalArgumentException("location");
        }
        ColonyJob job = begin(player, ColonyJob.Kind.SWEEP); job.targets = Objects.requireNonNull(List.of(min, max)); add(job);
    }
    private static void validateBounds(BlockPos min, BlockPos max) {
        long x = (long) max.getX() - min.getX() + 1, y = (long) max.getY() - min.getY() + 1, z = (long) max.getZ() - min.getZ() + 1;
        if (x < 1 || y < 1 || z < 1 || x > 256 || y > 256 || z > 256 || x * y * z > 262144) throw new IllegalArgumentException("limit");
    }
    public void craft(ServerPlayer player, BlockPos station, String recipe, int count) {
        validatePlayer(player); station(player, station, false);
        if (count < 1 || count > 64) throw new IllegalArgumentException("limit");
        if (!CraftingService.allowed(Objects.requireNonNull(player.serverLevel()), Objects.requireNonNull(player.getUUID()), recipe)) throw new IllegalArgumentException("recipe");
        ColonyJob job = begin(player, ColonyJob.Kind.CRAFT); job.station = station; job.recipe = recipe; job.requested = count; add(job);
    }
    public void research(ServerPlayer player, BlockPos station) {
        validatePlayer(player); station(player, station, true);
        int tier = TechProgress.get(Objects.requireNonNull(player.serverLevel())).tier(Objects.requireNonNull(player.getUUID()));
        if (tier >= 3 || jobs(Objects.requireNonNull(player.getUUID())).stream().anyMatch(job -> job.kind == ColonyJob.Kind.RESEARCH && !job.cancelled && !job.finished())) throw new IllegalArgumentException("research");
        ColonyJob job = begin(player, ColonyJob.Kind.RESEARCH); job.station = station; job.recipe = Objects.requireNonNull(String.valueOf(tier + 1)); add(job);
    }
    /** The caller validates both intents before adding either one. Supply shares its parent's FIFO position. */
    public void supply(ConstructionProject parent, BlockPos station, String recipe) {
        ColonyJob job = new ColonyJob(Objects.requireNonNull(UUID.randomUUID()), parent.owner, parent.ownerName, ColonyJob.Kind.CRAFT, parent.priority, parent.sequence);
        job.station = station; job.recipe = recipe; job.parentProject = parent.id(); add(job);
    }
    public void synchronizeSupplies(ConstructionManager construction) {
        for (ColonyJob job : jobs.values()) {
            UUID parentId = job.parentProject; if (parentId == null || job.finished() || job.cancelled) continue;
            ConstructionProject parent = construction.project(parentId);
            boolean cancelled = parent == null || !parent.owner.equals(job.owner) || parent.cancelled || parent.finished();
            boolean paused = parent != null && parent.paused;
            int priority = parent == null ? job.priority : parent.priority;
            if (job.cancelled != cancelled || job.paused != paused || job.priority != priority) {
                job.cancelled = cancelled; job.paused = paused; job.priority = priority; redispatch();
            }
        }
    }
    public static void station(ServerPlayer player, BlockPos pos, boolean research) {
        location(player, pos, Objects.requireNonNull(PrintingCores.owned(Objects.requireNonNull(player.serverLevel()), Objects.requireNonNull(player.getUUID()))));
        if (!(player.serverLevel().getBlockEntity(pos) instanceof MachineBlockEntity machine) || !machine.permits(player.getUUID())
                || machine.kind() != (research ? ColonyContent.Kind.RESEARCH_DESK : ColonyContent.Kind.COLONY_WORKBENCH)) throw new IllegalArgumentException("station");
    }
    public void update(UUID owner, UUID id, String action, int value) {
        if (readOnly()) throw new IllegalArgumentException("data");
        ColonyJob job = jobs.get(id); if (job == null || !job.owner.equals(owner)) throw new IllegalArgumentException("denied");
        switch (action) {
            case "priority" -> job.priority = new ProjectOrder(id, value, job.sequence).priority();
            case "pause" -> job.paused = !job.paused;
            case "cancel" -> job.cancelled = true;
            case "remove" -> { if (job.cancelled || job.finished()) jobs.remove(id); }
            default -> throw new IllegalArgumentException("invalid");
        }
        redispatch();
    }
    public void control(ServerLevel level, UUID owner, UUID id, String action, int value) {
        ColonyJob job = jobs.get(id); if (job == null || !job.owner.equals(owner)) throw new IllegalArgumentException("denied");
        UUID parentId = job.parentProject;
        if (parentId != null && !job.finished() && !job.cancelled) {
            ConstructionManager construction = ConstructionManager.get(level);
            if (readOnly() || construction.readOnly()) throw new IllegalArgumentException("data");
            construction.update(owner, parentId, action, value); synchronizeSupplies(construction); redispatch();
        } else update(owner, id, action, value);
    }
    public void dispatchJob(ServerLevel level, ColonyJob job, List<WorkerEntity> workers, Set<UUID> assigned, Set<BlockPos> claimed, ReservationLedger ledger, long time) {
        job.reason = job.cancelled ? "cancelled" : job.paused ? "paused" : job.finished() ? "complete" : "no_worker";
        if (!job.runnable() || readOnly()) return;
        var station = job.station;
        if (station != null && claimed.contains(station)) { job.reason = "queued"; return; }
        for (WorkerEntity worker : workers) {
            if (assigned.contains(worker.getUUID()) || !job.owner.equals(worker.owner())) continue;
            WorkTask next = switch (job.kind) {
                case MINE -> MiningService.assign(level, worker, job, claimed, ledger, time);
                case SWEEP -> SweepService.assign(level, worker, job, ledger, time);
                case CRAFT, RESEARCH -> CraftingService.assign(level, worker, job, ledger, time);
            };
            if (next == null) continue;
            WorkTask previous = worker.workTask;
            if (previous != null && previous.job.equals(next.job) && previous.targetIndex == next.targetIndex
                    && previous.stand.equals(next.stand) && Objects.equals(previous.source, next.source)
                    && Objects.equals(previous.destination, next.destination) && previous.toolPickup == next.toolPickup) {
                next.progress = previous.progress; next.lastWorked = previous.lastWorked; next.deadline = previous.deadline;
            } else worker.getNavigation().stop();
            worker.assignment = null; worker.workTask = next; assigned.add(worker.getUUID());
            if (job.kind == ColonyJob.Kind.MINE) claimed.add(job.targets.get(next.targetIndex));
            // A station is a single work position; disjoint stations may run concurrently.
            if (job.kind == ColonyJob.Kind.CRAFT || job.kind == ColonyJob.Kind.RESEARCH) { claimed.add(Objects.requireNonNull(job.station)); break; }
        }
        setDirty();
    }
    public void tick(ServerLevel level, List<WorkerEntity> workers, ReservationLedger ledger) {
        if (readOnly() || ConstructionManager.get(level).readOnly()) return;
        long time = level.getGameTime();
        for (WorkerEntity worker : workers) {
            if (!worker.isAlive() || worker.level() != level || !GroundResources.loaded(level, Objects.requireNonNull(worker.blockPosition()))) continue;
            WorkTask task = worker.workTask; if (task == null) continue;
            ColonyJob job = jobs.get(task.job);
            if (job == null || !job.runnable() || time > task.deadline || !GroundResources.searchAreaLoaded(level, worker)) { release(worker, ledger); continue; }
            var source = task.source;
            if (source != null && level.getEntity(source) instanceof net.minecraft.world.entity.item.ItemEntity item && item.getItem().getCount() < ledger.reserved(source)) {
                job.reason = "source_changed"; release(worker, ledger); continue;
            }
            boolean wasToolPickup = task.toolPickup;
            boolean ended = switch (job.kind) {
                case MINE -> MiningService.execute(level, worker, job, task, ledger, time);
                case SWEEP -> SweepService.execute(level, worker, job, task, ledger, time);
                case CRAFT, RESEARCH -> CraftingService.execute(level, worker, job, task, ledger, time);
            };
            if (wasToolPickup && !task.toolPickup) ledger.release(worker.getUUID());
            if (ended) { setDirty(); release(worker, ledger); }
        }
        if (time % 20 == 0) for (ServerPlayer player : level.players()) WorkNetwork.snapshot(Objects.requireNonNull(player), "");
    }
    private void release(WorkerEntity worker, ReservationLedger ledger) { worker.workTask = null; ledger.release(worker.getUUID()); worker.getNavigation().stop(); changed = true; }
    public JsonObject describe(ServerPlayer player) {
        JsonObject root = new JsonObject(); JsonArray list = new JsonArray();
        for (ColonyJob job : jobs(Objects.requireNonNull(player.getUUID()))) {
            JsonObject entry = new JsonObject(); entry.addProperty("id", job.id.toString()); entry.addProperty("kind", job.kind.name());
            entry.addProperty("name", "colonycamera.work.kind." + job.kind.name().toLowerCase(java.util.Locale.ROOT)); entry.addProperty("priority", job.priority);
            entry.addProperty("supply", job.parentProject != null);
            if (job.parentProject != null) entry.addProperty("name", CraftingService.output(Objects.requireNonNull(player.serverLevel()), job.recipe).getDescriptionId());
            entry.addProperty("done", job.done); entry.addProperty("total", job.total()); entry.addProperty("reason", job.reason);
            entry.addProperty("finished", job.finished());
            BlockPos position = job.station != null ? job.station : job.targets.isEmpty() ? null : job.targets.get(0);
            if (position != null) entry.addProperty("pos", position.asLong());
            entry.addProperty("paused", job.paused); entry.addProperty("cancelled", job.cancelled); entry.addProperty("sequence", job.sequence); list.add(entry);
        }
        root.add("jobs", list); JsonArray workers = new JsonArray();
        for (WorkerEntity worker : ConstructionManager.get(Objects.requireNonNull(player.serverLevel())).workers(Objects.requireNonNull(player.serverLevel()))) if (player.getUUID().equals(worker.owner())) {
            JsonObject entry = new JsonObject(); entry.addProperty("id", worker.getUUID().toString()); entry.addProperty("miningLevel", worker.miningLevel()); entry.addProperty("xp", worker.miningXp());
            entry.addProperty("tool", String.valueOf(net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(worker.getOffhandItem().getItem()))); workers.add(entry);
        }
        root.add("workers", workers); JsonArray stations = new JsonArray();
        for (BlockPos pos : WorkStations.owned(Objects.requireNonNull(player.serverLevel()), Objects.requireNonNull(player.getUUID())).stream().limit(128).toList()) {
            if (!(player.serverLevel().getBlockEntity(Objects.requireNonNull(pos)) instanceof MachineBlockEntity machine)) continue;
            JsonObject entry = new JsonObject(); entry.addProperty("pos", pos.asLong()); entry.addProperty("research", machine.kind() == ColonyContent.Kind.RESEARCH_DESK); stations.add(entry);
        }
        root.add("stations", stations); root.add("tech", TechProgress.describe(Objects.requireNonNull(player.serverLevel()), Objects.requireNonNull(player.getUUID())));
        root.add("recipes", CraftingService.catalog(Objects.requireNonNull(player.serverLevel()), Objects.requireNonNull(player.getUUID()))); return root;
    }
    @Override @Nonnull public CompoundTag save(CompoundTag root) {
        if (rejected != null) return Objects.requireNonNull(rejected.copy());
        root.putInt("schemaVersion", 2); ListTag list = new ListTag();
        for (ColonyJob job : jobs.values()) {
            CompoundTag tag = new CompoundTag(); tag.putUUID("id", job.id); tag.putUUID("owner", job.owner); tag.putString("ownerName", job.ownerName);
            tag.putString("kind", Objects.requireNonNull(job.kind.name())); tag.putInt("priority", job.priority); tag.putLong("sequence", job.sequence);
            tag.putLongArray("targets", Objects.requireNonNull(job.targets.stream().mapToLong((BlockPos checkedElement) -> Objects.requireNonNull(checkedElement).asLong()).toArray())); ListTag states = new ListTag();
            for (String state : job.expectedStates) states.add(Objects.requireNonNull(StringTag.valueOf(Objects.requireNonNull(state)))); tag.put("states", states);
            if (job.station != null) tag.putLong("station", job.station.asLong()); tag.putString("recipe", job.recipe); tag.putInt("requested", job.requested);
            UUID parent = job.parentProject; if (parent != null) tag.putUUID("parentProject", parent);
            tag.putInt("done", job.done); tag.putLongArray("completed", Objects.requireNonNull(job.completed.toLongArray())); tag.putBoolean("paused", job.paused); tag.putBoolean("cancelled", job.cancelled); list.add(tag);
        }
        root.put("jobs", list); return root;
    }
    @Nonnull public static ColonyWorkManager load(CompoundTag root) {
        ColonyWorkManager manager = new ColonyWorkManager();
        try {
            if (!root.contains("schemaVersion", Tag.TAG_INT) || root.getInt("schemaVersion") != 1 && root.getInt("schemaVersion") != 2 || !(root.get("jobs") instanceof ListTag entries)
                    || entries.size() > 4096 || !entries.isEmpty() && entries.getElementType() != Tag.TAG_COMPOUND) throw new IllegalArgumentException("data");
            for (int i = 0; i < entries.size(); i++) {
                CompoundTag tag = entries.getCompound(i);
                if (!tag.hasUUID("id") || !tag.hasUUID("owner") || tag.getString("ownerName").isBlank() || !tag.contains("targets", Tag.TAG_LONG_ARRAY)
                        || !tag.contains("completed", Tag.TAG_LONG_ARRAY) || !(tag.get("states") instanceof ListTag states)
                        || !states.isEmpty() && states.getElementType() != Tag.TAG_STRING || !tag.contains("sequence", Tag.TAG_LONG) || !tag.contains("priority", Tag.TAG_INT)
                        || !tag.contains("requested", Tag.TAG_INT) || !tag.contains("done", Tag.TAG_INT)) throw new IllegalArgumentException("data");
                ColonyJob job = new ColonyJob(Objects.requireNonNull(tag.getUUID("id")), Objects.requireNonNull(tag.getUUID("owner")), Objects.requireNonNull(tag.getString("ownerName")),
                        Objects.requireNonNull(ColonyJob.Kind.valueOf(Objects.requireNonNull(tag.getString("kind")))), tag.getInt("priority"), tag.getLong("sequence"));
                job.targets = Objects.requireNonNull(java.util.Arrays.stream(tag.getLongArray("targets")).mapToObj(BlockPos::of).toList());
                var expected = new ArrayList<String>(); for (int state = 0; state < states.size(); state++) expected.add(states.getString(state)); job.expectedStates = Objects.requireNonNull(List.copyOf(expected));
                job.station = tag.contains("station", Tag.TAG_LONG) ? BlockPos.of(tag.getLong("station")) : null; job.recipe = Objects.requireNonNull(tag.getString("recipe"));
                if (root.getInt("schemaVersion") >= 2 && tag.contains("parentProject")) {
                    if (!tag.hasUUID("parentProject") || job.kind != ColonyJob.Kind.CRAFT) throw new IllegalArgumentException("data");
                    job.parentProject = Objects.requireNonNull(tag.getUUID("parentProject"));
                }
                job.requested = tag.getInt("requested"); job.done = tag.getInt("done"); job.completed.or(BitSet.valueOf(tag.getLongArray("completed")));
                job.paused = tag.getBoolean("paused"); job.cancelled = tag.getBoolean("cancelled");
                if (job.done < 0 || job.requested < 1 || job.sequence == Long.MAX_VALUE || job.done > job.requested && job.kind != ColonyJob.Kind.MINE && job.kind != ColonyJob.Kind.SWEEP) throw new IllegalArgumentException("data");
                if (job.kind == ColonyJob.Kind.MINE && (job.targets.isEmpty() || job.targets.size() > 8192 || new HashSet<>(job.targets).size() != job.targets.size()
                        || job.expectedStates.size() != job.targets.size() || job.completed.length() > job.targets.size() || job.done != job.completed.cardinality())) throw new IllegalArgumentException("data");
                if (job.kind == ColonyJob.Kind.SWEEP) { if (job.targets.size() != 2 || job.completed.length() > 1) throw new IllegalArgumentException("data"); validateBounds(Objects.requireNonNull(job.targets.get(0)), Objects.requireNonNull(job.targets.get(1))); }
                if ((job.kind == ColonyJob.Kind.CRAFT || job.kind == ColonyJob.Kind.RESEARCH) && (job.station == null || job.requested > 64 || job.recipe.isBlank())) throw new IllegalArgumentException("data");
                if (manager.jobs.put(job.id, job) != null) throw new IllegalArgumentException("data");
            }
        } catch (RuntimeException invalid) { manager.jobs.clear(); manager.rejected = Objects.requireNonNull(root.copy()); }
        return manager;
    }
}
