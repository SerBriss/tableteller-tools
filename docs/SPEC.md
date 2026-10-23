# Tableteller Tools + Campaign Journal — Spec v0.10

Oct 12, 2026 · @MrDoctor · revised Oct 23, 2026

## Overview

One MCP server gives an AI DM a solo-RPG oracle and a persistent campaign journal, so rolls are real and the story survives between sessions. The DM runs in an off-the-shelf MCP client (Claude Desktop or similar); the server owns every random result and every piece of campaign state.

**Goals**

- The AI never decides an uncertain fact itself: it asks the oracle and interprets the result.
- Chaos factor, threads and characters lists persist and drive random events.
- A new session starts from a short, accurate recap instead of a stale chat history.
- Every roll is logged with its inputs, so player and AI see the same result and it can be audited later.

**Non-goals for v1**

- No custom UI or orchestrator: the client's chat is the interface.
- No rules engine for combat, HP or spells; an existing 5e MCP can sit beside this one.
- No vector search over lore; plain text lookup is enough at this scale.
- No multi-user or hosted service: one player, runs locally.
- No HTTP transport and no 2d10 fate check: stdio and the d100 fate chart only.

## Architecture

A single Spring Boot process runs locally and talks to the client over stdio; Postgres runs in Docker beside it.

- **Stack:** Java 21, Spring Boot 4.1, Spring AI 2.0 MCP server starter, Flyway, PostgreSQL 16.
- **Transport:** stdio only for v1, since the client launches the server as a subprocess. Tools do not depend on the transport, and every call names its campaign, so the server keeps no state per connection and Streamable HTTP can be added later without reworking them.
- **stdio hygiene:** stdout carries only JSON-RPC, so the banner is off, nothing logs to the console (logs go to a file) and the application is non-web. The client launches the built jar with an absolute path to a JDK 21 `java`; `bootRun` is for development only.
- **Randomness:** one injectable `RandomSource` with a single method, `between(min, max)`, is the only source of random numbers: SecureRandom in production, scripted or seeded in tests. There is no dice-notation parser; every roll is a whole number in a range.
- **Layers:** a thin tool layer validates input and writes the roll log; Oracle and Journal services hold the logic; repositories hold SQL.
- **Database:** Postgres 16 from the repo's `compose.yaml`, bound to `127.0.0.1:5432`, with database, user and password all `oracle`; `spring.datasource.*` overrides them. The server does not start without the database, and the log says why.
- **Transactions:** an oracle or tracker tool call locks its campaign's row, and its state change and roll-log row commit together, so overlapping calls cannot interleave.

&#91;embedded content: level 2 architecture · client, server, data\]

The client sends questions and facts; every random result and every stored fact comes back from the server, never from the model.

## Domain model

A campaign is the root: it owns one oracle state and one journal, and every tool call names the campaign it acts on.

| Entity | Fields | Notes |
| --- | --- | --- |
| Campaign | id, name, setting, tone, pc\_summary, pc\_skills, created\_at | Named in every tool call; the server keeps no active campaign; `pc_skills` is text the DM writes and the server never reads |
| OracleState | chaos\_factor (1–9, default 5), scene\_number | Changes only through `end_scene` or `set_chaos` |
| Scene | number, expected\_setup, actual\_setup, check\_result, summary, status | `check_result` = expected / altered / interrupted; status = open / closed |
| Thread | id, title, weight (1–3), status (open / closed) | The oracle's threads list; weight = duplicate entries |
| Character | id, name, weight (1–3), status (active / removed) | The oracle's characters list; NPCs and factions |
| JournalEntry | id, kind, title, body, tags, scene\_number, created\_at, updated\_at | kind = npc, location, item, fact, note; titles are unique per kind ignoring case; `scene_number` = scene of the latest write |
| SessionLog | id, started\_at, ended\_at, summary, open\_questions, status | status = open / ended / abandoned; at most one open per campaign; written at session end, read at session start |
| Tracker | id, name, value, min, max, note, created\_at, updated\_at | A number the story keeps, such as Hunger or ammunition; names are unique per campaign ignoring case; changes only through the tracker tools |
| RollLog | id, tool, inputs, dice, result, scene\_number, created\_at | Append-only; every oracle and tracker tool call lands here, with empty dice when nothing was rolled |

The split between lists and journal is deliberate. Threads and characters are what the oracle picks from; journal entries are what the DM must not contradict. A character can have both: a list entry for random events and a journal entry for its description.

**Naming the campaign.** The server keeps no active campaign. Every tool except `campaign_create` takes a required `campaign`: the campaign's name or id, trimmed, with the name matched ignoring case. Each call stands alone, so any number of chats can share one server process and each play its own campaign. This matters because Claude Desktop runs one server for all its chats, and over stdio the server cannot tell them apart. A blank or unknown `campaign` is an error that lists the existing campaigns, for example `no campaign 'Ashfal'; campaigns: Ashfall, Iron Coast`. A call that leaves `campaign` out fails the tool's input schema before it reaches the server's code, and the client sees that schema error.

## Oracle tools

Seven tools cover the oracle loop; each returns a structured result plus a one-line `summary` the DM can paste into narration. Every call writes a RollLog row in the campaign it names and returns its `roll_id`, which is the row's id written as `r_` plus the number padded to four digits (row 142 is `r_0142`); the side effects column lists state changes beyond that row. `roll` and `meaning` take `campaign` too, because their rows belong to a campaign.

| Tool | Input | Output | Side effects |
| --- | --- | --- | --- |
| `fate_check` | campaign, question, odds (nine fixed levels, see below), shift? (−8 to +8, default 0), reason? (required when shift is not 0) | answer (yes / no / exceptional yes / exceptional no), dice, chaos\_factor, random\_event\_triggered, random\_event?, shift\_effect? (see "Shifts") | May trigger a random event |
| `random_event` | campaign, context?, table? (meaning table, default `actions`) | focus, target? (see below), meaning pair | Reads the lists |
| `meaning` | campaign, table (actions, descriptions, or any loaded element table), count? (1–2), question? | word pair(s) | None |
| `scene_check` | campaign, expected\_setup | result (expected / altered / interrupted), random\_event? if interrupted | Opens a new Scene; fails if one is already open |
| `end_scene` | campaign, summary, pc\_in\_control (bool), actual\_setup? | new chaos\_factor, scene\_number | Closes the Scene; chaos −1 if in control, +1 if not, clamped 1–9 |
| `set_chaos` | campaign, value (1–9) | new chaos\_factor | Sets chaos directly, to correct a misjudged scene |
| `roll` | campaign, min, max (whole numbers, min < max, both within ±1,000,000), count? (1–10, default 1), question? | value, or values when count is more than 1 | None |

