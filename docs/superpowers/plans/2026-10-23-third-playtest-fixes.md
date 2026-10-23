# M3.3 Third-Playtest Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix what the third playtest («Ле Понт») turned up. A shifted `fate_check` names the odds it rolled on. `roll` and `meaning` record what they were for, and `roll` can roll a pool of dice. `campaign_update` changes the setting and tone. List and journal entries can be renamed. The system prompt stops at the PC's next moment to act and keeps the characters list current.

**Architecture:** No schema change and no new tools. Every change is a new optional parameter or summary text on an existing tool. Labels and pools live in `OracleTools.roll` and `OracleTools.meaning`. Renames reach `ListRepository.update` and `JournalRepository.update`, which each gain a title. `CampaignRepository.update` gains setting and tone. The prompts change only in text.

**Tech Stack:** M3.2's stack, unchanged: Java 21, Spring Boot 4.1.1, Spring AI 2.0.1 (MCP SDK 2.0.0), Jackson 3, Flyway, PostgreSQL 16, JUnit 5 + AssertJ, Testcontainers. No new dependencies.

**Spec:** `docs/SPEC.md` (v0.9). Before starting, read:
- "Shifts" and "The `roll` tool" and "Labels" (Oracle tools)
- the `campaign_update`, `list_add / list_update` and `journal_write` rows, and "Renaming" (Journal tools)
- the `end_session` prompt
- the session loop and rule 12 (Session protocol and DM prompt)
- the v0.9 decisions

**Branch:** a worktree branch cut from `master`.

## Global Constraints

- Every M1–M3.2 constraint still holds:
  - Run Gradle with `JAVA_HOME=/c/Users/sefim/.jdks/corretto-21.0.10`, and Docker must be running.
  - All randomness goes through `RandomSource.between`.
  - Every oracle tool writes exactly one roll-log row; `roll` with a `count` writes one row too.
  - Bad input throws `IllegalArgumentException` whose message says what is wrong and what is allowed.
  - No `@SpringBootTest`, and no text from published games in the repo.
- Do not run `bootJar` and do not touch `build\libs`: Claude Desktop runs that jar and locks it.
- A tool checks its own input before it resolves `campaign`, so input errors come before campaign errors and a rejected call writes nothing. Checks that need the database, such as a rename onto a taken title, come after.
- Parameter names are the tools' wire names: the Java parameter is literally `new_title`, like `pc_summary`, and the class keeps its "wire names" comment.
- A new optional text parameter (`question`, `new_title`, `setting`, `tone`) that is null, empty or only spaces counts as not given.
- A call that passes none of the new parameters returns and logs exactly what it does today. The one exception is a `fate_check` with a non-zero shift, whose summary Task 1 changes.
- Summaries and messages other than the ones this plan lists do not change.
- `tools/list` stays at 19 tools and `prompts/list` at 5. No migration.
- Roll-log `inputs` are jsonb, which does not keep key order, so tests compare them as maps.

## Review Focus

1. **The wire name `new_title`:** a Java parameter named `newTitle` would put `newTitle` in the schema, so the DM's `new_title` would be dropped. `journal_write` would then quietly rewrite the entry under its old title. Test: Task 4 `renamesOverStdio`.
2. **A mistyped title in a journal rename must not create an entry.** Test: Task 4 `renamingAMissingEntryFails`.
3. **A DM that sends `count: 1` or `"question": ""`** gets exactly today's single roll, with no question stored. Tests: Task 2 `aCountOfOneIsASingleRoll` and `aBlankQuestionIsLeftOut`.
4. **Cyrillic over stdio:** the playtests are in Russian. A `question` like `Кубы Голода` must come back intact in `roll_history`, and Cyrillic titles must match ignoring case when renamed. Tests: Task 2 `rollTakesACountAndAQuestionOverStdio`, and Task 4 `renameKeepsWeightAndStatus`.
5. **A renamed journal entry is found by its new name:** the generated search column follows the title. Test: Task 4 `renameAnEntryThatExists`, which runs `journal_search` for the new name.

## File Map

