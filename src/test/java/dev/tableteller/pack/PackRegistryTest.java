package dev.tableteller.pack;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tableteller.table.DemoChartFormula;
import dev.tableteller.table.FocusFixtures;
import dev.tableteller.table.MeaningTable;
import dev.tableteller.table.RangeColumn;
import dev.tableteller.table.RangeEntry;
import java.util.List;
import org.junit.jupiter.api.Test;

class PackRegistryTest {
    @Test
    void unknownMeaningTableListsLoadedNames() {
        var registry = new PackRegistry(List.of(DemoChartFormula.chart(), FocusFixtures.focus(),
            meaning("descriptions"), meaning("actions")));
        assertThatThrownBy(() -> registry.meaning("Actions"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("unknown meaning table 'Actions'; loaded: actions, descriptions");
    }

    @Test
    void requiresDefaultFateChart() {
        assertThatThrownBy(() -> new PackRegistry(List.of(FocusFixtures.focus(), meaning("actions"))))
            .isInstanceOf(IllegalStateException.class).hasMessage("no fate_chart named 'default'");
    }

    @Test
    void requiresDefaultFocusTable() {
        assertThatThrownBy(() -> new PackRegistry(List.of(DemoChartFormula.chart(), meaning("actions"))))
            .isInstanceOf(IllegalStateException.class).hasMessage("no focus table named 'default'");
    }

    private static MeaningTable meaning(String name) {
        var column = new RangeColumn(List.of(new RangeEntry(1, 2, "x")));
        return new MeaningTable("test", name, 2, column, column);
    }
}
