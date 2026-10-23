# M3.1 Campaign per Call Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every tool except `campaign_create` takes a required `campaign` and the server keeps no active campaign, so two Claude Desktop chats on one server can each play their own campaign.

**Architecture:** A new `CampaignResolver` turns a `campaign` argument (name or id) into the campaign's id, with the error messages the spec fixes. Each tools class swaps `ActiveCampaign` for the resolver and gains a `String campaign` first parameter, resolved exactly where `active.require()` was called. The campaign resources become URI templates with a `{campaign}` segment. Once nothing reads `ActiveCampaign`, it is deleted. No schema change.

**Tech Stack:** M3's stack, unchanged: Java 21, Spring Boot 4.1.1, Spring AI 2.0.1 (MCP SDK 2.0.0), Jackson 3, Flyway, PostgreSQL 16, JUnit 5 + AssertJ, Testcontainers. No new dependencies.

**Spec:** `docs/SPEC.md` (v0.6). Read "Naming the campaign" (Domain model), the Oracle and Journal tool tables, "Resources and prompts", the system prompt draft and the v0.6 decisions before starting.

**Branch:** the worktree branch `claude/sad-mahavira-c112bc`, cut from `master`.

## Global Constraints

- Every M1–M3 constraint still holds: run Gradle with `JAVA_HOME=/c/Users/sefim/.jdks/corretto-21.0.10`; Docker must be running; all randomness goes through `RandomSource.between`; every oracle tool writes exactly one roll-log row; bad input throws `IllegalArgumentException` whose message says what is wrong and what is allowed; no `@SpringBootTest`; no text from published games in the repo.
- Do not run `bootJar` and do not touch `build\libs`: the player is playing on that jar in Claude Desktop, which locks it.
- `campaign` is the first parameter of every tool except `campaign_create`, declared as `@McpToolParam(required = true, description = CampaignResolver.DESCRIPTION) String campaign`.
- A tool resolves `campaign` where it used to call `active.require()`: after its own input checks, and inside its transaction when it runs in one. Input errors therefore still come before campaign errors, and a rejected call writes nothing.
- Roll-log `inputs` do not record `campaign`; the row's `campaign_id` already does.
- The tools' fields that held `ActiveCampaign` become `private final CampaignResolver resolver`, in the same constructor position.
- Summaries and messages other than the ones this plan lists do not change.
- The e2e tool list (`StdioEndToEndTest.listsTheOracleToolsWithLowercaseOdds`) stays at the same 18 tools.

## Review Focus

1. **The playtest itself:** chat 2 creates its campaign while chat 1 has a scene open; chat 1's `end_scene` must close chat 1's scene, not chat 2's. Tests: Task 1 `twoChatsOnOneServerKeepTheirCampaignsApart` (enabled in Task 7), Task 3 `scenesBelongToTheCampaignTheyName`.
2. **The DM drops or misspells `campaign`** (models do drop arguments): the error lists the campaigns to pick from and nothing is written. Tests: every task's `…NeedAKnownCampaign` test asserts the messages and that no row was written.
3. **One campaign, many spellings:** `" пепел "`, `Пепел`, the id, and the id with spaces all name the same campaign, over stdio too. Tests: Task 2 `CampaignResolverTest`; Task 1's chat 2 passes its campaign by id.
4. **Resource URIs as clients send them:** `campaign://Iron%20Coast/brief`, raw Cyrillic, a `+` or a `%` in a name. Test: Task 6 `briefDecodesTheCampaignSegment`.
5. **Parallel calls on two campaigns:** 200 `roll` calls sent at once, alternating between two campaigns, each get a reply and land in their own campaign. Test: Task 1 `parallelCallsOnTwoCampaignsLandInTheirOwn` (enabled in Task 7).

## File Map