| Path (under `src/main/java/dev/tableteller/` unless shown) | Responsibility | Task |
| --- | --- | --- |
| `tools/OracleTools.java` | Shifted odds in the summary; `count` and `question` on `roll`; `question` on `meaning` | 1, 2 |
| `campaign/{CampaignRepository,CampaignTools}.java` | `setting` and `tone` in `campaign_update` | 3 |
| `lists/{ListRepository,ListTools}.java` | `new_title` on `list_update` | 4 |
| `journal/{JournalRepository,JournalTools}.java` | `new_title` on `journal_write` | 4 |
| `session/SessionPrompts.java`, `src/main/resources/system-prompt.md` | `end_session` text, rule 12, session steps 3–5, the setting line | 5 |
| `src/test/java/dev/tableteller/StdioEndToEndTest.java` | The new parameters over stdio; the system prompt names | 1, 2, 4, 5 |
| `README.md`, `docs/SPEC.md` | Status; tick M3.3 | 5 |

Test files whose existing calls only gain trailing `null` arguments:
- `OracleToolsTest`, `RollHistoryToolsTest` and `ScriptedSessionTest` (for `roll` and `meaning`)
- `ListToolsTest` and `ListRepositoryTest` (for list updates)
- `JournalToolsTest`, `JournalSearchTest`, `JournalRepositoryTest` and `OracleResourcesTest` (for journal writes and updates)
- `CampaignToolsTest` (for campaign updates)

Grep for `tools.roll(`, `tools.meaning(`, `.listUpdate(`, `.journalWrite(`, `.campaignUpdate(` and `.update(` to find them.

---

### Task 1: Name the shifted odds in the fate_check summary

**Files:**
- Modify: `tools/OracleTools.java`, `StdioEndToEndTest.java`
- Test: `tools/OracleToolsTest.java`

**Interfaces:**
- Produces in `OracleTools.fateCheck`: with a non-zero shift, the summary is `"%s (%d on %s %+d = %s, chaos %d)."` (answer label, roll, given odds, shift, shifted odds, chaos). When `shiftChangedTheAnswer()`, the `" %s turned %s into %s."` sentence follows as today. The random-event sentence comes last. Without a shift the summary is unchanged. `shift_effect`, the reply's other fields and the row are unchanged.

- [x] **Step 1: Write the failing tests**

Change these summaries in `OracleToolsTest`, and rename `aShiftThatChangesNothingLeavesTheReplyAsItIs` to `aShiftThatChangesNothingStillNamesTheOdds`:
- `aShiftThatTurnsTheAnswerMatchesTheSpecExample`: `Yes (62 on likely +1 = very_likely, chaos 4). Athletics +4 turned a no into a yes.`
- `aShiftThatChangesNothingStillNamesTheOdds`: `Yes (30 on likely +1 = very_likely, chaos 5).` with `shiftEffect` still null and the row unchanged
- `exceptionalBandsCount`: `Exceptional yes (15 on likely +1 = very_likely, chaos 5). Stealth +7 turned a yes into an exceptional yes.`
- `theShiftSentenceComesBeforeTheRandomEvent`: `No (44 on likely -2 = unlikely, chaos 6). Bad knee turned a yes into a no. Random event: Distant event (seek / ally).`
- `shiftsStopAtTheEnds`: `Yes (93 on nearly_certain +3 = certain, chaos 5). Lockpicking +11 turned a no into a yes.`, then `Yes (50 on certain -8 = certain, chaos 5).`
- `aReasonWithoutAShiftChangesNothing`: unchanged, `Yes (30 on likely, chaos 5).`

New test `thePlaytestShiftNamesLikely`, the case the third playtest got wrong:
- Call `open(3)`, then `fateCheck("Ashfall", "Does he hand over his phone?", unlikely, 2, "Благоговение +2")`.
- At chaos 5, unlikely has exceptional yes up to 7 and likely up to 13.
- Expect answer `exceptional_yes`, `shiftEffect` null, and summary `Exceptional yes (3 on unlikely +2 = likely, chaos 5).`

`StdioEndToEndTest.fateCheckShiftsOverStdio`: the summary also contains `on likely +2 = nearly_certain`.

