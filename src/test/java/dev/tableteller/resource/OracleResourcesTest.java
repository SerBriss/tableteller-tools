package dev.tableteller.resource;

import static dev.tableteller.journal.JournalKind.location;
import static dev.tableteller.journal.JournalKind.npc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tableteller.lists.ListName;
import dev.tableteller.support.TestDatabase;
import dev.tableteller.support.TestOracle;
import dev.tableteller.tracker.Tracker;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OracleResourcesTest {
    static final JsonMapper JSON = JsonMapper.builder().build();

    @BeforeEach
    void reset() {
        TestDatabase.reset();
    }

    private static TextResourceContents only(ReadResourceResult result) {
        assertThat(result.contents()).hasSize(1);
        return (TextResourceContents) result.contents().getFirst();
    }

    @Test
    void briefIsTheCampaignOpenJson() {
        var oracle = TestOracle.withRolls();
        oracle.campaignTools.campaignCreate("Ashfall", "a burnt valley", "grim", "Mara, a scout",
            "Stealth: exceptional, +2");
        oracle.listTools.listAdd("Ashfall", ListName.threads, "Find the heir", null, null);
        var contents = only(oracle.resources.brief("Ashfall"));
        assertThat(contents.uri()).isEqualTo("campaign://Ashfall/brief");
        assertThat(contents.mimeType()).isEqualTo("application/json");
        var brief = JSON.readTree(contents.text());
        assertThat(brief.get("name").asString()).isEqualTo("Ashfall");
        assertThat(brief.get("setting").asString()).isEqualTo("a burnt valley");
        assertThat(brief.get("pc_summary").asString()).isEqualTo("Mara, a scout");
        assertThat(brief.get("pc_skills").asString()).isEqualTo("Stealth: exceptional, +2");
        assertThat(brief.get("chaos_factor").asInt()).isEqualTo(5);
        assertThat(brief.at("/threads/0/title").asString()).isEqualTo("Find the heir");
        assertThat(brief.get("summary").asString())
            .isEqualTo("Campaign 'Ashfall': chaos 5; no scenes yet; threads 1, characters 0.");
    }

    @Test
    void briefLeavesOutUnsetLimitsAndNote() {
        var oracle = TestOracle.withRolls();
        var id = oracle.createCampaign("Ashfall");
        oracle.trackers.add(id, new Tracker("Голод", 1, 0, 5, "Rouse: d10"));
        oracle.trackers.add(id, new Tracker("Сила воли", 4, null, null, null));
        var trackers = JSON.readTree(only(oracle.resources.brief("Ashfall")).text()).get("trackers");
        assertThat(trackers.get(0).get("name").asString()).isEqualTo("Голод");
        assertThat(trackers.get(0).get("value").asInt()).isEqualTo(1);
        assertThat(trackers.get(0).get("min").asInt()).isZero();
        assertThat(trackers.get(0).get("max").asInt()).isEqualTo(5);
        assertThat(trackers.get(0).get("note").asString()).isEqualTo("Rouse: d10");
        assertThat(trackers.get(1).propertyNames()).containsExactlyInAnyOrder("name", "value");
    }

    @Test
    void briefIsTheNamedCampaigns() {
        var oracle = TestOracle.withRolls();
        oracle.createCampaign("Ashfall");
        oracle.createCampaign("Iron Coast");
        oracle.listTools.listAdd("Iron Coast", ListName.threads, "Reach the cape", null, null);
        assertThat(JSON.readTree(only(oracle.resources.brief("Ashfall")).text()).get("threads").isEmpty()).isTrue();
    }

    @Test
    void briefDecodesTheCampaignSegment() {   // clients percent-encode the URI; some send it raw
        var oracle = TestOracle.withRolls();
        for (var name : List.of("Iron Coast", "Пепел", "A+B", "100% Doom")) oracle.createCampaign(name);
        var segments = Map.of("Iron%20Coast", "Iron Coast", "%D0%9F%D0%B5%D0%BF%D0%B5%D0%BB", "Пепел",
            "пепел", "Пепел", "A+B", "A+B", "100%25%20Doom", "100% Doom", "100% Doom", "100% Doom");
        segments.forEach((segment, name) -> assertThat(
            JSON.readTree(only(oracle.resources.brief(segment)).text()).get("name").asString())
            .as(segment).isEqualTo(name));
        assertThat(only(oracle.resources.brief("Iron%20Coast")).uri()).isEqualTo("campaign://Iron%20Coast/brief");
    }

    @Test
    void briefOfAnUnknownCampaignListsTheCampaigns() {
        var oracle = TestOracle.withRolls();
        oracle.createCampaign("Ashfall");
        assertThatThrownBy(() -> oracle.resources.brief("Ashfal")).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("no campaign 'Ashfal'; campaigns: Ashfall");
    }

    @Test
    void journalTitlesAreSortedIgnoringCase() {
        var oracle = TestOracle.withRolls();
        oracle.createCampaign("Ashfall");
        oracle.createCampaign("Iron Coast");
        for (var title : List.of("varo", "Ilsa", "brother Tam")) {
            oracle.journalTools.journalWrite("Ashfall", npc, title, "Someone in town.", null, null);
        }
        oracle.journalTools.journalWrite("Ashfall", location, "Docks", "Wet planks.", null, null);
        oracle.journalTools.journalWrite("Iron Coast", npc, "Captain Orr", "Owns the ship.", null, null);
        var contents = only(oracle.resources.journalTitles("Ashfall", "npc"));
        assertThat(contents.uri()).isEqualTo("campaign://Ashfall/journal/npc");
        assertThat(contents.mimeType()).isEqualTo("application/json");
        var json = JSON.readTree(contents.text());
        assertThat(json.get("kind").asString()).isEqualTo("npc");
        assertThat(json.get("titles").valueStream().map(JsonNode::asString))
            .containsExactly("brother Tam", "Ilsa", "varo");
    }

    @Test
    void journalTitlesOfAnUnknownCampaignListsTheCampaigns() {
        assertThatThrownBy(() -> TestOracle.withRolls().resources.journalTitles("Ashfall", "npc"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("no campaign 'Ashfall'; none exist yet, create one with campaign_create");
    }

    @Test
    void unknownJournalKindListsTheKinds() {
        var oracle = TestOracle.withRolls();
        oracle.createCampaign("Ashfall");
        assertThatThrownBy(() -> oracle.resources.journalTitles("Ashfall", "npcs"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("unknown journal kind 'npcs'; one of npc, location, item, fact, note");
    }

    @Test
    void tablesListsTypeNameAndPack() {
        var contents = only(TestOracle.withRolls().resources.tables());
        assertThat(contents.uri()).isEqualTo("oracle://tables");
        assertThat(contents.mimeType()).isEqualTo("application/json");
        assertThat(JSON.readTree(contents.text()).valueStream()
                .map(table -> table.get("type").asString() + "/" + table.get("name").asString() + "/"
                    + table.get("pack").asString()))
            .containsExactly("fate_chart/default/demo", "focus/default/test", "meaning/actions/test",
                "meaning/single/test");
    }
}