| Path (under `src/main/java/dev/tableteller/` unless shown) | Responsibility | Task |
| --- | --- | --- |
| `src/test/java/dev/tableteller/TwoChatsEndToEndTest.java` | The playtest bug over stdio | 1, 7 |
| `campaign/CampaignResolver.java`, `campaign/CampaignTools.java` | Name or id → campaign id | 2 |
| `src/test/java/dev/tableteller/support/TestOracle.java` | Wiring; helpers by campaign name | 2–7 |
| `tools/OracleTools.java`, `scene/SceneTools.java` | Oracle and scene tools name their campaign | 3 |
| `lists/ListTools.java`, `journal/JournalTools.java`, `state/RollHistoryTools.java` | Lists, journal, roll history | 4 |
| `session/SessionTools.java`, `session/SessionPrompts.java` | Sessions and prompts | 5 |
| `resource/OracleResources.java`, `campaign/CampaignBriefs.java` | Campaign resources as templates | 6 |
| `campaign/ActiveCampaign.java` (deleted), `campaign/CampaignTools.java`, `README.md`, `docs/SPEC.md` | No active campaign; docs | 7 |
| `src/test/java/dev/tableteller/{StdioEndToEndTest,RestartEndToEndTest}.java` | e2e calls pass `campaign` | 3–7 |

Most test edits are mechanical: a converted tool's calls gain the campaign as their first argument (`oracle.tools.roll(1, 6)` becomes `oracle.tools.roll("Ashfall", 1, 6)`), using the name the test created. Do them with a scripted replace limited to the converted methods and the test files that call them, then fix method references (`tools::sessionStart`) and two-campaign tests by hand.

---

### Task 1: Reproduce the playtest bug over stdio

**Files:**
- Create: `src/test/java/dev/tableteller/TwoChatsEndToEndTest.java`

**Interfaces:**
- Consumes: `McpStdioServer.start(Path)`, `callTool`, `callToolsAtOnce`, `body`; `TestDatabase.reset()` and `jdbc()`; the repositories' existing `findAll` / `findOpen` / `titles` / `live` / `findById` methods.

- [ ] **Step 1: Write the test**

One server, as Claude Desktop runs it, and two "chats" that never share a campaign argument. A small record in the test keeps each chat's calls honest:

```java
/** One chat: every call it makes names its own campaign, and none may fail. */
record Chat(McpStdioServer server, String campaign) {
    JsonNode call(String tool, Object... namesAndValues) { … }   // adds "campaign", asserts isError is false
}
```

`twoChatsOnOneServerKeepTheirCampaignsApart`, in this order:
1. `campaign_create` `Ashfall`; chat 1 (`Ashfall`): `session_start`, `scene_check` `Mara reaches the gate`.
2. `campaign_create` `Iron Coast`, mid-scene, as in the playtest; chat 2 uses the returned `campaign_id` as its campaign: `session_start`.
3. Alternating: chat 1 `fate_check` (`Is the gate guarded?`, `likely`); chat 2 `scene_check` `Ilsa boards the ship`; chat 1 `journal_write` location `North gate`; chat 2 `journal_write` npc `Captain Orr`; chat 1 `list_add` threads `Find the heir`; chat 2 `set_chaos` 7; chat 1 `end_scene` (`Mara slipped past.`, in control); chat 2 `roll` 1–6; chat 1 `session_end` (`Mara got in.`, `none`).

Then, read straight from the database:
- roll-log tools: Ashfall `[scene_check, fate_check, end_scene]`, Iron Coast `[scene_check, set_chaos, roll]`
- scenes: Ashfall's scene 1 is closed with summary `Mara slipped past.`; Iron Coast's scene 1 is open with setup `Ilsa boards the ship`
- chaos: Ashfall 4, Iron Coast 7
- live threads: Ashfall `[Find the heir]`, Iron Coast empty
- journal titles: Ashfall location `[North gate]` and npc `[]`; Iron Coast npc `[Captain Orr]`
- session statuses: Ashfall `[ended]`, Iron Coast `[open]`

`parallelCallsOnTwoCampaignsLandInTheirOwn`: create both campaigns, then `callToolsAtOnce("roll", …)` with 200 argument maps alternating `Ashfall` / `Iron Coast` → 200 results without `isError`, and 100 roll-log rows in each campaign.

- [ ] **Step 2: Run it to see the bug**

Run: `./gradlew test --tests 'dev.tableteller.TwoChatsEndToEndTest'`
Expected: FAIL. Before the fix the tools ignore `campaign`, so after the second `campaign_create` chat 1's rows land in Iron Coast; the first failing assertion shows Ashfall with only `[scene_check]`. If the server instead rejects the unknown `campaign` argument, the failure is an `isError` in step 1's first call; note which one you saw in the commit message.

- [ ] **Step 3: Disable it until Task 7 and commit**

