package dev.tableteller.pack;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.tableteller.table.ChartCell;
import dev.tableteller.table.FateChart;
import dev.tableteller.table.FocusTable;
import dev.tableteller.table.FocusTarget;
import dev.tableteller.table.MeaningTable;
import dev.tableteller.table.OddsLevel;
import dev.tableteller.table.RangeColumn;
import dev.tableteller.table.RangeEntry;
import dev.tableteller.table.Table;
import dev.tableteller.table.TableType;
import dev.tableteller.table.TriggerRule;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.dataformat.yaml.YAMLMapper;

/** Parses and validates pack files. Every YAML document in a file is one table; JSON is read the same way. */
public class PackReader {

    private static final Pattern DIE = Pattern.compile("d([1-9][0-9]{0,5})");

    private final ObjectReader documents = YAMLMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES) // off by default in Jackson 3; a misspelled key must fail
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION) // a repeated key would silently replace the first
        .build()
        .readerFor(PackDocument.class);

    public List<Table> read(String source, String content) {
        List<PackDocument> parsed;
        try (var iterator = documents.<PackDocument>readValues(content)) {
            parsed = iterator.readAll();
        } catch (JacksonException e) {
            throw new PackException(source, e.getOriginalMessage());
        }
        var tables = new ArrayList<Table>();
        for (var document : parsed) {
            if (document != null) tables.add(new Validator(source, document).table());
        }
        return tables;
    }

    record PackDocument(String pack, String type, String name, String die, List<EntryDocument> entries,
            @JsonProperty("entries_2") List<EntryDocument> entries2,
            @JsonProperty("random_event") RandomEventDocument randomEvent,
            Map<String, Map<Integer, CellDocument>> cells) {
    }

    record EntryDocument(List<Integer> range, String value, String target) {
    }

    record RandomEventDocument(String rule) {
    }

    record CellDocument(@JsonProperty("exceptional_yes") Integer exceptionalYes, Integer yes,
            @JsonProperty("exceptional_no") Integer exceptionalNo) {
    }

    /** Turns one parsed document into a table, failing on its first problem. */
    private record Validator(String source, PackDocument doc) {

        Table table() {
            var type = type();
            require(doc.pack(), "pack");
            require(doc.name(), "name");
            int dieSize = dieSize();
            return switch (type) {
                case meaning -> meaning(dieSize);
                case focus -> focus(dieSize);
                case fate_chart -> fateChart(dieSize);
            };
        }

        private TableType type() {
            require(doc.type(), "type");
            if (doc.type().equals("fate_check")) {
                throw fail("type fate_check (2d10 fate check) is not supported yet");
            }
            try {
                return TableType.valueOf(doc.type());
            } catch (IllegalArgumentException e) {
                throw fail("unknown type '" + doc.type() + "'; expected meaning, focus or fate_chart");
            }
        }

        private int dieSize() {
            require(doc.die(), "die");
            var matcher = DIE.matcher(doc.die());
            if (!matcher.matches() || Integer.parseInt(matcher.group(1)) < 2) {
                throw fail("die must look like d100; got '" + doc.die() + "'");
            }
            return Integer.parseInt(matcher.group(1));
        }

        private MeaningTable meaning(int dieSize) {
            notAllowed(doc.cells(), "cells", "meaning");
            notAllowed(doc.randomEvent(), "random_event", "meaning");
            noTargets(doc.entries());
            noTargets(doc.entries2());
            var first = column("entries", doc.entries(), dieSize);
            var second = doc.entries2() == null ? first : column("entries_2", doc.entries2(), dieSize);
            return new MeaningTable(doc.pack(), doc.name(), dieSize, first, second);
        }

        private FocusTable focus(int dieSize) {
            notAllowed(doc.entries2(), "entries_2", "focus");
            notAllowed(doc.cells(), "cells", "focus");
            notAllowed(doc.randomEvent(), "random_event", "focus");
            var entries = column("entries", doc.entries(), dieSize);
            var targets = new HashMap<String, FocusTarget>();
            for (var entry : doc.entries()) {
                if (entry.target() == null) continue;
                try {
                    targets.put(entry.value(), FocusTarget.valueOf(entry.target()));
                } catch (IllegalArgumentException e) {
                    throw fail("entries: [" + entry.range().get(0) + ", " + entry.range().get(1)
                        + "] has unknown target '" + entry.target() + "'; expected character or thread");
                }
            }
            return new FocusTable(doc.pack(), doc.name(), dieSize, entries, targets);
        }

        private void noTargets(List<EntryDocument> entries) {
            if (entries != null && entries.stream().anyMatch(entry -> entry != null && entry.target() != null)) {
                throw fail("target is only allowed in focus tables");
            }
        }

        private FateChart fateChart(int dieSize) {
            notAllowed(doc.entries(), "entries", "fate_chart");
            notAllowed(doc.entries2(), "entries_2", "fate_chart");
            if (dieSize != 100) throw fail("fate_chart die must be d100");
            return new FateChart(doc.pack(), doc.name(), triggerRule(), cells());
        }

        private RangeColumn column(String key, List<EntryDocument> raw, int dieSize) {
            if (raw == null || raw.isEmpty()) throw fail("missing '" + key + "'");
            var entries = new ArrayList<RangeEntry>();
            for (var entry : raw) {
                if (entry == null || entry.range() == null || entry.range().size() != 2 || entry.range().contains(null)) {
                    throw fail(key + ": every entry needs range: [from, to]");
                }
                var parsed = new RangeEntry(entry.range().get(0), entry.range().get(1), entry.value());
                if (parsed.from() > parsed.to()) throw fail(key + ": " + range(parsed) + " runs backwards");
                if (parsed.from() < 1 || parsed.to() > dieSize) {
                    throw fail(key + ": " + range(parsed) + " is outside d" + dieSize);
                }
                if (parsed.value() == null || parsed.value().isBlank()) {
                    throw fail(key + ": " + range(parsed) + " has no value");
                }
                entries.add(parsed);
            }
            var sorted = entries.stream().sorted(Comparator.comparingInt(RangeEntry::from)).toList();
            int next = 1;
            RangeEntry previous = null;
            for (var entry : sorted) {
                if (previous != null && entry.from() <= previous.to()) {
                    throw fail(key + ": " + range(entry) + " overlaps " + range(previous));
                }
                if (entry.from() > next) throw fail(key + ": " + next + " is not covered");
                next = entry.to() + 1;
                previous = entry;
            }
            if (next <= dieSize) throw fail(key + ": " + next + " is not covered");
            return new RangeColumn(sorted);
        }

        private TriggerRule triggerRule() {
            if (doc.randomEvent() == null) throw fail("missing 'random_event'");
            var rule = doc.randomEvent().rule();
            require(rule, "random_event.rule");
            try {
                return TriggerRule.valueOf(rule);
            } catch (IllegalArgumentException e) {
                var known = Arrays.stream(TriggerRule.values()).map(Enum::name).collect(Collectors.joining(", "));
                throw fail("unknown random_event rule '" + rule + "'; expected " + known);
            }
        }

        private Map<OddsLevel, List<ChartCell>> cells() {
            var raw = doc.cells();
            if (raw == null) throw fail("missing 'cells'");
            for (var key : raw.keySet()) {
                if (Arrays.stream(OddsLevel.values()).noneMatch(odds -> odds.name().equals(key))) {
                    throw fail("cells: unknown odds level '" + key + "'");
                }
            }
            var cells = new EnumMap<OddsLevel, List<ChartCell>>(OddsLevel.class);
            for (var odds : OddsLevel.values()) {
                var column = raw.get(odds.name());
                if (column == null) throw fail("cells: missing " + odds);
                var where = "cells." + odds;
                for (var chaos : column.keySet()) {
                    if (chaos < 1 || chaos > 9) throw fail(where + ": chaos " + chaos + " is outside 1-9");
                }
                var list = new ArrayList<ChartCell>();
                for (int chaos = 1; chaos <= 9; chaos++) {
                    var cell = column.get(chaos);
                    if (cell == null) throw fail(where + ": missing chaos " + chaos);
                    list.add(cell(where + "." + chaos, cell));
                }
                cells.put(odds, list);
            }
            return cells;
        }

        private ChartCell cell(String where, CellDocument cell) {
            if (cell.exceptionalYes() == null || cell.yes() == null || cell.exceptionalNo() == null) {
                throw fail(where + ": needs exceptional_yes, yes and exceptional_no");
            }
            try {
                return new ChartCell(cell.exceptionalYes(), cell.yes(), cell.exceptionalNo());
            } catch (IllegalArgumentException e) {
                throw fail(where + ": " + e.getMessage());
            }
        }

        private void require(String value, String key) {
            if (value == null || value.isBlank()) throw fail("missing '" + key + "'");
        }

        private void notAllowed(Object value, String key, String type) {
            if (value != null) throw fail(key + " is not allowed for " + type);
        }

        private static String range(RangeEntry entry) {
            return "[" + entry.from() + ", " + entry.to() + "]";
        }

        private PackException fail(String reason) {
            return new PackException(source, reason);
        }
    }
}
