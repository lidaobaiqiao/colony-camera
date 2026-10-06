package dev.colony.camera.construction;

import dev.colony.camera.ColonyCamera;
import dev.colony.camera.crafting.CraftingService;
import dev.colony.camera.content.ColonyContent;
import dev.colony.camera.content.MachineBlockEntity;
import dev.colony.camera.network.BuildNetwork;
import dev.colony.camera.planning.ProjectionPlacement;
import dev.colony.camera.resources.GroundResources;
import dev.colony.camera.resources.StorageResources;
import dev.colony.camera.storage.StorageAccess;
import dev.colony.camera.work.ColonyWorkManager;
import dev.colony.camera.work.WorkStations;
import dev.colony.camera.worker.WorkerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.*;

/** Incremental, read-only reachability queries. Preview never changes an NPC's execution navigation. */
@Mod.EventBusSubscriber(modid = ColonyCamera.ID)
@ParametersAreNonnullByDefault
public final class BuildCatalogService {
    public record Entry(@Nonnull String item, int available, @Nonnull String recipe, @Nullable BlockPos station) {}
    private record Site(@Nonnull WorkerEntity worker, @Nonnull BlockPos station, @Nonnull BlockPos stand) {}
    private record Stock(@Nonnull Item item, int count) {}
    private static final Map<ServerLevel, Map<UUID, Scan>> SCANS = new WeakHashMap<>();
    private static final Map<ServerLevel, Map<UUID, Long>> REQUESTS = new WeakHashMap<>();
    private static final Map<ServerLevel, Map<UUID, List<Entry>>> CACHE = new WeakHashMap<>();
    private BuildCatalogService() {}
    public static void request(ServerPlayer player, UUID request) {
        ServerLevel level = Objects.requireNonNull(player.serverLevel()); UUID owner = Objects.requireNonNull(player.getUUID());
        Map<UUID, Scan> active = SCANS.computeIfAbsent(level, ignored -> new LinkedHashMap<>());
        Scan running = active.get(owner); if (running != null) { running.request = request; return; }
        Map<UUID, Long> times = REQUESTS.computeIfAbsent(level, ignored -> new HashMap<>());
        Long previous = times.get(owner); if (previous != null && level.getGameTime() - previous < 40) {
            BuildNetwork.catalog(player, request, Objects.requireNonNull(CACHE.getOrDefault(level, Map.of()).getOrDefault(owner, List.of()))); return;
        }
        times.put(owner, level.getGameTime()); active.put(owner, new Scan(player, request));
    }
    @SubscribeEvent public static void tick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) return;
        Map<UUID, Scan> active = SCANS.get(level); if (active == null) return;
        long deadline = System.nanoTime() + 4_000_000L; int paths = 4;
        for (var iterator = active.entrySet().iterator(); iterator.hasNext();) {
            var entry = iterator.next(); Scan scan = entry.getValue();
            if (scan.player.hasDisconnected() || scan.player.serverLevel() != level) { iterator.remove(); continue; }
            while (paths > 0 && System.nanoTime() < deadline && !scan.done()) { if (scan.step()) paths--; }
            if (scan.done()) { var entries = scan.entries(); CACHE.computeIfAbsent(level, ignored -> new HashMap<>()).put(entry.getKey(), entries); BuildNetwork.catalog(scan.player, scan.request, entries); iterator.remove(); }
            if (paths <= 0 || System.nanoTime() >= deadline) break;
        }
    }
    @SubscribeEvent public static void unload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) { SCANS.remove(level); REQUESTS.remove(level); CACHE.remove(level); }
    }
    /** Each step performs at most one path query or one station transaction simulation. */
    public static final class Scan {
        @Nonnull private final ServerPlayer player;
        @Nonnull private UUID request;
        @Nonnull private final ServerLevel level;
        @Nonnull private final UUID owner;
        private final Deque<Probe> probes = new ArrayDeque<>();
        private final List<Site> sites = new ArrayList<>();
        private final Map<UUID, Stock> sources = new LinkedHashMap<>();
        @Nonnull private final Map<Item, Integer> counts = new LinkedHashMap<>();
        private final Map<Item, Entry> craftable = new LinkedHashMap<>();
        @Nonnull private final Set<Item> recipeMaterials = new HashSet<>();
        private final Set<BlockPos> scannedStations = new HashSet<>();
        private List<String> recipes = List.of();
        private int recipeIndex, siteIndex;
        private boolean collected;
        public Scan(ServerPlayer player, UUID request) {
            this.player = player; this.request = request; this.level = Objects.requireNonNull(player.serverLevel()); this.owner = Objects.requireNonNull(player.getUUID());
            for (WorkerEntity worker : ConstructionManager.get(level).workers(level)) {
                if (!owner.equals(worker.owner())) continue;
                for (var list : GroundResources.scan(level, worker).values()) for (ItemEntity item : list) probes.add(new Probe(worker, Objects.requireNonNull(item.blockPosition()), item, false));
                for (BlockPos pos : StorageResources.scan(level, worker)) probes.add(new Probe(worker, Objects.requireNonNull(pos), null, false));
                for (BlockPos pos : WorkStations.owned(level, owner)) {
                    if (pos.distSqr(Objects.requireNonNull(worker.blockPosition())) <= WorkerEntity.NAVIGATION_RANGE * WorkerEntity.NAVIGATION_RANGE
                            && level.getBlockEntity(pos) instanceof MachineBlockEntity machine && machine.kind() == ColonyContent.Kind.COLONY_WORKBENCH) probes.add(new Probe(worker, Objects.requireNonNull(pos), null, true));
                }
            }
        }
        public boolean done() { return collected && recipeIndex >= recipes.size(); }
        public boolean step() {
            Probe probe = probes.peekFirst();
            if (probe != null) { if (probe.step()) probes.removeFirst(); return true; }
            if (!collected) {
                var ledger = ConstructionManager.get(level).reservations();
                sources.forEach((id, stock) -> counts.merge(stock.item(), Math.max(0, stock.count() - ledger.reserved(id)), (left, right) -> (int) Math.min(Integer.MAX_VALUE, (long) left + right)));
                recipes = CraftingService.blockRecipes(level); collected = true; return false;
            }
            if (done()) return false;
            String recipe = Objects.requireNonNull(recipes.get(recipeIndex)); ItemStack output = CraftingService.output(level, recipe); Item item = Objects.requireNonNull(output.getItem());
            if (sites.isEmpty() || output.isEmpty() || craftable.containsKey(item) || counts.getOrDefault(item, 0) > 0 || !CraftingService.hasIngredients(level, recipe, recipeMaterials)) { recipeIndex++; siteIndex = 0; return false; }
            Site site = sites.get(siteIndex++);
            if (site.worker().isAlive() && CraftingService.canProduce(level, site.worker(), site.station(), site.stand(), recipe, ConstructionManager.get(level).reservations())) {
                craftable.put(item, new Entry(id(item), 0, recipe, site.station())); recipeIndex++; siteIndex = 0;
            } else if (siteIndex >= sites.size()) { recipeIndex++; siteIndex = 0; }
            return true;
        }
        @Nonnull public List<Entry> entries() {
            Map<Item, Entry> result = new LinkedHashMap<>(craftable);
            counts.forEach((item, count) -> { if (count > 0) result.put(item, new Entry(id(Objects.requireNonNull(item)), count, "", null)); });
            return Objects.requireNonNull(result.values().stream().sorted(Comparator.comparing(entry -> Objects.requireNonNull(entry).item())).limit(BuildNetwork.MAX_ENTRIES).toList());
        }
        private final class Probe {
            @Nonnull private final WorkerEntity worker;
            @Nonnull private final BlockPos target;
            @Nullable private final ItemEntity drop;
            private final boolean station;
            private final List<BlockPos> stands = new ArrayList<>();
            private int index;
            private Probe(WorkerEntity worker, BlockPos target, @Nullable ItemEntity drop, boolean station) {
                this.worker = worker; this.target = target; this.drop = drop; this.station = station;
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                    BlockPos stand = Objects.requireNonNull(target.offset(dx, dy, dz));
                    if (ConstructionManager.standable(level, stand) && touches(level, worker, stand, target, drop)) stands.add(stand);
                }
                stands.sort(Comparator.comparingDouble(pos -> worker.distanceToSqr(GroundResources.bottom(Objects.requireNonNull(pos)))));
            }
            private boolean step() {
                if (!worker.isAlive() || !owner.equals(worker.owner()) || !GroundResources.searchAreaLoaded(level, worker) || index >= stands.size()) return true;
                BlockPos stand = Objects.requireNonNull(stands.get(index++));
                if (!ConstructionManager.standable(level, stand) || !touches(level, worker, stand, target, drop)
                        || worker.distanceToSqr(GroundResources.bottom(stand)) > 1.1 && GroundResources.path(level, worker, stand) == null) return index >= stands.size();
                ItemEntity itemDrop = drop;
                if (station) {
                    sites.add(new Site(worker, target, stand));
                    if (scannedStations.add(target)) recipeMaterials.addAll(CraftingService.materialsNear(level, owner, target));
                }
                else if (itemDrop != null) {
                    Item item = Objects.requireNonNull(itemDrop.getItem().getItem());
                    if (GroundResources.eligible(itemDrop, worker, item)) sources.put(Objects.requireNonNull(itemDrop.getUUID()), new Stock(item, itemDrop.getItem().getCount()));
                } else for (Item item : itemTypes(level, target, owner)) {
                    var source = StorageResources.resolve(level, target, owner, Objects.requireNonNull(item));
                    if (source != null) sources.put(source.identity(), new Stock(source.item(), source.count()));
                }
                // Crafting banks differ by stand; retain every reachable side of a workstation.
                return !station || index >= stands.size();
            }
        }
    }
    private static boolean touches(ServerLevel level, WorkerEntity worker, BlockPos stand, BlockPos target, @Nullable ItemEntity drop) {
        if (!GroundResources.loaded(level, target)) return false;
        Vec3 end = drop == null ? GroundResources.center(target) : Objects.requireNonNull(drop.position().add(0, .15, 0));
        Vec3 feet = GroundResources.bottom(stand); if (feet.distanceToSqr(end) > (drop == null ? 9 : 3.25)) return false;
        var hit = level.clip(new ClipContext(Objects.requireNonNull(feet.add(0, worker.getEyeHeight(), 0)), end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, worker));
        return hit.getType() == HitResult.Type.MISS || drop == null && hit.getBlockPos().equals(target);
    }
    private static Set<Item> itemTypes(ServerLevel level, BlockPos pos, UUID owner) {
        Set<Item> types = new LinkedHashSet<>(); var block = level.getBlockEntity(pos);
        if (block instanceof MachineBlockEntity machine) {
            if (!machine.permits(owner)) return types;
            for (var entry : StorageAccess.at(level, pos, owner).entries()) if (!entry.item().hasTag()) types.add(entry.item().getItem());
        } else if (block != null) block.getCapability(ForgeCapabilities.ITEM_HANDLER).ifPresent(handler -> {
            for (int slot = 0; slot < handler.getSlots(); slot++) { var stack = handler.getStackInSlot(slot); if (!stack.isEmpty() && !stack.hasTag()) types.add(stack.getItem()); }
        });
        return types;
    }
    @Nonnull private static String id(Item item) { return Objects.requireNonNull(Objects.requireNonNull(ForgeRegistries.ITEMS.getKey(item)).toString()); }
    public static void place(ServerPlayer player, UUID placementId, Item item, BlockPos pos, int rotation, String recipe, @Nullable BlockPos station) {
        ServerLevel level = Objects.requireNonNull(player.serverLevel()); UUID owner = Objects.requireNonNull(player.getUUID());
        ConstructionManager construction = ConstructionManager.get(level); ColonyWorkManager work = ColonyWorkManager.get(level);
        if (construction.readOnly() || work.readOnly()) throw new IllegalArgumentException("data");
        var preset = BlockBuilding.design(item);
        ProjectionPlacement placement = new ProjectionPlacement(placementId, preset, pos.getX(), pos.getY(), pos.getZ(), rotation, false, true);
        if (!recipe.isEmpty()) {
            if (station == null || CraftingService.output(level, recipe).getItem() != item) throw new IllegalArgumentException("recipe");
            ColonyWorkManager.station(player, station, false);
            if (construction.projects(owner).size() + work.jobs(owner).size() + 2 > ConstructionManager.MAX_PROJECTS) throw new IllegalArgumentException("limit");
            boolean ready = false;
            for (WorkerEntity worker : construction.workers(level)) {
                if (!owner.equals(worker.owner()) || !GroundResources.searchAreaLoaded(level, worker)) continue;
                for (int dy : new int[]{0, 1, -1}) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                    BlockPos stand = Objects.requireNonNull(station.offset(dx, dy, dz));
                    if (!ConstructionManager.standable(level, stand) || !touches(level, worker, stand, station, null)) continue;
                    if (worker.distanceToSqr(GroundResources.bottom(stand)) <= 1.1 || GroundResources.path(level, worker, stand) != null) {
                        if (CraftingService.canProduce(level, worker, station, stand, recipe, construction.reservations())) { ready = true; break; }
                    }
                }
                if (ready) break;
            }
            if (!ready) throw new IllegalArgumentException("changed");
        }
        construction.create(player, placement, 5);
        ConstructionProject project = Objects.requireNonNull(construction.project(placementId)); project.palette = true; construction.setDirty();
        if (!recipe.isEmpty() && station != null) work.supply(project, station, recipe);
    }
}