Add `@Disabled("needs every tool to name its campaign; enabled in Task 7 of the campaign-per-call plan")` to the class, run `./gradlew test` (BUILD SUCCESSFUL), and commit:

```bash
git add src/test
git commit -m "Reproduce the two-chat campaign mix-up over stdio"
```

---

### Task 2: CampaignResolver

**Files:**
- Create: `src/main/java/dev/tableteller/campaign/CampaignResolver.java`
- Modify: `campaign/CampaignTools.java`, `TestOracle.java`, and the tests that call `TestOracle`'s helpers
- Test: `src/test/java/dev/tableteller/campaign/CampaignResolverTest.java`, `CampaignToolsTest.java`

**Interfaces:**
- Produces: `@Component public class CampaignResolver`, constructed as `CampaignResolver(CampaignRepository campaigns)`, with:
  - `public static final String DESCRIPTION = "This chat's campaign: its name (ignoring case) or id";`
  - `public UUID resolve(String campaign)`: an id first (trimmed, parsed as a UUID), then the trimmed name ignoring case. This is `CampaignTools`' current `byId` / `findByName` / `unknown` logic, moved.
- Produces: `CampaignTools(CampaignRepository campaigns, CampaignResolver resolver, ActiveCampaign active, CampaignBriefs briefs)`; `campaign_open` resolves through it. Its output and `campaign_create` are unchanged in this task.
- Produces on `TestOracle`: `public final CampaignResolver resolver`, and helpers that name their campaign instead of reading the active one:
  - `UUID createCampaign(String name)` (renamed from `openCampaign`; still `campaignCreate(name, null, null, null)`)
  - `UUID campaignId(String campaign)` = `resolver.resolve(campaign)`
  - `int chaos(String campaign)`, `List<RollRecord> rows(String campaign)`

`<campaigns>` below is `campaigns: Ashfall, iron Coast` (every name, sorted ignoring case), or `none exist yet, create one with campaign_create` when there are none.

| Case | Message |
| --- | --- |
| null or blank | `campaign must not be blank; <campaigns>` |
| no such id or name | `no campaign 'Ashfal'; <campaigns>` (the trimmed argument) |

- [ ] **Step 1: Write the failing tests**

`CampaignResolverTest` (with `TestDatabase.reset()`):
- `resolvesANameIgnoringCaseAndSpaces`: `Пепел` resolves from `"  пепел "`
- `resolvesAnIdWithSpaces`: `" " + id + " "` resolves to `id`
- `blankListsTheCampaigns`: `null` and `" "` with none, then after creating `iron Coast` and `Ashfall`, each table row
- `unknownListsTheCampaigns`: both forms of the second row
- `anIdOfNoCampaignIsUnknown`: a random UUID → `no campaign '<that uuid>'; campaigns: Ashfall`

`CampaignToolsTest.openRejectsABlankName` now expects `campaign must not be blank; none exist yet, create one with campaign_create`.

Rename `openCampaign` to `createCampaign` across the tests, and pass the campaign's name to `rows`, `chaos` and anything that called `activeCampaign.require()` to get an id (use `campaignId(name)` or the id `createCampaign` returned). `OracleToolsTest` names its campaign `Ashfall` too (it was `Test`), so every tool test uses the same name.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'dev.tableteller.campaign.*'`
Expected: compilation fails (`CampaignResolver` does not exist).

- [ ] **Step 3: Implement `CampaignResolver`; resolve `campaign_open` through it; update `TestOracle`**

