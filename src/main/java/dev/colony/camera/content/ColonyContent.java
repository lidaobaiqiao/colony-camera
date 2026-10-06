package dev.colony.camera.content;

import dev.colony.camera.ColonyCamera;
import dev.colony.camera.storage.StorageMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Objects;

@ParametersAreNonnullByDefault
public final class ColonyContent {
    public enum Kind { PRINTING_CORE, WAREHOUSE, NETWORK_CONTROLLER, NETWORK_PORT, NETWORK_CABLE, COLONY_WORKBENCH, RESEARCH_DESK }
    @Nonnull public static final DeferredRegister<Block> BLOCKS = Objects.requireNonNull(DeferredRegister.create(ForgeRegistries.BLOCKS, ColonyCamera.ID));
    @Nonnull public static final DeferredRegister<Item> ITEMS = Objects.requireNonNull(DeferredRegister.create(ForgeRegistries.ITEMS, ColonyCamera.ID));
    @Nonnull public static final DeferredRegister<BlockEntityType<?>> ENTITIES = Objects.requireNonNull(DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, ColonyCamera.ID));
    @Nonnull public static final DeferredRegister<MenuType<?>> MENUS = Objects.requireNonNull(DeferredRegister.create(ForgeRegistries.MENU_TYPES, ColonyCamera.ID));
    @Nonnull public static final DeferredRegister<CreativeModeTab> TABS = Objects.requireNonNull(DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ColonyCamera.ID));
    @Nonnull public static final RegistryObject<Block> PRINTING_CORE = block("printing_core", Kind.PRINTING_CORE);
    @Nonnull public static final RegistryObject<Block> WAREHOUSE = block("warehouse", Kind.WAREHOUSE);
    @Nonnull public static final RegistryObject<Block> CONTROLLER = block("network_controller", Kind.NETWORK_CONTROLLER);
    @Nonnull public static final RegistryObject<Block> PORT = block("network_port", Kind.NETWORK_PORT);
    @Nonnull public static final RegistryObject<Block> CABLE = block("network_cable", Kind.NETWORK_CABLE);
    @Nonnull public static final RegistryObject<Block> COLONY_WORKBENCH = block("colony_workbench", Kind.COLONY_WORKBENCH);
    @Nonnull public static final RegistryObject<Block> RESEARCH_DESK = block("research_desk", Kind.RESEARCH_DESK);
    @Nonnull public static final RegistryObject<Item> CAPACITY_UPGRADE = Objects.requireNonNull(ITEMS.register("capacity_upgrade", () -> new WarehouseUpgradeItem(false)));
    @Nonnull public static final RegistryObject<Item> STACK_UPGRADE = Objects.requireNonNull(ITEMS.register("stack_upgrade", () -> new WarehouseUpgradeItem(true)));
    @Nonnull public static final RegistryObject<BlockEntityType<MachineBlockEntity>> MACHINE = Objects.requireNonNull(ENTITIES.register("machine", ColonyContent::machineType));
    // Forge mod registrations have no vanilla DFU type; the builder accepts null despite its default contract.
    @SuppressWarnings("null")
    @Nonnull private static BlockEntityType<MachineBlockEntity> machineType() {
        return Objects.requireNonNull(BlockEntityType.Builder.of(MachineBlockEntity::new, Objects.requireNonNull(PRINTING_CORE.get()), Objects.requireNonNull(WAREHOUSE.get()), Objects.requireNonNull(CONTROLLER.get()), Objects.requireNonNull(PORT.get()), Objects.requireNonNull(CABLE.get()), Objects.requireNonNull(COLONY_WORKBENCH.get()), Objects.requireNonNull(RESEARCH_DESK.get())).build(null));
    }
    @Nonnull public static final RegistryObject<MenuType<StorageMenu>> STORAGE_MENU = Objects.requireNonNull(MENUS.register("storage", () -> IForgeMenuType.create(StorageMenu::new)));
    @Nonnull public static final RegistryObject<CreativeModeTab> COLONY_TAB = Objects.requireNonNull(TABS.register("colony", () -> CreativeModeTab.builder()
            .title(Objects.requireNonNull(Component.translatable("itemGroup.colonycamera.colony"))).icon(() -> new ItemStack(Objects.requireNonNull(PRINTING_CORE.get())))
            .displayItems((parameters, output) -> {
                output.accept(Objects.requireNonNull(PRINTING_CORE.get())); output.accept(Objects.requireNonNull(WAREHOUSE.get())); output.accept(Objects.requireNonNull(CAPACITY_UPGRADE.get())); output.accept(Objects.requireNonNull(STACK_UPGRADE.get()));
                output.accept(Objects.requireNonNull(CONTROLLER.get())); output.accept(Objects.requireNonNull(PORT.get())); output.accept(Objects.requireNonNull(CABLE.get()));
                output.accept(Objects.requireNonNull(COLONY_WORKBENCH.get())); output.accept(Objects.requireNonNull(RESEARCH_DESK.get()));
            }).build()));
    private ColonyContent() {}
    @Nonnull private static RegistryObject<Block> block(String name, Kind kind) {
        RegistryObject<Block> result = Objects.requireNonNull(BLOCKS.register(name, () -> new MachineBlock(kind)));
        ITEMS.register(name, () -> new MachineBlockItem(Objects.requireNonNull(result.get()), kind));
        return result;
    }
    public static void register(IEventBus bus) { BLOCKS.register(bus); ITEMS.register(bus); ENTITIES.register(bus); MENUS.register(bus); TABS.register(bus); }
}
