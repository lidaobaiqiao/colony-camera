package dev.colony.camera.client.screen;

import dev.colony.camera.client.camera.StrategyCameraController;
import dev.colony.camera.client.input.CameraKeyMappings;
import dev.colony.camera.client.selection.BlockSelectionController;
import dev.colony.camera.client.planning.ProjectionController;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Draws the strategy HUD. Geometry and input ownership use the same layout. */
final class StrategyHud {
    private static final int BACKGROUND = 0xE0101C28;
    private static final int BORDER = 0xFF334958;
    private static final int TEXT = 0xE8F1F5;
    private static final int MUTED = 0x90A8B8;
    private static final int ACCENT = 0x67DEC2;
    private static final int GOLD = 0xFFD379;
    private static final int HOVER = 0xFF263B4B;
    private static final int ACTIVE = 0xFF203E40;

    private StrategyHudLayout layout;
    private boolean helpOpen;
    private List<HelpRow> helpRows = List.of();

    void resize(int width, int height) {
        layout = new StrategyHudLayout(width, height);
        helpRows = List.of(
                new HelpRow(text("mouse.left"), text("help.similar")),
                new HelpRow(text("mouse.left"), text("help.volume")),
                new HelpRow(text("mouse.right"), text("help.pan")),
                new HelpRow(text("mouse.middle"), text("help.rotate")),
                new HelpRow(CameraKeyMappings.angleLockKeyName(), text("help.angle_lock")),
                new HelpRow(text("mouse.wheel"), text("help.zoom")),
                new HelpRow(Objects.requireNonNull(Component.literal("W / A / S / D")), text("help.move")),
                new HelpRow(Objects.requireNonNull(Component.empty().append(CameraKeyMappings.moveUpKeyName()).append(" / ")
                        .append(CameraKeyMappings.moveDownKeyName())), text("help.elevation")),
                new HelpRow(Objects.requireNonNull(Component.literal("Shift")), text("help.speed")),
                new HelpRow(CameraKeyMappings.addSelectionKeyName(), text("help.additive")),
                new HelpRow(CameraKeyMappings.volumeSelectionKeyName(), text("help.mode")),
                new HelpRow(clearBinding(), text("help.clear")),
                new HelpRow(Objects.requireNonNull(Component.empty().append(CameraKeyMappings.toggleKeyName()).append(" / Esc")), text("help.exit")));
    }

    StrategyHudLayout layout() { return layout; }
    boolean helpOpen() { return helpOpen; }
    void toggleHelp() { helpOpen = !helpOpen; }
    void closeHelp() { helpOpen = false; }

    void render(GuiGraphics graphics, @Nonnull Font font, StrategyCameraController controller, int mouseX, int mouseY,
                int width, int height) {
        BlockSelectionController selection = controller.selection();
        drawTitle(graphics, font);
        if (ProjectionController.instance().draft() == null) drawSelection(graphics, font, selection);
        panel(graphics, layout.dock());
        for (StrategyHudLayout.Control control : layout.controls()) {
            drawControl(graphics, font, control, controller, !helpOpen && control.bounds().contains(mouseX, mouseY));
        }
        ProjectionController planning = ProjectionController.instance();
        Component hint = planning.draft() != null || planning.recentStatus() ? planning.status() : hint(selection);
        StrategyHudLayout.Rect hintBounds = layout.hint();
        panel(graphics, hintBounds);
        graphics.fill(hintBounds.x() + 1, hintBounds.y() + 3, hintBounds.x() + 3, hintBounds.bottom() - 3,
                0xFF000000 | (selection.statusMessage().getString().isEmpty() ? ACCENT : GOLD));
        drawFitted(graphics, font, hint, hintBounds.x() + 9, hintBounds.y() + 6, hintBounds.width() - 18,
                selection.statusMessage().getString().isEmpty() ? MUTED : GOLD);
        if (helpOpen) {
            drawHelp(graphics, font, mouseX, mouseY, width, height);
        } else {
            StrategyHudLayout.Action action = layout.actionAt(mouseX, mouseY);
            if (action != null) {
                drawTooltip(graphics, font, List.of(tooltip(action, controller)), mouseX, mouseY, width);
            } else if (layout.hint().contains(mouseX, mouseY)) {
                drawTooltip(graphics, font, List.of(hint), mouseX, mouseY, width);
            } else if (layout.selection().contains(mouseX, mouseY) && selection.selectionCount() == 1) {
                drawTooltip(graphics, font, List.of(selection.selectedName(), selection.selectedCoordinates()), mouseX, mouseY, width);
            }
        }
    }

