package dev.colony.camera.client.screen;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class BuildPanelTest {
    private final List<BuildCatalogFilter.Row> rows = List.of(
            new BuildCatalogFilter.Row("minecraft:stone", "石头", "structure", 1),
            new BuildCatalogFilter.Row("minecraft:stone_bricks", "石砖", "structure", 2),
            new BuildCatalogFilter.Row("colonycamera:warehouse", "殖民地仓库", "colony", 3),
            new BuildCatalogFilter.Row("minecraft:oak_planks", "橡木木板", "structure", 0));
    @Test void searchesChineseNamesAndCaseInsensitiveNamespaceWithMultipleTokens() {
        assertEquals("minecraft:stone_bricks", BuildCatalogFilter.filter(rows, "MINECRAFT 石砖", "all", false, Set.of()).get(0).id());
        assertEquals(2, BuildCatalogFilter.filter(rows, "stone", "all", false, Set.of()).size());
        assertTrue(BuildCatalogFilter.filter(rows, "不存在", "all", false, Set.of()).isEmpty());
    }
    @Test void availableFilterExcludesUnknownAndMissingItemsButKeepsBothConstructionRoutes() {
        assertEquals(Set.of(1, 2), Set.copyOf(BuildCatalogFilter.filter(rows, "", "all", true, Set.of()).stream().map(row -> java.util.Objects.requireNonNull(row).status()).toList()));
    }
    @Test void favoritesRemainIndependentOfAvailabilityAndSortBeforeOtherMatches() {
        var favorites = Set.of("colonycamera:warehouse");
        assertEquals("colonycamera:warehouse", BuildCatalogFilter.filter(rows, "", "all", false, favorites).get(0).id());
        assertEquals(1, BuildCatalogFilter.filter(rows, "", "favorites", false, favorites).size());
        assertTrue(BuildCatalogFilter.filter(rows, "", "favorites", true, favorites).isEmpty());
        assertEquals(3, BuildCatalogFilter.filter(rows, "", "structure", false, favorites).size());
    }
    @Test void gridCentersAndRightBottomEdgesResolveToTheRenderedItemAfterScrolling() {
        for (int[] size : new int[][] {{320, 180}, {400, 225}, {640, 360}, {854, 480}}) {
            var layout = new BuildPanelLayout(size[0], size[1], new StrategyHudLayout(size[0], size[1]).dock().y() - 4);
            assertTrue(layout.rows > 0, "The smallest supported GUI must still show buildable blocks");
            for (int i = 0; i < layout.columns * layout.rows; i++) {
                var cell = layout.cell(i);
                assertEquals(i + 3 * layout.columns, layout.rowAt(cell.x() + cell.width() / 2.0, cell.y() + 5, 3));
            }
            assertEquals(-1, layout.rowAt(layout.grid.right(), layout.grid.y() + 2, 0));
            assertEquals(-1, layout.rowAt(layout.grid.x() + 2, layout.grid.bottom(), 0));
        }
    }
    @Test void allPanelControlsStayInsideDuringTinyResizeAndCommonScales() {
        for (int[] size : new int[][] {{1, 1}, {16, 7}, {100, 80}, {320, 180}, {480, 270}, {854, 480}}) {
            var layout = new BuildPanelLayout(size[0], size[1], Math.max(0, size[1] - 68));
            var controls = new java.util.ArrayList<>(List.of(layout.panel, layout.grid, layout.search, layout.category, layout.detail, layout.choose, layout.favorite, layout.available, layout.close, layout.refresh, layout.draft));
            controls.add(layout.title); controls.add(layout.resize); controls.add(layout.newWindow);
            for (int i = 0; i < 7; i++) controls.add(layout.category(i, 7));
            for (var rect : controls) {
                assertTrue(rect.width() >= 0 && rect.height() >= 0 && rect.x() >= 0 && rect.y() >= 0 && rect.right() <= size[0] && rect.bottom() <= size[1], rect.toString());
            }
        }
    }
    @Test void sidebarLeavesTheSceneCenterAndMostOfTheViewportAvailable() {
        for (int[] size : new int[][] {{320, 180}, {480, 270}, {640, 360}, {854, 480}}) {
            var hud = new StrategyHudLayout(size[0], size[1]);
            var layout = new BuildPanelLayout(size[0], size[1], hud.dock().y() - 4);
            assertTrue(layout.panel.width() <= size[0] * 0.4);
            assertFalse(layout.panel.contains(size[0] / 2.0, size[1] / 2.0));
            assertTrue(layout.panel.bottom() <= hud.dock().y());
            assertTrue(layout.draft.height() <= 26);
            assertTrue(layout.draft.bottom() < hud.dock().y());
        }
    }
    @Test void categoryPopupFitsInsideTheSidebarAndCannotOverlapItsTrigger() {
        for (int[] size : new int[][] {{320, 180}, {640, 360}}) {
            var layout = new BuildPanelLayout(size[0], size[1], size[1] - 40);
            for (int i = 0; i < 7; i++) {
                var option = layout.category(i, 7);
                assertTrue(option.height() >= 9);
                assertTrue(option.y() >= layout.category.bottom() && option.bottom() <= layout.panel.bottom());
                assertTrue(option.x() >= layout.panel.x() && option.right() <= layout.panel.right());
            }
        }
    }
    @Test void scrollClampsToTheLastPartialRowWithoutHidingTheLastItem() {
        var layout = new BuildPanelLayout(640, 360, 280); int count = 137;
        int scroll = layout.maxScroll(count);
        assertTrue((scroll + layout.rows) * layout.columns >= count);
        assertTrue(scroll == 0 || (scroll + layout.rows - 1) * layout.columns < count);
    }
}
