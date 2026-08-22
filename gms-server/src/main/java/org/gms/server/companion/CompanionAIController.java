package org.gms.server.companion;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.Character;
import org.gms.client.Job;
import org.gms.client.Skill;
import org.gms.client.SkillFactory;
import org.gms.server.life.Monster;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapObjectType;
import org.gms.server.maps.MapleMap;
import org.gms.util.PacketCreator;

import java.awt.Point;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

@Slf4j
public class CompanionAIController {
    private static final Random random = new Random();

    public static void tickCompanion(CompanionCharacter companionWrapper, Character master) {
        if (companionWrapper == null || master == null) {
            return;
        }
        Character companion = companionWrapper.getCharacter();
        if (companion == null || !companion.isLoggedIn()) {
            return;
        }

        MapleMap masterMap = master.getMap();
        MapleMap compMap = companion.getMap();
        if (masterMap == null || compMap == null) {
            return;
        }

        long now = System.currentTimeMillis();

        // 1. 地图同步与跟随逻辑 (Map Synchronization & Follow)
        if (masterMap.getId() != compMap.getId()) {
            AccountCompanionManager.getInstance().onMasterChangeMap(master, masterMap, master.getPosition());
            return;
        }

        Point masterPos = master.getPosition();
        Point compPos = companion.getPosition();
        if (masterPos != null && compPos != null) {
            double distance = masterPos.distance(compPos);

            if (distance > 600) {
                // 距离过远直接瞬移归位
                Point targetPos = new Point(masterPos.x + (random.nextBoolean() ? 40 : -40), masterPos.y);
                if (masterMap.getFootholds() != null) {
                    Point below = masterMap.getGroundBelow(targetPos);
                    if (below != null) {
                        targetPos = below;
                    }
                }
                companion.setPosition(targetPos);
                companion.setStance(masterPos.x < targetPos.x ? 1 : 0);
                masterMap.broadcastMessage(PacketCreator.showForeignEffect(companion.getId(), 1005));
                masterMap.broadcastMessage(PacketCreator.movePlayer(companion.getId(), companion.getIdleMovement(), 15));
            } else if (distance > 120 && (now - companionWrapper.getLastMoveTime() > 300)) {
                // 平滑靠近主人并广播移动包
                int step = (int) Math.min(distance - 70, 45);
                int newX = compPos.x + (masterPos.x > compPos.x ? step : -step);
                Point newPos = new Point(newX, masterPos.y);
                if (masterMap.getFootholds() != null) {
                    Point below = masterMap.getGroundBelow(newPos);
                    if (below != null) {
                        newPos = below;
                    }
                }
                companion.setPosition(newPos);
                companion.setStance(masterPos.x < newPos.x ? 1 : 0);
                masterMap.broadcastMessage(PacketCreator.movePlayer(companion.getId(), companion.getIdleMovement(), 15));
                companionWrapper.setLastMoveTime(now);
            }
        }

        // 2. 自动施放职业专属核心 Buff 与治疗 (Auto Buff & Healing AI)
        if (now - companionWrapper.getLastBuffTime() > 4000) {
            boolean casted = tryCastCompanionBuffs(companionWrapper, master);
            if (casted) {
                companionWrapper.setLastBuffTime(now);
            }
        }

        // 3. 智能索敌与协助攻击 (Auto Combat AI)
        if (companionWrapper.getTacticMode() == CompanionTacticMode.BALANCED_COMBAT) {
            if (now - companionWrapper.getLastAttackTime() > 1500) {
                boolean attacked = tryCompanionAttack(companionWrapper, master);
                if (attacked) {
                    companionWrapper.setLastAttackTime(now);
                }
            }
        }
    }

