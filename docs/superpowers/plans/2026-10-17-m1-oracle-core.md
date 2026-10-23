# M1 Oracle Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A local stdio MCP server with four working oracle tools (`roll`, `fate_check`, `meaning`, `set_chaos`), backed by validated table packs, in-memory state and an append-only roll log.

**Architecture:** One non-web Spring Boot process speaks MCP over stdio through Spring AI's annotation-based tools. A thin `OracleTools` layer validates input, calls `FateService` / `MeaningService`, and appends one roll-log row per call. Tables come from a demo pack on the classpath, overridden by personal packs in `~/.oracle/packs/`. State sits behind repository interfaces that M2 will back with Postgres.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Spring AI 2.0.1 (`spring-ai-starter-mcp-server`, MCP Java SDK 2.0.0), Jackson 3 (`jackson-dataformat-yaml`), JUnit 5 + AssertJ, Gradle 9.8.1 (Kotlin DSL).

**Spec:** `docs/SPEC.md` (v0.3). Read "Architecture", "Oracle tools", "Table data and licensing" and "Testing, milestones and decisions" before starting.

**Branch:** `m1-oracle-core`, cut from `master`.

## Global Constraints

- Versions: Spring Boot 4.1.1, Spring AI BOM 2.0.1, Gradle wrapper 9.8.1, Java toolchain 21. Jackson is version 3 (`tools.jackson.*`); its annotations stay in `com.fasterxml.jackson.annotation`.
- There is no `java` on PATH. Run every Gradle command with `JAVA_HOME=/c/Users/sefim/.jdks/corretto-21.0.10` (Bash) or `$env:JAVA_HOME='C:\Users\sefim\.jdks\corretto-21.0.10'` (PowerShell).
- Coordinates: group `dev.tableteller`, artifact `tableteller-tools`, version `0.1.0`, root package `dev.tableteller`.
- All random numbers go through `RandomSource.between(min, max)` (inclusive at both ends). No `Math.random`, `new Random()` or `ThreadLocalRandom` anywhere else. Tests use `ScriptedRandomSource` or `new GeneratorRandomSource(new SplittableRandom(seed))`.
- No table text from published games in the repo. The demo words and the demo chart are original, and this plan fixes them.
- Every successful oracle tool call appends exactly one roll-log row and returns its `roll_id`, which is `r_` plus the id padded to four digits. A call rejected for bad input throws `IllegalArgumentException` (Spring AI returns it as `isError: true`) and writes no row. The message says what is wrong and what is allowed.
- stdout carries only JSON-RPC: the banner is off, console logging is off and the app is non-web. Logs go to `${user.home}/.oracle/logs/tableteller-tools.log`.
- Enums whose values appear outside the code (tool schema, tool output, pack files) use lowercase constants equal to that value, for example `OddsLevel.fifty_fifty`. Spring AI 2.0.1's schema generator emits `Enum.name()` and ignores `@JsonProperty` on enum constants.
- Tool output fields are snake_case (`roll_id`, `chaos_factor`, `random_event_triggered`) via `@JsonProperty` on record components.
- `fate_check` answers are `yes`, `no`, `exceptional_yes`, `exceptional_no`: the spec's "exceptional yes" written in the odds-level style.
- No `@SpringBootTest`. An in-process context would start the stdio transport on the test JVM's own System.in/out. Unit tests construct objects directly, and the whole app is tested only through the subprocess test in Task 8.
- Not in M1: generating random events, using focus tables, scenes, campaigns, Postgres, journal, resources, prompts. The loader accepts and validates `focus` tables, but the demo focus table waits for M2, when focus entries gain targets.

## Review Focus

1. A personal pack with Cyrillic words (the spec names Russian) must reach the client intact over stdio on Windows, where the JVM's default stdout charset is not UTF-8. Test: Task 8 `cyrillicWordsSurviveStdio`.
2. A personal pack saved with a UTF-8 byte-order mark (common from Windows editors) must load instead of failing on its first key. Test: Task 4 `readsUtf8WithByteOrderMarkAndCyrillic`.
3. `oracle.packs.dir` written as `~/...` must resolve to the home directory. Otherwise the missing-directory rule silently falls back to the demo chart. Test: Task 4 `expandsLeadingTildeToHome`.
4. Odds sent in the wrong format by the model (`"Likely"`, `"50/50"`) must be rejected with the nine valid values in the error, never guessed. Test: Task 8 `offFormatOddsAreRejectedWithTheValidValues`.
5. A miscased or unknown meaning table name (`"Actions"`) must fail with the names of the loaded meaning tables, so the DM can retry correctly. Tests: Task 4 `unknownMeaningTableListsLoadedNames`, Task 7 `meaningRejectsUnknownTable`.

## File Map

| Path (under `src/main/java/dev/tableteller/` unless shown) | Responsibility | Task |
| --- | --- | --- |
| `build.gradle.kts`, `settings.gradle.kts`, `gradlew*`, `gradle/wrapper/*`, `.gitignore` | Build | 1 |
| `TabletellerToolsApplication.java` | Spring Boot entry point | 1 |
| `OracleConfig.java` | `RandomSource` and `PackRegistry` beans | 1, 4 |
| `random/RandomSource.java`, `random/GeneratorRandomSource.java` | The only source of random numbers | 1 |
| `table/Table.java`, `TableType.java`, `OddsLevel.java`, `FateAnswer.java`, `ChartCell.java`, `TriggerRule.java`, `FateChart.java` | Fate chart model | 2 |
| `fate/FateService.java`, `fate/FateOutcome.java` | One fate check against a chart | 2 |
| `table/RangeEntry.java`, `RangeColumn.java`, `MeaningTable.java`, `FocusTable.java` | Range table model | 3 |
| `pack/PackReader.java`, `pack/PackException.java` | Parse and validate one pack file | 3 |
| `pack/PackLoader.java`, `pack/PackRegistry.java` | Demo + personal packs, precedence, lookup | 4 |
| `src/main/resources/packs/demo/*.yaml` | Demo fate chart, actions, descriptions | 4 |
| `src/main/resources/application.yaml` | Packs dir (Task 4); stdio, banner, logging (Task 8) | 4, 8 |
| `state/*` | Oracle state and roll log behind repository interfaces, in memory | 5 |
| `tools/OracleTools.java` | The four `@McpTool` methods and their result records | 6, 7 |
| `meaning/MeaningService.java`, `meaning/MeaningPair.java` | Word-pair rolls | 7 |
| `src/test/java/dev/tableteller/random/ScriptedRandomSource.java` | Returns given numbers in order | 1 |
| `src/test/java/dev/tableteller/table/DemoChartFormula.java` | The spec's demo chart formula | 2 |
| `src/test/java/dev/tableteller/pack/PackYaml.java` | Fixture YAML builder | 3 |
| `src/test/java/dev/tableteller/StdioEndToEndTest.java` | Runs the server as a subprocess over stdio | 8 |
| `README.md` | Build and client setup | 8 |

---

### Task 1: Project skeleton and RandomSource

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `.gitignore`, wrapper files (generated)
- Create: `src/main/java/dev/tableteller/TabletellerToolsApplication.java`, `OracleConfig.java`, `random/RandomSource.java`, `random/GeneratorRandomSource.java`
- Test: `src/test/java/dev/tableteller/random/ScriptedRandomSource.java`, `src/test/java/dev/tableteller/random/RandomSourceTest.java`

**Interfaces:**
- Produces: `public interface RandomSource { int between(int min, int max); }`. The range is inclusive at both ends; `min > max` throws `IllegalArgumentException`.
- Produces: `public final class GeneratorRandomSource implements RandomSource`, constructed as `GeneratorRandomSource(RandomGenerator generator)`.
- Produces (test): `public final class ScriptedRandomSource implements RandomSource` (public, because tests in other packages use it), constructed as `ScriptedRandomSource(int... values)`, returns the values in order. It throws `IllegalStateException` when a value lies outside the requested range (the message names the value and `min-max`) or when the values run out.
- Produces: bean `OracleConfig.randomSource()` returning `new GeneratorRandomSource(new SecureRandom())`.

