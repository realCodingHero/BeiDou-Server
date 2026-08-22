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
import org.gms.server.movement.AbsoluteLifeMovement;
import org.gms.util.PacketCreator;

import java.awt.Point;
import java.util.Arrays;
import java.util.Collections;
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

        // 1. 地图同步与跟随逻辑 (Map Synchronization & Smooth Follow)
        if (masterMap.getId() != compMap.getId()) {
            AccountCompanionManager.getInstance().onMasterChangeMap(master, masterMap, master.getPosition());
            return;
        }

        Point masterPos = master.getPosition();
        Point compPos = companion.getPosition();
        if (masterPos != null && compPos != null) {
            double distance = masterPos.distance(compPos);

            if (distance > 500) {
                // 1. 距离过远瞬移归位 (Teleport Catch-up)
                companionWrapper.clearTrail();
                Point targetPos = new Point(masterPos.x + (random.nextBoolean() ? 40 : -40), masterPos.y);
                if (masterMap.getFootholds() != null) {
                    Point below = masterMap.getGroundBelow(targetPos);
                    if (below != null) {
                        targetPos = below;
                    }
                }
                int fh = 0;
                if (masterMap.getFootholds() != null && masterMap.getFootholds().findBelow(targetPos) != null) {
                    fh = masterMap.getFootholds().findBelow(targetPos).getId();
                }

                companion.setPosition(targetPos);
                byte stance = (byte) (masterPos.x < targetPos.x ? 1 : 0);
                companion.setStance(stance);

                AbsoluteLifeMovement teleMove = new AbsoluteLifeMovement(0, targetPos, 100, stance);
                teleMove.setPixelsPerSecond(new Point(0, 0));
                teleMove.setFh(fh);

                masterMap.broadcastMessage(PacketCreator.showForeignEffect(companion.getId(), 1005));
                masterMap.broadcastMessage(PacketCreator.movePlayer(companion.getId(), Collections.singletonList(teleMove)));
                companionWrapper.setLastMoveTime(now);
            } else if (distance <= 60 && companionWrapper.getTrailHistory().isEmpty()) {
                // 2. 已跟随至主人身边，进入自然待机站立姿态 (Idle Stand)
                if (companion.getStance() != 0 && companion.getStance() != 1) {
                    boolean faceLeft = masterPos.x < compPos.x;
                    byte standStance = (byte) (faceLeft ? 1 : 0);
                    companion.setStance(standStance);

                    int fh = 0;
                    if (masterMap.getFootholds() != null && masterMap.getFootholds().findBelow(compPos) != null) {
                        fh = masterMap.getFootholds().findBelow(compPos).getId();
                    }

                    AbsoluteLifeMovement idleMove = new AbsoluteLifeMovement(0, compPos, 100, standStance);
                    idleMove.setPixelsPerSecond(new Point(0, 0));
                    idleMove.setFh(fh);

                    masterMap.broadcastMessage(PacketCreator.movePlayer(companion.getId(), Collections.singletonList(idleMove)));
                }
            } else if (distance > 50) {
                // 3. 轨迹拟真跟随：根据主人历史动作录制重放或目标逼近 (Natural Path Replay & Navigation)
                int duration = 150; // 150ms 刷新周期
                Point targetPos = null;
                byte moveStance = 0;
                boolean isClimbing = false;

                // 优先从主人留下的移动轨迹中获取下一个路标
                CompanionCharacter.TrailPoint nextPoint = null;
                while (!companionWrapper.getTrailHistory().isEmpty()) {
                    CompanionCharacter.TrailPoint pt = companionWrapper.getTrailHistory().peekFirst();
                    if (pt.mapId != masterMap.getId() || pt.position.distance(compPos) < 22) {
                        companionWrapper.getTrailHistory().pollFirst(); // 已经到达或过期点
                    } else {
                        nextPoint = pt;
                        break;
                    }
                }

                if (nextPoint != null) {
                    // 使用轨迹路径点的姿态进行仿真还原
                    int ptStance = nextPoint.stance;
                    if (ptStance == 10 || ptStance == 11 || ptStance == 12 || ptStance == 13) {
                        // 爬绳/爬梯子动作 (Rope/Ladder Climbing)
                        isClimbing = true;
                        moveStance = (byte) (ptStance == 11 || ptStance == 13 ? 13 : 12);
                    } else if (ptStance == 6 || ptStance == 7 || nextPoint.position.y < compPos.y - 20) {
                        // 跳跃跳台动作 (Jumping / Platform Leaping)
                        moveStance = (byte) (nextPoint.position.x < compPos.x ? 7 : 6);
                    } else {
                        // 走动步态 (Authentic Walking)
                        moveStance = (byte) (nextPoint.position.x < compPos.x ? 3 : 2);
                    }

                    int dx = nextPoint.position.x - compPos.x;
                    int dy = nextPoint.position.y - compPos.y;
                    int stepX = (int) Math.signum(dx) * Math.min(Math.abs(dx), 45);
                    int stepY = (int) Math.signum(dy) * Math.min(Math.abs(dy), isClimbing ? 35 : 45);
                    targetPos = new Point(compPos.x + stepX, compPos.y + stepY);

                    if (targetPos.distance(nextPoint.position) < 25) {
                        companionWrapper.getTrailHistory().pollFirst();
                    }
                } else {
                    // 无轨迹点时，直觉朝向主人靠近
                    boolean toLeft = masterPos.x < compPos.x;
                    int dy = masterPos.y - compPos.y;
                    if (dy < -35) {
                        // 主人在上方平台，施展跳跃向上跟随 (Jump Up)
                        moveStance = (byte) (toLeft ? 7 : 6);
                    } else if (dy > 45) {
                        // 主人在下方平台，下跳跟随 (Jump Down)
                        moveStance = (byte) (toLeft ? 7 : 6);
                    } else {
                        // 平地走动 (Walk)
                        moveStance = (byte) (toLeft ? 3 : 2);
                    }

                    int step = (int) Math.min(Math.abs(masterPos.x - compPos.x) - 40, 45);
                    int targetX = compPos.x + (toLeft ? -step : step);
                    int targetY = masterPos.y;
                    targetPos = new Point(targetX, targetY);
                }

                if (!isClimbing && masterMap.getFootholds() != null) {
                    Point below = masterMap.getGroundBelow(targetPos);
                    if (below != null && Math.abs(below.y - targetPos.y) < 60) {
                        targetPos = below;
                    }
                }

                int fh = 0;
                if (!isClimbing && masterMap.getFootholds() != null && masterMap.getFootholds().findBelow(targetPos) != null) {
                    fh = masterMap.getFootholds().findBelow(targetPos).getId();
                }

                short vx = (short) ((targetPos.x - compPos.x) * 1000 / duration);
                short vy = (short) ((targetPos.y - compPos.y) * 1000 / duration);

                AbsoluteLifeMovement move = new AbsoluteLifeMovement(0, targetPos, duration, moveStance);
                move.setPixelsPerSecond(new Point(vx, vy));
                move.setFh(fh);

                companion.setPosition(targetPos);
                companion.setStance(moveStance);

                masterMap.broadcastMessage(PacketCreator.movePlayer(companion.getId(), Collections.singletonList(move)));
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
