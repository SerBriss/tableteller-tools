package dev.tableteller.scene;

import com.fasterxml.jackson.annotation.JsonProperty;

public record Scene(int number, @JsonProperty("expected_setup") String expectedSetup,
        @JsonProperty("actual_setup") String actualSetup, @JsonProperty("check_result") SceneResult checkResult,
        String summary, boolean open) {

    /** What the scene is about: the actual setup once known, otherwise the expected one. */
    public String setup() {
        return actualSetup != null ? actualSetup : expectedSetup;
    }

    /**
     * How far a campaign has got, for summaries: {@code no scenes yet}, {@code scene 4 open} or
     * {@code scene 4 closed}.
     */
    public static String progress(Scene openScene, int sceneNumber) {
        if (openScene != null) return "scene " + openScene.number() + " open";
        return sceneNumber == 0 ? "no scenes yet" : "scene " + sceneNumber + " closed";
    }
}
