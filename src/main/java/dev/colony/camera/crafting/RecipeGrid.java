package dev.colony.camera.crafting;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Native recipes receive a real three by three crafting grid without an open player menu. */
final class RecipeGrid extends SimpleContainer implements CraftingContainer {
    RecipeGrid() { super(9); }
    @Override public int getWidth() { return 3; }
    @Override public int getHeight() { return 3; }
    @Override public List<ItemStack> getItems() {
        List<ItemStack> result = new ArrayList<>(); for (int slot = 0; slot < 9; slot++) result.add(getItem(slot)); return result;
    }
    @Override public void fillStackedContents(@Nonnull StackedContents contents) {
        for (int slot = 0; slot < 9; slot++) contents.accountSimpleStack(Objects.requireNonNull(getItem(slot)));
    }
}
