package dev.tableteller.campaign;

import java.time.Instant;
import java.util.UUID;

public record Campaign(UUID id, String name, String setting, String tone, String pcSummary, String pcSkills,
        int chaosFactor, int sceneNumber, Instant createdAt) {
}