**Odds levels.** The nine levels are a fixed enum in code and appear in the tool schema: `impossible`, `nearly_impossible`, `very_unlikely`, `unlikely`, `fifty_fifty`, `likely`, `very_likely`, `nearly_certain`, `certain`. Every chart pack must supply all nine.

**Fate method.** The d100 fate chart is the only method in v1. The tool has no `method` parameter yet; the name is reserved for when the 2d10 fate check is added.

**Shifts.** A shift moves the odds before the roll, so the PC's skills count without the server knowing any game system. The DM picks the odds for an ordinary capable person, passes the shift that the campaign's `pc_skills` gives for the action (see "PC skills"), and names where it came from in `reason`, for example `"Athletics +4"`. The server moves the odds that many levels, stopping at `impossible` and `certain` (`nearly_certain` +2 is `certain`); odds of `impossible` or `certain` never move. The one d100 roll is read on the chart at both the given and the shifted odds, at the current chaos factor:

- Whenever the shift is not 0, the summary names the odds the roll was read at, after the given odds and the shift: `Yes (30 on likely +1 = very_likely, chaos 5).` The DM states the odds with every result (rule 9), so it should not have to work them out.
- When both readings give the same answer, the result is otherwise exactly what it would be without a shift: no `shift_effect` and no extra sentence. The shift changed nothing in the story.
- When they differ, the answer is the shifted one, the result adds `shift_effect`, for example `{"odds": "very_likely", "without_shift": "no"}`, and the summary adds one sentence after the roll, before any random event: `Athletics +4 turned a no into a yes.` `without_shift` takes the same values as `answer`, and a change between yes and exceptional yes, or no and exceptional no, counts too.

The RollLog row always holds the given odds, the shift, the reason and the shifted odds, so `roll_history` shows every shift, whether it mattered or not.

**The `roll` tool.** `roll` returns one evenly distributed whole number from `min` to `max`, inclusive, for questions like "how many guards, 1 to 6?". With `count` from 2 to 10 it returns `values`, that many independent numbers in the same range in the order rolled, for a pool read die by die, such as V5 Hunger dice, where each 1 and 10 matters. One call writes one RollLog row. With `count` omitted or 1, the reply and the row are exactly what a single roll gives. It never adds dice together, so a 2d6-shaped result (7 more often than 2 or 12) is out of scope; combat damage belongs to a rules MCP beside this one.

**Labels.** `roll` and `meaning` take an optional `question`: what the number or the words are for, for example `Which city? 1 Rome, 2 Milan, 3 Venice, 4 Naples` or `Rouse check for Awe`. It goes into the RollLog row's inputs, so `roll_history` shows what each roll was for; a blank `question` is left out.

**Rules the server enforces, not the model**

- A random event fires on a fate check when the chart's trigger rule is met against the current chaos factor (usually doubles with a digit at or below chaos). The pack names the rule, for example `doubles_at_or_below_chaos` (11, 22 … 99 where the digit is at or below chaos; 100 never triggers); the server implements a small set of named rules, not an expression language.
- A scene check rolls d10 against chaos: at or below with an odd roll = altered, even = interrupted.
- `scene_check` fails while a scene is open and names it; `end_scene` fails when none is open. Scene 1 goes through `scene_check` like any other scene.
- On an expected result the server copies `expected_setup` into `actual_setup`. For an altered or interrupted scene the DM must pass `actual_setup` to `end_scene`; for an expected scene it may, to refine the setup.
- A focus entry may name a target list in the pack (`target: character` or `target: thread`). The server then picks an active character or open thread, weighted by `weight` (weight 2 counts twice). The result's `target` is `{"list": "characters", "title": "Innkeeper Varo"}`; when that list is empty it is `{"list": "characters", "new": true}`, which tells the DM to introduce a new one and `list_add` it.
- A random event's meaning pair comes from the `actions` table, unless `random_event` names another meaning table.
- A `question`, on `fate_check`, `roll` or `meaning`, is stored, never interpreted: the server rolls, the DM interprets.
- `shift` is a whole number from −8 to +8, and `reason` must not be blank when `shift` is not 0. The server applies the shift and quotes `reason` in the summary, but never reads it.

**Example call and result**

```json
{"tool":"fate_check","campaign":"Ashfall","question":"Is the innkeeper hiding something?","odds":"likely"}
```

```json
{"roll_id":"r_0142","answer":"yes","dice":[44,31,1,88,12],"chaos_factor":6,"random_event_triggered":true,"random_event":{"focus":"A character acts","target":{"list":"characters","title":"Innkeeper Varo"},"meaning":["betray","secret"]},"summary":"Yes (44 on likely, chaos 6). Random event: A character acts → Innkeeper Varo (betray / secret)."}
```

A shift that changed the answer: at chaos 4, 62 is a no on `likely` (yes up to 60) and a yes on `very_likely` (yes up to 75).

```json
{"tool":"fate_check","campaign":"Ashfall","question":"Do I break free before the guards turn?","odds":"likely","shift":1,"reason":"Athletics +4"}
```

```json
{"roll_id":"r_0157","answer":"yes","dice":[62],"chaos_factor":4,"random_event_triggered":false,"shift_effect":{"odds":"very_likely","without_shift":"no"},"summary":"Yes (62 on likely +1 = very_likely, chaos 4). Athletics +4 turned a no into a yes."}
```

## Journal tools

The journal tools keep canon outside the chat window; the DM writes facts as they are established and reads them before narrating anything it isn't sure of.