- [ ] **Step 4: Run the full suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src
git commit -m "Add CampaignResolver for campaign names and ids"
```

---

### Task 3: Oracle and scene tools name their campaign

**Files:**
- Modify: `tools/OracleTools.java`, `scene/SceneTools.java`, `TestOracle.java`, `StdioEndToEndTest.java`, `RestartEndToEndTest.java`
- Test: `tools/OracleToolsTest.java`, `scene/SceneToolsTest.java`, `campaign/CampaignToolsTest.java`, `ScriptedSessionTest.java`, and every other test that calls these tools

**Interfaces:**
- Consumes: `CampaignResolver.resolve`, `CampaignResolver.DESCRIPTION` (Task 2).
- Produces: `OracleTools(RandomSource random, FateService fate, MeaningService meaning, RandomEventService events, PackRegistry packs, CampaignResolver resolver, OracleStateRepository state, RollLogRepository rolls, TransactionTemplate transactions)` with `roll(String campaign, Integer min, Integer max)`, `setChaos(String campaign, Integer value)`, `fateCheck(String campaign, String question, OddsLevel odds)`, `randomEvent(String campaign, String context, String table)`, `meaning(String campaign, String table, Integer count)`.
- Produces: `SceneTools(SceneService sceneService, RandomEventService events, PackRegistry packs, CampaignResolver resolver, OracleStateRepository state, SceneRepository scenes, RollLogRepository rolls, TransactionTemplate transactions)` with `sceneCheck(String campaign, String expected_setup)` and `endScene(String campaign, String summary, Boolean pc_in_control, String actual_setup)`.

- [ ] **Step 1: Write the failing tests**

Add `campaign` to every call of these seven tools (scripted replace, then by hand where a test plays two campaigns). Replace `stateAndRollIdsPersistAcrossToolInstances`' `second.activeCampaign.open(id)` with nothing: the second instance names `"Ashfall"` itself.

Move `CampaignToolsTest.oracleToolsFailWithoutAnOpenCampaign` to `OracleToolsTest.oracleToolsNeedAKnownCampaign`: `roll(null, 1, 6)` with no campaigns → `campaign must not be blank; none exist yet, create one with campaign_create`; after creating `iron Coast` and `Ashfall`, each of the five tools with `"Ashfal"` → `no campaign 'Ashfal'; campaigns: Ashfall, iron Coast`, and with `" "` → `campaign must not be blank; campaigns: Ashfall, iron Coast`; neither campaign has a roll-log row afterwards.

New:
- `OracleToolsTest.eachCallActsOnTheCampaignItNames`: `Ashfall` and `Iron Coast`; `setChaos("Iron Coast", 7)`, `roll("Ashfall", 1, 6)` → `rows("Ashfall")` tools `[roll]`, `rows("Iron Coast")` `[set_chaos]`, `chaos("Ashfall")` 5; `fateCheck("Iron Coast", …).chaosFactor()` 7
- `SceneToolsTest.scenesBelongToTheCampaignTheyName` (the playtest at unit level): scripted `8, 8`; `sceneCheck("Ashfall", …)`, `sceneCheck("Iron Coast", …)` (no "still open" error), `endScene("Ashfall", "Mara slipped past.", true, null)` → Ashfall's scene 1 closed and chaos 4; Iron Coast's scene 1 still open and chaos 5
- `SceneToolsTest.sceneToolsNeedAKnownCampaign` replaces `sceneToolsRequireAnOpenCampaign`: both tools with `"Ashfal"` → `no campaign 'Ashfal'; campaigns: Ashfall`, and no roll-log row

e2e: `fate_check`, `roll`, `meaning` calls in `StdioEndToEndTest` pass `"campaign", "E2E"`. In `RestartEndToEndTest`, rename `stateSurvivesARestartButTheOpenCampaignDoesNot` to `stateSurvivesARestart`: the first server's `set_chaos` names `Persisted`; on the second server a `roll` without `campaign` fails the input schema (`required property 'campaign'`), one with campaign `Persistd` is `no campaign 'Persistd'; campaigns: Persisted`, a `roll` with campaign `persisted` (no `campaign_open` first) returns `r_0002`, and `campaign_open` still shows chaos 7 and the thread.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'dev.tableteller.tools.*' --tests 'dev.tableteller.scene.*'`
Expected: compilation fails (no `roll(String, Integer, Integer)`).

- [ ] **Step 3: Implement**

Swap `ActiveCampaign` for `CampaignResolver` in both classes and in `TestOracle`; add the `campaign` parameter; `resolver.resolve(campaign)` replaces `campaign.require()`.