- [x] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'dev.tableteller.tools.OracleToolsTest'`
Expected: the six shifted-summary tests fail on the summary text.

- [x] **Step 3: Implement the summary in `OracleTools.fateCheck`**

- [x] **Step 4: Run the full suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [x] **Step 5: Commit**

```bash
git add src
git commit -m "Name the shifted odds in every shifted fate_check summary"
```

---

### Task 2: Labels on roll and meaning, and dice pools on roll

**Files:**
- Modify: `tools/OracleTools.java`, `StdioEndToEndTest.java`, and the callers in the File Map
- Test: `tools/OracleToolsTest.java`

**Interfaces:**
- Produces: `public record RollResult(@JsonProperty("roll_id") String rollId, @JsonInclude(NON_NULL) Integer value, @JsonInclude(NON_NULL) List<Integer> values, String summary)`.
- Produces: `OracleTools.roll(String campaign, Integer min, Integer max, Integer count, String question)`:
  - Tool description: "Roll an evenly distributed whole number from min to max, inclusive; with count, several such numbers, read one by one and never added. Use it whenever the story needs a number, instead of choosing one yourself."
  - `count`: optional, "How many numbers to roll in the same range, 1-10; default 1. They are never added together."
  - `question`: optional, "What the roll is for, e.g. Which city? 1 Rome, 2 Milan. Stored, not interpreted"
  - Checks run in order, both before the campaign is resolved. First, today's range check. Then, for a count outside 1–10: `count must be 1-10; got 11`.
  - With `count` null or 1, the reply is `value` with `values` null and today's summary `Rolled 4 (1 to 6).` The row inputs are `min, max`, and the result is `{value}`.
  - With `count` 2–10, `between(min, max)` is called `count` times. The reply is `values` in the order rolled, with `value` null. The summary is `"Rolled %s (%d rolls of %d to %d)."`, with the values joined by `", "`. The row's `dice` are the values, its inputs are `min, max, count`, and its result is `{values}`.
  - A given `question` is stored as given under `question` in the inputs.
- Produces: `OracleTools.meaning(String campaign, String table, Integer count, String question)`:
  - `question`: optional, "What the words are for, e.g. Who is watching Jacques? Stored, not interpreted"
  - A given `question` is added to the row's inputs, and nothing else changes.

- [x] **Step 1: Write the failing tests**

In `OracleToolsTest`, existing `roll` calls gain `, null, null`, `meaning` calls gain `, null`, and `new RollResult("r_0001", 4, "Rolled 4 (1 to 6).")` becomes `new RollResult("r_0001", 4, null, "Rolled 4 (1 to 6).")`. Then add:
- `rollLogsItsQuestion`
  - Call: `open(2).tools.roll("Ashfall", 1, 4, null, "Which city? 1 Rome, 2 Milan, 3 Venice, 4 Naples")`.
  - Expect the result `new RollResult("r_0001", 2, null, "Rolled 2 (1 to 4).")`.
  - Expect the row's inputs `Map.of("question", "Which city? 1 Rome, 2 Milan, 3 Venice, 4 Naples", "min", 1, "max", 4)`.
- `rollWithACountReturnsEachValue`
  - Call: `open(7, 1, 10).tools.roll("Ashfall", 1, 10, 3, "Hunger dice")`.
  - Expect the result `new RollResult("r_0001", null, List.of(7, 1, 10), "Rolled 7, 1, 10 (3 rolls of 1 to 10).")`.
  - Expect exactly one row, with dice `[7, 1, 10]`, inputs `Map.of("question", "Hunger dice", "min", 1, "max", 10, "count", 3)` and result `Map.of("values", List.of(7, 1, 10))`.
- `aCountOfOneIsASingleRoll`
  - Call: `open(4).tools.roll("Ashfall", 1, 6, 1, null)`.
  - Expect the result `new RollResult("r_0001", 4, null, "Rolled 4 (1 to 6).")` and the row's inputs `Map.of("min", 1, "max", 6)`.
- `rollRejectsACountOutsideOneToTen` (`@ValueSource(ints = {0, -1, 11})`)
  - Expect `count must be 1-10; got <count>` and no rows.
  - With campaign `"Ashfal"` and count 11, expect the same count message.
- `aBlankQuestionIsLeftOut`
  - Calls: `open(4, 2, 3)`, then `roll("Ashfall", 1, 6, null, "  ")` and `meaning("Ashfall", "actions", null, "")`.
  - Expect the rows' inputs `Map.of("min", 1, "max", 6)` and `Map.of("table", "actions", "count", 1)`.
- `meaningLogsItsQuestion`
  - Call: `open(2, 3).tools.meaning("Ashfall", "actions", null, "Who is watching Jacques?")`.
  - Expect the summary `actions: hide / debt` and the row's inputs `Map.of("question", "Who is watching Jacques?", "table", "actions", "count", 1)`.

`StdioEndToEndTest.rollTakesACountAndAQuestionOverStdio`:
- Call `roll` with `campaign` `E2E`, `min` 1, `max` 10, `count` 3 and `question` `Кубы Голода`.
- Expect the body to have no `value`, and `values` to hold 3 numbers, each from 1 to 10.
- Then call `roll_history` with `last_n` 1. Expect `rolls[0].inputs.question` to be `Кубы Голода` and `rolls[0].inputs.count` to be 3.

- [x] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'dev.tableteller.tools.OracleToolsTest'`
Expected: compilation fails (`roll` takes three arguments).