- [ ] **Step 1: Write the build files**

`settings.gradle.kts`: `rootProject.name = "tableteller-tools"`

`build.gradle.kts`:

```kotlin
plugins {
    java
    id("org.springframework.boot") version "4.1.1"
}

group = "dev.tableteller"
version = "0.1.0"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories { mavenCentral() }

dependencies {
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    implementation(platform("org.springframework.ai:spring-ai-bom:2.0.1"))
    implementation("org.springframework.ai:spring-ai-starter-mcp-server")
    implementation("tools.jackson.dataformat:jackson-dataformat-yaml")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.jar { enabled = false } // only the boot jar lands in build/libs
tasks.test { useJUnitPlatform() }
```

`.gitignore`: `.gradle/`, `build/`, `out/`, `.idea/`, `*.iml`

- [ ] **Step 2: Generate the wrapper with the cached Gradle 9.3.0**

Run: `JAVA_HOME=/c/Users/sefim/.jdks/corretto-21.0.10 ~/.gradle/wrapper/dists/gradle-9.3.0-bin/*/gradle-9.3.0/bin/gradle wrapper --gradle-version 9.8.1`
Expected: `gradlew`, `gradlew.bat` and `gradle/wrapper/gradle-wrapper.{jar,properties}` exist, and `distributionUrl` ends in `gradle-9.8.1-bin.zip`. If the cached distribution is gone, ask your human partner before downloading one.

- [ ] **Step 3: Write the failing tests**

```java
class RandomSourceTest {
    @Test
    void generatorSourceCoversInclusiveRange() {
        var source = new GeneratorRandomSource(new SplittableRandom(42));
        var seen = new TreeSet<Integer>();
        for (int i = 0; i < 1_000; i++) seen.add(source.between(1, 6));
        assertThat(seen).containsExactly(1, 2, 3, 4, 5, 6);
    }

    @Test
    void generatorSourceHandlesIntegerMaxValue() {
        var source = new GeneratorRandomSource(new SplittableRandom(42));
        for (int i = 0; i < 100; i++)
            assertThat(source.between(Integer.MAX_VALUE - 1, Integer.MAX_VALUE)).isGreaterThanOrEqualTo(Integer.MAX_VALUE - 1);
    }

    @Test
    void generatorSourceRejectsMinAboveMax() {
        var source = new GeneratorRandomSource(new SplittableRandom(42));
        assertThatThrownBy(() -> source.between(6, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void scriptedSourceReturnsValuesInOrder() {
        var source = new ScriptedRandomSource(44, 11);
        assertThat(source.between(1, 100)).isEqualTo(44);
        assertThat(source.between(1, 100)).isEqualTo(11);
    }

    @Test
    void scriptedSourceRejectsValueOutsideRequestedRange() {
        assertThatThrownBy(() -> new ScriptedRandomSource(7).between(1, 6))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("7").hasMessageContaining("1-6");
    }

    @Test
    void scriptedSourceFailsWhenExhausted() {
        var source = new ScriptedRandomSource(3);
        source.between(1, 6);
        assertThatThrownBy(() -> source.between(1, 6)).isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 4: Run the tests to verify they fail**

Run: `./gradlew test --tests 'dev.tableteller.random.*'`
Expected: compilation fails (`GeneratorRandomSource` does not exist). The first run downloads Gradle 9.8.1 and the dependencies.

- [ ] **Step 5: Implement `RandomSource`, `GeneratorRandomSource`, `ScriptedRandomSource`, `TabletellerToolsApplication`, `OracleConfig`**

`GeneratorRandomSource.between` returns `(int) generator.nextLong(min, (long) max + 1)` so that `max = Integer.MAX_VALUE` cannot overflow. `TabletellerToolsApplication` is a plain `@SpringBootApplication` main.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, 6 tests passed.

- [ ] **Step 7: Commit**

```bash
git add .gitignore settings.gradle.kts build.gradle.kts gradlew gradlew.bat gradle src
git commit -m "Add Gradle project skeleton and RandomSource"
```

---

### Task 2: Fate chart model and FateService

**Files:**
- Create: `src/main/java/dev/tableteller/table/{Table,TableType,OddsLevel,FateAnswer,ChartCell,TriggerRule,FateChart}.java`
- Create: `src/main/java/dev/tableteller/fate/{FateService,FateOutcome}.java`
- Test: `src/test/java/dev/tableteller/table/{DemoChartFormula,ChartCellTest,TriggerRuleTest}.java`, `src/test/java/dev/tableteller/fate/{FateServiceTest,FateStatisticsTest}.java`

**Interfaces:**
- Consumes: `RandomSource`, `ScriptedRandomSource`, `GeneratorRandomSource` (Task 1).
- Produces (package `dev.tableteller.table`):
  - `public interface Table { TableType type(); String pack(); String name(); }`
  - `public enum TableType { meaning, focus, fate_chart }`
  - `public enum OddsLevel { impossible, nearly_impossible, very_unlikely, unlikely, fifty_fifty, likely, very_likely, nearly_certain, certain }` in this order.
  - `public enum FateAnswer { yes, no, exceptional_yes, exceptional_no; public String label() }`. Labels: `Yes`, `No`, `Exceptional yes`, `Exceptional no`.
  - `public record ChartCell(int exceptionalYes, int yes, int exceptionalNo)`. Its constructor throws `IllegalArgumentException("thresholds must satisfy 0 <= exceptional_yes <= yes <= 100 and yes < exceptional_no <= 101")`. Method: `FateAnswer answer(int roll)`, banded as in the spec.
  - `public enum TriggerRule { doubles_at_or_below_chaos; public boolean triggers(int roll, int chaosFactor) }`. It fires for 11, 22 … 99 when the digit is at or below chaos; 100 never fires.
  - `public record FateChart(String pack, String name, TriggerRule trigger, Map<OddsLevel, List<ChartCell>> cells) implements Table`. The constructor requires all nine odds levels with exactly nine cells each (list index = chaos − 1), otherwise `IllegalArgumentException`, and keeps an unmodifiable copy. Methods: `ChartCell cell(OddsLevel odds, int chaosFactor)`; `type()` returns `fate_chart`.
- Produces (package `dev.tableteller.fate`): `public record FateOutcome(int roll, FateAnswer answer, boolean randomEventTriggered)` and `@Service public class FateService`, constructed as `FateService(RandomSource random)`. `FateOutcome check(FateChart chart, OddsLevel odds, int chaosFactor)` rolls `between(1, 100)` once.
- Produces (test): `DemoChartFormula.cell(OddsLevel, int chaos)` and `DemoChartFormula.chart()`. The chart has pack `demo`, name `default` and rule `doubles_at_or_below_chaos`.

- [ ] **Step 1: Write the formula helper and the failing tests**

`DemoChartFormula` is the spec's formula ("Demo fate chart formula"):

```java
public final class DemoChartFormula {   // public: tests in fate, pack and tools use it
    private static final Map<OddsLevel, Integer> BASE = Map.of(
        impossible, 5, nearly_impossible, 10, very_unlikely, 20, unlikely, 35, fifty_fifty, 50,
        likely, 65, very_likely, 80, nearly_certain, 90, certain, 95);

    public static ChartCell cell(OddsLevel odds, int chaos) {
        int yes = Math.clamp(BASE.get(odds) + 5 * (chaos - 5), 1, 99);
        return new ChartCell(yes / 5, yes, 101 - (100 - yes) / 5);
    }

