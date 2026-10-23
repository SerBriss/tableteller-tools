# Tableteller Tools

An MCP server that gives an AI game master a solo-RPG oracle: fate checks against a d100 chart, a chaos factor, meaning tables and plain number rolls. The server makes every random result and logs every roll, so the model never decides an uncertain fact itself. See [docs/SPEC.md](docs/SPEC.md) for the full design.

**Status: beta, version 0.4.0-beta, with everything through milestone 3.4 (trackers).** What each release changed is in [CHANGELOG.md](CHANGELOG.md). Campaigns, scenes, threads and characters lists, random events, the roll log, the campaign journal and play sessions live in Postgres and survive restarts. Every tool except `campaign_create` takes the campaign's name or id, so several chats can play different campaigns on one server. The PC's skills, four quick ones or a converted character sheet, shift the odds of fate checks on the PC's own actions, and the reply says when a skill decided the answer. `roll` takes a count for dice pools such as Hunger dice, `roll` and `meaning` record what they were for, list and journal entries can be renamed, and `campaign_update` also changes the setting and tone. Trackers keep numbers the story needs between sessions, such as V5 Hunger; the DM asks before tracking something new and announces every change, and the brief and the recap show them. The tools are:

- campaigns: `campaign_create`, `campaign_open`, `campaign_update`
- sessions: `session_start` (the recap), `session_end`
- the oracle: `fate_check`, `random_event`, `meaning`, `roll`, `set_chaos`
- scenes: `scene_check`, `end_scene`
- lists: `list_add`, `list_update`, `list_get`
- the journal: `journal_write`, `journal_search`, `journal_get`
- trackers: `tracker_add`, `tracker_adjust`, `tracker_remove`
- audits: `roll_history`

## Requirements

- JDK 21
- Docker Desktop (or another Docker engine) for the Postgres database

## Database

Start the database once from the repo folder:

```bash
docker compose up -d
```

This runs Postgres 16 reachable only from this machine (`127.0.0.1:5432`), with database, user and password all `oracle`. Your campaigns live in the `oracle-data` Docker volume, so they survive restarts of both the container and the server. The container restarts by itself whenever Docker starts, until you stop it with `docker compose stop`. Docker has to be running whenever you play: without the database the server won't start, and its log says why.

To use another database, pass `--spring.datasource.url=…`, `--spring.datasource.username=…` and `--spring.datasource.password=…` in the server's `args`.

## Build

```bash
JAVA_HOME=/path/to/jdk-21 ./gradlew bootJar
```

This produces `build/libs/tableteller-tools-0.4.0-beta.jar`. `./gradlew test` runs the tests, including one that starts the server and talks to it over stdio.

## Use it from Claude Desktop

The client starts the server itself over stdio. Add an entry to `claude_desktop_config.json` (on Windows, `%APPDATA%\Claude\claude_desktop_config.json`), using absolute paths to a JDK 21 `java` and to the jar:

```json
{
  "mcpServers": {
    "tableteller": {
      "command": "C:\\path\\to\\jdk-21\\bin\\java.exe",
      "args": ["-jar", "C:\\path\\to\\tableteller-tools-0.4.0-beta.jar"]
    }
  }
}
```

To read packs from somewhere other than `~/.oracle/packs/`, add `"--oracle.packs.dir=C:\\path\\to\\packs"` to `args`. A directory you set this way must exist, or the server will not start; only the default directory may be missing.

The first time, create a campaign: the `new_campaign` prompt has the DM interview you, including your character's skills, and call `campaign_create`. For skills you can name four quick ones (exceptional, good, bad and awful) or give a character sheet from any game system, which the DM converts once into odds shifts with your approval. Run the `character_sheet` prompt again whenever the sheet changes. After that, begin every session with the `start_session` prompt, or ask the DM to call `campaign_open` and then `session_start` with the campaign's name. End each session with the `end_session` prompt, so the next one starts from your summary and open questions.

The server keeps no "open" campaign: the DM passes the campaign's name (or id) to every tool call, and a misspelled name is an error that lists your campaigns. Claude Desktop runs one server for all its chats, so this is what lets two chats play two campaigns at the same time without mixing up their rolls, scenes and journals. Keep one campaign per chat.

Give the DM the rules in [src/main/resources/system-prompt.md](src/main/resources/system-prompt.md): paste the whole file into a Claude Desktop project's instructions, so every chat in the project starts with them. Without a project, pick the `game_master` prompt at the start of a chat; the server sends the same text. Rule 8 is the one that asks the DM to name the chat's campaign in every call.

After you rebuild the jar, restart Claude Desktop so that it starts the new server. The server updates the database itself when it starts, and your campaigns stay as they are. When the version changes, the jar's name changes with it, so point `claude_desktop_config.json` at the new jar before you restart. If you pasted `system-prompt.md` into a project's instructions, paste it again: the project keeps the old copy, and the rules change between versions.

The server writes nothing but protocol messages to stdout. Its log is `~/.oracle/logs/tableteller-tools.log`; look there first if the client says the server failed to start.

## Resources and prompts

Resources the client can attach without a tool call:

- `campaign://{campaign}/brief`: a campaign's setting, tone, PC, PC skills, chaos factor, current scene, both lists and trackers
- `campaign://{campaign}/journal/{kind}`: titles of a campaign's journal entries of one kind (`npc`, `location`, `item`, `fact` or `note`)
- `oracle://tables`: every loaded table with its type and pack, so you can check that your own pack is in play

The first two are templates: `{campaign}` is the campaign's name, percent-encoded where needed (`campaign://Iron%20Coast/brief`), or its id. A client that only offers listed resources for attaching won't show them; `campaign_open` returns the same brief.

Prompts, which only tell the DM what to do; picking one changes nothing by itself:

- `start_session`: open a campaign, start a session, recap it and set the scene
- `end_session`: journal what's missing, then end the session with a summary and open questions
- `new_campaign`: interview you, create a campaign with your character's skills and 2-3 threads, and start the first session
- `character_sheet`: convert a character sheet from any game system into the PC's skills, or convert what changed on it
- `game_master`: the DM's rules, the same text as `src/main/resources/system-prompt.md`, for a chat outside a project

## Table packs

Tables are data files, so the code contains no text from published games. A small original demo pack ships inside the jar: a fate chart named `default`, a focus table named `default` for random events, and the `actions` and `descriptions` meaning tables. The pack format is described under "Table data and licensing" in the spec.

Your own packs go in `~/.oracle/packs/`:

- Any subfolder works. Files ending in `.yaml`, `.yml` or `.json` are read as UTF-8; other files are ignored.
- One file can hold several tables, separated by `---`.
- A table of yours replaces the demo table with the same type and name. For example, your own `fate_chart` named `default` becomes the chart every fate check uses.
- Two of your tables with the same type and name, or any table that fails validation, stop the server from starting. The log names the file and the reason.

Tables from a published game are for you to type in from your own copy. Keep them in `~/.oracle/packs/` and out of this repository: publishing them needs the publisher's permission.