    private void drawTitle(GuiGraphics graphics, @Nonnull Font font) {
        StrategyHudLayout.Rect bounds = layout.title();
        panel(graphics, bounds);
        icon(graphics, StrategyHudLayout.Action.VOLUME, bounds.x() + 8, bounds.y() + 9, ACCENT, false);
        drawFitted(graphics, font, text("title"), bounds.x() + 29, bounds.y() + 6, bounds.width() - 35, TEXT);
        drawFitted(graphics, font, text("subtitle"), bounds.x() + 29, bounds.y() + 19, bounds.width() - 35, MUTED);
    }

    private void drawSelection(GuiGraphics graphics, @Nonnull Font font, BlockSelectionController selection) {
        StrategyHudLayout.Rect bounds = layout.selection();
        if (bounds.height() < 40) {
            return;
        }
        panel(graphics, bounds);
        drawFitted(graphics, font, text("selection"), bounds.x() + 10, bounds.y() + 8, bounds.width() - 58, MUTED);
        Component mode = text(selection.volumeMode() ? "tool.volume" : "tool.similar");
        int modeWidth = font.width(mode);
        graphics.drawString(font, mode, bounds.right() - modeWidth - 10, bounds.y() + 8, ACCENT, false);
        graphics.pose().pushPose();
        graphics.pose().translate(bounds.x() + 10, bounds.y() + 22, 0);
        graphics.pose().scale(1.8F, 1.8F, 1);
        graphics.drawString(font, Objects.requireNonNull(Integer.toString(selection.selectionCount())), 0, 0,
                selection.selectionCount() == 0 ? MUTED : GOLD, false);
        graphics.pose().popPose();
        graphics.drawString(font, text("blocks"), bounds.x() + 64, bounds.y() + 28, MUTED, false);
        if (bounds.height() >= 54) {
            Component detail = selection.selectionCount() == 1 ? selection.selectedName()
                    : text(selection.selectionCount() == 0 ? "empty" : "selected");
            drawFitted(graphics, font, detail, bounds.x() + 10, bounds.y() + 41, bounds.width() - 20, TEXT);
        }
        if (bounds.height() >= 66) {
            Component detail = selection.selectionCount() == 1 ? selection.selectedCoordinates()
                    : text(selection.additive() ? "add.on" : "add.off");
            drawFitted(graphics, font, detail, bounds.x() + 10, bounds.y() + 54, bounds.width() - 20, MUTED);
        }
    }