    public static FateChart chart() { /* all 9 × 9 cells from cell(...) */ }
}
```

```java
class ChartCellTest {
    @Test
    void likelyAtChaosFiveBands() {               // 13 / 65 / 94
        var cell = new ChartCell(13, 65, 94);
        assertThat(cell.answer(1)).isEqualTo(exceptional_yes);
        assertThat(cell.answer(13)).isEqualTo(exceptional_yes);
        assertThat(cell.answer(14)).isEqualTo(yes);
        assertThat(cell.answer(65)).isEqualTo(yes);
        assertThat(cell.answer(66)).isEqualTo(no);
        assertThat(cell.answer(93)).isEqualTo(no);
        assertThat(cell.answer(94)).isEqualTo(exceptional_no);
        assertThat(cell.answer(100)).isEqualTo(exceptional_no);
    }

    @Test
    void zeroAndHundredOneMeanTheExceptionalResultCannotOccur() {
        var cell = new ChartCell(0, 1, 101);
        assertThat(cell.answer(1)).isEqualTo(yes);
        assertThat(cell.answer(100)).isEqualTo(no);
    }

    @ParameterizedTest
    @CsvSource({"14,13,94", "-1,5,90", "0,101,101", "0,65,65", "0,65,102"})
    void rejectsUnorderedThresholds(int exceptionalYes, int yes, int exceptionalNo) {
        assertThatThrownBy(() -> new ChartCell(exceptionalYes, yes, exceptionalNo))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("thresholds must satisfy");
    }
}

class TriggerRuleTest {
    @ParameterizedTest
    @CsvSource({"11,1,true", "22,1,false", "22,2,true", "55,5,true", "66,5,false",
                "99,9,true", "100,9,false", "54,9,false", "1,9,false"})
    void doublesAtOrBelowChaos(int roll, int chaos, boolean expected) {
        assertThat(TriggerRule.doubles_at_or_below_chaos.triggers(roll, chaos)).isEqualTo(expected);
    }
}

class FateServiceTest {
    FateChart chart = DemoChartFormula.chart();

    @Test
    void readsTheCellForOddsAndChaos() {           // likely, chaos 6: 14 / 70 / 95
        assertThat(service(70).check(chart, likely, 6).answer()).isEqualTo(yes);
        assertThat(service(71).check(chart, likely, 6).answer()).isEqualTo(no);
        assertThat(service(95).check(chart, likely, 6).answer()).isEqualTo(exceptional_no);
    }

    @Test
    void reportsRandomEventFromTheChartRule() {
        assertThat(service(44).check(chart, likely, 6)).isEqualTo(new FateOutcome(44, yes, true));
        assertThat(service(44).check(chart, likely, 3)).isEqualTo(new FateOutcome(44, yes, false));
    }

    @Test
    void certainCanStillBeNoAtHundred() {          // certain, chaos 9: yes is clamped to 99
        assertThat(service(100).check(chart, certain, 9).answer()).isEqualTo(no);
    }

    private FateService service(int... rolls) { return new FateService(new ScriptedRandomSource(rolls)); }
}

