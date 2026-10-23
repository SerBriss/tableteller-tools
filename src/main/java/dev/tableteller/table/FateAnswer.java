package dev.tableteller.table;

import java.util.Locale;

/** Constants are the {@code answer} values in tool output. */
public enum FateAnswer {
    yes("Yes"), no("No"), exceptional_yes("Exceptional yes"), exceptional_no("Exceptional no");

    private final String label;

    FateAnswer(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** The answer inside a sentence: {@code a yes}, {@code an exceptional no}. */
    public String withArticle() {
        var lower = label.toLowerCase(Locale.ROOT);
        return (lower.startsWith("e") ? "an " : "a ") + lower;
    }
}
