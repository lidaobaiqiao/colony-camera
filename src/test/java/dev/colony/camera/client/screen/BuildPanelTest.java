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
            var layout = new BuildPanelLayout(size[0], size[1], size[1] - 68);
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
            for (var rect : List.of(layout.panel, layout.grid, layout.search, layout.detail, layout.choose, layout.favorite, layout.available, layout.close, layout.refresh, layout.draft)) {
                assertTrue(rect.width() >= 0 && rect.height() >= 0 && rect.x() >= 0 && rect.y() >= 0 && rect.right() <= size[0] && rect.bottom() <= size[1], rect.toString());
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