- [x] **Step 3: Implement, and give the other callers their trailing `null`s**

- [x] **Step 4: Run the full suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [x] **Step 5: Commit**

```bash
git add src
git commit -m "Let roll and meaning say what they are for, and roll a pool of dice"
```

---

### Task 3: Setting and tone in campaign_update

**Files:**
- Modify: `campaign/CampaignRepository.java`, `campaign/CampaignTools.java`
- Test: `campaign/CampaignToolsTest.java`

**Interfaces:**
- Produces: `CampaignRepository.update(UUID id, String setting, String tone, String pcSummary, String pcSkills)`, still one statement with `coalesce(?, column)` for each field.
- Produces: `CampaignTools.campaignUpdate(String campaign, String setting, String tone, String pc_summary, String pc_skills)`:
  - Tool description: "Change the campaign's setting or tone, or the PC's summary or skills: for a setting the story has filled in, a PC who has changed, or a character sheet converted again. Replaces each field it is given, keeps the others, and returns the brief. Writes no roll."
  - `setting`: optional, "The world and situation now; omit to keep it"
  - `tone`: optional, "The mood now; omit to keep it"
  - With none of the four given: `campaign_update needs setting, tone, pc_summary or pc_skills`, checked before the campaign is resolved.
  - The class javadoc says "updating them" instead of "updating the PC".

- [x] **Step 1: Write the failing tests**

In `CampaignToolsTest`, existing `campaignUpdate(c, a, b)` calls become `campaignUpdate(c, null, null, a, b)`. Then:
- `updateNeedsAField`
  - Cases: four nulls, and `("", " ", "", "  ")`.
  - Each, and the call with campaign `"Ashfal"`, gives `campaign_update needs setting, tone, pc_summary or pc_skills`.
- `updateReplacesTheSettingAndTone`
  - Setup: `campaignCreate("Ashfall", "Modern Italy; the city is picked in the first scene", "Spy novel", "Jacques", null)`.
  - `campaignUpdate("Ashfall", "Modern Milan", null, null, null)` returns a brief with setting `Modern Milan`, tone `Spy novel` and pc_summary `Jacques`.
  - Then `campaignUpdate("Ashfall", " ", "Spy novel with irony", null, null)` keeps the setting `Modern Milan` and sets the tone.

- [x] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'dev.tableteller.campaign.CampaignToolsTest'`
Expected: compilation fails (`campaignUpdate` takes three arguments).

- [x] **Step 3: Implement**

- [x] **Step 4: Run the full suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [x] **Step 5: Commit**

```bash
git add src
git commit -m "Let campaign_update change the setting and tone"
```

---

### Task 4: Rename list and journal entries with new_title

**Files:**
- Modify: `lists/ListRepository.java`, `lists/ListTools.java`, `journal/JournalRepository.java`, `journal/JournalTools.java`, `StdioEndToEndTest.java`, and the callers in the File Map
- Test: `lists/ListToolsTest.java`, `journal/JournalToolsTest.java`

**Interfaces:**
- Produces: `ListRepository.update(UUID campaignId, ListName list, String title, String newTitle, int weight, ListStatus status)` sets `title` (trimmed), `weight` and `status` on the entry `title` matches ignoring case. `ListRepositoryTest` passes the same title as `newTitle`.
- Produces: `ListTools.listUpdate(String campaign, ListName list, String title, String new_title, Integer weight, ListStatus status)`:
  - Tool description: "Rename a list entry, change its weight, or close a thread / remove a character."
  - `new_title`: optional, "A new title for the entry, e.g. once an NPC's name is known; omit to keep it"
  - With none of the three given: `list_update needs new_title, weight or status`. This replaces `list_update needs weight or status`.
  - After the entry is found, a `new_title` that matches another entry on that list ignoring case (not the entry itself) gives `'<that entry's title>' is already on <list>; pick another new_title`.
  - Without a rename, the summary is unchanged. With one, it is `"Renamed '%s' to '%s' on %s: weight %d, %s."` (old stored title, new title trimmed, list, weight, status).