| Tool | Input | Output | Notes |
| --- | --- | --- | --- |
| `campaign_create` | name, setting, tone, pc\_summary, pc\_skills? | campaign\_id | Sets chaos 5, scene 0; names are unique ignoring case; the only tool without `campaign` |
| `campaign_open` | campaign | campaign brief (see resource below) | Reads the brief and changes nothing; the DM calls it at session start to check the name and load the state |
| `campaign_update` | campaign, setting?, tone?, pc\_summary?, pc\_skills? (at least one) | campaign brief | Replaces the fields it is given: a setting the story has filled in, a PC who has changed, a character sheet converted again; the name never changes |
| `list_add` / `list_update` | campaign, list (threads / characters), title, weight?, status?; `list_update` also new\_title? | updated list | Weight 1–3 maps to duplicate entries; titles are unique per list ignoring case; adding a title already on the list is an error that points to `list_update`; `new_title` renames the entry |
| `list_get` | campaign, list | entries with weight and status | Open threads and active characters first, then the rest, each in the order added |
| `journal_write` | campaign, kind, title, body, tags?, new\_title? | entry\_id, replaced, previous\_body? | Upsert by kind + title ignoring case, so "Innkeeper Varo" is one entry; overwrites in place; `new_title` renames an entry that exists |
| `journal_search` | campaign, query, kind? | matching entries (title + first 300 chars), best first, at most 20 | Postgres full-text search; every query word must match the start of a word |
| `journal_get` | campaign, entry\_id or title (exactly one) | full entry | A title used by entries of several kinds is an error that lists their ids |
| `session_start` | campaign | recap: last session summary, open questions, chaos, lists, last 3 scenes, PC skills, trackers | Call first, every session, after `campaign_open`; marks a session left open abandoned |
| `session_end` | campaign, summary, open\_questions | session\_id | Call last; the DM writes the summary; allowed with a scene still open; fails when no session is open |
| `roll_history` | campaign, last\_n? (1–100, default 20) or scene? | RollLog rows, oldest first | For audits and "what did we roll?"; passing both is an error |

`campaign` is always the campaign's name or id, matched as "Naming the campaign" describes. Journal tools write no RollLog rows; only the oracle and tracker tools do.

Writes from the DM are trusted but traceable: every journal row stores the scene of its latest write. `journal_write` never rejects an existing title. When the kind + title already exists it overwrites the entry in place and returns `replaced: true` with the previous body, so the DM can reconcile in the same turn. Earlier versions are not kept. Titles are trimmed and match ignoring case; the stored spelling is the first one written, until a rename. Omitted `tags` keep the entry's tags, and given `tags` replace them.

**Renaming.** A name often changes once it is known: "the priest at the party" turns out to be Father Sébastien. `list_update` and `journal_write` take an optional `new_title` for this; a blank `new_title` counts as not given.

- `list_update` renames the entry `title` names, together with any weight or status change in the same call; it needs at least one of `new_title`, `weight` or `status`.
- `journal_write` with `new_title` renames the kind + `title` entry and writes `body` (and `tags`, when given) to it, returning `replaced: true` and the previous body. The entry must exist: renaming one that doesn't is an error that says to leave `new_title` out to record a new entry.
- The new title must not be another entry's title on the same list, or of the same kind, ignoring case; that is an error. A new title that differs from the old one only in case changes the stored spelling.
- RollLog rows written before a rename keep the old title, as they keep every input.

`journal_search` reads the query as words, and an entry matches when every word is the start of a word in its title or body, so `Вар` finds `Варо` and `Варом` and `inn varo` finds "Innkeeper Varo". The excerpt is the first 300 characters of the body, ending in `…` when the body is longer.

A session that was never ended does not block the next one: `session_start` marks it abandoned and builds the recap from the last three scene summaries instead of a session summary. The recap's last session is always the one just before the new one: ended, with its summary and open questions, or abandoned, without them; the first session has none. A scene can span sessions, so the recap also shows a scene left open.

## Tracker tools

A tracker is a number the story keeps from scene to scene and session to session: V5 Hunger, Willpower, ammunition, potions, a countdown. The server knows nothing about what a tracker means. The DM decides what to track, asks the player first, and writes the rule for how the number changes in the tracker's note.

| Tool | Input | Output | Notes |
| --- | --- | --- | --- |
| `tracker_add` | campaign, name, value, min?, max?, note? | tracker | Starts tracking; a name already tracked, ignoring case, is an error that points to `tracker_adjust` and `tracker_remove` |
| `tracker_adjust` | campaign, name, delta, reason | tracker, previous\_value, clamped | Adds `delta`, a whole number other than 0, and stops at `min` or `max` |
| `tracker_remove` | campaign, name | the tracker as it was | Stops tracking it |

- Values, limits and deltas are whole numbers within ±1,000,000, as in `roll`. `min` and `max` are optional; when both are given, `min` is less than `max`. `tracker_add` rejects a value outside the limits, for example `value 7 is outside 0 to 5`.
- `tracker_adjust` never leaves the limits. A change that would pass `min` or `max` stops there, `clamped` is true, and the summary adds a sentence: `Hunger: 5 → 5 (+1, Rouse check 3). Stopped at the max, 5.` In V5 that is the moment the Beast breaks loose, so the DM must hear it. On a side with no limit, a result past ±1,000,000 is an error.
- `reason` is required, and stored, never interpreted, like `fate_check`'s: what changed the number, for example `Rouse check 4` or `fed on Lucia`.
- `note` is optional text the DM writes and the server shows but never reads, like `pc_skills`: usually the house rule, for example `Rouse check: d10, 6+ no change, 1–5 +1; a failed check at 5 means a frenzy check`.
- Names are trimmed and match ignoring case; the stored spelling is the first one written. An unknown name is an error that lists the tracked names, `no tracker 'Hungr'; tracked: Hunger, Willpower`, or says `nothing is tracked yet; start with tracker_add`.
- No tool sets a value or edits a tracker. A correction is a `tracker_adjust` with its reason; new limits or a new note mean `tracker_remove` and `tracker_add` again.
- A tracker belongs to the campaign, not to a character. A number that belongs to an NPC says so in its name, for example `Sébastien: trust`.
- Each call locks the campaign row and writes one RollLog row with empty dice, as `set_chaos` does: the inputs as given, and a result with the tracker's name and value after the call, plus `previous_value` and `clamped` for an adjust. `roll_history` then answers "why is Hunger 3?", and the Rouse check's `roll` sits right before the change it caused.
- Summaries: `Tracking Hunger: 1 (0 to 5).` for an add, `Hunger: 1 → 2 (+1, Rouse check 4).` for an adjust, and `Stopped tracking Hunger at 2.` for a remove. The limits read `(0 to 5)`, `(min 0)` or `(max 5)`, and are left out when there are none.
- The brief, from `campaign_open` and the brief resource, and the `session_start` recap list the trackers in the order added, each with `name`, `value` and, when set, `min`, `max` and `note`. That is what carries Hunger into the next session.

