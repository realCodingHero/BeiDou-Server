package org.gms.server.companion;

import lombok.Getter;
import lombok.Setter;
import org.gms.client.Character;

@Getter
@Setter
public class CompanionCharacter {
    private final Character character;
    private int masterCharacterId;
    private CompanionTacticMode tacticMode;
    private long lastBuffTime;
    private long lastAttackTime;
    private long lastMoveTime;
    private long lastTagTime;

    public CompanionCharacter(Character character, int masterCharacterId) {
        this.character = character;
        this.masterCharacterId = masterCharacterId;
        this.tacticMode = CompanionTacticMode.SUPPORT_ONLY;
        this.lastBuffTime = 0L;
        this.lastAttackTime = 0L;
        this.lastMoveTime = 0L;
        this.lastTagTime = System.currentTimeMillis();
    }

    public int getId() {
        return character != null ? character.getId() : 0;
    }

    public String getName() {
        return character != null ? character.getName() : "";
    }

    public int getLevel() {
        return character != null ? character.getLevel() : 1;
    }

    public int getJobId() {
        return character != null && character.getJob() != null ? character.getJob().getId() : 0;
    }
}