- [ ] **Step 4: Run the full suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src
git commit -m "Oracle and scene tools name their campaign"
```

---

### Task 4: Lists, journal and roll_history name their campaign

**Files:**
- Modify: `lists/ListTools.java`, `journal/JournalTools.java`, `state/RollHistoryTools.java`, `TestOracle.java`, `StdioEndToEndTest.java`, `RestartEndToEndTest.java`
- Test: `lists/ListToolsTest.java`, `journal/{JournalToolsTest,JournalSearchTest}.java`, `state/RollHistoryToolsTest.java`, and every other test that calls these tools

**Interfaces:**
- Produces: `ListTools(ListRepository lists, CampaignResolver resolver)` with `listAdd(String campaign, ListName list, String title, Integer weight, ListStatus status)`, `listUpdate(String campaign, ListName list, String title, Integer weight, ListStatus status)`, `listGet(String campaign, ListName list)`.
- Produces: `JournalTools(JournalRepository journal, CampaignResolver resolver, OracleStateRepository state, TransactionTemplate transactions)` with `journalWrite(String campaign, JournalKind kind, String title, String body, List<String> tags)`, `journalGet(String campaign, String entry_id, String title)`, `journalSearch(String campaign, String query, JournalKind kind)`.
- Produces: `RollHistoryTools(RollLogRepository rolls, CampaignResolver resolver)` with `rollHistory(String campaign, Integer last_n, Integer scene)`.

- [ ] **Step 1: Write the failing tests**

Add `campaign` to every call of these seven tools. The tests that switched campaigns with `openCampaign("Iron Coast")` now create it and name it:
- `JournalToolsTest.getNeverReturnsAnotherCampaignsEntry`: write in `Ashfall`; `journalGet("Iron Coast", id, null)` and `journalGet("Iron Coast", null, title)` give the same messages as before
- `JournalSearchTest.searchStaysInTheOpenCampaign` becomes `searchStaysInTheNamedCampaign`
- `RollHistoryToolsTest.onlyTheOpenCampaignsRolls` becomes `onlyTheNamedCampaignsRolls`: rolls in `Ashfall` → `rollHistory("Iron Coast", null, null)` is `No rolls yet.`

New: `ListToolsTest.listsBelongToTheCampaignTheyName`: `listAdd("Iron Coast", threads, "Find the heir", …)` after `Ashfall` has the same title → no "already on threads" error, and `listGet("Ashfall", threads)` has one entry.

The three `…RequireAnOpenCampaign` tests become `…NeedAKnownCampaign`: each tool with `"Ashfal"` → `no campaign 'Ashfal'; campaigns: Ashfall`, and `journal_write` with `null` → `campaign must not be blank; campaigns: Ashfall`; nothing is written.

e2e: `list_get` (the 200 parallel calls), `journal_write` and `journal_get` in `StdioEndToEndTest`, and `list_add` in `RestartEndToEndTest`, pass the campaign.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'dev.tableteller.lists.*' --tests 'dev.tableteller.journal.*' --tests 'dev.tableteller.state.*'`
Expected: compilation fails.

- [ ] **Step 3: Implement, as in Task 3**

- [ ] **Step 4: Run the full suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src
git commit -m "Lists, journal and roll_history name their campaign"
```

---

### Task 5: Sessions and prompts name the campaign

**Files:**
- Modify: `session/SessionTools.java`, `session/SessionPrompts.java`, `TestOracle.java`, `StdioEndToEndTest.java`, `RestartEndToEndTest.java`
- Test: `session/{SessionToolsTest,SessionPromptsTest}.java`, `ScriptedSessionTest.java`

**Interfaces:**
- Produces: `SessionTools(SessionRepository sessions, SceneRepository scenes, ListRepository lists, CampaignRepository campaigns, CampaignResolver resolver, OracleStateRepository state, TransactionTemplate transactions)` with `sessionStart(String campaign)` and `sessionEnd(String campaign, String summary, String open_questions)`.
- Produces: the prompt texts below, exactly. `start_session` keeps its one required `campaign` argument; `end_session` and `new_campaign` still take none.

```text
Let's play the campaign "<campaign>".
1. Call campaign_open with campaign "<campaign>", then session_start with campaign "<campaign>".
2. Recap the story for me in 3 sentences, using the recap session_start returns.
3. If a scene is still open, pick it up where it left off. Otherwise propose the next scene's expected setup and call scene_check.
For the rest of this chat, pass campaign "<campaign>" to every tool you call.
```

```text
Let's end the session here.
1. Record anything established this session that isn't in the journal yet, with journal_write.
2. Write a summary of this session in a few sentences, and the questions the story has left open.
3. Call session_end with them. A scene that is still open can stay open; it carries over to the next session.
Pass the campaign this chat has been playing as campaign to each of these calls.
```

```text
Let's start a new campaign. Interview me one question at a time about:
1. The setting: the world and the situation the story starts in.
2. The tone, for example grim, hopeful or pulpy.
3. My player character: who they are and what they want.
Then call campaign_create with a short name, the setting, the tone and pc_summary, and seed the threads list with 2-3 threads from what I told you, using list_add. Finally call session_start and set up the first scene with scene_check.
After campaign_create, pass the new campaign's name as campaign to every tool you call in this chat.
```

- [ ] **Step 1: Write the failing tests**

Add `campaign` to every `sessionStart` / `sessionEnd` call, including the method reference in `sessionToolsRequireAnOpenCampaign`, which becomes `sessionToolsNeedAKnownCampaign` (both tools with `"Ashfal"` → `no campaign 'Ashfal'; campaigns: Ashfall`; no session row). `sessionsBelongToTheirCampaign` names both campaigns instead of switching. The three prompt-text tests expect the texts above.

e2e: `session_start` and `session_end` calls pass the campaign; `getsAPromptOverStdio` still finds `Call campaign_open with campaign "E2E"` and now also `pass campaign "E2E" to every tool`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'dev.tableteller.session.*'`
Expected: compilation fails.