    private void drawControl(GuiGraphics graphics, @Nonnull Font font, StrategyHudLayout.Control control,
                             StrategyCameraController controller, boolean hovered) {
        StrategyHudLayout.Rect bounds = control.bounds();
        StrategyHudLayout.Action action = control.action();
        boolean tool = switch (action) {
            case SIMILAR, VOLUME, ADDITIVE, CLEAR, SAVE_PRESET, LIBRARY, WORK, SIMILAR_LIMIT, BUILD -> true;
            default -> false;
        };
        boolean active = switch (action) {
            case SIMILAR -> !controller.selection().volumeMode();
            case VOLUME -> controller.selection().volumeMode();
            case ADDITIVE -> controller.selection().additive();
            case ANGLE_LOCK -> controller.angleLocked();
            case HELP -> helpOpen;
            default -> false;
        };
        if (!tool) {
            panel(graphics, bounds);
        }
        if (active || hovered) {
            graphics.fill(bounds.x() + 1, bounds.y() + 1, bounds.right() - 1, bounds.bottom() - 1,
                    hovered ? HOVER : ACTIVE);
        }
        int color = active ? ACCENT : hovered ? TEXT : MUTED;
        boolean compact = tool && bounds.width() < 60;
        if (!compact) {
            icon(graphics, action, bounds.x() + (tool ? 8 : (bounds.width() - 14) / 2),
                    bounds.y() + (tool ? 7 : (bounds.height() - 14) / 2), color, controller.angleLocked());
        }
        if (tool) {
            int inset = compact ? 5 : 27;
            drawFitted(graphics, font, label(action), bounds.x() + inset, bounds.y() + 6, bounds.width() - inset - 4, color);
            drawFitted(graphics, font, binding(action), bounds.x() + inset, bounds.y() + 22, bounds.width() - inset - 4, MUTED);
        }
        if (active) {
            graphics.fill(bounds.x() + 5, bounds.bottom() - 3, bounds.right() - 5, bounds.bottom() - 2,
                    0xFF000000 | ACCENT);
        }
    }

    private void drawHelp(GuiGraphics graphics, @Nonnull Font font, int mouseX, int mouseY, int width, int height) {
        graphics.fill(0, 0, width, height, 0x80071019);
        StrategyHudLayout.Rect bounds = layout.help();
        panel(graphics, bounds);
        graphics.drawString(font, text("help.title"), bounds.x() + 12, bounds.y() + 10, TEXT, false);
        graphics.drawString(font, "×", bounds.right() - 17, bounds.y() + 10, MUTED, false);
        graphics.fill(bounds.x() + 12, bounds.y() + 24, bounds.right() - 12, bounds.y() + 25, BORDER);
        int rowHeight = Math.max(9, Math.min(12, (bounds.height() - 42) / helpRows.size()));
        int keyWidth = Math.min(104, (bounds.width() - 24) / 3);
        int y = bounds.y() + 29;
        HelpRow hoveredRow = null;
        for (HelpRow row : helpRows) {
            drawFitted(graphics, font, row.key(), bounds.x() + 12, y, keyWidth - 8, ACCENT);
            drawFitted(graphics, font, row.description(), bounds.x() + 12 + keyWidth, y,
                    bounds.width() - keyWidth - 24, TEXT);
            if (mouseX >= bounds.x() + 12 && mouseX < bounds.right() - 12 && mouseY >= y && mouseY < y + rowHeight) {
                hoveredRow = row;
            }
            y += rowHeight;
        }
        drawFitted(graphics, font, text("help.close"), bounds.x() + 12, bounds.bottom() - 12, bounds.width() - 24, MUTED);
        if (hoveredRow != null) {
            drawTooltip(graphics, font, List.of(hoveredRow.key(), hoveredRow.description()), mouseX, mouseY, width);
        }
    }

    @Nonnull
    private Component hint(BlockSelectionController selection) {
        if (!selection.statusMessage().getString().isEmpty()) {
            return selection.statusMessage();
        }
        if (selection.working()) {
            return Objects.requireNonNull(Component.translatable("screen.colonycamera.selection.working"));
        }
        if (selection.selectingHeight()) {
            return text("hint.height", selection.heightLayers(), Component.translatable(selection.heightOutward()
                    ? "screen.colonycamera.selection.outward" : "screen.colonycamera.selection.inward"));
        }
        return selection.volumeMode() ? text("hint.volume") : text("hint.similar", dev.colony.camera.config.SelectionConfig.similarLimit());
    }

    @Nonnull
    private Component tooltip(StrategyHudLayout.Action action, StrategyCameraController controller) {
        if (action == StrategyHudLayout.Action.ANGLE_LOCK) {
            return text(controller.angleLocked() ? "tip.unlock" : "tip.lock", CameraKeyMappings.angleLockKeyName());
        }
        return text("tip." + action.name().toLowerCase(java.util.Locale.ROOT), binding(action));
    }