- Produces: `JournalRepository.update(UUID entryId, String title, String body, List<String> tags, int sceneNumber)` also sets the title, trimmed. `JournalTools` passes the stored title when not renaming, and `JournalRepositoryTest` passes the inserted title.
- Produces: `JournalTools.journalWrite(String campaign, JournalKind kind, String title, String body, List<String> tags, String new_title)`:
  - Tool description: "Record something the story has established: an npc, location, item, fact or note. Writing a kind and title that already exist replaces that entry and returns its previous body; new_title also renames it."
  - `new_title`: optional, "A new title for an entry that exists, e.g. once an NPC's name is known; omit to keep the title"
  - Inside the transaction, when renaming:
    - No kind + `title` entry: `no <kind> '<title trimmed>' to rename; leave out new_title to record a new entry`, and nothing is written.
    - Another entry of the kind has `new_title` ignoring case: `<kind> '<its title>' already exists; write to it, or pick another new_title`.
    - Otherwise, update the title, body and tags (omitted tags are kept). Return `WriteResult(id, true, previous body, "Renamed %s '%s' to '%s'; check previous_body for anything the new text dropped.")` with kind, old stored title and new title trimmed.

- [x] **Step 1: Write the failing tests**

Existing `listUpdate` calls gain `null` as the fourth argument; existing `journalWrite` calls gain a trailing `null`.

`ListToolsTest`:
- `updateNeedsANewTitleWeightOrStatus` (replaces `updateNeedsWeightOrStatus`): `(null, null, null)` and `("  ", null, null)` → `list_update needs new_title, weight or status`
- `renameKeepsWeightAndStatus`
  - Setup: add characters `Священник с приёма` with weight 2.
  - `listUpdate("Ashfall", characters, "священник с приёма", "Отец Себастьен", null, null)` gives entries `[ListEntry("Отец Себастьен", 2, active)]` and summary `Renamed 'Священник с приёма' to 'Отец Себастьен' on characters: weight 2, active.`
  - Then `listUpdate("Ashfall", characters, "отец себастьен", null, 3, null)` finds the entry by its new title.
- `renameTogetherWithAStatusChange`: rename threads `Find the heir` to `Crown the heir` with `ListStatus.closed` → `[ListEntry("Crown the heir", 1, closed)]`
- `renameToATakenTitleFails`
  - Setup: threads `Find the heir` and `Clear the pass`.
  - Renaming `Find the heir` to `" clear the PASS "` gives `'Clear the pass' is already on threads; pick another new_title`.
  - Both entries are unchanged.
- `renameCanFixTheCase`: add characters `innkeeper varo`, then rename it to `Innkeeper Varo` → `[ListEntry("Innkeeper Varo", 1, active)]`

`JournalToolsTest`:
- `renameAnEntryThatExists`
  - Setup: write npc `Священник с приёма`, body `A priest with Jacques's photo.`, tags `["Квадрилатеро"]`.
  - `journalWrite("Ashfall", npc, "священник с приёма", "Отец Себастьен, архивист.", null, "Отец Себастьен")` keeps the same `entryId` and returns `replaced` true, `previousBody` `A priest with Jacques's photo.` and summary `Renamed npc 'Священник с приёма' to 'Отец Себастьен'; check previous_body for anything the new text dropped.`
  - `journalGet` by `отец себастьен` shows title `Отец Себастьен`, the new body and tags `["Квадрилатеро"]`.
  - `journalGet` by the old title gives `no journal entry titled 'Священник с приёма'; find it with journal_search`.
  - `journalSearch("Ashfall", "Себастьен", null)` has 1 entry.
- `renamingAMissingEntryFails`
  - `journalWrite("Ashfall", npc, "Священник", "x", null, "Отец Себастьен")` gives `no npc 'Священник' to rename; leave out new_title to record a new entry`.
  - `journalSearch("Ashfall", "Себастьен", null)` has no entries.
- `renameToATakenTitleFails`
  - Setup: npc `Varo` and npc `Innkeeper Varo`.
  - Renaming `Varo` to `innkeeper varo` gives `npc 'Innkeeper Varo' already exists; write to it, or pick another new_title`.
  - Then, with location `Таверна` written, renaming npc `Varo` to `Таверна` succeeds, because a title another kind uses is free.
