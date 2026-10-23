package dev.tableteller.state;

import java.util.List;
import java.util.Map;

/** A roll-log row before it is stored. Map keys are snake_case; enum values are stored as their names. */
public record NewRoll(String tool, Map<String, Object> inputs, List<Integer> dice, Map<String, Object> result,
        int sceneNumber) {
}