    @Nonnull
    private Component label(StrategyHudLayout.Action action) {
        return text("tool." + action.name().toLowerCase(java.util.Locale.ROOT));
    }

    @Nonnull
    private Component clearBinding() {
        return Objects.requireNonNull(Component.empty().append(text("mouse.right_click")).append(" / ")
                .append(CameraKeyMappings.clearSelectionKeyName()));
    }

    @Nonnull
    private Component binding(StrategyHudLayout.Action action) {
        return switch (action) {
            case SIMILAR -> text("mouse.left.short");
            case SIMILAR_LIMIT -> Objects.requireNonNull(Component.literal(Objects.requireNonNull(Integer.toString(dev.colony.camera.config.SelectionConfig.similarLimit()))));
            case VOLUME -> CameraKeyMappings.volumeSelectionKeyName();
            case ADDITIVE -> CameraKeyMappings.addSelectionKeyName();
            case CLEAR -> clearBinding();
            case ANGLE_LOCK -> CameraKeyMappings.angleLockKeyName();
            case EXIT -> CameraKeyMappings.toggleKeyName();
            case HELP -> Objects.requireNonNull(Component.empty());
            case SAVE_PRESET -> Objects.requireNonNull(Component.literal("B"));
            case LIBRARY -> Objects.requireNonNull(Component.literal("P"));
            case WORK -> Objects.requireNonNull(Component.literal("O"));
            case BUILD -> Objects.requireNonNull(Component.literal("N"));
        };
    }

    @Nonnull
    private static Component text(@Nonnull String suffix, @Nonnull Object... arguments) {
        return Objects.requireNonNull(Component.translatable("screen.colonycamera.ui." + suffix, arguments));
    }

    static void drawFitted(GuiGraphics graphics, @Nonnull Font font, @Nonnull Component text, int x, int y, int width, int color) {
        if (width <= 0) {
            return;
        }
        String value = Objects.requireNonNull(text.getString());
        if (font.width(value) > width) {
            value = font.plainSubstrByWidth(value, Math.max(0, width - font.width("…"))) + "…";
        }
        graphics.drawString(font, value, x, y, color, false);
    }

    private static void drawTooltip(GuiGraphics graphics, @Nonnull Font font, List<Component> lines, int mouseX, int mouseY, int width) {
        int wrapWidth = Math.max(1, Math.min(300, width - 24));
        List<FormattedCharSequence> wrapped = new ArrayList<>();
        for (Component line : lines) {
            wrapped.addAll(font.split(Objects.requireNonNull(line), wrapWidth));
        }
        graphics.renderTooltip(font, wrapped, mouseX, mouseY);
    }

    static void panel(GuiGraphics graphics, StrategyHudLayout.Rect bounds) {
        if (bounds.width() < 2 || bounds.height() < 2) {
            return;
        }
        graphics.fill(bounds.x() + 2, bounds.y() + 2, bounds.right() + 2, bounds.bottom() + 2, 0x35000000);
        graphics.fill(bounds.x() + 1, bounds.y(), bounds.right() - 1, bounds.bottom(), BORDER);
        graphics.fill(bounds.x(), bounds.y() + 1, bounds.right(), bounds.bottom() - 1, BORDER);
        graphics.fill(bounds.x() + 1, bounds.y() + 1, bounds.right() - 1, bounds.bottom() - 1, BACKGROUND);
    }

