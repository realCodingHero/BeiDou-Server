/*
 This file is part of the OdinMS Maple Story Server
 Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
 Matthias Butz <matze@odinms.de>
 Jan Christian Meyer <vimes@odinms.de>

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU Affero General Public License as
 published by the Free Software Foundation version 3 as published by
 the Free Software Foundation.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.gms.server.quest;

import org.gms.client.Character;
import org.gms.client.QuestStatus;
import org.gms.client.inventory.InventoryType;
import org.gms.constants.id.MobId;
import org.gms.constants.inventory.ItemConstants;
import org.gms.provider.Data;
import org.gms.provider.DataDirectoryEntry;
import org.gms.provider.DataEntity;
import org.gms.provider.DataFileEntry;
import org.gms.provider.DataProvider;
import org.gms.provider.DataProviderFactory;
import org.gms.provider.DataTool;
import org.gms.provider.wz.WZFiles;
import org.gms.server.ItemInformationProvider;
import org.gms.server.life.LifeFactory;
import org.gms.server.life.MonsterInformationProvider;
import org.gms.server.maps.MapFactory;
import org.gms.util.DatabaseConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 任务辅助服务类：提供怪物、物品掉落、NPC 与地图之间的反查与分析服务
 */
public final class QuestHelpService {
    private static final Logger log = LoggerFactory.getLogger(QuestHelpService.class);
    private static final QuestHelpService instance = new QuestHelpService();

    private final Map<Integer, Set<Integer>> mobToMaps = new ConcurrentHashMap<>();
    private final Map<Integer, Set<Integer>> npcToMaps = new ConcurrentHashMap<>();
    private final Map<String, Set<Integer>> nameToMobIds = new ConcurrentHashMap<>();
    private final Map<Integer, String> mobNameCache = new ConcurrentHashMap<>();
    private final Map<Integer, String> npcNameCache = new ConcurrentHashMap<>();
    private final Map<Integer, String> itemNameCache = new ConcurrentHashMap<>();
    private final Map<Integer, MapLocation> mapLocationCache = new ConcurrentHashMap<>();
    private final Map<Integer, List<MapLocation>> mobMapsCache = new ConcurrentHashMap<>();
    private final Map<Integer, List<MapLocation>> npcMapsCache = new ConcurrentHashMap<>();
    private final Map<Integer, List<DropMobInfo>> itemDropMobsCache = new ConcurrentHashMap<>();

    private final AtomicBoolean initialized = new AtomicBoolean(false);

    public static QuestHelpService getInstance() {
        return instance;
    }

    private QuestHelpService() {
    }