class FateStatisticsTest {
    @ParameterizedTest
    @EnumSource(OddsLevel.class)
    void yesRateMatchesTheChartWithinOnePoint(OddsLevel odds) {
        var service = new FateService(new GeneratorRandomSource(new SplittableRandom(20261017L)));
        var chart = DemoChartFormula.chart();
        int yesCount = 0;
        for (int i = 0; i < 100_000; i++) {
            var answer = service.check(chart, odds, 5).answer();
            if (answer == yes || answer == exceptional_yes) yesCount++;
        }
        assertThat(yesCount / 100_000.0).isCloseTo(chart.cell(odds, 5).yes() / 100.0, within(0.01));
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'dev.tableteller.table.*' --tests 'dev.tableteller.fate.*'`
Expected: compilation fails (`ChartCell` does not exist).

- [ ] **Step 3: Implement the table types, `FateOutcome` and `FateService` with the signatures above**

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src
git commit -m "Add fate chart model and FateService"
```

---

### Task 3: Range tables and PackReader

**Files:**
- Create: `src/main/java/dev/tableteller/table/{RangeEntry,RangeColumn,MeaningTable,FocusTable}.java`
- Create: `src/main/java/dev/tableteller/pack/{PackReader,PackException}.java`
- Test: `src/test/java/dev/tableteller/pack/{PackYaml,PackReaderTest}.java`

**Interfaces:**
- Consumes: the Task 2 table types and `DemoChartFormula`.
- Produces (table):
  - `public record RangeEntry(int from, int to, String value)`
  - `public record RangeColumn(List<RangeEntry> entries)` with `String valueFor(int roll)`
  - `public record MeaningTable(String pack, String name, int dieSize, RangeColumn first, RangeColumn second) implements Table`. A one-column table has `second == first`.
  - `public record FocusTable(String pack, String name, int dieSize, RangeColumn entries) implements Table`
- Produces (pack):
  - `public class PackException extends RuntimeException`, constructed as `PackException(String source, String reason)`, with message `source + ": " + reason`.
  - `public class PackReader` with `List<Table> read(String source, String content)`. Every YAML document in `content` is one table, and JSON is read by the same YAML parser.
- Produces (test): `PackYaml.fateChart(String pack, String name)` renders `DemoChartFormula` as pack YAML with one line per cell (`5: { exceptional_yes: 13, yes: 65, exceptional_no: 94 }`), plus whatever tweak helpers the rejection cases need. Task 4 reuses `fateChart` to write the demo file.

Parsing: build the mapper as `YAMLMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()`. Jackson 3 turned that default off, and a misspelled key must fail. Read every document with `readerFor(...).readValues(...)`. Keys are the spec's: `pack`, `type`, `name`, `die`, `entries`, `entries_2`, `random_event.rule` and `cells.<odds>.<chaos>.{exceptional_yes, yes, exceptional_no}`. Read `cells` as `Map<String, Map<Integer, …>>` so unknown odds names get this reader's message, not Jackson's. Wrap Jackson errors as `PackException(source, originalMessage)`. Report the first problem in a file. A missing required key gives `missing '<key>'`. `die` must match `d<N>` with N ≥ 2.

- [ ] **Step 1: Write the failing tests for accepted input**

```java
class PackReaderTest {
    PackReader reader = new PackReader();

    static final String COLORS = """
        pack: test
        type: meaning
        name: colors
        die: d4
        entries:
          - { range: [1, 2], value: "red" }
          - { range: [3, 3], value: "green" }
          - { range: [4, 4], value: "blue" }
        """;

    @Test
    void readsOneColumnMeaningTable() {
        var table = (MeaningTable) single(reader.read("test.yaml", COLORS));
        assertThat(table.pack()).isEqualTo("test");
        assertThat(table.name()).isEqualTo("colors");
        assertThat(table.dieSize()).isEqualTo(4);
        assertThat(table.first().valueFor(2)).isEqualTo("red");
        assertThat(table.second().valueFor(4)).isEqualTo("blue");
    }

    @Test
    void readsTwoColumnMeaningTable() {
        var table = (MeaningTable) single(reader.read("test.yaml", COLORS + """
            entries_2:
              - { range: [1, 4], value: "bright" }
            """));
        assertThat(table.first().valueFor(1)).isEqualTo("red");
        assertThat(table.second().valueFor(1)).isEqualTo("bright");
    }

    @Test
    void readsFocusTable() {
        assertThat(single(reader.read("test.yaml", COLORS.replace("type: meaning", "type: focus"))))
            .isInstanceOf(FocusTable.class);
    }

    @Test
    void readsFateChart() {
        var chart = (FateChart) single(reader.read("chart.yaml", PackYaml.fateChart("test", "default")));
        assertThat(chart.trigger()).isEqualTo(TriggerRule.doubles_at_or_below_chaos);
        for (var odds : OddsLevel.values())
            for (int chaos = 1; chaos <= 9; chaos++)
                assertThat(chart.cell(odds, chaos)).isEqualTo(DemoChartFormula.cell(odds, chaos));
    }

    @Test
    void readsJson() {
        var json = """
            {"pack": "test", "type": "meaning", "name": "colors", "die": "d4",
             "entries": [{"range": [1, 2], "value": "red"}, {"range": [3, 3], "value": "green"},
                         {"range": [4, 4], "value": "blue"}]}
            """;
        assertThat(reader.read("test.json", json)).isEqualTo(reader.read("test.yaml", COLORS));
    }

    @Test
    void readsEveryYamlDocumentInAFile() {
        var tables = reader.read("both.yaml", COLORS + "---\n" + COLORS.replace("name: colors", "name: shades"));
        assertThat(tables).extracting(Table::name).containsExactly("colors", "shades");
    }
}
```

- [ ] **Step 2: Write the failing rejection tests**

Write one parameterized test, `rejectsInvalidPack(String yaml, String expected)`. Each case applies one change to `COLORS`, or to `PackYaml.fateChart("test", "default")` for cases 13–20. Each asserts `PackException`, a message starting with `"test.yaml: "`, and the message fragment below.

| # | Change | Message contains |
| --- | --- | --- |
| 1 | `type: oracle` | `unknown type 'oracle'; expected meaning, focus or fate_chart` |
| 2 | `type: fate_check` | `type fate_check (2d10 fate check) is not supported yet` |
| 3 | drop the `name` line | `missing 'name'` |
| 4 | `die: 2d6` | `die must look like d100; got '2d6'` |
| 5 | drop the `[4, 4]` entry | `entries: 4 is not covered` |
| 6 | `[3, 3]` → `[2, 3]` | `entries: [2, 3] overlaps [1, 2]` |
| 7 | `[4, 4]` → `[4, 5]` | `entries: [4, 5] is outside d4` |
| 8 | `[3, 3]` → `[3, 2]` | `entries: [3, 2] runs backwards` |
| 9 | first value `""` | `entries: [1, 2] has no value` |
| 10 | `type: focus` plus an `entries_2` block | `entries_2 is not allowed for focus` |
| 11 | add `random_event: { rule: doubles_at_or_below_chaos }` | `random_event is not allowed for meaning` |
| 12 | `entires:` instead of `entries:` | `entires` |
| 13 | chart `die: d10` | `fate_chart die must be d100` |
| 14 | chart rule `sevens` | `unknown random_event rule 'sevens'; expected doubles_at_or_below_chaos` |
| 15 | chart without the `very_likely` block | `cells: missing very_likely` |
| 16 | chart with an extra `probable` block | `cells: unknown odds level 'probable'` |
| 17 | chart `likely` without chaos 7 | `cells.likely: missing chaos 7` |
| 18 | chart `likely` with an extra chaos 10 | `cells.likely: chaos 10 is outside 1-9` |
| 19 | chart `likely` chaos 5 = `{ exceptional_yes: 14, yes: 13, exceptional_no: 94 }` | `cells.likely.5: thresholds must satisfy` |
| 20 | chart with an `entries` block | `entries is not allowed for fate_chart` |

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew test --tests 'dev.tableteller.pack.*'`
Expected: compilation fails (`PackReader` does not exist).

- [ ] **Step 4: Implement the range table records, `PackException` and `PackReader`**

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src
git commit -m "Add pack reader with validation"
```

---

### Task 4: PackLoader, PackRegistry and the demo pack

**Files:**
- Create: `src/main/java/dev/tableteller/pack/{PackLoader,PackRegistry}.java`
- Create: `src/main/resources/application.yaml`, `src/main/resources/packs/demo/{fate-chart,actions,descriptions}.yaml`
- Modify: `src/main/java/dev/tableteller/OracleConfig.java` (add the `packRegistry` bean)
- Test: `src/test/java/dev/tableteller/pack/{PackLoaderTest,PackRegistryTest}.java`

**Interfaces:**
- Consumes: `PackReader`, `PackException`, the table types, `DemoChartFormula`, `PackYaml`.
- Produces: `public class PackRegistry`, constructed as `PackRegistry(Collection<Table> tables)`.
  - The constructor throws `IllegalStateException("no fate_chart named 'default'")` when the default chart is missing.
  - `FateChart fateChart()`
  - `MeaningTable meaning(String name)` throws `IllegalArgumentException("unknown meaning table 'Actions'; loaded: actions, descriptions")`.
  - `List<String> meaningTableNames()`, sorted.
- Produces: `public class PackLoader`, constructed as `PackLoader(Path packsDir)`, with `PackRegistry load()` and `static Path expandHome(String raw)`. `expandHome` turns a leading `~`, `~/` or `~\` into `user.home`.
- Produces: bean `OracleConfig.packRegistry(@Value("${oracle.packs.dir}") String packsDir)` returning `new PackLoader(PackLoader.expandHome(packsDir)).load()`. `application.yaml` sets `oracle.packs.dir: ${user.home}/.oracle/packs`.

Loader rules, from spec "How packs combine":
1. Read the demo pack from `classpath:packs/demo/*.yaml` (`PathMatchingResourcePatternResolver`), with source `classpath:packs/demo/<file>`.
2. If `packsDir` exists, walk it recursively. Read files ending in `.yaml`, `.yml` or `.json` (any case) in sorted path order, as UTF-8, with a leading U+FEFF stripped. The source is the absolute path, and other files are ignored. A missing directory logs one INFO line and loading continues.
3. A table's key is (type, name). A personal table replaces a demo table with the same key, and an INFO line names both sources. Two personal tables with the same key throw `PackException("packs", "duplicate <type> table '<name>' in <sourceA> and <sourceB>")`.
4. Log one INFO line per loaded table: type, name, pack and source.

Demo pack contents. Start each file with `# Tableteller Tools demo pack: original content written for this project, no text from published games.` and use `pack: demo`.
- `fate-chart.yaml`: `type: fate_chart`, `name: default`, `die: d100`, `random_event: { rule: doubles_at_or_below_chaos }`. Generate the cells by printing `PackYaml.fateChart("demo", "default")` from a throwaway snippet; don't commit the generator. Add the comment `# Thresholds come from the demo fate chart formula in docs/SPEC.md.`
- `actions.yaml` and `descriptions.yaml`: `type: meaning`, `die: d100`, two columns (`entries`, `entries_2`). Word *i* covers `[5i−4, 5i]`.

| # | actions `entries` | actions `entries_2` | descriptions `entries` | descriptions `entries_2` |
| --- | --- | --- | --- | --- |
| 1–5 | seek, hide, break, guard, betray | secret, ally, debt, border, oath | quietly, boldly, strangely, carefully, wildly | ancient, broken, hidden, bright, hollow |
| 6–10 | bargain, warn, follow, steal, repair | rival, map, storm, heir, ruin | sadly, quickly, coldly, gently, oddly | sacred, rotten, gilded, frozen, tangled |
| 11–15 | summon, escape, reveal, delay, protect | fire, crown, plague, letter, gate | proudly, barely, warmly, darkly, eagerly | quiet, crimson, fragile, massive, foreign |
| 16–20 | deceive, gather, awaken, challenge, restore | song, beast, memory, coin, shrine | slowly, softly, fiercely, kindly, nervously | humble, cursed, polished, ragged, sweet |

- [ ] **Step 1: Write the failing tests**

```java
class PackLoaderTest {
    @TempDir Path packs;

    @Test
    void demoPackLoadsWhenPacksDirIsMissing() {
        var registry = new PackLoader(packs.resolve("missing")).load();
        assertThat(registry.fateChart().pack()).isEqualTo("demo");
        assertThat(registry.fateChart().name()).isEqualTo("default");
        assertThat(registry.meaningTableNames()).containsExactly("actions", "descriptions");
    }

    @Test
    void demoChartMatchesTheFormulaInAll81Cells() {
        var chart = new PackLoader(packs).load().fateChart();
        for (var odds : OddsLevel.values())
            for (int chaos = 1; chaos <= 9; chaos++)
                assertThat(chart.cell(odds, chaos)).isEqualTo(DemoChartFormula.cell(odds, chaos));
    }

    @Test
    void demoMeaningTablesHaveTheirWordLists() {
        var registry = new PackLoader(packs).load();
        assertThat(registry.meaning("actions").first().valueFor(1)).isEqualTo("seek");
        assertThat(registry.meaning("actions").second().valueFor(100)).isEqualTo("shrine");
        assertThat(registry.meaning("descriptions").first().valueFor(50)).isEqualTo("oddly");
        assertThat(registry.meaning("descriptions").second().valueFor(96)).isEqualTo("sweet");
        for (var name : List.of("actions", "descriptions"))
            for (int n = 1; n <= 100; n++) {
                assertThat(registry.meaning(name).first().valueFor(n)).isNotBlank();
                assertThat(registry.meaning(name).second().valueFor(n)).isNotBlank();
            }
    }

    @Test
    void personalTableReplacesDemoTableWithSameTypeAndName() throws IOException {
        Files.writeString(packs.resolve("my-actions.yaml"), meaningYaml("mine", "actions"));
        var registry = new PackLoader(packs).load();
        assertThat(registry.meaning("actions").pack()).isEqualTo("mine");
        assertThat(registry.meaning("descriptions").pack()).isEqualTo("demo");
        assertThat(registry.fateChart().pack()).isEqualTo("demo");
    }

    @Test
    void duplicatePersonalTablesStopLoading() throws IOException {
        Files.writeString(packs.resolve("a.yaml"), meaningYaml("one", "actions"));
        Files.createDirectories(packs.resolve("sub"));
        Files.writeString(packs.resolve("sub/b.yaml"), meaningYaml("two", "actions"));
        assertThatThrownBy(() -> new PackLoader(packs).load())
            .isInstanceOf(PackException.class)
            .hasMessageContaining("duplicate meaning table 'actions'")
            .hasMessageContaining("a.yaml").hasMessageContaining("b.yaml");
    }

    @Test
    void invalidPersonalPackStopsLoadingAndNamesTheFile() throws IOException {
        Files.writeString(packs.resolve("broken.yaml"), meaningYaml("mine", "broken").replace("die: d2", "die: 2d6"));
        assertThatThrownBy(() -> new PackLoader(packs).load())
            .isInstanceOf(PackException.class)
            .hasMessageContaining("broken.yaml").hasMessageContaining("die must look like d100");
    }

    @Test
    void ignoresNonPackFilesAndReadsNestedJson() throws IOException {
        Files.writeString(packs.resolve("README.md"), "# my notes");
        Files.writeString(packs.resolve("notes.txt"), "not a pack");
        Files.createDirectories(packs.resolve("nested"));
        Files.writeString(packs.resolve("nested/extra.json"), """
            {"pack": "mine", "type": "meaning", "name": "extra", "die": "d2",
             "entries": [{"range": [1, 2], "value": "x"}]}
            """);
        assertThat(new PackLoader(packs).load().meaningTableNames()).containsExactly("actions", "descriptions", "extra");
    }

    @Test
    void readsUtf8WithByteOrderMarkAndCyrillic() throws IOException {
        var yaml = """
            pack: ru
            type: meaning
            name: slova
            die: d2
            entries:
              - { range: [1, 1], value: "тайна" }
              - { range: [2, 2], value: "предательство" }
            """.getBytes(StandardCharsets.UTF_8);
        var withBom = new byte[yaml.length + 3];
        withBom[0] = (byte) 0xEF; withBom[1] = (byte) 0xBB; withBom[2] = (byte) 0xBF;
        System.arraycopy(yaml, 0, withBom, 3, yaml.length);
        Files.write(packs.resolve("slova.yaml"), withBom);
        assertThat(new PackLoader(packs).load().meaning("slova").first().valueFor(1)).isEqualTo("тайна");
    }

    @Test
    void expandsLeadingTildeToHome() {
        var home = Path.of(System.getProperty("user.home"));
        assertThat(PackLoader.expandHome("~")).isEqualTo(home);
        assertThat(PackLoader.expandHome("~/packs")).isEqualTo(home.resolve("packs"));
        assertThat(PackLoader.expandHome("~\\packs")).isEqualTo(home.resolve("packs"));
        assertThat(PackLoader.expandHome(packs.toString())).isEqualTo(packs);
    }

    /** A valid one-column d2 meaning table. */
    private static String meaningYaml(String pack, String name) { /* entries [1,1] "a", [2,2] "b" */ }
}

class PackRegistryTest {
    @Test
    void unknownMeaningTableListsLoadedNames() {
        var registry = new PackRegistry(List.of(DemoChartFormula.chart(), meaning("descriptions"), meaning("actions")));
        assertThatThrownBy(() -> registry.meaning("Actions"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("unknown meaning table 'Actions'; loaded: actions, descriptions");
    }

    @Test
    void requiresDefaultFateChart() {
        assertThatThrownBy(() -> new PackRegistry(List.of(meaning("actions"))))
            .isInstanceOf(IllegalStateException.class).hasMessage("no fate_chart named 'default'");
    }

    private static MeaningTable meaning(String name) {
        var column = new RangeColumn(List.of(new RangeEntry(1, 2, "x")));
        return new MeaningTable("test", name, 2, column, column);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'dev.tableteller.pack.PackLoaderTest' --tests 'dev.tableteller.pack.PackRegistryTest'`
Expected: compilation fails (`PackLoader` does not exist).

- [ ] **Step 3: Implement `PackRegistry`, `PackLoader`, the bean, `application.yaml` and the three demo files**

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src
git commit -m "Add pack loader, registry and demo pack"
```

---

### Task 5: In-memory oracle state and roll log

**Files:**
- Create: `src/main/java/dev/tableteller/state/{OracleState,OracleStateRepository,InMemoryOracleStateRepository,ActiveCampaign,NewRoll,RollRecord,RollLogRepository,InMemoryRollLogRepository}.java`
- Test: `src/test/java/dev/tableteller/state/{InMemoryOracleStateRepositoryTest,InMemoryRollLogRepositoryTest}.java`

**Interfaces:**
- Produces: `public record OracleState(int chaosFactor, int sceneNumber)`.
- Produces: `public interface OracleStateRepository { OracleState find(UUID campaignId); void saveChaos(UUID campaignId, int chaosFactor); }`. The `@Repository InMemoryOracleStateRepository` starts any campaign it hasn't seen at `(5, 0)`, which is the M1 implicit campaign.
- Produces: `@Component public class ActiveCampaign` with `UUID require()`. In M1 it returns one UUID fixed at construction; in M2 it throws when nothing is open.
- Produces: `public record NewRoll(String tool, Map<String, Object> inputs, List<Integer> dice, Map<String, Object> result, int sceneNumber)`. Map keys are snake_case and enum values are stored as their lowercase names.
- Produces: `public record RollRecord(long id, UUID campaignId, String tool, Map<String, Object> inputs, List<Integer> dice, Map<String, Object> result, int sceneNumber, Instant createdAt)` with `String rollId()` returning `"r_%04d".formatted(id)`.
- Produces: `public interface RollLogRepository { RollRecord append(UUID campaignId, NewRoll roll); List<RollRecord> findAll(UUID campaignId); }`. The `@Repository InMemoryRollLogRepository` is append-only, gives ids from 1 upward across all campaigns, is thread-safe, and `findAll` returns an unmodifiable list in id order.

- [ ] **Step 1: Write the failing tests**

```java
class InMemoryOracleStateRepositoryTest {
    UUID campaign = new ActiveCampaign().require();
    InMemoryOracleStateRepository repo = new InMemoryOracleStateRepository();

    @Test
    void implicitCampaignStartsAtChaosFiveSceneZero() {
        assertThat(repo.find(campaign)).isEqualTo(new OracleState(5, 0));
    }

    @Test
    void saveChaosKeepsSceneNumber() {
        repo.saveChaos(campaign, 7);
        assertThat(repo.find(campaign)).isEqualTo(new OracleState(7, 0));
    }

    @Test
    void activeCampaignIsStable() {
        var active = new ActiveCampaign();
        assertThat(active.require()).isEqualTo(active.require());
    }
}

class InMemoryRollLogRepositoryTest {
    UUID campaign = UUID.randomUUID();
    InMemoryRollLogRepository repo = new InMemoryRollLogRepository();
    NewRoll roll = new NewRoll("roll", Map.of("min", 1, "max", 6), List.of(4), Map.of("value", 4), 0);

    @Test
    void appendAssignsIncreasingIdsFromOne() {
        var first = repo.append(campaign, roll);
        var second = repo.append(campaign, roll);
        assertThat(first.id()).isEqualTo(1);
        assertThat(second.id()).isEqualTo(2);
        assertThat(first.rollId()).isEqualTo("r_0001");
        assertThat(first.createdAt()).isNotNull();
    }

    @Test
    void rollIdPadsToFourDigitsAndGrowsPastThem() {
        assertThat(record(142).rollId()).isEqualTo("r_0142");
        assertThat(record(12345).rollId()).isEqualTo("r_12345");
    }

    @Test
    void findAllReturnsOnlyThisCampaignInOrderAndCannotBeModified() {
        repo.append(campaign, roll);
        repo.append(UUID.randomUUID(), roll);
        repo.append(campaign, roll);
        var rows = repo.findAll(campaign);
        assertThat(rows).extracting(RollRecord::id).containsExactly(1L, 3L);
        assertThatThrownBy(() -> rows.add(rows.getFirst())).isInstanceOf(UnsupportedOperationException.class);
    }

    private RollRecord record(long id) {
        return new RollRecord(id, campaign, "roll", Map.of(), List.of(), Map.of(), 0, Instant.now());
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'dev.tableteller.state.*'`
Expected: compilation fails.

- [ ] **Step 3: Implement the `state` package with the signatures above**

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src
git commit -m "Add in-memory oracle state and roll log"
```

---

### Task 6: OracleTools: roll, set_chaos, fate_check

**Files:**
- Create: `src/main/java/dev/tableteller/tools/OracleTools.java`
- Test: `src/test/java/dev/tableteller/tools/OracleToolsTest.java`

**Interfaces:**
- Consumes: `RandomSource`, `FateService`, `PackRegistry`, `ActiveCampaign`, `OracleStateRepository`, `RollLogRepository`, `RollRecord.rollId()`.
- Produces: `@Component public class OracleTools`, constructed as `OracleTools(RandomSource random, FateService fate, PackRegistry packs, ActiveCampaign campaign, OracleStateRepository state, RollLogRepository rolls)`. Task 7 adds `MeaningService meaning` after `fate`. Nested result records:
  - `public record RollResult(@JsonProperty("roll_id") String rollId, int value, String summary)`
  - `public record SetChaosResult(@JsonProperty("roll_id") String rollId, @JsonProperty("chaos_factor") int chaosFactor, String summary)`
  - `public record FateCheckResult(@JsonProperty("roll_id") String rollId, FateAnswer answer, List<Integer> dice, @JsonProperty("chaos_factor") int chaosFactor, @JsonProperty("random_event_triggered") boolean randomEventTriggered, String summary)`
- Tool methods. Every parameter is `@McpToolParam(required = true, description = …)`, and the boxed types let a missing argument be reported instead of silently becoming 0.
  - `@McpTool(name = "roll") RollResult roll(Integer min, Integer max)`. Description: "Roll one evenly distributed whole number from min to max, inclusive. Use it whenever the story needs a number, instead of choosing one yourself." Params: "Lowest possible value, inclusive", "Highest possible value, inclusive".
  - `@McpTool(name = "set_chaos") SetChaosResult setChaos(Integer value)`. Description: "Set the chaos factor (1-9) directly, to correct a misjudged scene." Param: "New chaos factor, 1-9".
  - `@McpTool(name = "fate_check") FateCheckResult fateCheck(String question, OddsLevel odds)`. Description: "Ask the oracle a yes/no question about the story. Pick honest odds; the server rolls d100 on the fate chart at the current chaos factor and reports whether a random event triggered. Narrate the answer faithfully and never reroll." Params: "The yes/no question, stored as asked", "How likely a yes is".

Roll-log rows (`sceneNumber` is the current scene from `OracleState`):

| Tool | inputs | dice | result |
| --- | --- | --- | --- |
| `roll` | `{min, max}` | `[value]` | `{value}` |
| `set_chaos` | `{value}` | `[]` | `{chaos_factor, previous_chaos_factor}` |
| `fate_check` | `{question, odds}` | `[roll]` | `{answer, chaos_factor, random_event_triggered}` |

Error messages:
- `roll`: `roll needs whole numbers min < max, both within ±1,000,000; got min=<min>, max=<max>`
- `set_chaos`: `chaos factor must be 1-9; got <value>`
- `fate_check`: `question must not be blank` and `odds is required; one of impossible, nearly_impossible, very_unlikely, unlikely, fifty_fifty, likely, very_likely, nearly_certain, certain`

- [ ] **Step 1: Write the failing tests**

```java
class OracleToolsTest {
    ActiveCampaign campaign = new ActiveCampaign();
    InMemoryOracleStateRepository state = new InMemoryOracleStateRepository();
    InMemoryRollLogRepository rolls = new InMemoryRollLogRepository();
    PackRegistry packs = new PackRegistry(List.of(DemoChartFormula.chart()));

    OracleTools tools(int... scripted) {
        var random = new ScriptedRandomSource(scripted);
        return new OracleTools(random, new FateService(random), packs, campaign, state, rolls);
    }

    List<RollRecord> rows() { return rolls.findAll(campaign.require()); }

    @Test
    void rollReturnsValueAndLogsIt() {
        assertThat(tools(4).roll(1, 6)).isEqualTo(new OracleTools.RollResult("r_0001", 4, "Rolled 4 (1 to 6)."));
        var row = rows().getFirst();
        assertThat(row.tool()).isEqualTo("roll");
        assertThat(row.inputs()).isEqualTo(Map.of("min", 1, "max", 6));
        assertThat(row.dice()).containsExactly(4);
        assertThat(row.result()).isEqualTo(Map.of("value", 4));
        assertThat(row.sceneNumber()).isZero();
    }

    @Test
    void rollAcceptsTheWidestRange() {
        assertThat(tools(0).roll(-1_000_000, 1_000_000).value()).isZero();
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {"5,5", "6,1", "-1000001,0", "0,1000001", "null,6", "1,null"})
    void rollRejectsBadRangesWithoutLogging(Integer min, Integer max) {
        assertThatThrownBy(() -> tools().roll(min, max))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("min < max");
        assertThat(rows()).isEmpty();
    }

    @Test
    void setChaosUpdatesStateAndLogsWithoutDice() {
        assertThat(tools().setChaos(7))
            .isEqualTo(new OracleTools.SetChaosResult("r_0001", 7, "Chaos factor is now 7 (was 5)."));
        assertThat(state.find(campaign.require()).chaosFactor()).isEqualTo(7);
        assertThat(rows().getFirst().dice()).isEmpty();
        assertThat(rows().getFirst().result()).isEqualTo(Map.of("chaos_factor", 7, "previous_chaos_factor", 5));
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {"0", "10", "null"})
    void setChaosRejectsValuesOutsideOneToNine(Integer value) {
        assertThatThrownBy(() -> tools().setChaos(value))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("chaos factor must be 1-9");
        assertThat(rows()).isEmpty();
        assertThat(state.find(campaign.require()).chaosFactor()).isEqualTo(5);
    }

    @Test
    void fateCheckMatchesTheSpecExample() {        // likely at chaos 6: 14 / 70 / 95; 44 is a double at or below 6
        var tools = tools(44);
        tools.setChaos(6);
        assertThat(tools.fateCheck("Is the innkeeper hiding something?", OddsLevel.likely))
            .isEqualTo(new OracleTools.FateCheckResult("r_0002", FateAnswer.yes, List.of(44), 6, true,
                "Yes (44 on likely, chaos 6). A random event is triggered."));
        var row = rows().get(1);
        assertThat(row.tool()).isEqualTo("fate_check");
        assertThat(row.inputs()).isEqualTo(Map.of("question", "Is the innkeeper hiding something?", "odds", "likely"));
        assertThat(row.dice()).containsExactly(44);
        assertThat(row.result()).isEqualTo(Map.of("answer", "yes", "chaos_factor", 6, "random_event_triggered", true));
    }

    @Test
    void fateCheckExceptionalNoWithoutEvent() {   // likely at chaos 5: exceptional no from 94
        var result = tools(97).fateCheck("Is the bridge intact?", OddsLevel.likely);
        assertThat(result.answer()).isEqualTo(FateAnswer.exceptional_no);
        assertThat(result.randomEventTriggered()).isFalse();
        assertThat(result.summary()).isEqualTo("Exceptional no (97 on likely, chaos 5).");
    }

    @Test
    void fateCheckRejectsBlankQuestionAndMissingOdds() {
        assertThatThrownBy(() -> tools().fateCheck("  ", OddsLevel.likely))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("question must not be blank");
        assertThatThrownBy(() -> tools().fateCheck("Is it?", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("odds is required; one of impossible, nearly_impossible, very_unlikely, unlikely, "
                + "fifty_fifty, likely, very_likely, nearly_certain, certain");
        assertThat(rows()).isEmpty();
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'dev.tableteller.tools.*'`
Expected: compilation fails (`OracleTools` does not exist).

- [ ] **Step 3: Implement `OracleTools` with the three tools**

Each method validates input, reads `OracleState` for the active campaign, does its work, appends one `NewRoll` and builds its result from the returned `RollRecord.rollId()`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src
git commit -m "Add roll, set_chaos and fate_check tools"
```

---

### Task 7: MeaningService and the meaning tool

**Files:**
- Create: `src/main/java/dev/tableteller/meaning/{MeaningService,MeaningPair}.java`
- Modify: `src/main/java/dev/tableteller/tools/OracleTools.java` (constructor, `meaning` tool)
- Test: `src/test/java/dev/tableteller/meaning/MeaningServiceTest.java`
- Modify: `src/test/java/dev/tableteller/tools/OracleToolsTest.java` (the `tools(...)` helper and the registry fixture, plus new tests)

**Interfaces:**
- Consumes: `RandomSource`, `MeaningTable`, `PackRegistry.meaning(String)`, everything `OracleTools` already uses.
- Produces: `public record MeaningPair(int firstRoll, String first, int secondRoll, String second)` and `@Service public class MeaningService`, constructed as `MeaningService(RandomSource random)`. `List<MeaningPair> roll(MeaningTable table, int count)` works pair by pair: it rolls `between(1, dieSize)` on `first`, then on `second`.
- Produces: the `OracleTools` constructor becomes `(RandomSource random, FateService fate, MeaningService meaning, PackRegistry packs, ActiveCampaign campaign, OracleStateRepository state, RollLogRepository rolls)`. It gains `public record MeaningResult(@JsonProperty("roll_id") String rollId, String table, List<List<String>> pairs, List<Integer> dice, String summary)` and the tool `@McpTool(name = "meaning") MeaningResult meaning(String table, Integer count)`.
  - Description: "Roll a word pair on a meaning table, such as actions or descriptions, to interpret an event. Tell the player which words you got and how you read them."
  - Params: `table` is required, "Meaning table name, for example actions or descriptions"; `count` has `required = false`, "Number of word pairs, 1 or 2; default 1".
  - Row: inputs `{table, count}` with the count actually used, dice holding every roll in order, result `{pairs}`.
  - Summary: `"<table>: <w1> / <w2>"`, with pairs joined by `"; "`.
  - Errors: an unknown table passes the registry's message through; a bad count gives `count must be 1 or 2; got <count>`.

Test fixtures used below:
- `single`: d4, one column: fire, water, earth, air.
- `actions`: d4, first column seek, hide, break, guard; second column secret, ally, debt, border.

The `OracleToolsTest` registry becomes `new PackRegistry(List.of(DemoChartFormula.chart(), single, actions))`.

- [ ] **Step 1: Write the failing tests**

```java
class MeaningServiceTest {
    @Test
    void oneColumnTableRollsBothWordsOnIt() {
        assertThat(service(1, 4).roll(single, 1)).containsExactly(new MeaningPair(1, "fire", 4, "air"));
    }

    @Test
    void twoColumnTableRollsTheSecondWordOnTheSecondColumn() {
        assertThat(service(2, 3).roll(actions, 1)).containsExactly(new MeaningPair(2, "hide", 3, "debt"));
    }

    @Test
    void countTwoRollsTwoPairsInOrder() {
        assertThat(service(1, 2, 3, 4).roll(actions, 2)).containsExactly(
            new MeaningPair(1, "seek", 2, "ally"), new MeaningPair(3, "break", 4, "border"));
    }
}

// added to OracleToolsTest
@Test
void meaningDefaultsToOnePairAndLogsIt() {
    assertThat(tools(2, 3).meaning("actions", null)).isEqualTo(new OracleTools.MeaningResult(
        "r_0001", "actions", List.of(List.of("hide", "debt")), List.of(2, 3), "actions: hide / debt"));
    var row = rows().getFirst();
    assertThat(row.inputs()).isEqualTo(Map.of("table", "actions", "count", 1));
    assertThat(row.dice()).containsExactly(2, 3);
    assertThat(row.result()).isEqualTo(Map.of("pairs", List.of(List.of("hide", "debt"))));
}

@Test
void meaningTwoPairsSummary() {
    assertThat(tools(1, 2, 3, 4).meaning("actions", 2).summary()).isEqualTo("actions: seek / ally; break / border");
}

@Test
void meaningRejectsUnknownTable() {
    assertThatThrownBy(() -> tools().meaning("Actions", 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("unknown meaning table 'Actions'; loaded: actions, single");
    assertThat(rows()).isEmpty();
}

@ParameterizedTest
@ValueSource(ints = {0, 3})
void meaningRejectsCountOutsideOneToTwo(int count) {
    assertThatThrownBy(() -> tools().meaning("actions", count))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("count must be 1 or 2; got " + count);
    assertThat(rows()).isEmpty();
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'dev.tableteller.meaning.*' --tests 'dev.tableteller.tools.*'`
Expected: compilation fails (`MeaningService` does not exist).

- [ ] **Step 3: Implement `MeaningPair`, `MeaningService` and the `meaning` tool; update the constructor and the test helper**

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src
git commit -m "Add meaning tool"
```

---

### Task 8: stdio end to end, README, M1 done

**Files:**
- Modify: `src/main/resources/application.yaml`
- Test: `src/test/java/dev/tableteller/StdioEndToEndTest.java`
- Create: `README.md`
- Modify: `docs/SPEC.md` (tick M1)

**Interfaces:**
- Consumes: the whole app, through its main class `dev.tableteller.TabletellerToolsApplication`.

- [ ] **Step 1: Write the failing end-to-end test**

The test starts the server once (`@BeforeAll`) as a subprocess and talks JSON-RPC over its stdin/stdout. Don't add any `-D` encoding flags: the test must see what a real client sees.

```java
class StdioEndToEndTest {
    @TempDir static Path dir;
    static Process server;
    static BufferedWriter stdin;
    static final BlockingQueue<String> stdout = new LinkedBlockingQueue<>();
    static final JsonMapper json = JsonMapper.builder().build();
    static int nextId = 1;

    @BeforeAll
    static void startServer() throws Exception {
        var packs = Files.createDirectories(dir.resolve("packs"));
        Files.writeString(packs.resolve("slova.yaml"), """
            pack: ru
            type: meaning
            name: slova
            die: d2
            entries:
              - { range: [1, 1], value: "тайна" }
              - { range: [2, 2], value: "предательство" }
            """, StandardCharsets.UTF_8);
        server = new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(),
                "-cp", System.getProperty("java.class.path"), "dev.tableteller.TabletellerToolsApplication",
                "--oracle.packs.dir=" + packs, "--logging.file.name=" + dir.resolve("oracle.log"))
            .redirectError(dir.resolve("stderr.txt").toFile())
            .start();
        stdin = new BufferedWriter(new OutputStreamWriter(server.getOutputStream(), StandardCharsets.UTF_8));
        // a virtual thread reads server stdout as UTF-8, line by line, into `stdout`
        var init = request("initialize", Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of(),
            "clientInfo", Map.of("name", "e2e", "version", "1")));
        assertThat(init.at("/result/serverInfo/name").asString()).isEqualTo("tableteller-tools");
        notify("notifications/initialized");
    }

    @AfterAll
    static void stopServer() { /* close stdin, destroy, wait 10 s, then destroyForcibly */ }

    /**
     * Sends a request and returns the response with the same id, waiting at most 30 s per line.
     * Every stdout line must parse as JSON with "jsonrpc": "2.0"; otherwise fail and quote the raw line.
     * Lines with other ids, and notifications, are checked and skipped.
     */
    static JsonNode request(String method, Object params) { … }
    static void notify(String method) { … }
    static JsonNode callTool(String name, Map<String, Object> arguments) {
        return request("tools/call", Map.of("name", name, "arguments", arguments)).get("result");
    }
    static JsonNode body(JsonNode toolResult) { return json.readTree(toolResult.at("/content/0/text").asString()); }

    @Test
    void listsTheFourOracleToolsWithLowercaseOdds() {
        var tools = request("tools/list", Map.of()).at("/result/tools");
        assertThat(tools.valueStream().map(t -> t.get("name").asString()))
            .containsExactlyInAnyOrder("roll", "fate_check", "meaning", "set_chaos");
        var fate = tools.valueStream().filter(t -> t.get("name").asString().equals("fate_check")).findFirst().orElseThrow();
        assertThat(fate.at("/inputSchema/properties/odds/enum").valueStream().map(JsonNode::asString)).containsExactly(
            "impossible", "nearly_impossible", "very_unlikely", "unlikely", "fifty_fifty",
            "likely", "very_likely", "nearly_certain", "certain");
    }

    @Test
    void fateCheckReturnsRollIdAnswerAndDice() {   // no e2e test changes chaos
        var result = callTool("fate_check", Map.of("question", "Is the door locked?", "odds", "likely"));
        assertThat(result.path("isError").asBoolean(false)).isFalse();
        var body = body(result);
        assertThat(body.get("roll_id").asString()).matches("r_\\d{4,}");
        assertThat(body.get("answer").asString()).isIn("yes", "no", "exceptional_yes", "exceptional_no");
        assertThat(body.get("dice").get(0).asInt()).isBetween(1, 100);
        assertThat(body.get("chaos_factor").asInt()).isEqualTo(5);
        assertThat(body.has("random_event_triggered")).isTrue();
    }

    @Test
    void offFormatOddsAreRejectedWithTheValidValues() {
        var result = callTool("fate_check", Map.of("question", "Is it?", "odds", "Likely"));
        assertThat(result.get("isError").asBoolean()).isTrue();
        assertThat(result.at("/content/0/text").asString()).contains("fifty_fifty");
    }

    @Test
    void badRollRangeIsAnErrorResult() {
        var result = callTool("roll", Map.of("min", 5, "max", 5));
        assertThat(result.get("isError").asBoolean()).isTrue();
        assertThat(result.at("/content/0/text").asString()).contains("min < max");
    }

    @Test
    void cyrillicWordsSurviveStdio() {
        var pair = body(callTool("meaning", Map.of("table", "slova"))).at("/pairs/0");
        assertThat(pair.valueStream().map(JsonNode::asString)).allMatch(w -> Set.of("тайна", "предательство").contains(w));
    }

    @Test
    void logsGoToTheLogFile() {
        assertThat(dir.resolve("oracle.log")).content(StandardCharsets.UTF_8).contains("Started TabletellerToolsApplication");
    }
}
```

Use Jackson 3's `JsonNode` accessors (`asString()`, `valueStream()`); adjust the names if the 3.1.5 API differs, but keep the assertions.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'dev.tableteller.StdioEndToEndTest'`
Expected: FAIL. Without the stdio settings the banner or log lines reach stdout, and the request helper fails on a non-JSON line (or the server never answers `initialize`).

- [ ] **Step 3: Complete `application.yaml`**

```yaml
spring:
  main:
    web-application-type: none
    banner-mode: off
  ai:
    mcp:
      server:
        name: tableteller-tools
        version: 0.1.0
        type: SYNC
        stdio: true
logging:
  console:
    enabled: false
  file:
    name: ${user.home}/.oracle/logs/tableteller-tools.log
oracle:
  packs:
    dir: ${user.home}/.oracle/packs
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'dev.tableteller.StdioEndToEndTest'`
Expected: PASS, 6 tests.

If a test still fails:
- **A non-JSON line on stdout:** find what printed it (banner, a logger, `System.out`) and silence it in configuration. Never filter stdout in the test.
- **`cyrillicWordsSurviveStdio` shows garbled text:** at the start of `main`, before `SpringApplication.run`, add `System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8))`, then re-run.
- **`offFormatOddsAreRejectedWithTheValidValues` fails because the library's conversion message leaves out the values:** stop and report it. Don't change `odds` to a `String`, because the spec requires the enum in the tool schema.

- [ ] **Step 5: Write `README.md`**

It covers:
- what the server is, in one paragraph pointing to `docs/SPEC.md`
- requirements: JDK 21, no database in M1
- the build: `./gradlew bootJar` with `JAVA_HOME` set, producing `build/libs/tableteller-tools-0.1.0.jar`
- a Claude Desktop `claude_desktop_config.json` entry that runs the jar with an absolute path to a JDK 21 `java.exe`, plus `--oracle.packs.dir=…` in `args` as the optional override
- where personal packs go (`~/.oracle/packs/`, any subfolder, `.yaml`/`.yml`/`.json`, several tables per file separated by `---`), and that they replace demo tables by type + name
- that an invalid pack stops the server, with the reason in `~/.oracle/logs/tableteller-tools.log`
- that tables from published games are typed in by the user and never committed
- M1 limits: one implicit campaign held in memory, so chaos and the roll log reset when the server restarts

- [ ] **Step 6: Tick M1 in `docs/SPEC.md`**

Change `- [ ] M1 Oracle core:` to `- [x] M1 Oracle core:`.

- [ ] **Step 7: Run the full build**

Run: `./gradlew test bootJar`
Expected: BUILD SUCCESSFUL, all tests pass, and `build/libs/tableteller-tools-0.1.0.jar` exists.

- [ ] **Step 8: Commit**

```bash
git add src README.md docs/SPEC.md
git commit -m "Run oracle tools over stdio end to end"
```

- [ ] **Step 9: Hand-off check with your human partner**

Ask them to add the jar to Claude Desktop following the README, then ask the oracle one question and roll on `actions`. Report what they saw. This step is optional; the milestone is complete after Step 8.
