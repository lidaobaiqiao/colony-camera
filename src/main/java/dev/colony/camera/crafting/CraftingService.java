package dev.colony.camera.crafting;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import dev.colony.camera.content.ColonyContent;
import dev.colony.camera.content.MachineBlockEntity;
import dev.colony.camera.resources.GroundResources;
import dev.colony.camera.resources.StorageResources;
import dev.colony.camera.resources.ReservationLedger;
import dev.colony.camera.storage.StorageNetwork;
import dev.colony.camera.storage.StorageAccess;
import dev.colony.camera.storage.WarehouseInventory;
import dev.colony.camera.tech.TechProgress;
import dev.colony.camera.work.ColonyJob;
import dev.colony.camera.work.WorkTask;
import dev.colony.camera.worker.WorkerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.registries.ForgeRegistries;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.nio.charset.StandardCharsets;
import net.minecraft.nbt.CompoundTag;
import com.mojang.logging.LogUtils;

/** A worker operates a station; each finished recipe is an atomic transaction in real warehouses. */
@ParametersAreNonnullByDefault
public final class CraftingService {
    public static final int CRAFT_TICKS = 60;
    // Registry items resolve when a recipe is used, after Forge registration.
    private record Definition(@Nonnull String id, @Nonnull Supplier<Item> output, int tier) {}
    private record InventoryCopy(@Nonnull MachineBlockEntity holder, @Nonnull WarehouseInventory live, @Nonnull WarehouseInventory staged, @Nonnull BlockPos identity, @Nonnull CompoundTag before) {}
    private record Prepared(@Nonnull String reason, @Nullable Bank bank) {}
    private static final List<Definition> DEFINITIONS = definitions();
    private CraftingService() {}