```json
{"tool":"tracker_adjust","campaign":"Ле Понт","name":"Голод","delta":1,"reason":"Rouse check 4"}
```

```json
{"roll_id":"r_0092","tracker":{"name":"Голод","value":2,"min":0,"max":5,"note":"Rouse check: d10, 6+ no change, 1–5 +1"},"previous_value":1,"clamped":false,"summary":"Голод: 1 → 2 (+1, Rouse check 4)."}
```

The player decides what gets tracked, but the server cannot know whether the DM asked. So asking first and announcing every change are a system prompt rule (rule 13).

## Resources and prompts

Resources give the client read-only context without a tool call; prompts give the player one-click session starters.

**Resources**

- `campaign://{campaign}/brief` — setting, tone, PC summary, PC skills, chaos factor, current scene, both lists, trackers: the JSON `campaign_open` returns. Small enough to attach every session.
- `campaign://{campaign}/journal/{kind}` — all entries of one kind, titles only, sorted ignoring case.
- `oracle://tables` — type, name and pack of every loaded table, so the DM knows what it may ask `meaning` for and the player can see whether their own pack replaced a demo table.

The two campaign resources are URI templates. `{campaign}` is the campaign's name or id, matched like the tools' `campaign` and percent-encoded where needed, so `campaign://Iron%20Coast/brief` and `campaign://Пепел/brief` both work; a name containing `/` can only be reached by its id. An unknown campaign or kind is an error that lists the valid ones. Templates appear in `resources/templates/list`, not `resources/list`, so a client that attaches only listed resources will not offer the brief; `campaign_open` returns the same JSON.

**Prompts**

A prompt is an instruction to the DM: getting it changes nothing on the server, and the DM makes the calls it names.

