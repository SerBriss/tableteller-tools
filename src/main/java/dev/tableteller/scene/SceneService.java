package dev.tableteller.scene;

import dev.tableteller.random.RandomSource;
import org.springframework.stereotype.Service;

/** The scene check: d10 against chaos; at or below and odd is altered, at or below and even is interrupted. */
@Service
public class SceneService {

    private final RandomSource random;

    public SceneService(RandomSource random) {
        this.random = random;
    }

    public record SceneCheck(int roll, SceneResult result) {
    }

    public SceneCheck check(int chaosFactor) {
        int roll = random.between(1, 10);
        if (roll > chaosFactor) return new SceneCheck(roll, SceneResult.expected);
        return new SceneCheck(roll, roll % 2 == 1 ? SceneResult.altered : SceneResult.interrupted);
    }
}
