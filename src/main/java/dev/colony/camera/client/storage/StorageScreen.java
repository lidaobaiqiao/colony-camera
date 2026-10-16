package dev.colony.camera.client.storage;

import dev.colony.camera.client.screen.UiTheme;

import dev.colony.camera.ColonyCamera;
import dev.colony.camera.content.ColonyContent;
import dev.colony.camera.storage.StorageMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Objects;

@Mod.EventBusSubscriber(modid = ColonyCamera.ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
@ParametersAreNonnullByDefault
public final class StorageScreen extends AbstractContainerScreen<StorageMenu> {
    @Nonnull private final Minecraft client = Objects.requireNonNull(Minecraft.getInstance());
    public StorageScreen(StorageMenu menu, Inventory inventory, Component title) { super(menu, inventory, title); imageWidth = 194; imageHeight = 232; inventoryLabelX = 17; inventoryLabelY = 136; titleLabelX = 9; titleLabelY = 8; }
    @Override protected void init() {
        super.init();
        addRenderableWidget(Objects.requireNonNull(Button.builder(Objects.requireNonNull(Component.literal("‹")), button -> turnPage(0)).bounds(leftPos + 17, topPos + 112, 22, 18).build()));
        addRenderableWidget(Objects.requireNonNull(Button.builder(Objects.requireNonNull(Component.literal("›")), button -> turnPage(1)).bounds(leftPos + 155, topPos + 112, 22, 18).build()));
    }
    private void turnPage(int direction) { var gameMode = client.gameMode; if (gameMode != null) gameMode.handleInventoryButtonClick(menu.containerId, direction); }
    @Override protected void renderBg(GuiGraphics graphics, float partial, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, UiTheme.SURFACE);
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + 2, UiTheme.ACCENT);
        for (var slot : menu.slots) graphics.fill(leftPos + slot.x - 1, topPos + slot.y - 1, leftPos + slot.x + 17, topPos + slot.y + 17, UiTheme.RAISED);
    }
    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        Font currentFont = Objects.requireNonNull(font);
        graphics.drawString(currentFont, Objects.requireNonNull(title), titleLabelX, titleLabelY, UiTheme.TEXT, false);
        Component status = menu.status() == 0 ? Component.translatable("colonycamera.storage.capacity", 27 * (menu.capacityLevel() + 1), 1 << menu.stackLevel())
                : Component.translatable("colonycamera.storage.network_status." + menu.status());
        graphics.drawString(currentFont, Objects.requireNonNull(status), 9, 22, UiTheme.ACCENT, false);
        graphics.drawCenteredString(currentFont, Objects.requireNonNull(Component.translatable("colonycamera.storage.page", menu.page() + 1, menu.pages())), imageWidth / 2, 117, UiTheme.TEXT);
        graphics.drawString(currentFont, Objects.requireNonNull(playerInventoryTitle), inventoryLabelX, inventoryLabelY, UiTheme.SECONDARY, false);
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        renderBackground(graphics); super.render(graphics, mouseX, mouseY, partial);
        Font currentFont = Objects.requireNonNull(font);
        for (int index = 0; index < StorageMenu.PAGE_SIZE; index++) {
            int count = menu.count(index); if (count <= menu.slots.get(index).getItem().getMaxStackSize()) continue;
            var slot = menu.slots.get(index); String label = count >= 10000 ? (count / 1000) + "k" : String.valueOf(count);
            int x = leftPos + slot.x + 17 - currentFont.width(Objects.requireNonNull(label)); int y = topPos + slot.y + 9;
            graphics.fill(x - 1, y - 1, leftPos + slot.x + 18, y + 8, 0xE011202A); graphics.drawString(currentFont, Objects.requireNonNull(label), x, y, UiTheme.ACCENT, true);
        }
        renderTooltip(graphics, mouseX, mouseY);
        var hovered = hoveredSlot;
        if (hovered != null && hovered.index < StorageMenu.PAGE_SIZE && menu.count(hovered.index) > 0) graphics.renderTooltip(currentFont,
                Objects.requireNonNull(java.util.List.of(hovered.getItem().getHoverName(), Component.translatable("colonycamera.storage.total", menu.count(hovered.index)))), Objects.requireNonNull(java.util.Optional.empty()), mouseX, mouseY);
    }
    @SubscribeEvent public static void setup(FMLClientSetupEvent event) { event.enqueueWork(() -> MenuScreens.register(Objects.requireNonNull(ColonyContent.STORAGE_MENU.get()), StorageScreen::new)); }
}
