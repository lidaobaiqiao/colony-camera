package dev.colony.camera.client.work;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import javax.annotation.Nonnull;
import java.util.Objects;

/** Normal station screen; the strategy camera uses its embedded panel instead. */
public final class ColonyWorkScreen extends Screen {
    private final WorkPanel panel = new WorkPanel(false);
    public ColonyWorkScreen() { super(Component.translatable("colonycamera.work.title")); panel.open(WorkPanel.Tab.PEOPLE); }
    public ColonyWorkScreen(@Nonnull BlockPos residence) { super(Component.translatable("colonycamera.work.title")); panel.openResidences(residence); }
    public ColonyWorkScreen(@Nonnull BlockPos station, boolean research) {
        super(Component.translatable("colonycamera.work.title")); panel.openStation(station, research);
    }
    @Override protected void init() { panel.resize(Objects.requireNonNull(font), width, height); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void tick() { panel.tick(); if (!panel.isOpen()) onClose(); }
    @Override public boolean keyPressed(int key, int scan, int modifiers) { boolean handled = panel.keyPressed(key, scan, modifiers); if (!panel.isOpen()) onClose(); return handled; }
    @Override public boolean charTyped(char character, int modifiers) { return panel.charTyped(character, modifiers); }
    @Override public boolean mouseClicked(double x, double y, int button) { boolean handled = panel.click(x, y, button); if (!panel.isOpen()) onClose(); return handled; }
    @Override public boolean mouseScrolled(double x, double y, double delta) { return panel.scroll(x, y, delta); }
    @Override public void render(@Nonnull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) { panel.render(graphics, mouseX, mouseY); }
}