- `renameCanFixTheCase`: write npc `innkeeper varo`, then rename it to `Innkeeper Varo` → summary `Renamed npc 'innkeeper varo' to 'Innkeeper Varo'; …` and the stored title `Innkeeper Varo`
- `aBlankNewTitleIsAPlainWrite`: `journalWrite("Ashfall", npc, "Lucia", "A stylist.", null, " ")` → `replaced` false, summary `Recorded npc 'Lucia' (scene 0).`

`StdioEndToEndTest.renamesOverStdio`:
- Call `list_add` characters `Священник с приёма`, then `list_update` with `new_title` `Отец Себастьен`. Expect the body's entries to include the title `Отец Себастьен`.
- Call `journal_write` npc `Священник с приёма`, body `A priest.`, then `journal_write` with the same title, body `Father Sébastien.` and `new_title` `Отец Себастьен`. Expect `journal_get` by `Отец Себастьен` to return body `Father Sébastien.`

- [x] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'dev.tableteller.lists.*' --tests 'dev.tableteller.journal.*'`
Expected: compilation fails (`listUpdate` takes five arguments).

- [x] **Step 3: Implement, and give the other callers their trailing `null`s**

- [x] **Step 4: Run the full suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [x] **Step 5: Commit**

```bash
git add src
git commit -m "Rename list and journal entries with new_title"
```

---

### Task 5: Prompts, the system prompt and docs

**Files:**
- Modify: `session/SessionPrompts.java`, `src/main/resources/system-prompt.md`, `StdioEndToEndTest.java`, `README.md`, `docs/SPEC.md`
- Test: `session/SessionPromptsTest.java`

**Interfaces:**
- Consumes: `count` and `question` (Task 2), `campaign_update`'s `setting` (Task 3), and `new_title` (Task 4).
- Produces: step 1 of the `end_session` text becomes:
  `1. Record anything established this session that isn't in the journal yet, with journal_write, and put NPCs who will matter again on the characters list with list_add.`
- Produces in `system-prompt.md`:
  - A new rule after rule 11:
    `12. Stop narrating at the next moment the player character could act, and let the player choose. Never play out an opening the player set up, such as a distraction, before they have used it.`
  - Session step 3: `a number → roll` becomes `a number → roll, with count for several dice read one by one`. The step ends with the sentence `Pass roll and meaning the question they answer, so the roll log shows what each was for.`
  - Session step 4 ends with the sentence `When a name changes, for example an NPC's real name comes out, rename the list entry and the journal entry with new_title.`
  - Session step 5 becomes:
    `5. The scene ends → check the characters list and add the NPCs who will matter again. Then end_scene with a short summary and an honest pc_in_control: did the player character steer how the scene went?`
  - The closing paragraph ends with the sentence `When the story fills in something the setting left open, such as the city, update the setting with campaign_update.`

- [x] **Step 1: Write the failing tests**

`SessionPromptsTest`:
- `endSessionText`: step 1 is the text above
- `gameMasterIsTheShippedSystemPrompt` also checks `.contains("12. Stop narrating at the next moment")`

`StdioEndToEndTest.systemPromptNamesOnlyWhatTheToolsHave`: the required names also include `new_title` and `list_update`.

- [x] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'dev.tableteller.session.SessionPromptsTest'`
Expected: `endSessionText` and `gameMasterIsTheShippedSystemPrompt` fail.

- [x] **Step 3: Change the `end_session` text and edit `system-prompt.md`**

- [x] **Step 4: Update `README.md` and tick M3.3**

- "Status" names milestone 3.3 (third-playtest fixes) and adds one sentence: `roll` takes a count for dice pools such as Hunger dice, `roll` and `meaning` record what they were for, list and journal entries can be renamed, and `campaign_update` also changes the setting and tone.
- In `docs/SPEC.md`, change `- [ ] M3.3 Third-playtest fixes` to `- [x] M3.3 Third-playtest fixes`.

- [x] **Step 5: Run the full suite**

Run: `./gradlew cleanTest test`
Expected: BUILD SUCCESSFUL with every test passing (the symlink test may be skipped). Do not run `bootJar`.

- [x] **Step 6: Commit**

```bash
git add src README.md docs/SPEC.md
git commit -m "Teach the DM to stop at the PC's next move and keep the characters list"
```
