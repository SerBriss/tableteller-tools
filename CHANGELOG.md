# Changelog

## 0.4.0-beta (2026-10-24)

The first beta, with everything through milestone 3.4 of [docs/SPEC.md](docs/SPEC.md) (v0.10). Milestone 4, playtesting and tuning the system prompt, comes next.

### What's in it

- **Oracle (M1):** `fate_check` on a d100 chart with nine odds levels and a chaos factor, plus `meaning`, `roll` and `set_chaos`. Tables come from packs: a small original demo pack ships in the jar, and your own go in `~/.oracle/packs/`. Every roll lands in the roll log.
- **Campaigns and scenes (M2):** campaigns, scenes with `scene_check` and `end_scene`, the threads and characters lists, and random events, all kept in Postgres.
- **Journal and sessions (M3):** `journal_write`, `journal_search` and `journal_get`, `roll_history`, `session_start` with its recap and `session_end`, the resources, and the prompts.
- **Campaign per call (M3.1):** every tool except `campaign_create` names its campaign, so several chats can play different campaigns on one server.
- **PC skills (M3.2):** `pc_skills`, `campaign_update`, `shift` and `reason` on `fate_check`, and the `character_sheet` prompt.
- **Third-playtest fixes (M3.3):** the summary names the shifted odds; `question` on `roll` and `meaning`; `count` on `roll`; `setting` and `tone` in `campaign_update`; renames with `new_title`; system prompt rule 12.
- **Trackers (M3.4):** `tracker_add`, `tracker_adjust` and `tracker_remove` keep numbers such as V5 Hunger between sessions. The brief and the recap show them, and system prompt rule 13 has the DM ask before tracking something new and announce every change.

### Upgrading from a 0.1.0 build

1. Rebuild the jar. It is now `build/libs/tableteller-tools-0.4.0-beta.jar`.
2. Point `claude_desktop_config.json` at the new jar and restart Claude Desktop. On startup the server adds the `tracker` table to your database; your campaigns stay as they are.
3. Paste `src/main/resources/system-prompt.md` into your project's instructions again, so the DM gets the current rules, rule 13 included.
