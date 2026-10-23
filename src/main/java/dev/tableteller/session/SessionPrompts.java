package dev.tableteller.session;

import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpArg;
import org.springframework.ai.mcp.annotation.McpPrompt;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * One-click session starters for the player, and the game master's rules. Each is a fixed instruction to the DM:
 * getting a prompt changes nothing on the server, and the DM makes the calls it names. This class has no
 * dependencies so it cannot change state.
 */
@Component
public class SessionPrompts {

    private static final String START_SESSION = "Open a campaign, start a session with a recap, and set the scene.";
    private static final String END_SESSION = "Record the session's summary and open questions, and end it.";
    private static final String NEW_CAMPAIGN =
        "Interview the player, create a campaign with 2-3 threads, and start the first session.";
    private static final String GAME_MASTER =
        "The game master's rules for these tools; pick it first in a chat that has no project instructions.";
    private static final String CHARACTER_SHEET =
        "Convert the player's character sheet into pc_skills, the reference fate_check shifts come from.";
    /** How the DM turns a character sheet into pc_skills; character_sheet and new_campaign both give it. */
    private static final String CONVERSION = """
        Convert the sheet into the reference fate_check shifts come from:
        1. Name the game system and, for each kind of value on the sheet, its floor, average and ceiling. \
        That is the first line.
        2. Give everything on the sheet my character can act with its own line: \
        abilities, skills, saves, proficiencies, features and gear. \
        Each line is the entry, its value and its shift, for example "Stealth +7: +3". \
        Average is 0; judge each entry on its own, and a clearly outstanding value may go past 2.
        3. Show me the reference as a table and change what I object to.
        4. When I agree, save it with campaign_update as pc_skills, the first line and then one line per entry.""";
    /** The system prompt the server is built for, shipped in the jar from src/main/resources/system-prompt.md. */
    private static final String SYSTEM_PROMPT = load("system-prompt.md");

    @McpPrompt(name = "start_session", description = START_SESSION)
    public GetPromptResult startSession(
            @McpArg(name = "campaign", description = "The campaign's name", required = true) String campaign) {
        if (campaign == null || campaign.isBlank()) {
            throw new IllegalArgumentException("campaign must not be blank");
        }
        return prompt(START_SESSION, """
            Let's play the campaign "%1$s".
            1. Call campaign_open with campaign "%1$s", then session_start with campaign "%1$s".
            2. Recap the story for me in 3 sentences, using the recap session_start returns.
            3. If a scene is still open, pick it up where it left off. \
            Otherwise propose the next scene's expected setup and call scene_check.
            For the rest of this chat, pass campaign "%1$s" to every tool you call.""".formatted(campaign.trim()));
    }

    @McpPrompt(name = "end_session", description = END_SESSION)
    public GetPromptResult endSession() {
        return prompt(END_SESSION, """
            Let's end the session here.
            1. Record anything established this session that isn't in the journal yet, with journal_write, \
            and put NPCs who will matter again on the characters list with list_add.
            2. Write a summary of this session in a few sentences, and the questions the story has left open.
            3. Call session_end with them. \
            A scene that is still open can stay open; it carries over to the next session.
            Pass the campaign this chat has been playing as campaign to each of these calls.""");
    }

    @McpPrompt(name = "new_campaign", description = NEW_CAMPAIGN)
    public GetPromptResult newCampaign() {
        return prompt(NEW_CAMPAIGN, """
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
            %s
            Finally call session_start and set up the first scene with scene_check.
            After campaign_create, pass the new campaign's name as campaign to every tool you call in this chat."""
            .formatted(CONVERSION));
    }

    @McpPrompt(name = "character_sheet", description = CHARACTER_SHEET)
    public GetPromptResult characterSheet(
            @McpArg(name = "campaign", description = "The campaign's name", required = true) String campaign) {
        if (campaign == null || campaign.isBlank()) {
            throw new IllegalArgumentException("campaign must not be blank");
        }
        return prompt(CHARACTER_SHEET, """
            Let's set up my character's skills for the campaign "%1$s" from a character sheet.
            First call campaign_open with campaign "%1$s" to see its pc_skills. \
            If I haven't given you the sheet in this chat yet, ask me for it.
            %2$s
            If the campaign already has pc_skills, convert only what changed on the sheet, keep the other lines, \
            and show me the changes before you save.
            Pass campaign "%1$s" to campaign_open and campaign_update.""".formatted(campaign.trim(), CONVERSION));
    }

    @McpPrompt(name = "game_master", description = GAME_MASTER)
    public GetPromptResult gameMaster() {
        return prompt(GAME_MASTER, SYSTEM_PROMPT);
    }

    private static GetPromptResult prompt(String description, String text) {
        return new GetPromptResult(description, List.of(new PromptMessage(Role.USER, new TextContent(text))));
    }

    /** A text file from the classpath, with Windows line ends made plain so every checkout serves the same text. */
    private static String load(String name) {
        try {
            return new ClassPathResource(name).getContentAsString(StandardCharsets.UTF_8).replace("\r\n", "\n").strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