    private static boolean tryCastCompanionBuffs(CompanionCharacter companionWrapper, Character master) {
        Character companion = companionWrapper.getCharacter();
        Job companionJob = companion.getJob();
        if (companionJob == null) {
            return false;
        }

        // 牧师/祭司/主教 (230, 231, 232)
        if (companionJob.isA(Job.CLERIC)) {
            // 检测是否需要治疗
            if (master.getHp() < master.getMaxHp() * 0.8) {
                if (castSkillIfAvailable(companion, master, 2301002)) { // 群体治疗 (Heal)
                    return true;
                }
            }
            // 神圣祈祷 (Holy Symbol - 2311003)
            if (castSkillIfAvailable(companion, master, 2311003)) {
                return true;
            }
            // 祝福 (Bless - 2301004)
            if (castSkillIfAvailable(companion, master, 2301004)) {
                return true;
            }
        }

        // 枪战士/龙骑士/黑骑士 (130, 131, 132)
        if (companionJob.isA(Job.SPEARMAN)) {
            // 神圣之火 (Hyper Body - 1301007)
            if (castSkillIfAvailable(companion, master, 1301007)) {
                return true;
            }
            // 极限防御 (Iron Will - 1301006)
            if (castSkillIfAvailable(companion, master, 1301006)) {
                return true;
            }
        }

        // 刺客/无影人/隐士 (410, 411, 412)
        if (companionJob.isA(Job.ASSASSIN) || companionJob.isA(Job.BANDIT)) {
            // 速度激发 (Haste - 4101003 / 4201003)
            if (castSkillIfAvailable(companion, master, 4101003) || castSkillIfAvailable(companion, master, 4201003)) {
                return true;
            }
        }

        // 猎人/弩弓手/神射手/箭神 (310, 311, 312, 320, 321, 322)
        if (companionJob.isA(Job.BOWMAN)) {
            // 火眼晶晶 (Sharp Eyes - 3121002 / 3221002)
            if (castSkillIfAvailable(companion, master, 3121002) || castSkillIfAvailable(companion, master, 3221002)) {
                return true;
            }
        }

        // 狂战士/十字军/英雄 (110, 111, 112)
        if (companionJob.isA(Job.FIGHTER)) {
            // 愤怒之火 (Rage - 1101006)
            if (castSkillIfAvailable(companion, master, 1101006)) {
                return true;
            }
        }

        // 拳手/冲锋队长 (510, 511, 512)
        if (companionJob.isA(Job.BRAWLER)) {
            // 超速光学 (Speed Infusion - 5121009)
            if (castSkillIfAvailable(companion, master, 5121009)) {
                return true;
            }
        }

        return false;
    }

    private static boolean castSkillIfAvailable(Character companion, Character master, int skillId) {
        Skill skill = SkillFactory.getSkill(skillId);
        if (skill == null) {
            return false;
        }
        int level = companion.getSkillLevel(skill);
        if (level <= 0) {
            level = skill.getMaxLevel() > 0 ? Math.min(10, skill.getMaxLevel()) : 1;
        }
        try {
            skill.getEffect(level).applyTo(companion, true);
            if (master != null && master.getMap() == companion.getMap()) {
                skill.getEffect(level).applyTo(master, true);
            }
            if (companion.getMap() != null) {
                companion.getMap().broadcastMessage(PacketCreator.showBuffEffect(companion.getId(), skillId, 1));
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean tryCompanionAttack(CompanionCharacter companionWrapper, Character master) {
        Character companion = companionWrapper.getCharacter();
        MapleMap map = companion.getMap();
        if (map == null) {
            return false;
        }

        Point compPos = companion.getPosition();
        if (compPos == null) {
            return false;
        }

        List<MapObject> mobs = map.getMapObjectsInRange(compPos, 160000.0, Arrays.asList(MapObjectType.MONSTER));
        if (mobs == null || mobs.isEmpty()) {
            return false;
        }

        Monster targetMob = null;
        double minDistance = Double.MAX_VALUE;
        for (MapObject obj : mobs) {
            if (obj instanceof Monster mob && mob.isAlive()) {
                double dist = compPos.distance(mob.getPosition());
                if (dist < minDistance) {
                    minDistance = dist;
                    targetMob = mob;
                }
            }
        }

        if (targetMob != null) {
            int baseLevel = Math.max(1, companion.getLevel());
            int baseDamage = (int) (baseLevel * 45.0 + random.nextInt(Math.max(1, baseLevel * 20)));
            targetMob.damage(companion, baseDamage, false);
            map.broadcastMessage(PacketCreator.showForeignEffect(companion.getId(), 1003));
            return true;
        }

        return false;
    }
}