    public void ensureInitialized() {
        if (!initialized.compareAndSet(false, true)) {
            return;
        }
        long start = System.currentTimeMillis();
        try {
            DataProvider mapSource = DataProviderFactory.getDataProvider(WZFiles.MAP);
            if (mapSource != null && mapSource.getRoot() != null) {
                scanDir(mapSource, mapSource.getRoot());
            }
        } catch (Exception e) {
            log.error("Error scanning Map.wz for life index", e);
        }

        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT life, type, map FROM plife");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int lifeId = rs.getInt("life");
                String type = rs.getString("type");
                int mapId = rs.getInt("map");
                if ("m".equalsIgnoreCase(type)) {
                    mobToMaps.computeIfAbsent(lifeId, k -> ConcurrentHashMap.newKeySet()).add(mapId);
                } else if ("n".equalsIgnoreCase(type)) {
                    npcToMaps.computeIfAbsent(lifeId, k -> ConcurrentHashMap.newKeySet()).add(mapId);
                }
            }
        } catch (SQLException e) {
            log.warn("Failed to load plife table for quest help service", e);
        }

        // 构建怪物名称倒排索引，用于极速 O(1) 反查同名任务变种怪的野外地图
        for (int mobId : mobToMaps.keySet()) {
            String name = getMobName(mobId);
            if (name != null && !name.isBlank() && !name.startsWith("怪物 ") && !"MISSINGNO".equals(name)) {
                nameToMobIds.computeIfAbsent(name, k -> ConcurrentHashMap.newKeySet()).add(mobId);
            }
        }

        log.info("QuestHelpService initialized life index in {}ms. Indexed {} mobs, {} NPCs",
                System.currentTimeMillis() - start, mobToMaps.size(), npcToMaps.size());
    }

    private void scanDir(DataProvider mapSource, DataEntity entity) {
        if (entity instanceof DataDirectoryEntry dir) {
            dir.getFiles().parallelStream().forEach(fileEntry -> scanFile(mapSource, fileEntry));
            dir.getSubdirectories().parallelStream().forEach(subDir -> {
                if (subDir.getName().startsWith("Map") || subDir.getName().startsWith("map")) {
                    scanDir(mapSource, subDir);
                }
            });
        }
    }

    private void scanFile(DataProvider mapSource, DataFileEntry fileEntry) {
        String fileName = fileEntry.getName();
        if (!fileName.endsWith(".img")) {
            return;
        }
        int mapId;
        try {
            mapId = Integer.parseInt(fileName.substring(0, fileName.length() - 4));
        } catch (NumberFormatException e) {
            return;
        }

        StringBuilder pathBuilder = new StringBuilder();
        resolvePath(mapSource, fileEntry, pathBuilder);
        pathBuilder.append(fileEntry.getName());

        Data mapData = mapSource.getData(pathBuilder.toString());
        if (mapData == null) {
            return;
        }

        Data lifeData = mapData.getChildByPath("life");
        if (lifeData == null) {
            return;
        }

        for (Data child : lifeData.getChildren()) {
            String type = DataTool.getString("type", child, "");
            int lifeId = DataTool.getInt("id", child, -1);
            if (lifeId <= 0) {
                String idStr = DataTool.getString("id", child, null);
                if (idStr != null) {
                    try {
                        lifeId = Integer.parseInt(idStr);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }

            if (lifeId > 0) {
                if ("m".equalsIgnoreCase(type)) {
                    mobToMaps.computeIfAbsent(lifeId, k -> ConcurrentHashMap.newKeySet()).add(mapId);
                } else if ("n".equalsIgnoreCase(type)) {
                    npcToMaps.computeIfAbsent(lifeId, k -> ConcurrentHashMap.newKeySet()).add(mapId);
                }
            }
        }
    }

    private void resolvePath(DataProvider mapSource, DataEntity dataEntry, StringBuilder pathBuilder) {
        DataEntity parent = dataEntry.getParent();
        if (parent != null && parent != mapSource.getRoot()) {
            pathBuilder.insert(0, parent.getName() + "/");
            resolvePath(mapSource, parent, pathBuilder);
        }
    }

    public MapLocation getMapLocation(int mapId) {
        return mapLocationCache.computeIfAbsent(mapId, id ->
                new MapLocation(id, MapFactory.loadPlaceName(id), MapFactory.loadStreetName(id))
        );
    }

    public List<MapLocation> getMapsForMob(int mobId) {
        ensureInitialized();
        List<MapLocation> cached = mobMapsCache.get(mobId);
        if (cached != null) {
            return cached;
        }

        Set<Integer> mapIds = mobToMaps.get(mobId);
        if (mapIds == null || mapIds.isEmpty()) {
            Set<Integer> combined = new HashSet<>();
            // 1. 常见任务别名怪物映射 (如绿蘑菇、僵尸蘑菇、幽灵树桩等)
            if (mobId == MobId.GREEN_MUSHROOM_QUEST) {
                Set<Integer> m1 = mobToMaps.get(MobId.GREEN_MUSHROOM);
                if (m1 != null) combined.addAll(m1);
                Set<Integer> m2 = mobToMaps.get(MobId.DEJECTED_GREEN_MUSHROOM);
                if (m2 != null) combined.addAll(m2);
            } else if (mobId == MobId.ZOMBIE_MUSHROOM_QUEST) {
                Set<Integer> m1 = mobToMaps.get(MobId.ZOMBIE_MUSHROOM);
                if (m1 != null) combined.addAll(m1);
                Set<Integer> m2 = mobToMaps.get(MobId.ANNOYED_ZOMBIE_MUSHROOM);
                if (m2 != null) combined.addAll(m2);
            } else if (mobId == MobId.GHOST_STUMP_QUEST) {
                Set<Integer> m1 = mobToMaps.get(MobId.GHOST_STUMP);
                if (m1 != null) combined.addAll(m1);
                Set<Integer> m2 = mobToMaps.get(MobId.SMIRKING_GHOST_STUMP);
                if (m2 != null) combined.addAll(m2);
            }

            // 2. 名称回退机制：若为特殊任务变种怪，在已索引的野外怪中查找同名怪物的地图 (O(1) 倒排索引查找)
            if (combined.isEmpty()) {
                String targetName = getMobName(mobId);
                if (targetName != null && !targetName.isBlank() && !targetName.startsWith("怪物 ") && !"MISSINGNO".equals(targetName)) {
                    Set<Integer> sameNameMobIds = nameToMobIds.get(targetName);
                    if (sameNameMobIds != null) {
                        for (int otherMobId : sameNameMobIds) {
                            if (otherMobId != mobId) {
                                Set<Integer> otherMaps = mobToMaps.get(otherMobId);
                                if (otherMaps != null) {
                                    combined.addAll(otherMaps);
                                }
                            }
                        }
                    }
                }
            }

            if (!combined.isEmpty()) {
                mapIds = combined;
            }
        }

        if (mapIds == null || mapIds.isEmpty()) {
            List<MapLocation> empty = Collections.emptyList();
            mobMapsCache.put(mobId, empty);
            return empty;
        }

        List<MapLocation> result = new ArrayList<>(mapIds.size());
        for (int mapId : mapIds) {
            result.add(getMapLocation(mapId));
        }
        result.sort(Comparator.comparingInt(MapLocation::getMapId));
        List<MapLocation> unmod = Collections.unmodifiableList(result);
        mobMapsCache.put(mobId, unmod);
        return unmod;
    }

    public List<MapLocation> getMapsForNpc(int npcId) {
        ensureInitialized();
        List<MapLocation> cached = npcMapsCache.get(npcId);
        if (cached != null) {
            return cached;
        }

        Set<Integer> mapIds = npcToMaps.get(npcId);
        if (mapIds == null || mapIds.isEmpty()) {
            List<MapLocation> empty = Collections.emptyList();
            npcMapsCache.put(npcId, empty);
            return empty;
        }

        List<MapLocation> result = new ArrayList<>(mapIds.size());
        for (int mapId : mapIds) {
            result.add(getMapLocation(mapId));
        }
        result.sort(Comparator.comparingInt(MapLocation::getMapId));
        List<MapLocation> unmod = Collections.unmodifiableList(result);
        npcMapsCache.put(npcId, unmod);
        return unmod;
    }

    public List<DropMobInfo> getDropMobsForItem(int itemId) {
        ensureInitialized();
        return itemDropMobsCache.computeIfAbsent(itemId, this::loadDropMobsForItem);
    }

    private List<DropMobInfo> loadDropMobsForItem(int itemId) {
        List<DropMobInfo> dropMobs = new ArrayList<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT dropperid, chance FROM drop_data WHERE itemid = ? AND dropperid > 0 ORDER BY chance DESC LIMIT 30")) {
            ps.setInt(1, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int mobId = rs.getInt("dropperid");
                    int chance = rs.getInt("chance");
                    String mobName = getMobName(mobId);
                    String chanceText;
                    if (chance >= 1000000) {
                        chanceText = "100%";
                    } else if (chance > 0) {
                        double percent = chance / 10000.0;
                        if (percent < 0.01) {
                            chanceText = String.format("1/%d", Math.max(1, 1000000 / chance));
                        } else {
                            chanceText = String.format("%.2f%%", percent);
                        }
                    } else {
                        chanceText = "极低";
                    }
                    List<MapLocation> maps = getMapsForMob(mobId);
                    dropMobs.add(new DropMobInfo(mobId, mobName, chance, chanceText, maps));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to query drop data for itemId: {}", itemId, e);
        }
        return Collections.unmodifiableList(dropMobs);
    }

    public List<QuestSummary> getStartedQuestSummaries(Character player) {
        if (player == null) {
            return Collections.emptyList();
        }
        List<QuestSummary> list = new ArrayList<>();
        for (QuestStatus qs : player.getStartedQuests()) {
            Quest q = qs.getQuest();
            if (q == null) continue;
            String name = q.getName();
            if (name == null || name.isBlank()) {
                name = "任务 " + q.getId();
            }
            list.add(new QuestSummary(q.getId(), name));
        }
        list.sort(Comparator.comparingInt(QuestSummary::getQuestId));
        return Collections.unmodifiableList(list);
    }

    public QuestDetailInfo getQuestDetail(Character player, int questId) {
        if (player == null) {
            return null;
        }
        QuestStatus qs = player.getQuest(Quest.getInstance(questId));
        if (qs == null) {
            return null;
        }
        Quest q = qs.getQuest();
        if (q == null) {
            return null;
        }

        String questName = q.getName();
        if (questName == null || questName.isBlank()) {
            questName = "任务 " + questId;
        }

        // 1. NPC Info
        int startNpcId = q.getNpcRequirement(false);
        NpcLocationInfo startNpc = null;
        if (startNpcId > 0) {
            startNpc = new NpcLocationInfo(startNpcId, getNPCName(startNpcId), 0, getMapsForNpc(startNpcId));
        }

        int completeNpcId = q.getNpcRequirement(true);
        NpcLocationInfo completeNpc = null;
        if (completeNpcId > 0) {
            completeNpc = new NpcLocationInfo(completeNpcId, getNPCName(completeNpcId), 1, getMapsForNpc(completeNpcId));
        }

        // 2. Mob Objectives
        List<MobObjective> mobObjectives = new ArrayList<>();
        Map<Integer, Integer> reqMobs = new HashMap<>(q.getRequiredMobs());
        if (reqMobs.isEmpty() && !q.getRelevantMobs().isEmpty()) {
            for (int mobId : q.getRelevantMobs()) {
                int count = q.getMobAmountNeeded(mobId);
                if (count > 0) {
                    reqMobs.put(mobId, count);
                }
            }
        }

        for (Map.Entry<Integer, Integer> entry : reqMobs.entrySet()) {
            int mobId = entry.getKey();
            int reqCount = entry.getValue();
            int currentKills = parseProgress(qs.getProgress(mobId));
            List<MapLocation> maps = getMapsForMob(mobId);
            mobObjectives.add(new MobObjective(mobId, getMobName(mobId), currentKills, reqCount, maps));
        }
        mobObjectives.sort(Comparator.comparingInt(MobObjective::getMobId));

        // 3. Item Objectives
        List<ItemObjective> itemObjectives = new ArrayList<>();
        Map<Integer, Integer> reqItems = q.getRequiredItems();
        for (Map.Entry<Integer, Integer> entry : reqItems.entrySet()) {
            int itemId = entry.getKey();
            int reqCount = entry.getValue();
            InventoryType iType = ItemConstants.getInventoryType(itemId);
            int currentCount = 0;
            if (iType != null && !iType.equals(InventoryType.UNDEFINED) && player.getInventory(iType) != null) {
                currentCount = player.getInventory(iType).countById(itemId);
            }
            List<DropMobInfo> dropMobs = getDropMobsForItem(itemId);
            itemObjectives.add(new ItemObjective(itemId, getItemName(itemId), currentCount, reqCount, dropMobs));
        }
        itemObjectives.sort(Comparator.comparingInt(ItemObjective::getItemId));

        return new QuestDetailInfo(questId, questName, startNpc, completeNpc, mobObjectives, itemObjectives);
    }

    private static int parseProgress(String progress) {
        if (progress == null || progress.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(progress);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String getMobName(int mobId) {
        return mobNameCache.computeIfAbsent(mobId, id -> {
            String name = MonsterInformationProvider.getInstance().getMobNameFromId(id);
            return (name == null || name.isBlank()) ? "怪物 " + id : name;
        });
    }

    private String getNPCName(int npcId) {
        return npcNameCache.computeIfAbsent(npcId, id -> {
            String name = LifeFactory.getNPCName(id);
            return (name == null || name.isBlank() || "MISSINGNO".equals(name)) ? "NPC " + id : name;
        });
    }

    private String getItemName(int itemId) {
        return itemNameCache.computeIfAbsent(itemId, id -> {
            String name = ItemInformationProvider.getInstance().getName(id);
            return (name == null || name.isBlank()) ? "道具 " + id : name;
        });
    }

    // Models
    public static class QuestSummary {
        private final int questId;
        private final String questName;

        public QuestSummary(int questId, String questName) {
            this.questId = questId;
            this.questName = questName;
        }

        public int getQuestId() {
            return questId;
        }

        public String getQuestName() {
            return questName;
        }
    }

    public static class MapLocation {
        private final int mapId;
        private final String mapName;
        private final String streetName;

        public MapLocation(int mapId, String mapName, String streetName) {
            this.mapId = mapId;
            this.mapName = mapName != null ? mapName : "";
            this.streetName = streetName != null ? streetName : "";
        }

        public int getMapId() {
            return mapId;
        }

        public String getMapName() {
            return mapName;
        }

        public String getStreetName() {
            return streetName;
        }

        public String getDisplayName() {
            if (!streetName.isBlank() && !mapName.isBlank()) {
                if (streetName.equals(mapName)) {
                    return mapName + " (" + mapId + ")";
                }
                return streetName + " - " + mapName + " (" + mapId + ")";
            }
            if (!mapName.isBlank()) {
                return mapName + " (" + mapId + ")";
            }
            if (!streetName.isBlank()) {
                return streetName + " (" + mapId + ")";
            }
            return "地图 (" + mapId + ")";
        }
    }

    public static class DropMobInfo {
        private final int mobId;
        private final String mobName;
        private final int chance;
        private final String chanceText;
        private final List<MapLocation> maps;

        public DropMobInfo(int mobId, String mobName, int chance, String chanceText, List<MapLocation> maps) {
            this.mobId = mobId;
            this.mobName = mobName;
            this.chance = chance;
            this.chanceText = chanceText;
            this.maps = maps != null ? maps : Collections.emptyList();
        }

        public int getMobId() {
            return mobId;
        }

        public String getMobName() {
            return mobName;
        }

        public int getChance() {
            return chance;
        }

        public String getChanceText() {
            return chanceText;
        }

        public List<MapLocation> getMaps() {
            return maps;
        }
    }

    public static class MobObjective {
        private final int mobId;
        private final String mobName;
        private final int currentKills;
        private final int requiredKills;
        private final List<MapLocation> maps;

        public MobObjective(int mobId, String mobName, int currentKills, int requiredKills, List<MapLocation> maps) {
            this.mobId = mobId;
            this.mobName = mobName;
            this.currentKills = currentKills;
            this.requiredKills = requiredKills;
            this.maps = maps != null ? maps : Collections.emptyList();
        }

        public int getMobId() {
            return mobId;
        }

        public String getMobName() {
            return mobName;
        }

        public int getCurrentKills() {
            return currentKills;
        }

        public int getRequiredKills() {
            return requiredKills;
        }

        public boolean isCompleted() {
            return currentKills >= requiredKills;
        }

        public List<MapLocation> getMaps() {
            return maps;
        }
    }

    public static class ItemObjective {
        private final int itemId;
        private final String itemName;
        private final int currentCount;
        private final int requiredCount;
        private final List<DropMobInfo> dropMobs;

        public ItemObjective(int itemId, String itemName, int currentCount, int requiredCount, List<DropMobInfo> dropMobs) {
            this.itemId = itemId;
            this.itemName = itemName;
            this.currentCount = currentCount;
            this.requiredCount = requiredCount;
            this.dropMobs = dropMobs != null ? dropMobs : Collections.emptyList();
        }

        public int getItemId() {
            return itemId;
        }

        public String getItemName() {
            return itemName;
        }

        public int getCurrentCount() {
            return currentCount;
        }

        public int getRequiredCount() {
            return requiredCount;
        }

        public boolean isCompleted() {
            return currentCount >= requiredCount;
        }

        public List<DropMobInfo> getDropMobs() {
            return dropMobs;
        }
    }

    public static class NpcLocationInfo {
        private final int npcId;
        private final String npcName;
        private final int type; // 0: start, 1: complete
        private final List<MapLocation> maps;

        public NpcLocationInfo(int npcId, String npcName, int type, List<MapLocation> maps) {
            this.npcId = npcId;
            this.npcName = npcName;
            this.type = type;
            this.maps = maps != null ? maps : Collections.emptyList();
        }

        public int getNpcId() {
            return npcId;
        }

        public String getNpcName() {
            return npcName;
        }

        public int getType() {
            return type;
        }

        public List<MapLocation> getMaps() {
            return maps;
        }
    }

    public static class QuestDetailInfo {
        private final int questId;
        private final String questName;
        private final NpcLocationInfo startNpc;
        private final NpcLocationInfo completeNpc;
        private final List<MobObjective> mobObjectives;
        private final List<ItemObjective> itemObjectives;

        public QuestDetailInfo(int questId, String questName, NpcLocationInfo startNpc, NpcLocationInfo completeNpc,
                               List<MobObjective> mobObjectives, List<ItemObjective> itemObjectives) {
            this.questId = questId;
            this.questName = questName;
            this.startNpc = startNpc;
            this.completeNpc = completeNpc;
            this.mobObjectives = mobObjectives != null ? mobObjectives : Collections.emptyList();
            this.itemObjectives = itemObjectives != null ? itemObjectives : Collections.emptyList();
        }

        public int getQuestId() {
            return questId;
        }

        public String getQuestName() {
            return questName;
        }

        public NpcLocationInfo getStartNpc() {
            return startNpc;
        }

        public NpcLocationInfo getCompleteNpc() {
            return completeNpc;
        }

        public List<MobObjective> getMobObjectives() {
            return mobObjectives;
        }

        public List<ItemObjective> getItemObjectives() {
            return itemObjectives;
        }
    }
}
