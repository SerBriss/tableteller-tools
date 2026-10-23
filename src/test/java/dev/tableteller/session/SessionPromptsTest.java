package dev.tableteller.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class SessionPromptsTest {
    SessionPrompts prompts = new SessionPrompts();

    private static String text(GetPromptResult result) {
        return ((TextContent) result.messages().getFirst().content()).text();
    }

    @Test
    void startSessionNamesTheCampaign() {
        assertThat(text(prompts.startSession("  Ashfall "))).isEqualTo("""
            Let's play the campaign "Ashfall".
            1. Call campaign_open with campaign "Ashfall", then session_start with campaign "Ashfall".
            2. Recap the story for me in 3 sentences, using the recap session_start returns.
            3. If a scene is still open, pick it up where it left off. \
            Otherwise propose the next scene's expected setup and call scene_check.
            For the rest of this chat, pass campaign "Ashfall" to every tool you call.""");
    }

    @Test
    void startSessionRejectsABlankCampaign() {
        assertThatThrownBy(() -> prompts.startSession(" "))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("campaign must not be blank");
        assertThatThrownBy(() -> prompts.startSession(null))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("campaign must not be blank");
    }

    @Test
    void endSessionText() {
        assertThat(text(prompts.endSession())).isEqualTo("""
            Let's end the session here.
            1. Record anything established this session that isn't in the journal yet, with journal_write, \
            and put NPCs who will matter again on the characters list with list_add.
            2. Write a summary of this session in a few sentences, and the questions the story has left open.
            3. Call session_end with them. \
            A scene that is still open can stay open; it carries over to the next session.
            Pass the campaign this chat has been playing as campaign to each of these calls.""");
    }

    @Test
    void newCampaignText() {
        assertThat(text(prompts.newCampaign())).isEqualTo("""
            Let's start a new campaign. Interview me one question at a time about:
            1. The setting: the world and the situation the story starts in.
            2. The tone, for example grim, hopeful or pulpy.
            3. My player character: who they are and what they want.
            4. My character's skills: four quick ones, or a character sheet from any game system. \
            For quick skills I name four, anything I like, one at each grade: \
            exceptional (+2), good (+1), bad (-1) and awful (-2).
            Then call campaign_create with a short name, the setting, the tone, pc_summary and, for quick skills, \
            pc_skills with one line per skill, for example "Swordplay: exceptional, +2". \
            Seed the threads list with 2-3 threads from what I told you, using list_add.
            For a character sheet, after campaign_create:
            Convert the sheet into the reference fate_check shifts come from:
            1. Name the game system and, for each kind of value on the sheet, its floor, average and ceiling. \
            That is the first line.
            2. Give everything on the sheet my character can act with its own line: \
            abilities, skills, saves, proficiencies, features and gear. \
            Each line is the entry, its value and its shift, for example "Stealth +7: +3". \
            Average is 0; judge each entry on its own, and a clearly outstanding value may go past 2.
            3. Show me the reference as a table and change what I object to.
            4. When I agree, save it with campaign_update as pc_skills, the first line and then one line per entry.
            Finally call session_start and set up the first scene with scene_check.
            After campaign_create, pass the new campaign's name as campaign to every tool you call in this chat.""");
    }

    @Test
    void characterSheetNamesTheCampaign() {
        assertThat(text(prompts.characterSheet("  Ashfall "))).isEqualTo("""
            Let's set up my character's skills for the campaign "Ashfall" from a character sheet.
            First call campaign_open with campaign "Ashfall" to see its pc_skills. \
            If I haven't given you the sheet in this chat yet, ask me for it.
            Convert the sheet into the reference fate_check shifts come from:
            1. Name the game system and, for each kind of value on the sheet, its floor, average and ceiling. \
            That is the first line.
            2. Give everything on the sheet my character can act with its own line: \
            abilities, skills, saves, proficiencies, features and gear. \
            Each line is the entry, its value and its shift, for example "Stealth +7: +3". \
            Average is 0; judge each entry on its own, and a clearly outstanding value may go past 2.
            3. Show me the reference as a table and change what I object to.
            4. When I agree, save it with campaign_update as pc_skills, the first line and then one line per entry.
            If the campaign already has pc_skills, convert only what changed on the sheet, keep the other lines, \
            and show me the changes before you save.
            Pass campaign "Ashfall" to campaign_open and campaign_update.""");
    }

    @Test
    void characterSheetRejectsABlankCampaign() {
        assertThatThrownBy(() -> prompts.characterSheet(" "))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("campaign must not be blank");
        assertThatThrownBy(() -> prompts.characterSheet(null))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("campaign must not be blank");
    }

    @Test
    void gameMasterIsTheShippedSystemPrompt() throws Exception {
        var shipped = Files.readString(Path.of("src/main/resources/system-prompt.md")).replace("\r\n", "\n").strip();
        assertThat(text(prompts.gameMaster())).isEqualTo(shipped)
            .startsWith("You are the game master for a solo campaign.")
            .contains("8. This chat plays one campaign.", "11. For the player character's own action",
                "if no entry fits, pass no shift", "12. Stop narrating at the next moment",
                "13. Ask the player before you start tracking");
    }

    @Test
    void eachPromptIsOneUserMessage() {
        for (var result : List.of(prompts.startSession("Ashfall"), prompts.endSession(), prompts.newCampaign(),
                prompts.gameMaster(), prompts.characterSheet("Ashfall"))) {
            assertThat(result.description()).isNotBlank();
            assertThat(result.messages()).hasSize(1);
            assertThat(result.messages().getFirst().role()).isEqualTo(Role.USER);
        }
    }
}
