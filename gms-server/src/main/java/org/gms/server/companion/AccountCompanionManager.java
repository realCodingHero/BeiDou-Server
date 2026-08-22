package org.gms.server.companion;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.dao.entity.CharactersDO;
import org.gms.manager.ServerManager;
import org.gms.net.server.world.Party;
import org.gms.server.maps.MapleMap;
import org.gms.service.CharacterService;
import org.gms.util.I18nUtil;
import org.gms.util.PacketCreator;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public class AccountCompanionManager {
    @Getter
    private static final AccountCompanionManager instance = new AccountCompanionManager();

    private final Map<Integer, List<CompanionCharacter>> activeCompanions = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> companionToMasterMap = new ConcurrentHashMap<>();
    private final Map<Integer, Long> lastTagTimeMap = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Companion-AI-Worker");
        t.setDaemon(true);
        return t;
    });

    private AccountCompanionManager() {
        scheduler.scheduleWithFixedDelay(this::tickAll, 150, 150, TimeUnit.MILLISECONDS);
    }

    private void tickAll() {
        try {
            for (Map.Entry<Integer, List<CompanionCharacter>> entry : activeCompanions.entrySet()) {
                int masterId = entry.getKey();
                List<CompanionCharacter> list = entry.getValue();
                if (list == null || list.isEmpty()) {
                    continue;
                }

                Character master = null;
                for (CompanionCharacter comp : list) {
                    if (comp.getCharacter() != null && comp.getCharacter().getMap() != null) {
                        master = comp.getCharacter().getMap().getCharacterById(masterId);
                        if (master != null) {
                            break;
                        }
                    }
                }

                if (master == null) {
                    continue;
                }

                for (CompanionCharacter comp : list) {
                    CompanionAIController.tickCompanion(comp, master);
                }
            }
        } catch (Exception e) {
            log.error("Companion AI tick loop error", e);
        }
    }

    public List<CharactersDO> getAvailableAccountCharacters(Character master) {
        if (master == null) {
            return Collections.emptyList();
        }
        CharacterService characterService = ServerManager.getApplicationContext().getBean(CharacterService.class);
        List<CharactersDO> allChars = characterService.getCharacterByAccountId(master.getAccountId());
        if (allChars == null) {
            return Collections.emptyList();
        }

        List<CharactersDO> available = new ArrayList<>();
        for (CharactersDO cdo : allChars) {
            if (cdo.getId() != master.getId() && !companionToMasterMap.containsKey(cdo.getId())) {
                available.add(cdo);
            }
        }
        return available;
    }

    public synchronized boolean summonCompanion(Character master, int companionCharId) {
        if (master == null || !master.isLoggedIn() || master.getMap() == null) {
            return false;
        }

        List<CompanionCharacter> currentList = activeCompanions.computeIfAbsent(master.getId(), k -> new ArrayList<>());
        if (currentList.size() >= 3) {
            master.dropMessage(5, "随从队伍已满（最多同时召唤 3 个随从）！");
            return false;
        }

        if (companionToMasterMap.containsKey(companionCharId)) {
            master.dropMessage(5, "该角色当前已在出战状态！");
            return false;
        }

        CharacterService characterService = ServerManager.getApplicationContext().getBean(CharacterService.class);
        CharactersDO cdo = characterService.findById(companionCharId);
        if (cdo == null || cdo.getAccountid() != master.getAccountId()) {
            master.dropMessage(5, "无法召唤非同账号下的角色！");
            return false;
        }

        try {
            Character compChr = characterService.loadCharFromDB(companionCharId, master.getClient(), true);
            if (compChr == null) {
                return false;
            }

            CompanionCharacter compWrapper = new CompanionCharacter(compChr, master.getId());
            currentList.add(compWrapper);
            companionToMasterMap.put(companionCharId, master.getId());

            MapleMap map = master.getMap();
            compChr.setMap(map);
            Point masterPos = master.getPosition();
            Point spawnPos = new Point(masterPos.x + 35, masterPos.y);
            if (map.getFootholds() != null) {
                Point below = map.getGroundBelow(spawnPos);
                if (below != null) {
                    spawnPos = below;
                }
            }
            compChr.setPosition(spawnPos);
            compChr.setStance(0);

            if (master.getClient() != null && master.getClient().getChannelServer() != null) {
                master.getClient().getChannelServer().getPlayerStorage().addPlayer(compChr);
            }

            map.addPlayer(compChr);

            // 自动组队整合
            Party party = master.getParty();
            if (party == null) {
                Party.createParty(master, true);
            }
            if (master.getParty() != null) {
                Party.joinParty(compChr, master.getParty().getId(), true);
            }

            // 同步随从头顶组队血条
            master.sendPacket(PacketCreator.updatePartyMemberHP(compChr.getId(), compChr.getHp(), compChr.getCurrentMaxHp()));

            master.dropMessage(5, "成功召唤同账号伙伴 【" + compChr.getName() + "】！");
            return true;
        } catch (Exception e) {
            log.error("Summon companion failed for master {} compId {}", master.getId(), companionCharId, e);
            master.dropMessage(5, "召唤随从失败，请稍后重试。");
            return false;
        }
    }

    public synchronized boolean dismissCompanion(Character master, int companionCharId) {
        if (master == null) {
            return false;
        }
        List<CompanionCharacter> list = activeCompanions.get(master.getId());
        if (list == null || list.isEmpty()) {
            return false;
        }

        CompanionCharacter target = null;
        for (CompanionCharacter comp : list) {
            if (comp.getId() == companionCharId) {
                target = comp;
                break;
            }
        }

        if (target == null) {
            return false;
        }

        Character compChr = target.getCharacter();
        if (compChr != null) {
            if (compChr.getMap() != null) {
                compChr.getMap().removePlayer(compChr);
            }

            if (master.getClient() != null && master.getClient().getChannelServer() != null) {
                master.getClient().getChannelServer().getPlayerStorage().removePlayer(compChr.getId());
            }

            // 保存数据
            try {
                CharacterService characterService = ServerManager.getApplicationContext().getBean(CharacterService.class);
                characterService.saveCharToDB(compChr, false);
            } catch (Exception e) {
                log.error("Save companion to DB error", e);
            }
        }

        list.remove(target);
        companionToMasterMap.remove(companionCharId);
        master.dropMessage(5, "伙伴 【" + target.getName() + "】 已解散并保存数据！");
        return true;
    }

    public synchronized boolean dismissAllCompanions(Character master) {
        if (master == null) {
            return false;
        }
        List<CompanionCharacter> list = activeCompanions.remove(master.getId());
        if (list == null || list.isEmpty()) {
            return true;
        }

        CharacterService characterService = ServerManager.getApplicationContext().getBean(CharacterService.class);
        for (CompanionCharacter comp : list) {
            Character compChr = comp.getCharacter();
            if (compChr != null) {
                if (compChr.getMap() != null) {
                    compChr.getMap().removePlayer(compChr);
                }
                if (master.getClient() != null && master.getClient().getChannelServer() != null) {
                    master.getClient().getChannelServer().getPlayerStorage().removePlayer(compChr.getId());
                }
                try {
                    characterService.saveCharToDB(compChr, false);
                } catch (Exception e) {
                    log.error("Save companion on dismiss all error", e);
                }
            }
            companionToMasterMap.remove(comp.getId());
        }
        return true;
    }

    public synchronized boolean hotSwitchCharacter(Character master, int targetCompanionCharId) {
        if (master == null || !master.isLoggedIn() || master.getMap() == null) {
            return false;
        }

        long now = System.currentTimeMillis();
        long lastTag = lastTagTimeMap.getOrDefault(master.getAccountId(), 0L);
        if (now - lastTag < 2500) {
            master.dropMessage(5, "角色切换冷却中，请稍候...");
            return false;
        }

        List<CompanionCharacter> list = activeCompanions.get(master.getId());
        if (list == null || list.isEmpty()) {
            master.dropMessage(5, "当前未召唤任何随从，无法切换！");
            return false;
        }

        CompanionCharacter targetWrapper = null;
        for (CompanionCharacter comp : list) {
            if (comp.getId() == targetCompanionCharId) {
                targetWrapper = comp;
                break;
            }
        }

        if (targetWrapper == null) {
            master.dropMessage(5, "找不到指定的目标随从！");
            return false;
        }

        Character targetChr = targetWrapper.getCharacter();
        if (targetChr == null || targetChr.getHp() <= 0) {
            master.dropMessage(5, "目标随从处于阵亡或虚弱状态，无法切入！");
            return false;
        }

        try {
            Client client = master.getClient();
            MapleMap map = master.getMap();

            // 1. 原主控 A 从本地玩家降级为随从
            Character oldMaster = master;
            list.remove(targetWrapper);
            companionToMasterMap.remove(targetCompanionCharId);

            CompanionCharacter newCompWrapper = new CompanionCharacter(oldMaster, targetChr.getId());
            list.add(newCompWrapper);
            companionToMasterMap.put(oldMaster.getId(), targetChr.getId());

            // 2. 迁移随从映射键
            activeCompanions.remove(oldMaster.getId());
            activeCompanions.put(targetChr.getId(), list);

            // 更新所有子随从的 masterId
            for (CompanionCharacter c : list) {
                c.setMasterCharacterId(targetChr.getId());
            }

            // 3. 切换 Client 会话绑定
            targetChr.setClient(client);
            client.setPlayer(targetChr);

            // 4. 下发原地 SetField (getCharInfo) 数据包重建客户端本地玩家
            client.sendPacket(PacketCreator.getCharInfo(targetChr));
            targetChr.silentPartyUpdate();

            lastTagTimeMap.put(master.getAccountId(), now);
            targetChr.dropMessage(5, "已切换主控角色为 【" + targetChr.getName() + "】！");
            return true;
        } catch (Exception e) {
            log.error("Hot switch character error from master {} to comp {}", master.getId(), targetCompanionCharId, e);
            master.dropMessage(5, "切换角色异常，请稍后重试。");
            return false;
        }
    }

    public void onMasterChangeMap(Character master, MapleMap toMap, Point targetPos) {
        if (master == null || toMap == null) {
            return;
        }
        List<CompanionCharacter> list = activeCompanions.get(master.getId());
        if (list == null || list.isEmpty()) {
            return;
        }

        for (CompanionCharacter comp : list) {
            Character compChr = comp.getCharacter();
            if (compChr != null) {
                MapleMap oldMap = compChr.getMap();
                if (oldMap != null) {
                    oldMap.removePlayer(compChr);
                }
                compChr.setMap(toMap);
                Point spawnPos = targetPos != null ? targetPos : master.getPosition();
                if (toMap.getFootholds() != null) {
                    Point below = toMap.getGroundBelow(spawnPos);
                    if (below != null) {
                        spawnPos = below;
                    }
                }
                compChr.setPosition(spawnPos);
                compChr.setStance(0);
                toMap.addPlayer(compChr);
            }
        }
    }

    public void onMasterLogout(Character master) {
        if (master == null) {
            return;
        }
        dismissAllCompanions(master);
    }

    public List<CompanionCharacter> getCompanions(Character master) {
        if (master == null) {
            return Collections.emptyList();
        }
        List<CompanionCharacter> list = activeCompanions.get(master.getId());
        return list != null ? list : Collections.emptyList();
    }

    public boolean isCompanion(int charId) {
        return companionToMasterMap.containsKey(charId);
    }
}
