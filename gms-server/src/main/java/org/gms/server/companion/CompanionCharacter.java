package org.gms.server.companion;

import lombok.Getter;
import lombok.Setter;
import org.gms.client.Character;

import java.awt.Point;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;

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

    private final Deque<TrailPoint> trailHistory = new ConcurrentLinkedDeque<>();

    public static class TrailPoint {
        public final int mapId;
        public final Point position;
        public final int stance;
        public final long timestamp;

        public TrailPoint(int mapId, Point position, int stance, long timestamp) {
            this.mapId = mapId;
            this.position = new Point(position);
            this.stance = stance;
            this.timestamp = timestamp;
        }
    }

    public CompanionCharacter(Character character, int masterCharacterId) {
        this.character = character;
        this.masterCharacterId = masterCharacterId;
        this.tacticMode = CompanionTacticMode.SUPPORT_ONLY;
        this.lastBuffTime = 0L;
        this.lastAttackTime = 0L;
        this.lastMoveTime = 0L;
        this.lastTagTime = System.currentTimeMillis();
    }

    public void addTrailPoint(int mapId, Point pos, int stance) {
        if (pos == null) return;
        long now = System.currentTimeMillis();
        TrailPoint last = trailHistory.peekLast();
        if (last != null && last.mapId == mapId && last.position.distance(pos) < 15 && last.stance == stance) {
            return;
        }
        trailHistory.addLast(new TrailPoint(mapId, pos, stance, now));
        while (trailHistory.size() > 60) {
            trailHistory.pollFirst();
        }
    }

    public void clearTrail() {
        trailHistory.clear();
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