- `start_session` — takes a `campaign` argument; tells the DM to call `campaign_open` and `session_start` with it, pass it as `campaign` to every tool for the rest of the chat, recap in 3 sentences, then continue an open scene or set the next one with `scene_check`.
- `end_session` — takes no argument; tells the DM to journal anything unrecorded and put NPCs who will matter again on the characters list, write the summary and open questions, then call `session_end`, passing the campaign this chat has been playing to each call.
- `new_campaign` — tells the DM to interview the player (setting, tone, PC, and the PC's skills: four quick ones or a character sheet, see "PC skills"), then call `campaign_create` and pass the new campaign's name as `campaign` to every later call: convert a character sheet as `character_sheet` says, seed 2–3 threads with `list_add`, call `session_start` and set the first scene.
- `game_master` — takes no argument; its text is the system prompt (see "Session protocol and DM prompt"), for a chat whose client has no project instructions or system prompt to put it in.
- `character_sheet` — takes a `campaign` argument; tells the DM to ask for the sheet if the chat doesn't have it yet, convert it into a reference as "PC skills" describes, show the reference to the player as a table, change what the player objects to, and save it with `campaign_update` as `pc_skills`. When a reference already exists, the DM converts only what changed on the sheet.

## Session protocol and DM prompt

At level 2 the protocol lives in the system prompt, so the prompt is part of the spec and gets versioned with the server. It ships in the repo as `src/main/resources/system-prompt.md`, which is also bundled in the jar and served as the `game_master` prompt.

**Session loop**

1. `campaign_open` → `session_start` → recap to the player. Every call in the loop names the same campaign.
2. For each scene:
   1. Propose the expected setup → `scene_check` → narrate the setup as altered or interrupted if needed.
   2. Play: any uncertain fact → `fate_check`, with the shift from `pc_skills` when it is the PC's own action; any "what does it mean?" → `meaning`.
   3. New NPC, place or established fact → `journal_write`; new plot line or recurring NPC → `list_add`; a name that changes → `new_title`.
   4. A tracked number changes → `tracker_adjust`, announced with its value before and after; something new worth tracking → ask the player, then `tracker_add`.
   5. Scene ends → check the characters list for NPCs who will matter again, then `end_scene` with an honest `pc_in_control`.
3. `session_end` with summary and open questions.

**PC skills**

`pc_skills` is how the PC's abilities reach the odds. The DM writes it, the server stores it and shows it in the brief and the recap, and only the DM reads it. It is filled one of two ways:

- **Four quick skills**, for diving straight into the story and giving the PC some individuality. The player names four skills, anything they like, one at each grade: exceptional (+2), good (+1), bad (−1) and awful (−2). An action none of them fits takes no shift.
- **A character sheet** from any game system, converted once by the DM with the `character_sheet` prompt. The reference first names the system and the floor, average and ceiling of each kind of value on the sheet. Then everything on the sheet the PC can act with gets its own entry and shift: abilities, skills, saves, proficiencies, features and gear. Average is 0. Each entry is judged on its own, so a clearly outstanding value can go past ±2. The player reviews the reference before the DM saves it with `campaign_update`. When the sheet changes, the DM converts only what changed, and the player confirms again.

Either way the reference is fixed, so the same action shifts the same way every session. A check uses one entry, the most specific that fits (Stealth over Dexterity). Combining several factors into one shift is a rare exception, and `reason` then names each. A power or a spent resource that adds to whatever the PC does, such as V5's Awe or Blood Surge, is the usual case: the DM works out the combined shift and says it before rolling (rule 10), and the player corrects it there. Only the PC's own actions take a shift; facts about the world never do. Chaos is not affected: every check, the PC's own included, reads the chart at the current chaos factor.

**System prompt**

`src/main/resources/system-prompt.md` holds the prompt and nothing else, so it can be pasted as it is into a client's system prompt or a Claude Desktop project's instructions. It has thirteen rules, the session loop above spelled out tool by tool, and a line each on `set_chaos`, `roll_history` and `campaign_update`. The rules:

1. Never decide an uncertain fact; ask `fate_check` with honest odds and narrate the result faithfully.
2. Start every scene with `scene_check` and end it with `end_scene`.
3. `journal_search` before naming or describing anything that may already exist.
4. `journal_write` whatever becomes true, in the same turn.
5. Say which meaning words came up and how you read them.
6. Never reroll; a result that makes no sense is a twist.
7. Begin with `campaign_open` and `session_start`; end with `session_end`.
8. One campaign per chat, named in every call.
9. State the odds and the roll with every result, for example "likely, 93: exceptional no".
10. Before rolling on the PC's own action, say why it is uncertain and which odds and shift you chose, so the player can object.
11. For the PC's own action, pick the odds for an ordinary capable person, then pass the one `pc_skills` entry that fits as `shift` and `reason`; never shift a fact about the world.
12. Stop narrating at the next moment the PC could act, and let the player choose; never play out an opening the player set up before they have used it.
13. Ask the player before tracking something new with `tracker_add`, and announce every tracked change with the value before, what changed it, and the value after.

Rule 5 keeps the oracle visible to the player, which is part of the fun and makes skipped rolls obvious. Rule 6 closes the most common way a model dodges an inconvenient result. Rule 8 is what keeps two chats on one server apart: the server trusts the `campaign` each call names. Rules 9 and 10 come from the second playtest (Oct 21, 2026, "Ожерелье королевы"). The DM showed every answer but named the odds only now and then, so when a check rated "likely" rolled 93 (the first exceptional-no number at chaos 4), the player read bad luck as the DM calling a trivial action a failure. Saying why a PC action is uncertain before rolling lets the player object ("this is exactly my craft") before the dice decide. Rule 11 keeps a skill from counting twice: once in odds that already assume an expert, and again in the shift. Rule 12 comes from the third playtest (Oct 23, 2026, «Ле Понт»). The player had a stylist flirt with a priest so the PC could get at the priest's phone. The DM rolled what she learned, rolled whether he noticed, and had him leave the room in the same reply, so the player had to ask for the moment back. Rule 13 comes from the same playtest: the DM brought in V5 Hunger on its own, and the player wants each new tracker agreed first and each change shown in order, for example "Hunger 1. Rouse check, d10: 4. Hunger 1 → 2."

## Table data and licensing

Tables are data files the server loads at startup, so the engine contains no text from published games and the repo can stay public. Packs load from `~/.oracle/packs/` by default; the `oracle.packs.dir` property overrides it.

**Table pack format (YAML or JSON)**

```yaml
pack: my-tables            # never committed; lives in ~/.oracle/packs/
type: meaning              # meaning | focus | fate_chart
name: actions
die: d100
entries:
  - { range: [1, 1], value: "abandon" }
  - { range: [2, 2], value: "accompany" }
```

```yaml
pack: demo
type: fate_chart
name: default
die: d100
random_event: { rule: doubles_at_or_below_chaos }
cells:                     # all nine odds levels × chaos 1–9; numbers here are illustrative
  unlikely:
    5: { exceptional_yes: 8, yes: 40, exceptional_no: 89 }
```

- A `fate_chart` cell reads: a roll at or below `exceptional_yes` is an exceptional yes, at or below `yes` is a yes, at or above `exceptional_no` is an exceptional no, and anything else is a no. `exceptional_yes: 0` and `exceptional_no: 101` mean that result cannot occur in that cell.
- The `fate_check` type (2d10 modifiers) is reserved; the loader rejects it until that method is built.
- A `meaning` table has one or two columns. With `entries` alone, both words of a pair are rolled on it; a two-column table adds `entries_2`, and the second word is rolled there. `count` on the `meaning` tool is the number of pairs.
- The server validates packs on load: full die coverage, no overlapping ranges, known `type`. A chart must also have all 81 cells, a known trigger rule and ordered thresholds: 0 ≤ `exceptional_yes` ≤ `yes` ≤ 100 and `yes` < `exceptional_no` ≤ 101.
- A `focus` table entry may carry `target: character` or `target: thread`, for example `- { range: [25, 42], value: "A character acts", target: character }`. Only focus entries take `target`.
- `die` is written `dN` and means the whole numbers 1 to N. It is the only dice syntax the server reads.
- A file may hold several tables as separate YAML documents divided by `---`; each document is read as its own table.

**How packs combine**

- The demo pack ships inside the jar and always loads first; files in the packs directory load after it, following symlinks. The default directory may be missing (a fresh install); a directory set with `oracle.packs.dir` must exist, or startup stops.
- A table is identified by its type and name. A personal table with the same type and name as a demo table replaces it; two personal tables with the same type and name stop startup, and the log names both files.
- `fate_check` always uses the `fate_chart` named `default`, and random events use the `focus` table named `default`; the demo pack provides both, so both always exist. `meaning` looks up its `table` argument among the `meaning` tables.
- Any pack that fails validation stops startup; the log names the file and the reason. Playing on the demo chart while believing it is your own is worse than a server that will not start.

**Demo fate chart formula**

Each odds level has a yes chance at chaos 5, and every chaos point above or below 5 moves it by 5:

| Odds | impossible | nearly\_impossible | very\_unlikely | unlikely | fifty\_fifty | likely | very\_likely | nearly\_certain | certain |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Yes at chaos 5 | 5 | 10 | 20 | 35 | 50 | 65 | 80 | 90 | 95 |

- `yes` = base + 5 × (chaos − 5), clamped to 1–99, so no answer is ever fully certain or fully ruled out.
- `exceptional_yes` = ⌊`yes` / 5⌋, the bottom fifth of the yes band.
- `exceptional_no` = 101 − ⌊(100 − `yes`) / 5⌋, the top fifth of the no band.

For example, `likely` at chaos 5 gives `yes` 65, `exceptional_yes` 13 and `exceptional_no` 94: 1–13 is an exceptional yes, 14–65 a yes, 66–93 a no and 94–100 an exceptional no. The demo pack stores all 81 cells as data, and a test checks them against this formula.

**What ships with the repo**

- A small original demo pack written for this project, so the server runs out of the box: a d100 fate chart built from the formula above, small actions and descriptions tables, and a focus table with its own categories, wording and ranges.
- Optionally, open-licensed oracles (for example the Ironsworn/Starforged tables via Datasworn, CC BY 4.0) with attribution.
- Tables from a published game are typed in by the user from their own copy and kept out of git; this is fine for personal use, but publishing them needs the publisher's permission.

## Persistence schema

Postgres with Flyway migrations; one schema, seven tables, every row keyed by `campaign_id`.

```sql
create table campaign (
  id uuid primary key, name text not null, setting text, tone text,
  pc_summary text, pc_skills text, chaos_factor smallint not null default 5 check (chaos_factor between 1 and 9),
  scene_number int not null default 0, created_at timestamptz not null default now()
);
create unique index campaign_name_key on campaign (lower(name));

create table scene (
  campaign_id uuid not null references campaign, number int not null, expected_setup text not null,
  actual_setup text, check_result text not null check (check_result in ('expected','altered','interrupted')),
  summary text, status text not null check (status in ('open','closed')),
  primary key (campaign_id, number)
);

create table list_entry (
  id uuid primary key, campaign_id uuid not null references campaign,
  list text not null check (list in ('thread','character')), title text not null,
  weight smallint not null default 1 check (weight between 1 and 3),
  status text not null, created_at timestamptz not null default now(),
  check ((list = 'thread' and status in ('open','closed'))
      or (list = 'character' and status in ('active','removed')))
);
create unique index list_entry_title_key on list_entry (campaign_id, list, lower(title));

create table journal_entry (
  id uuid primary key, campaign_id uuid not null references campaign,
  kind text not null check (kind in ('npc','location','item','fact','note')),
  title text not null, body text not null, tags text[] not null default '{}', scene_number int not null,
  search tsvector generated always as (to_tsvector('simple', title || ' ' || body)) stored,
  created_at timestamptz not null default now(), updated_at timestamptz not null default now()
);
create unique index journal_entry_title_key on journal_entry (campaign_id, kind, lower(title));
create index journal_entry_search_idx on journal_entry using gin (search);

create table session_log (
  id uuid primary key, campaign_id uuid not null references campaign,
  started_at timestamptz not null default now(), ended_at timestamptz, summary text, open_questions text,
  status text not null default 'open' check (status in ('open','ended','abandoned'))
);
create unique index session_log_open_key on session_log (campaign_id) where status = 'open';

create table tracker (
  id uuid primary key, campaign_id uuid not null references campaign, name text not null,
  value int not null, min_value int, max_value int, note text,
  created_at timestamptz not null default now(), updated_at timestamptz not null default now(),
  check (min_value is null or max_value is null or min_value < max_value),
  check (min_value is null or value >= min_value), check (max_value is null or value <= max_value)
);
create unique index tracker_name_key on tracker (campaign_id, lower(name));

create table roll_log (
  id bigserial primary key, campaign_id uuid not null references campaign, tool text not null,
  inputs jsonb not null, dice jsonb not null, result jsonb not null,
  scene_number int, created_at timestamptz default now()
);
```

`'simple'` text search keeps names searchable in any language, including Russian, without stemming surprises. `roll_log` is append-only; nothing updates or deletes it.

M1 has no database: the same repository interfaces are backed by memory, with one implicit campaign (chaos 5, scene 0) and an append-only roll log, so `roll_id` exists from the first milestone. M2's migration creates `campaign`, `scene`, `list_entry` and `roll_log` and removes the implicit campaign and the in-memory repositories; M3's migration adds `journal_entry` and `session_log`; M3.2's adds `pc_skills` to `campaign`; M3.4's adds `tracker`. A removed tracker's row is deleted; its history stays in `roll_log`.

## Testing, milestones and decisions

Random numbers are the only nondeterminism, so tests inject a scripted or seeded `RandomSource` and assert exact results. A scripted source returns given numbers in order, so a test can roll exactly 11 and then 22.

**Testing**

- Unit: every table pack resolves every die value; the demo chart matches the formula at all 9 × 9 odds/chaos cells; fate checks read the right cell and band; chaos stays within 1–9. A personal pack is covered by the load validator, since its chart is not in the repo.
- Shifts: a shift moves the odds the right number of levels and stops at the ends; `impossible` and `certain` never move; a shift outside −8 to +8, or a non-zero shift without a reason, is an error; the summary names the shifted odds whenever the shift is not 0; `shift_effect` and the summary sentence appear exactly when the two readings differ; the RollLog row holds the given odds, shift, reason and shifted odds either way.
- Labels and pools: `roll` and `meaning` store a given `question` in the row's inputs and leave a blank one out; `roll` with `count` 2–10 returns that many values in range in one row; `count` 1 or omitted gives today's reply and row; `count` outside 1–10 is an error.
- Renames: `list_update` and `journal_write` rename with `new_title`; a new title that another entry has is an error, and so is `journal_write`'s `new_title` for an entry that doesn't exist; a rename that changes only case changes the stored spelling; `campaign_update` replaces `setting` and `tone` like the PC fields.
- Trackers: `tracker_add` rejects a name already tracked ignoring case, a value outside its limits and `min` not below `max`; `tracker_adjust` stops at `min` and `max` and says so, and rejects a delta of 0, a blank reason and a result past ±1,000,000; an unknown name lists the tracked ones; every tracker call writes one RollLog row with empty dice; the brief and the recap list trackers in the order added; trackers stay with the campaign that names them.
- Statistical: 100,000 seeded fate checks per odds level land within 1 point of the expected yes rate.
- Lifecycle: a blank or unknown campaign, scene already open, no open scene and an abandoned session each have a test for the documented behaviour.
- Integration: Testcontainers Postgres; a scripted session (open → start → 3 scenes → end) produces the expected rows.
- Two chats: over stdio, two chats on one server alternate calls on two campaigns, the second created while the first has a scene open, and each campaign's rolls, scenes, lists, journal and sessions stay its own.
- System prompt: the `game_master` prompt is the shipped file, and every snake\_case name in it is a tool, a parameter or an enum value in `tools/list`, so renaming one cannot leave the prompt stale.
- Playtest: two sessions with the system prompt alone vs two with the server; count rolls skipped, contradictions and surprises.

**Milestones**

- [x] M1 Oracle core: `roll`, `fate_check`, `meaning`, `set_chaos`, pack loader and validation, demo pack, stdio transport, in-memory state and roll log. `fate_check` reports `random_event_triggered` but does not generate the event yet.
- [x] M2 State: Postgres, campaigns (`campaign_create`, `campaign_open`), scenes, `scene_check`, `end_scene`, lists, `random_event`, persistent roll log. `fate_check` now returns the full random event.
- [x] M3 Journal: `journal_*` tools, `roll_history`, `session_start` / `session_end`, resources, prompts.
- [x] M3.1 Campaign per call (v0.6): every tool but `campaign_create` names its campaign, the server keeps no active campaign, the campaign resources become templates, and the prompts and system prompt pass the campaign.
- [x] M3.2 PC skills (v0.8): `pc_skills` on the campaign, `campaign_update`, `shift` and `reason` on `fate_check`, the `character_sheet` prompt, and the prompts and system prompt that use them.
- [x] M3.3 Third-playtest fixes (v0.9): the summary names the shifted odds; `question` on `roll` and `meaning`; `count` on `roll`; `setting` and `tone` in `campaign_update`; `new_title` on `list_update` and `journal_write`; system prompt rule 12 and the characters-list check.
- [x] M3.4 Trackers (v0.10): the `tracker` table, `tracker_add`, `tracker_adjust` and `tracker_remove`, trackers in the brief and the recap, and system prompt rule 13.
- [ ] M4 Playtest and tune: system prompt v2 based on what the model skipped or fudged.

**Decisions (resolved in v0.2)**

- Fate method: the d100 fate chart is the default and the only method built; the 2d10 fate check is deferred.
- Transport: stdio only.
- `journal_write`: a plain upsert that warns with the previous body; no rejection and no revision history.
- Active campaign: none at startup; `campaign_open` is required each time the server starts. (Replaced in v0.6: there is no active campaign, and every call names its campaign.)
- Odds levels: nine fixed levels in code.
- Stack: Spring Boot 4.1 and Spring AI 2.0, because every Boot 3.x line left open-source support in June 2026.

**Decisions (resolved in v0.3)**

- Demo fate chart: the formula in "Demo fate chart formula", written for this project.
- Pack precedence: personal tables replace demo tables by type + name; duplicates among personal packs, invalid packs and a configured packs directory that does not exist stop startup; `fate_check` uses the chart named `default`.
- `roll`: one evenly distributed number from `min` to `max`; no dice notation and no sums of dice.
- `roll_id`: `r_` plus the RollLog id padded to four digits.
- Versions: Spring Boot 4.1.1, Spring AI 2.0.1 (built against Boot 4.1.1), Gradle 9.8.1.

**Decisions (resolved in v0.4)**

- Random event targets: focus entries name `target: character` or `target: thread`; the pick is weighted over active characters or open threads; an empty list returns `{"list": …, "new": true}`.
- Random event meaning: the `actions` table by default; `random_event` takes an optional `table`.
- `end_scene`: `actual_setup` is required after an altered or interrupted scene.
- Names: campaign names and list titles are unique ignoring case; `list_add` of an existing title is an error that points to `list_update`.
- Database: Postgres 16 via `compose.yaml` on `127.0.0.1:5432`, `oracle`/`oracle`/`oracle`; no database means no start.
- Concurrency: each oracle tool call runs in one transaction that locks its campaign row.
- Tests: one Testcontainers Postgres per test run, emptied between tests; the in-memory repositories are deleted rather than kept as fakes.

**Decisions (resolved in v0.5)**

- Journal titles: unique per kind ignoring case, like campaign names and list titles; the schema's unique key is on `lower(title)`. Omitted `tags` keep the entry's tags.
- `journal_search`: every query word must be the start of a word in the title or body (prefix match, so inflected names still match); at most 20 entries, best first.
- `journal_get`: exactly one of `entry_id` or `title`; a title shared by several kinds is an error that lists each kind with its id.
- Sessions: at most one open session per campaign; `session_start` marks a session left open abandoned and leaves its `ended_at` empty; `session_end` without an open session is an error that points to `session_start`; `open_questions` is required.
- `roll_history`: `last_n` 1–100, default 20, or `scene`, not both; rows oldest first.
- Prompts are instructions only: `prompts/get` has no side effects.
- Journal tools, including `session_*` and `roll_history`, write no RollLog rows.

**Decisions (resolved in v0.6)**

- One active campaign per server process was unsafe in Claude Desktop. In the first playtest (Oct 20, 2026), Claude Desktop ran one server for both chats, so `campaign_create` in the second chat switched the active campaign for the first. The first chat's `end_scene`, `scene_check`, rolls and journal writes then landed in the other campaign without any error. Over stdio the server has one connection and no chat id, so it cannot tell chats apart. Of the two options (a `campaign` argument on every tool, or one active campaign named in every summary with one chat at a time), the first was chosen.
- Every tool except `campaign_create` takes a required `campaign`, the name or id matched as `campaign_open` matches it. This includes `roll` and `meaning`, whose RollLog rows belong to a campaign. The server keeps no active campaign, so any number of chats can share one server and the design also suits Streamable HTTP.
- `campaign_open` keeps its name and returns the brief, but changes nothing; `campaign_create` no longer opens anything either. A blank `campaign` is `campaign must not be blank; campaigns: …` and an unknown one is `no campaign 'X'; campaigns: …`, or `none exist yet, create one with campaign_create` when there are none.
- The campaign resources become the templates `campaign://{campaign}/brief` and `campaign://{campaign}/journal/{kind}`, with `{campaign}` percent-decoded; the "no campaign open" notice is gone, and an unknown campaign is an error.
- Prompts: `start_session` tells the DM to pass its `campaign` to every call; `end_session` takes no argument and tells the DM to name the chat's campaign; `new_campaign` tells the DM to pass the new name to every call after `campaign_create`. System prompt rule 8 says the same.

**Decisions (resolved in v0.7)**

- The system prompt ships with the server: `src/main/resources/system-prompt.md` is the only copy, the spec summarizes it, and the `game_master` prompt serves it from the jar with Windows line ends normalized.
- It takes in the second playtest's two lessons as rules 9 and 10 (state the odds with every result; explain why a PC action is uncertain before rolling). M4 still tunes it from further playtests.

**Decisions (resolved in v0.8)**

- PC skills come one of two ways: four quick skills the player names (exceptional +2, good +1, bad −1, awful −2), or a character sheet from any system, converted once into a reference. Both are stored as `pc_skills`, text the server shows but never reads.
- Converting a sheet is the DM's job, guided by the `character_sheet` prompt. The server knows no game system and calls no model, so the split stays as it was: the server rolls and keeps state, the model interprets.
- `fate_check` takes a plain `shift` and a `reason`, not the name of a stored skill. The DM already judges the odds, and a shift is the same kind of judgment; a number covers sheets, quick skills and later uses without more server code. The cost is that the server cannot check a shift against the reference, so consistency and one entry per roll are DM rules, kept visible by the roll log and rule 10.
- One entry per roll. Combining several factors into one shift is allowed now and then, as an exception, with each factor named in `reason`.
- A shift is −8 to +8 and stops at the ends of the scale; `impossible` and `certain` never move.
- The reply changes only when the shift changed the answer. Then `shift_effect` and one summary sentence say so, which gives the DM the story beat that the PC succeeded or failed because of a skill. The roll log has every shift either way.
- Chaos is unchanged: checks on the PC's own actions read the chart at the current chaos factor like any other check. Chaos stays the pressure of the world, and skills are what set the PC apart from an ordinary person.
- `campaign_update` is new, because nothing could change `pc_summary` after `campaign_create`. It changes only `pc_summary` and `pc_skills`. (Widened in v0.9 to `setting` and `tone`.)

**Decisions (resolved in v0.9)**

From the third playtest (Oct 23, 2026, «Ле Понт»): VtM V5, a converted character sheet, one session of about 75 minutes with 32 logged rolls. The roll log, the journal and the transcript agreed throughout, so these are small fixes rather than redesigns.

- The `fate_check` summary names the shifted odds whenever the shift is not 0. Before, a shift that changed nothing left the summary as `3 on unlikely`, so the DM worked out the odds itself for rule 9 and wrote "unlikely +2 = fifty_fifty"; the server had rolled on likely. `shift_effect` and the "turned" sentence still appear only when the shift changed the answer.
- `roll` and `meaning` take an optional `question`, stored in the row's inputs. In `roll_history` the city pick, a Rouse check and a Hunger die all read as a bare range.
- `roll` takes `count`, 1–10, for a pool read die by die (V5 Hunger dice); the numbers are never added. One roll keeps `value`, so earlier rows and replies stay as they were; two or more return `values`.
- `campaign_update` also replaces `setting` and `tone`. The setting still said the city would be picked in the first scene after a roll had picked Milan. The name never changes, because every call names the campaign by it.
- `list_update` and `journal_write` rename with `new_title`. "The priest at the party" stayed on the characters list and in the journal after he gave his name, because a title could not change. `journal_write` renames only an entry that exists, so a mistyped title fails instead of quietly creating an entry.
- System prompt rule 12: stop at the next moment the PC could act. Session step 5 and the `end_session` prompt check the characters list: the old signora with a debt to the PC, the stylist who knows his address and the Kindred informant were never added, so random events could pick only the PC's sire or the priest.
- Not changed: the session's one long scene, which ended at a natural break the DM offered, and the PC winning 7 of his 8 checks. He spent the session in his strongest domain, and 5 of those 7 wins needed no shift.

**Decisions (resolved in v0.10)**

From the third playtest, where the DM kept V5 Hunger in a journal note that neither the brief nor the recap shows.

- Trackers: a campaign keeps named whole numbers, each with optional limits and a note. The server knows no game system. The DM decides what to track, and the player agrees to each new tracker and hears every change (rule 13). In the player's words, "Our job is to provide tool."
- Three tools, like the lists:
  - `tracker_add` fails on a name already tracked, so a misspelled name cannot quietly start a second tracker.
  - `tracker_adjust` changes a value by a delta with a reason, and stops at the limits.
  - `tracker_remove` ends a tracker.
  - There is no set or edit tool: a correction is an adjust with its reason, and new limits or a new note mean removing and adding again.
- Tracker calls lock the campaign row and write RollLog rows with empty dice, like `set_chaos`, so a Rouse check and the change it caused sit next to each other in `roll_history`.
- Trackers are in the brief and the recap, so the next session starts with them.
- Boosts are not a new kind of reference entry. A power or a spent resource that adds to an action, such as Awe or Blood Surge, goes into one combined shift. The DM names each part in `reason` and says the shift before rolling, and the player corrects it then (rule 10).
- Conditions, such as the first playtest's bruised hand or the PC's Addiction penalty, get no feature of their own. The DM tracks one as a tracker, writes it in the journal, or keeps it in mind and folds it into the shift as above. This settles the deferred question of temporary conditions.

**Open questions**

- **Deferred: more uses of `shift`.** Ideas from the first two playtests, not designed yet. Since `fate_check` takes any shift, each may need only prompt changes:
  - Key NPCs with skills too; in a contest the shift is the PC's minus the NPC's.
  - Growth: at session end the DM may propose moving one skill a step when the story earned it, and the player confirms.