- [ ] **Step 3: Implement**

- [ ] **Step 4: Run the full suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src
git commit -m "Sessions and prompts name the campaign"
```

---

### Task 6: Campaign resources become templates

**Files:**
- Modify: `resource/OracleResources.java`, `campaign/CampaignBriefs.java` (javadoc only), `TestOracle.java`, `StdioEndToEndTest.java`
- Test: `resource/OracleResourcesTest.java`

**Interfaces:**
- Produces: `OracleResources(CampaignResolver resolver, CampaignBriefs briefs, JournalRepository journal, PackRegistry packs)`, with:
  - `@McpResource(uri = "campaign://{campaign}/brief", name = "campaign_brief", description = "A campaign's setting, tone, PC, chaos factor, current scene and both lists. {campaign} is its name or id.") ReadResourceResult brief(String campaign)`
  - `@McpResource(uri = "campaign://{campaign}/journal/{kind}", name = "journal_titles", description = "Titles of a campaign's journal entries of one kind: npc, location, item, fact or note. {campaign} is its name or id.") ReadResourceResult journalTitles(String campaign, String kind)`
  - `tables()` unchanged.
- Spring AI binds template variables to parameters by position and does not decode them. The method percent-decodes the segment with `URLDecoder.decode(segment.replace("+", "%2B"), UTF_8)`, so `+` stays a plus; a segment that does not decode (a lone `%`) is used as it is. The decoded value goes to `resolver.resolve`, so an unknown campaign throws the resolver's message. The returned contents' `uri` is the request's URI, rebuilt from the raw segment: `campaign://Iron%20Coast/brief`.
- The "no campaign open" notice and its `text/plain` branch are removed.

- [ ] **Step 1: Write the failing tests**

`OracleResourcesTest`:
- `briefIsTheCampaignOpenJson`: `brief("Ashfall")`, uri `campaign://Ashfall/brief`, otherwise as before
- `briefIsTheNamedCampaigns`: with `Iron Coast` also created and given a thread, `brief("Ashfall")` has no threads
- `briefDecodesTheCampaignSegment`: campaigns `Iron Coast`, `Пепел`, `A+B` and `100% Doom`. `Iron%20Coast`, `%D0%9F%D0%B5%D0%BF%D0%B5%D0%BB`, raw `пепел`, `A+B`, `100%25%20Doom` and raw `100% Doom` each give a brief with that campaign's name; the uri of the first is `campaign://Iron%20Coast/brief`
- `briefOfAnUnknownCampaignListsTheCampaigns` replaces `briefWithoutAnOpenCampaignIsANotice`: `brief("Ashfal")` throws `no campaign 'Ashfal'; campaigns: Ashfall`
- `journalTitlesAreSortedIgnoringCase`: `journalTitles("Ashfall", "npc")`, uri `campaign://Ashfall/journal/npc`; an npc written in `Iron Coast` is not listed
- `journalTitlesOfAnUnknownCampaignListsTheCampaigns` replaces `journalTitlesWithoutAnOpenCampaignIsANotice`
- `unknownJournalKindListsTheKinds`: `journalTitles("Ashfall", "npcs")`, same message as before