    @Nonnull private static List<Definition> definitions() {
        return Objects.requireNonNull(List.of(new Definition("colonycamera:printing_core", () -> Objects.requireNonNull(ColonyContent.PRINTING_CORE.get().asItem()), 0),
                new Definition("colonycamera:warehouse", () -> Objects.requireNonNull(ColonyContent.WAREHOUSE.get().asItem()), 0),
                new Definition("colonycamera:colony_workbench", () -> Objects.requireNonNull(ColonyContent.COLONY_WORKBENCH.get().asItem()), 0),
                new Definition("colonycamera:research_desk", () -> Objects.requireNonNull(ColonyContent.RESEARCH_DESK.get().asItem()), 0),
                new Definition("colonycamera:capacity_upgrade", () -> Objects.requireNonNull(ColonyContent.CAPACITY_UPGRADE.get().asItem()), 0),
                new Definition("colonycamera:stack_upgrade", () -> Objects.requireNonNull(ColonyContent.STACK_UPGRADE.get().asItem()), 0),
                new Definition("colonycamera:network_controller", () -> Objects.requireNonNull(ColonyContent.CONTROLLER.get().asItem()), 0),
                new Definition("colonycamera:network_port", () -> Objects.requireNonNull(ColonyContent.PORT.get().asItem()), 0),
                new Definition("colonycamera:network_cable", () -> Objects.requireNonNull(ColonyContent.CABLE.get().asItem()), 0),
                new Definition("minecraft:stick", () -> Objects.requireNonNull(Items.STICK), 0),
                new Definition("minecraft:wooden_pickaxe", () -> Objects.requireNonNull(Items.WOODEN_PICKAXE), 0),
                new Definition("minecraft:wooden_axe", () -> Objects.requireNonNull(Items.WOODEN_AXE), 0),
                new Definition("minecraft:wooden_shovel", () -> Objects.requireNonNull(Items.WOODEN_SHOVEL), 0),
                new Definition("minecraft:stone_pickaxe", () -> Objects.requireNonNull(Items.STONE_PICKAXE), 1),
                new Definition("minecraft:stone_axe", () -> Objects.requireNonNull(Items.STONE_AXE), 1),
                new Definition("minecraft:stone_shovel", () -> Objects.requireNonNull(Items.STONE_SHOVEL), 1),
                new Definition("minecraft:iron_pickaxe", () -> Objects.requireNonNull(Items.IRON_PICKAXE), 2),
                new Definition("minecraft:iron_axe", () -> Objects.requireNonNull(Items.IRON_AXE), 2),
                new Definition("minecraft:iron_shovel", () -> Objects.requireNonNull(Items.IRON_SHOVEL), 2),
                new Definition("minecraft:diamond_pickaxe", () -> Objects.requireNonNull(Items.DIAMOND_PICKAXE), 3),
                new Definition("minecraft:diamond_axe", () -> Objects.requireNonNull(Items.DIAMOND_AXE), 3),
                new Definition("minecraft:diamond_shovel", () -> Objects.requireNonNull(Items.DIAMOND_SHOVEL), 3)));
    }
    @Nullable private static Definition definition(String id) { return DEFINITIONS.stream().filter(value -> value.id().equals(id)).findFirst().orElse(null); }
    @Nullable private static CraftingRecipe recipe(ServerLevel level, String id) {
        ResourceLocation location = ResourceLocation.tryParse(id); if (location == null) return null;
        var value = level.getRecipeManager().byKey(location).orElse(null);
        if (!(value instanceof CraftingRecipe crafting) || crafting.isSpecial() || crafting.getIngredients().isEmpty()
                || crafting.getIngredients().size() > 9 || crafting.getIngredients().stream().allMatch(ingredient -> Objects.requireNonNull(ingredient).isEmpty())) return null;
        if (crafting instanceof ShapedRecipe shaped && shaped.getWidth() >= 1 && shaped.getWidth() <= 3 && shaped.getHeight() >= 1 && shaped.getHeight() <= 3) return shaped;
        return crafting instanceof ShapelessRecipe ? crafting : null;
    }
    @Nullable private static Definition definition(ServerLevel level, String id) {
        Definition known = definition(id); if (known != null) return known;
        CraftingRecipe recipe = recipe(level, id); if (recipe == null) return null;
        ItemStack result = recipe.getResultItem(Objects.requireNonNull(level.registryAccess()));
        if (result.isEmpty() || result.hasTag() || !(result.getItem() instanceof BlockItem)) return null;
        Item item = Objects.requireNonNull(result.getItem()); return new Definition(id, () -> item, 0);
    }
    @Nonnull public static List<String> blockRecipes(ServerLevel level) {
        return Objects.requireNonNull(level.getRecipeManager().getAllRecipesFor(Objects.requireNonNull(RecipeType.CRAFTING)).stream()
                .filter(value -> value.getResultItem(Objects.requireNonNull(level.registryAccess())).getItem() instanceof BlockItem)
                .map(value -> Objects.requireNonNull(value.getId().toString())).filter(id -> definition(level, Objects.requireNonNull(id)) != null).sorted().toList());
    }
    @Nonnull public static ItemStack output(ServerLevel level, String id) {
        CraftingRecipe recipe = recipe(level, id);
        return Objects.requireNonNull(recipe == null ? ItemStack.EMPTY : recipe.getResultItem(Objects.requireNonNull(level.registryAccess())).copy());
    }
    public static boolean hasIngredients(ServerLevel level, String id, Set<Item> materials) {
        CraftingRecipe recipe = recipe(level, id); if (recipe == null) return false;
        return recipe.getIngredients().stream().filter(ingredient -> !ingredient.isEmpty()).allMatch(ingredient ->
                java.util.Arrays.stream(ingredient.getItems()).anyMatch(stack -> materials.contains(stack.getItem())));
    }
    /** Cheap prefilter only; canProduce still checks touch, reservations, quantity and output space. */
    @Nonnull public static Set<Item> materialsNear(ServerLevel level, UUID owner, BlockPos station) {
        Set<Item> result = new java.util.HashSet<>();
        for (int dy = -3; dy <= 3; dy++) for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
            if (dx * dx + dy * dy + dz * dz > 9) continue; BlockPos pos = Objects.requireNonNull(station.offset(dx, dy, dz));
            if (!GroundResources.loaded(level, pos) || !(level.getBlockEntity(pos) instanceof MachineBlockEntity machine)
                    || !machine.permits(owner) || machine.kind() != ColonyContent.Kind.WAREHOUSE && machine.kind() != ColonyContent.Kind.NETWORK_PORT) continue;
            for (var entry : StorageAccess.at(level, pos, owner).entries()) if (!entry.item().hasTag()) result.add(entry.item().getItem());
        }
        return Objects.requireNonNull(result);
    }

    @Nonnull public static JsonArray catalog(ServerLevel level, UUID owner) {
        TechProgress technology = TechProgress.get(level); int tier = technology.tier(owner); JsonArray result = new JsonArray();
        for (Definition definition : DEFINITIONS) {
            CraftingRecipe recipe = recipe(level, definition.id()); if (recipe == null) continue;
            ItemStack output = Objects.requireNonNull(recipe.getResultItem(Objects.requireNonNull(level.registryAccess())));
            if (!validOutput(output, definition)) continue;
            JsonObject entry = new JsonObject(); entry.addProperty("id", definition.id()); entry.addProperty("name", output.getDescriptionId());
            entry.addProperty("output", String.valueOf(ForgeRegistries.ITEMS.getKey(output.getItem()))); entry.addProperty("count", output.getCount());
            entry.addProperty("requiredTier", definition.tier()); entry.addProperty("unlocked", !technology.readOnly() && tier >= definition.tier());
            entry.addProperty("craftTicks", CRAFT_TICKS); JsonArray ingredients = new JsonArray();
            Map<String, JsonObject> aggregated = new LinkedHashMap<>();
            for (Ingredient ingredient : recipe.getIngredients()) {
                if (ingredient.isEmpty()) continue;
                ItemStack[] choices = ingredient.getItems(); if (choices.length == 0) continue;
                String key = String.valueOf(ForgeRegistries.ITEMS.getKey(choices[0].getItem()));
                JsonObject material = aggregated.get(key);
                if (material == null) {
                    material = new JsonObject(); material.addProperty("item", key); material.addProperty("name", choices[0].getDescriptionId()); material.addProperty("count", 0);
                    JsonArray alternatives = new JsonArray(); for (ItemStack choice : choices) alternatives.add(String.valueOf(ForgeRegistries.ITEMS.getKey(choice.getItem())));
                    material.add("alternatives", alternatives); aggregated.put(key, material);
                }
                material.addProperty("count", material.get("count").getAsInt() + 1);
            }
            aggregated.values().forEach(ingredients::add); entry.add("ingredients", ingredients); result.add(entry);
        }
        return result;
    }
    public static boolean supportedRecipe(String id) { return definition(id) != null; }
    /** Uses the same inventory simulation as execution; this never reserves or moves materials. */
    public static boolean canProduce(ServerLevel level, WorkerEntity worker, BlockPos station, BlockPos stand, String id, ReservationLedger ledger) {
        UUID owner = worker.owner();
        if (owner == null || !allowed(level, owner, id) || !safeStand(level, stand)) return false;
        ColonyJob preview = new ColonyJob(Objects.requireNonNull(UUID.randomUUID()), owner, "BuildPreview", ColonyJob.Kind.CRAFT, 5, 0);
        preview.station = station; preview.recipe = id;
        return station(level, worker, preview, station) && prepare(level, worker, preview, stand, ledger, null).bank() != null;
    }
    public static boolean allowed(ServerLevel level, UUID owner, String id) {
        Definition definition = definition(level, id); CraftingRecipe recipe = recipe(level, id); TechProgress technology = TechProgress.get(level);
        return definition != null && recipe != null && !technology.readOnly() && technology.tier(owner) >= definition.tier()
                && validOutput(Objects.requireNonNull(recipe.getResultItem(Objects.requireNonNull(level.registryAccess()))), definition);
    }

    @Nullable public static WorkTask assign(ServerLevel level, WorkerEntity worker, ColonyJob job, ReservationLedger ledger, long time) {
        BlockPos station = job.station;
        if (station == null || !station(level, worker, job, station)) { job.reason = "station"; return null; }
        if (TechProgress.get(level).readOnly()) { job.reason = "tech_data"; return null; }
        if (job.kind == ColonyJob.Kind.CRAFT) {
            Definition definition = definition(level, job.recipe);
            if (definition == null || recipe(level, job.recipe) == null) { job.reason = "recipe"; return null; }
            if (TechProgress.get(level).tier(job.owner) < definition.tier()) { job.reason = "technology"; return null; }
        } else {
            int target = researchTarget(job); int tier = TechProgress.get(level).tier(job.owner);
            if (target == 0) { job.reason = "recipe"; return null; }
            if (tier >= target) { job.done = job.requested; job.reason = "finished"; return null; }
            if (target != tier + 1) { job.reason = "technology"; return null; }
        }
        List<BlockPos> stands = new ArrayList<>();
        for (int dy : new int[]{0, 1, -1}) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue; BlockPos stand = station.offset(dx, dy, dz);
            if (safeStand(level, Objects.requireNonNull(stand))) stands.add(stand);
        }
        stands.sort(Comparator.comparingDouble(pos -> worker.distanceToSqr(GroundResources.bottom(Objects.requireNonNull(pos)))));
        job.reason = "unreachable";
        for (BlockPos stand : stands) {
            var path = GroundResources.path(level, worker, Objects.requireNonNull(stand));
            // A worker placed this tick may not have a grounded navigation state yet. Keep a
            // bounded, loaded assignment and let the next server tick calculate the path.
            double distance = worker.distanceToSqr(GroundResources.bottom(Objects.requireNonNull(stand)));
            if (path == null && distance > 1.1 && (worker.onGround()
                    || distance > (long) WorkerEntity.NAVIGATION_RANGE * WorkerEntity.NAVIGATION_RANGE)) continue;
            Prepared prepared = prepare(level, worker, job, stand, ledger, null); Bank bank = prepared.bank();
            if (bank == null) { if (reasonWeight(prepared.reason()) > reasonWeight(job.reason)) job.reason = prepared.reason(); continue; }
            if (!ledger.reserveBatch(Objects.requireNonNull(worker.getUUID()), job.id, bank.demands())) { job.reason = "reserved"; continue; }
            WorkTask task = new WorkTask(job.id, Objects.requireNonNull(stand.immutable()), time + 1800);
            bank.demands().forEach((source, demand) -> task.materialClaims.put(Objects.requireNonNull(source), demand.quantity()));
            return task;
        }
        if (stands.isEmpty()) job.reason = "unreachable";
        return null;
    }
    private static int reasonWeight(String reason) {
        return switch (reason) { case "full" -> 5; case "reserved" -> 4; case "materials" -> 3; case "storage" -> 2; default -> 1; };
    }

    /** Returns true when this unit finished or must release its worker and be reassigned. */
    public static boolean execute(ServerLevel level, WorkerEntity worker, ColonyJob job, WorkTask task, ReservationLedger ledger, long time) {
        BlockPos station = job.station;
        if (station == null || !job.runnable() || !station(level, worker, job, station)) { job.reason = "station"; return true; }
        if (time > task.deadline) { job.reason = "unreachable"; return true; }
        if (!safeStand(level, task.stand)) { job.reason = "unreachable"; return true; }
        if (worker.distanceToSqr(GroundResources.bottom(task.stand)) > 1.1) {
            task.progress = 0; job.reason = "walking";
            if (worker.getNavigation().isDone()) {
                var path = GroundResources.path(level, worker, task.stand);
                if (path == null || !GroundResources.follow(level, worker, path)) {
                    if (!worker.onGround()) { job.reason = "walking"; return false; }
                    job.reason = "unreachable"; return true;
                }
            }
            return false;
        }
        worker.getNavigation().stop();
        if (!StorageResources.canTake(level, worker, station)) { job.reason = "unreachable"; return true; }
        int tier = TechProgress.get(level).tier(job.owner);
        int target = researchTarget(job);
        if (job.kind == ColonyJob.Kind.RESEARCH && tier >= target && target != 0) { job.done = job.requested; job.reason = "finished"; return true; }
        int duration = job.kind == ColonyJob.Kind.RESEARCH ? TechProgress.researchTicks(target) : CRAFT_TICKS;
        if (duration == 0 || TechProgress.get(level).readOnly()) { job.reason = tier == 3 ? "researched" : "tech_data"; return true; }
        Prepared prepared = null;
        if (task.progress % 20 == 0 || task.progress + 1 >= duration) {
            prepared = prepare(level, worker, job, task.stand, ledger, task.materialClaims);
            Bank current = prepared.bank(); if (current == null) { job.reason = prepared.reason(); return true; }
            if (!current.matches(task.materialClaims)) { job.reason = "reserved"; return true; }
        }
        task.progress++; job.reason = job.kind == ColonyJob.Kind.RESEARCH ? "researching" : "crafting";
        if (task.progress < duration) return false;
        if (prepared == null) prepared = prepare(level, worker, job, task.stand, ledger, task.materialClaims);
        Bank bank = prepared.bank(); if (bank == null) { job.reason = prepared.reason(); return true; }
        if (!bank.matches(task.materialClaims)) { job.reason = "reserved"; return true; }
        // All inventory mutations and the unlock happen on one server tick after the final recheck.
        try {
            if (job.kind == ColonyJob.Kind.RESEARCH) {
                if (!TechProgress.get(level).advance(job.owner, tier, bank::commit)) { job.reason = "technology"; return true; }
            } else bank.commit();
        } catch (RuntimeException failure) {
            LogUtils.getLogger().warn("Colony station transaction rolled back for job {}", job.id, failure); job.reason = "transaction"; return true;
        }
        job.done++; job.reason = job.finished() ? "finished" : "queued"; return true;
    }
    private static boolean station(ServerLevel level, WorkerEntity worker, ColonyJob job, BlockPos pos) {
        if (!job.owner.equals(worker.owner()) || !GroundResources.loaded(level, pos)
                || !(level.getBlockEntity(pos) instanceof MachineBlockEntity machine) || !machine.permits(job.owner)) return false;
        ColonyContent.Kind kind = job.kind == ColonyJob.Kind.RESEARCH ? ColonyContent.Kind.RESEARCH_DESK : ColonyContent.Kind.COLONY_WORKBENCH;
        return machine.kind() == kind && level.mayInteract(actor(level, worker, job), pos);
    }
    @Nonnull private static FakePlayer actor(ServerLevel level, WorkerEntity worker, ColonyJob job) {
        FakePlayer actor = Objects.requireNonNull(FakePlayerFactory.get(level, new GameProfile(job.owner, job.ownerName)));
        actor.moveTo(worker.getX(), worker.getY(), worker.getZ()); return actor;
    }
    private static boolean safeStand(ServerLevel level, BlockPos pos) {
        if (!GroundResources.loaded(level, pos) || !GroundResources.loaded(level, Objects.requireNonNull(pos.below()))
                || !GroundResources.loaded(level, Objects.requireNonNull(pos.above()))) return false;
        BlockState floor = level.getBlockState(Objects.requireNonNull(pos.below()));
        return floor.isFaceSturdy(level, Objects.requireNonNull(pos.below()), net.minecraft.core.Direction.UP)
                && level.getBlockState(pos).getFluidState().isEmpty() && level.getBlockState(Objects.requireNonNull(pos.above())).getFluidState().isEmpty()
                // Match the construction stand contract so arrival and redispatch use
                // the same block collision rules.
                && level.noCollision(GroundResources.body(pos));
    }
    @Nonnull private static Prepared prepare(ServerLevel level, WorkerEntity worker, ColonyJob job, @Nullable BlockPos stand, ReservationLedger ledger, @Nullable Map<UUID, Integer> claims) {
        BlockPos station = job.station; if (station == null) return new Prepared("station", null);
        Bank bank = bank(level, worker, job, station, stand, ledger, claims); if (bank.copies.isEmpty()) return new Prepared("storage", null);
        if (!bank.claimsValid()) return new Prepared("reserved", null);
        int tier = TechProgress.get(level).tier(job.owner);
        if (job.kind == ColonyJob.Kind.RESEARCH) {
            int target = researchTarget(job);
            if (target != tier + 1 || TechProgress.get(level).readOnly()) return new Prepared("technology", null);
            for (TechProgress.Cost cost : TechProgress.costs(target)) if (!bank.consume(new ItemStack(cost.item()), cost.count())) return new Prepared("materials", null);
            return new Prepared("ready", bank);
        }
        Definition definition = definition(level, job.recipe); CraftingRecipe recipe = recipe(level, job.recipe);
        if (definition == null || recipe == null) return new Prepared("recipe", null);
        if (tier < definition.tier()) return new Prepared("technology", null);
        RecipeGrid grid = new RecipeGrid(); int[] attempts = {0};
        if (!bank.fill(recipe, grid, 0, attempts)) return new Prepared("materials", null);
        if (!recipe.matches(grid, level)) return new Prepared("recipe", null);
        ItemStack output = Objects.requireNonNull(recipe.assemble(grid, Objects.requireNonNull(level.registryAccess()))); if (!validOutput(output, definition)) return new Prepared("recipe", null);
        List<ItemStack> remainders = recipe.getRemainingItems(grid); if (remainders.size() != 9) return new Prepared("recipe", null);
        if (!bank.insert(output)) return new Prepared("full", null);
        for (ItemStack remainder : remainders) if (!remainder.isEmpty() && !bank.insert(Objects.requireNonNull(remainder))) return new Prepared("full", null);
        return new Prepared("ready", bank);
    }
    private static boolean validOutput(ItemStack output, Definition definition) {
        return !output.isEmpty() && output.is(Objects.requireNonNull(definition.output().get())) && output.getCount() >= 1 && output.getCount() <= output.getMaxStackSize();
    }
    private static int researchTarget(ColonyJob job) {
        return switch (job.recipe) { case "1" -> 1; case "2" -> 2; case "3" -> 3; default -> 0; };
    }
    @Nonnull private static Bank bank(ServerLevel level, WorkerEntity worker, ColonyJob job, BlockPos station, @Nullable BlockPos stand, ReservationLedger ledger, @Nullable Map<UUID, Integer> claims) {
        Bank bank = new Bank(level, ledger, claims); Set<WarehouseInventory> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        FakePlayer actor = actor(level, worker, job);
        for (int dy = -3; dy <= 3; dy++) for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
            if (dx * dx + dy * dy + dz * dz > 9) continue; BlockPos pos = station.offset(dx, dy, dz);
            if (!GroundResources.loaded(level, Objects.requireNonNull(pos)) || !level.mayInteract(actor, pos)
                    || !(level.getBlockEntity(pos) instanceof MachineBlockEntity machine) || !machine.permits(job.owner)
                    || machine.kind() != ColonyContent.Kind.WAREHOUSE && machine.kind() != ColonyContent.Kind.NETWORK_PORT
                    || !accessible(level, worker, pos, stand)) continue;
            StorageNetwork.Component component = StorageNetwork.find(level, pos, job.owner);
            if (machine.kind() == ColonyContent.Kind.WAREHOUSE) {
                // A connected warehouse publishes its stock through ports, exactly like construction.
                if (component.valid() || component.status().equals("multiple_controllers") || component.status().equals("too_large")) continue;
                if (!machine.inventory.readOnly() && seen.add(machine.inventory)) bank.add(machine, pos);
            } else if (component.valid() && component.controller() != null) {
                for (MachineBlockEntity box : component.warehouses()) if (!box.inventory.readOnly() && seen.add(box.inventory)) bank.add(box, Objects.requireNonNull(component.controller()));
            }
        }
        return bank;
    }
    private static boolean accessible(ServerLevel level, WorkerEntity worker, BlockPos pos, @Nullable BlockPos stand) {
        if (stand == null) return StorageResources.canTake(level, worker, pos);
        Vec3 body = GroundResources.bottom(stand); if (body.distanceToSqr(GroundResources.center(pos)) > 9) return false;
        // Assignment and transaction rechecks use the same reachable station stand.
        // The worker can stop slightly off its center; that must not change which
        // side of the workbench supplies stock, while a wall still blocks access.
        Vec3 eye = Objects.requireNonNull(body.add(0, worker.getEyeHeight(), 0));
        var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, GroundResources.center(pos),
                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, worker));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS || hit.getBlockPos().equals(pos);
    }
    private static final class Bank {
        private final List<InventoryCopy> copies = new ArrayList<>();
        @Nonnull private final ServerLevel level;
        private final ReservationLedger ledger;
        @Nullable private final Map<UUID, Integer> claims;
        private final Map<UUID, Integer> stocks = new LinkedHashMap<>();
        private final Map<UUID, Integer> taken = new LinkedHashMap<>();
        Bank(ServerLevel level, ReservationLedger ledger, @Nullable Map<UUID, Integer> claims) { this.level = level; this.ledger = ledger; this.claims = claims; }
        void add(MachineBlockEntity holder, BlockPos identity) {
            WarehouseInventory live = holder.inventory; CompoundTag before = Objects.requireNonNull(live.save());
            WarehouseInventory staged = new WarehouseInventory(() -> {}); staged.load(before);
            InventoryCopy copy = new InventoryCopy(holder, live, staged, identity, before); copies.add(copy);
            for (int slot = 0; slot < live.getSlots(); slot++) {
                ItemStack item = live.getStackInSlot(slot);
                if (!item.isEmpty() && GroundResources.matches(item, Objects.requireNonNull(item.getItem()))) stocks.merge(source(copy, item), item.getCount(), (a, b) -> Objects.requireNonNull(a) + Objects.requireNonNull(b));
            }
        }
        @Nonnull UUID source(InventoryCopy copy, ItemStack item) {
            return Objects.requireNonNull(UUID.nameUUIDFromBytes((level.dimension().location() + "/" + copy.identity().asLong() + "/" + ForgeRegistries.ITEMS.getKey(item.getItem())).getBytes(StandardCharsets.UTF_8)));
        }
        boolean available(UUID source) {
            var activeClaims = this.claims;
            if (activeClaims != null && !activeClaims.containsKey(source)) return false;
            int ownClaim = activeClaims == null ? 0 : Objects.requireNonNull(activeClaims.getOrDefault(source, 0));
            return taken.getOrDefault(source, 0) < stocks.getOrDefault(source, 0) - ledger.reserved(source) + ownClaim;
        }
        @Nonnull Map<UUID, ReservationLedger.Demand> demands() {
            Map<UUID, ReservationLedger.Demand> result = new LinkedHashMap<>();
            taken.forEach((source, quantity) -> { if (quantity > 0) result.put(Objects.requireNonNull(source), new ReservationLedger.Demand(stocks.getOrDefault(source, 0), quantity)); });
            return result;
        }
        boolean matches(Map<UUID, Integer> claims) {
            for (var entry : taken.entrySet()) if (entry.getValue() > claims.getOrDefault(entry.getKey(), 0)) return false;
            return true;
        }
        boolean claimsValid() {
            var activeClaims = this.claims;
            if (activeClaims != null) for (UUID source : activeClaims.keySet()) if (stocks.getOrDefault(source, 0) < ledger.reserved(Objects.requireNonNull(source))) return false;
            return true;
        }
        boolean consume(ItemStack key, int quantity) {
            int remaining = quantity;
            for (InventoryCopy copy : copies) for (int slot = 0; slot < copy.staged().getSlots() && remaining > 0; slot++) {
                ItemStack candidate = copy.staged().getStackInSlot(slot);
                if (!ItemStack.isSameItemSameTags(key, Objects.requireNonNull(candidate))) continue;
                UUID source = source(copy, candidate); var activeClaims = this.claims;
                if (activeClaims != null && !activeClaims.containsKey(source)) continue;
                int ownClaim = activeClaims == null ? 0 : Objects.requireNonNull(activeClaims.getOrDefault(source, 0));
                int allowed = stocks.getOrDefault(source, 0) - taken.getOrDefault(source, 0) - ledger.reserved(source) + ownClaim;
                if (allowed <= 0) continue;
                int consumed = copy.staged().extractItem(slot, Math.min(remaining, allowed), false).getCount(); remaining -= consumed;
                taken.merge(source, consumed, (a, b) -> Objects.requireNonNull(a) + Objects.requireNonNull(b));
            }
            return remaining == 0;
        }
        boolean insert(ItemStack item) {
            ItemStack residual = item.copy();
            for (InventoryCopy copy : copies) { residual = ItemHandlerHelper.insertItemStacked(copy.staged(), residual, false); if (residual.isEmpty()) return true; }
            return residual.isEmpty();
        }
        boolean fill(CraftingRecipe recipe, RecipeGrid grid, int index, int[] attempts) {
            if (index >= recipe.getIngredients().size()) return true;
            if (++attempts[0] > 2048) return false;
            Ingredient ingredient = recipe.getIngredients().get(index);
            int gridSlot = recipe instanceof ShapedRecipe shaped ? index / shaped.getWidth() * 3 + index % shaped.getWidth() : index;
            if (ingredient.isEmpty()) return fill(recipe, grid, index + 1, attempts);
            for (InventoryCopy copy : copies) for (int slot = 0; slot < copy.staged().getSlots(); slot++) {
                ItemStack candidate = copy.staged().getStackInSlot(slot);
                if (candidate.isEmpty() || !GroundResources.matches(candidate, Objects.requireNonNull(candidate.getItem())) || !ingredient.test(candidate)) continue;
                UUID source = source(copy, candidate); if (!available(source)) continue;
                ItemStack before = candidate.copy(); ItemStack consumed = Objects.requireNonNull(copy.staged().extractItem(slot, 1, false)); grid.setItem(gridSlot, consumed);
                taken.merge(source, 1, (a, b) -> Objects.requireNonNull(a) + Objects.requireNonNull(b));
                if (fill(recipe, grid, index + 1, attempts)) return true;
                copy.staged().setStackInSlot(slot, before); grid.setItem(gridSlot, Objects.requireNonNull(ItemStack.EMPTY));
                taken.computeIfPresent(source, (ignored, amount) -> amount - 1);
            }
            return false;
        }
        void commit() {
            try {
                for (InventoryCopy copy : copies) {
                    copy.live().load(copy.staged().save());
                    if (copy.live().readOnly()) throw new IllegalStateException("Warehouse rejected staged transaction");
                    copy.holder().setChanged();
                }
                StorageNetwork.invalidate(level);
            } catch (RuntimeException failure) {
                // Every live inventory has an original snapshot, including writes preceding the failure.
                for (InventoryCopy copy : copies) copy.live().load(copy.before());
                StorageNetwork.invalidate(level);
                throw failure;
            }
        }
    }
}
