package dev.colony.camera.client.screen;

import dev.colony.camera.config.SelectionConfig;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

/** A modal inside the planning screen, so editing preferences retains the camera session. */
final class SelectionLimitPanel {
    private boolean open;
    @Nullable private Font font;
    @Nullable private EditBox input;
    private String remembered = "";
    private boolean saveFailed;
    private StrategyHudLayout.Rect bounds = new StrategyHudLayout.Rect(0, 0, 0, 0);
    private StrategyHudLayout.Rect accept = bounds;
    private StrategyHudLayout.Rect cancel = bounds;

    void resize(@Nonnull Font currentFont, StrategyHudLayout.Rect dialog) {
        if (input != null) remembered = input.getValue();
        font = currentFont;
        bounds = dialog;
        input = null;
        accept = cancel = new StrategyHudLayout.Rect(0, 0, 0, 0);
        if (dialog.width() < 120 || dialog.height() < 116) return;
        int buttonWidth = (dialog.width() - 36) / 2;
        accept = new StrategyHudLayout.Rect(dialog.x() + 12, dialog.bottom() - 32, buttonWidth, 20);
        cancel = new StrategyHudLayout.Rect(accept.right() + 12, accept.y(), buttonWidth, 20);
        var field = new EditBox(currentFont, dialog.x() + 12, dialog.y() + 44, dialog.width() - 24, 20, text("title"));
        field.setMaxLength(5);
        field.setFilter(value -> value.matches("[0-9]*"));
        field.setValue(Objects.requireNonNull(remembered));
        field.setFocused(true);
        input = field;
    }

    void open() {
        open = true;
        saveFailed = false;
        remembered = Objects.requireNonNull(Integer.toString(SelectionConfig.similarLimit()));
        EditBox field = input;
        if (field != null) { field.setValue(Objects.requireNonNull(remembered)); field.setFocused(true); }
    }

    boolean isOpen() { return open; }
    void close() { open = false; }
    void tick() { if (open && input != null) input.tick(); }

    boolean keyPressed(int key, int scan, int modifiers) {
        if (!open) return false;
        if (key == GLFW.GLFW_KEY_ESCAPE) close();
        else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) save();
        else if (input != null) input.keyPressed(key, scan, modifiers);
        return true;
    }

    boolean charTyped(char character, int modifiers) {
        if (!open) return false;
        if (input != null) input.charTyped(character, modifiers);
        return true;
    }

    boolean click(double x, double y, int button) {
        if (!open) return false;
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && accept.contains(x, y)) save();
        else if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && cancel.contains(x, y)) close();
        else if (input != null) input.mouseClicked(x, y, button);
        return true;
    }

    private int candidate() {
        if (input == null) return -1;
        try {
            int value = Integer.parseInt(input.getValue());
            return value >= 1 && value <= SelectionConfig.MAX_SIMILAR_LIMIT ? value : -1;
        } catch (NumberFormatException invalid) { return -1; }
    }

    private void save() {
        int value = candidate();
        if (value < 1) return;
        try { SelectionConfig.similarLimit(value); close(); }
        catch (RuntimeException failure) { saveFailed = true; }
    }

    void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, int width, int height) {
        Font currentFont = font;
        if (!open || currentFont == null) return;
        graphics.fill(0, 0, width, height, 0xA0071019);
        StrategyHud.panel(graphics, bounds);
        StrategyHud.drawFitted(graphics, currentFont, text("title"), bounds.x() + 12, bounds.y() + 10, bounds.width() - 24, UiTheme.ACCENT);
        EditBox field = input;
        if (field == null) return;
        StrategyHud.drawFitted(graphics, currentFont, text("description"), bounds.x() + 12, bounds.y() + 27, bounds.width() - 24, UiTheme.TEXT);
        field.render(graphics, mouseX, mouseY, partialTick);
        StrategyHud.drawFitted(graphics, currentFont, text(saveFailed ? "save_failed" : "range", SelectionConfig.MAX_SIMILAR_LIMIT),
                bounds.x() + 12, bounds.y() + 72, bounds.width() - 24, candidate() > 0 && !saveFailed ? UiTheme.SECONDARY : UiTheme.WARNING);
        button(graphics, currentFont, accept, text("save"), candidate() > 0, mouseX, mouseY);
        button(graphics, currentFont, cancel, text("cancel"), true, mouseX, mouseY);
    }

    private static void button(GuiGraphics graphics, @Nonnull Font font, StrategyHudLayout.Rect rect, @Nonnull Component label,
                               boolean enabled, int mouseX, int mouseY) {
        if (rect.width() == 0) return;
        StrategyHud.panel(graphics, rect);
        if (enabled && rect.contains(mouseX, mouseY)) graphics.fill(rect.x() + 1, rect.y() + 1, rect.right() - 1, rect.bottom() - 1, UiTheme.RAISED);
        StrategyHud.drawFitted(graphics, font, label, rect.x() + 6, rect.y() + 6, rect.width() - 12, enabled ? UiTheme.TEXT : UiTheme.SECONDARY);
    }

    @Nonnull private static Component text(@Nonnull String key, @Nonnull Object... args) {
        return Objects.requireNonNull(Component.translatable("screen.colonycamera.similar_limit." + key, args));
    }
}