`StdioEndToEndTest`:
- `listsTheResourcesAndTheJournalTemplate`: `resources/list` URIs are exactly `[oracle://tables]`; `resources/templates/list` has `campaign://{campaign}/brief` and `campaign://{campaign}/journal/{kind}`
- `readsTheBriefAndTablesOverStdio` reads `campaign://E2E/brief`
- `readsJournalTitlesThroughTheTemplate` reads `campaign://E2E/journal/npc`

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'dev.tableteller.resource.*'`
Expected: compilation fails (`brief(String)` does not exist).

- [ ] **Step 3: Implement**

- [ ] **Step 4: Run the full suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. If a template variable arrives null or in the wrong parameter over stdio, check `resources/templates/list` and the parameter order before changing anything else.

- [ ] **Step 5: Commit**

```bash
git add src
git commit -m "Campaign resources take the campaign in their URI"
```

---

### Task 7: No active campaign; the two-chat test passes; docs

**Files:**
- Delete: `src/main/java/dev/tableteller/campaign/ActiveCampaign.java`
- Modify: `campaign/CampaignTools.java`, `TestOracle.java`, `TwoChatsEndToEndTest.java`, `StdioEndToEndTest.java`, `README.md`, `docs/SPEC.md`
- Test: `campaign/CampaignToolsTest.java`

**Interfaces:**
- Produces: `CampaignTools(CampaignRepository campaigns, CampaignResolver resolver, CampaignBriefs briefs)`. Neither tool sets anything.
  - `campaign_open` description: "Read a campaign's brief by name or id: setting, tone, PC, chaos factor, the current scene and both lists. Call it at the start of every session. It changes nothing; every other tool names the campaign itself." Its `campaign` parameter uses `CampaignResolver.DESCRIPTION`. Output unchanged.
  - `campaign_create` description: "Create a campaign. Chaos starts at 5 with no scenes. Every other tool takes its name as campaign." Summary: `Campaign 'Ashfall' created. Chaos 5, no scenes yet; pass campaign 'Ashfall' to every other tool.`

- [ ] **Step 1: Write the failing tests**

`CampaignToolsTest`: `createOpensTheNewCampaign` becomes `createReturnsTheIdAndNamesTheCampaign` (the summary above; `campaignId("Ashfall")` equals the returned id); delete `openSwitchesTheActiveCampaign`.

`StdioEndToEndTest.everyToolButCampaignCreateRequiresCampaign`: in `tools/list`, every tool except `campaign_create` has `campaign` in `inputSchema.required`, and `campaign_create` has no `campaign` property.

Remove `@Disabled` from `TwoChatsEndToEndTest`.

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew test --tests 'dev.tableteller.campaign.CampaignToolsTest'`
Expected: FAIL on the new summary.

- [ ] **Step 3: Delete `ActiveCampaign`; rewrite `CampaignTools`' descriptions and summary; drop it from `TestOracle`**

`grep -r ActiveCampaign src` must print nothing.

- [ ] **Step 4: Update `README.md` and tick M3.1**

- "Status" names milestone 3.1: every tool but `campaign_create` takes the campaign's name or id, so several chats can play different campaigns on one server.
- "Use it from Claude Desktop": drop "until a campaign is open, every other tool answers with the list of campaigns to open"; say the DM passes the campaign's name to every call, that two chats can play two campaigns at once, and that the system prompt's rule 8 asks for exactly that.
- "Resources and prompts": the two campaign resources are templates, `campaign://{campaign}/brief` and `campaign://{campaign}/journal/{kind}`, with `{campaign}` the name (percent-encoded, e.g. `Iron%20Coast`) or id.
- After rebuilding the jar, restart Claude Desktop so it starts the new server.

In `docs/SPEC.md`, change `- [ ] M3.1 Campaign per call` to `- [x] M3.1 Campaign per call`.

- [ ] **Step 5: Run the full suite**

Run: `./gradlew cleanTest test`
Expected: BUILD SUCCESSFUL with every test passing, `TwoChatsEndToEndTest` included (the symlink test may be skipped). Do not run `bootJar`.

- [ ] **Step 6: Commit**

```bash
git add -A src README.md docs/SPEC.md
git commit -m "Drop the active campaign; two chats keep their campaigns apart"
```