    private static void icon(GuiGraphics graphics, StrategyHudLayout.Action action, int x, int y, int color, boolean locked) {
        int ink = 0xFF000000 | color;
        switch (action) {
            case SIMILAR -> {
                line(graphics, x, y + 7, x + 7, y + 3, ink);
                line(graphics, x + 7, y + 3, x + 14, y + 7, ink);
                line(graphics, x + 14, y + 7, x + 7, y + 11, ink);
                line(graphics, x + 7, y + 11, x, y + 7, ink);
            }
            case VOLUME -> {
                line(graphics, x, y + 4, x + 7, y, ink);
                line(graphics, x + 7, y, x + 14, y + 4, ink);
                line(graphics, x, y + 4, x + 7, y + 8, ink);
                line(graphics, x + 14, y + 4, x + 7, y + 8, ink);
                line(graphics, x, y + 4, x, y + 11, ink);
                line(graphics, x + 14, y + 4, x + 14, y + 11, ink);
                line(graphics, x + 7, y + 8, x + 7, y + 15, ink);
                line(graphics, x, y + 11, x + 7, y + 15, ink);
                line(graphics, x + 14, y + 11, x + 7, y + 15, ink);
            }
            case ADDITIVE -> {
                graphics.fill(x + 6, y + 1, x + 8, y + 13, ink);
                graphics.fill(x + 1, y + 6, x + 13, y + 8, ink);
            }
            case CLEAR -> {
                line(graphics, x + 3, y + 3, x + 11, y + 11, ink);
                line(graphics, x + 11, y + 3, x + 3, y + 11, ink);
            }
            case ANGLE_LOCK -> {
                graphics.fill(x + 2, y + 7, x + 13, y + 14, ink);
                line(graphics, x + (locked ? 4 : 9), y + 7, x + (locked ? 4 : 9), y + 2, ink);
                line(graphics, x + (locked ? 4 : 9), y + 2, x + (locked ? 10 : 14), y + 2, ink);
                line(graphics, x + (locked ? 10 : 14), y + 2, x + (locked ? 10 : 14), y + 5, ink);
                graphics.fill(x + 7, y + 9, x + 8, y + 12, BACKGROUND);
            }
            case HELP -> {
                line(graphics, x + 3, y + 2, x + 10, y + 2, ink);
                line(graphics, x + 10, y + 2, x + 10, y + 6, ink);
                line(graphics, x + 10, y + 6, x + 7, y + 8, ink);
                graphics.fill(x + 7, y + 8, x + 8, y + 10, ink);
                graphics.fill(x + 7, y + 12, x + 8, y + 14, ink);
            }
            case EXIT -> {
                line(graphics, x + 1, y + 7, x + 13, y + 7, ink);
                line(graphics, x + 1, y + 7, x + 6, y + 2, ink);
                line(graphics, x + 1, y + 7, x + 6, y + 12, ink);
            }
            case BUILD, SAVE_PRESET -> {
                graphics.fill(x + 2, y + 1, x + 12, y + 13, ink);
                graphics.fill(x + 4, y + 2, x + 9, y + 5, BACKGROUND);
                graphics.fill(x + 4, y + 8, x + 10, y + 12, BACKGROUND);
            }
            case LIBRARY -> {
                for (int row = 0; row < 3; row++) {
                    graphics.fill(x + 1, y + 2 + row * 5, x + 4, y + 5 + row * 5, ink);
                    graphics.fill(x + 6, y + 2 + row * 5, x + 13, y + 5 + row * 5, ink);
                }
            }
            case SIMILAR_LIMIT, WORK -> {
                graphics.fill(x + 2, y + 2, x + 12, y + 14, ink);
                for (int row = 0; row < 3; row++) graphics.fill(x + 4, y + 4 + row * 3, x + 10, y + 5 + row * 3, BACKGROUND);
                graphics.fill(x + 5, y, x + 9, y + 3, ink);
            }
        }
    }

    private static void line(GuiGraphics graphics, int x, int y, int endX, int endY, int color) {
        int dx = Math.abs(endX - x), dy = -Math.abs(endY - y);
        int stepX = x < endX ? 1 : -1, stepY = y < endY ? 1 : -1;
        int error = dx + dy;
        while (true) {
            graphics.fill(x, y, x + 1, y + 1, color);
            if (x == endX && y == endY) {
                return;
            }
            int doubled = 2 * error;
            if (doubled >= dy) { error += dy; x += stepX; }
            if (doubled <= dx) { error += dx; y += stepY; }
        }
    }

    private record HelpRow(@Nonnull Component key, @Nonnull Component description) { }
}
