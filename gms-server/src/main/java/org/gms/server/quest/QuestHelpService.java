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
    private final Map<Integer, Boolean> regularMaterialCache = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> materialUnitPriceCache = new ConcurrentHashMap<>();

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

    private void resolvePath(DataProvider mapSource, DataEntity fileEntry, StringBuilder pathBuilder) {
        DataEntity parent = fileEntry.getParent();
        if (parent != null && parent != mapSource.getRoot()) {
            resolvePath(mapSource, parent, pathBuilder);
            pathBuilder.append(parent.getName()).append("/");
        }
    }

    public MapLocation getMapLocation(int mapId) {
        return mapLocationCache.computeIfAbsent(mapId, id -> {
            String mapName = MapFactory.loadPlaceName(id);
            String streetName = MapFactory.loadStreetName(id);
            return new MapLocation(id, mapName, streetName);
        });
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
                     "SELECT dropperid, chance FROM drop_data WHERE itemid = ? AND dropperid > 0 ORDER BY chance DESC LIMIT 60")) {
            ps.setInt(1, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int mobId = rs.getInt("dropperid");
                    int chance = rs.getInt("chance");
                    String mobName = getMobName(mobId);
                    boolean isBoss = MonsterInformationProvider.getInstance().isBoss(mobId);
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
                    dropMobs.add(new DropMobInfo(mobId, mobName, chance, chanceText, isBoss, maps));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to query drop data for itemId: {}", itemId, e);
        }
        return Collections.unmodifiableList(dropMobs);
    }

    /**
     * 判断道具是否为单纯由普通怪物掉落的普通杂物/消耗品材料（可供任务辅助快捷补齐）
     * 允许：普通 ETC 杂物材料、普通 USE 消耗品道具
     * 严格排除：装备、商城道具、任务专属(403xxxx/quest标记)、不可出售/不可交易/唯一道具、仅Boss掉落/仅箱子掉落道具
     */
    public boolean isRegularMonsterMaterial(int itemId) {
        return regularMaterialCache.computeIfAbsent(itemId, id -> {
            // 1. 类别必须为普通 ETC 杂物 或 普通 USE 消耗品（排除装备 1xxxxxx、商城 5xxxxxx、专属任务道具 403xxxx）
            InventoryType invType = ItemConstants.getInventoryType(id);
            if (invType != InventoryType.ETC && invType != InventoryType.USE && invType != InventoryType.SETUP) {
                return false;
            }
            if (id >= 4030000 && id < 4040000) {
                return false;
            }

            ItemInformationProvider ii = ItemInformationProvider.getInstance();
            if (ii.isQuestItem(id) || ii.isPartyQuestItem(id) || ii.isPickupRestricted(id) ||
                ii.isUntradeableRestricted(id) || ii.isAccountRestricted(id) || ii.isDropRestricted(id)) {
                return false;
            }

            Data itemData = ii.getItemData(id);
            if (itemData != null) {
                int notSale = DataTool.getIntConvert("info/notSale", itemData, 0);
                int tradeBlock = DataTool.getIntConvert("info/tradeBlock", itemData, 0);
                int only = DataTool.getIntConvert("info/only", itemData, 0);
                if (notSale == 1 || tradeBlock == 1 || only == 1) {
                    return false;
                }
            }

            // 2. 检查掉落来源：必须存在非 Boss 的普通野外怪物掉落
            List<DropMobInfo> dropMobs = getDropMobsForItem(id);
            if (dropMobs == null || dropMobs.isEmpty()) {
                return false;
            }

            MonsterInformationProvider mip = MonsterInformationProvider.getInstance();
            boolean hasRegularMob = false;
            for (DropMobInfo mob : dropMobs) {
                int mobId = mob.getMobId();
                if (mobId > 0 && !mip.isBoss(mobId)) {
                    if (mob.getMaps() != null && !mob.getMaps().isEmpty()) {
                        hasRegularMob = true;
                        break;
                    }
                }
            }

            return hasRegularMob;
        });
    }

    /**
     * 判断玩家是否已达成该任务的全部完成条件（杀怪、道具收集等），可前往交付
     * 传入该任务预期的完成 NPC ID，以便正确通过底层 NpcRequirement 校验
     */
    public boolean isQuestCompletable(Character player, Quest q) {
        if (player == null || q == null) {
            return false;
        }
        int completeNpcId = q.getNpcRequirement(true);
        Integer npcId = completeNpcId > 0 ? completeNpcId : null;
        return q.canComplete(player, npcId);
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
            boolean canComplete = isQuestCompletable(player, q);
            list.add(new QuestSummary(q.getId(), name, canComplete));
        }
        list.sort(Comparator.comparingInt(QuestSummary::getQuestId));
        return Collections.unmodifiableList(list);
    }

    public List<QuestSummary> getCompletableQuestSummaries(Character player) {
        List<QuestSummary> list = new ArrayList<>();
        for (QuestSummary s : getStartedQuestSummaries(player)) {
            if (s.isCanComplete()) {
                list.add(s);
            }
        }
        return Collections.unmodifiableList(list);
    }

    public List<QuestSummary> getInProgressQuestSummaries(Character player) {
        List<QuestSummary> list = new ArrayList<>();
        for (QuestSummary s : getStartedQuestSummaries(player)) {
            if (!s.isCanComplete()) {
                list.add(s);
            }
        }
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
        boolean canComplete = isQuestCompletable(player, q);

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
            boolean isBoss = MonsterInformationProvider.getInstance().isBoss(mobId);
            List<MapLocation> maps = getMapsForMob(mobId);
            mobObjectives.add(new MobObjective(mobId, getMobName(mobId), currentKills, reqCount, isBoss, maps));
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
            boolean deliverable = isRegularMonsterMaterial(itemId);
            int unitPrice = deliverable ? getMaterialUnitPrice(itemId) : 0;
            List<DropMobInfo> dropMobs = getDropMobsForItem(itemId);
            itemObjectives.add(new ItemObjective(itemId, getItemName(itemId), currentCount, reqCount, deliverable, unitPrice, dropMobs));
        }
        itemObjectives.sort(Comparator.comparingInt(ItemObjective::getItemId));

        return new QuestDetailInfo(questId, questName, canComplete, startNpc, completeNpc, mobObjectives, itemObjectives);
    }

    /**
     * 获取普通怪物材料的辅助系统出售单价（基于商店回收价、掉落怪物等级与爆率稀缺度综合计算）
     * 公式：
     * BasePrice = wholePrice * 20 + mobLevel * 30
     * RarityFactor = clamp((500,000 / chance)^0.90, 0.75, 10.0)
     * UnitPrice = max(20, round(BasePrice * RarityFactor))
     */
    public int getMaterialUnitPrice(int itemId) {
        return materialUnitPriceCache.computeIfAbsent(itemId, id -> {
            ItemInformationProvider ii = ItemInformationProvider.getInstance();
            int wholePrice = ii.getWholePrice(id);
            if (wholePrice <= 0) {
                wholePrice = 1;
            }

            int bestMobLevel = 10;
            int bestChance = 500000; // 默认 50% 爆率
            boolean foundMob = false;

            List<DropMobInfo> dropMobs = getDropMobsForItem(id);
            if (dropMobs != null && !dropMobs.isEmpty()) {
                for (DropMobInfo mob : dropMobs) {
                    if (!mob.isBoss()) {
                        int mobId = mob.getMobId();
                        int chance = mob.getChance();
                        int mobLevel = LifeFactory.getMonsterLevel(mobId);
                        if (mobLevel <= 0) {
                            mobLevel = 10;
                        }
                        if (!foundMob || chance > bestChance) {
                            bestChance = Math.max(1, chance);
                            bestMobLevel = Math.max(1, mobLevel);
                            foundMob = true;
                        }
                    }
                }
            }

            double basePrice = (wholePrice * 20.0) + (bestMobLevel * 30.0);
            double ratio = 500000.0 / bestChance;
            double rarityFactor = Math.pow(ratio, 0.90);
            if (rarityFactor < 0.75) {
                rarityFactor = 0.75;
            } else if (rarityFactor > 10.0) {
                rarityFactor = 10.0;
            }

            int unitPrice = (int) Math.round(basePrice * rarityFactor);
            return Math.max(20, unitPrice);
        });
    }

    /**
     * 单独向玩家出售并补齐某一项任务普通怪物材料（带金币扣除与背包空间校验）
     */
    public DeliveryResult deliverQuestMaterial(Character player, int questId, int itemId) {
        if (player == null || player.getClient() == null) {
            return new DeliveryResult(false, "玩家状态异常。", 0);
        }
        QuestStatus qs = player.getQuest(Quest.getInstance(questId));
        if (qs == null || !qs.getStatus().equals(QuestStatus.Status.STARTED)) {
            return new DeliveryResult(false, "您尚未接取该任务或任务已结束。", 0);
        }
        Quest q = qs.getQuest();
        if (q == null) {
            return new DeliveryResult(false, "任务数据不存在。", 0);
        }
        Integer reqCount = q.getRequiredItems().get(itemId);
        if (reqCount == null || reqCount <= 0) {
            return new DeliveryResult(false, "该任务不需要此道具。", 0);
        }
        if (!isRegularMonsterMaterial(itemId)) {
            return new DeliveryResult(false, "该道具属于特殊/剧情/Boss掉落道具，不支持快捷购买，请在游戏中探索获取！", 0);
        }

        InventoryType iType = ItemConstants.getInventoryType(itemId);
        int currentCount = 0;
        if (player.getInventory(iType) != null) {
            currentCount = player.getInventory(iType).countById(itemId);
        }
        int neededCount = reqCount - currentCount;
        if (neededCount <= 0) {
            return new DeliveryResult(true, "您背包中已有足够的 【" + getItemName(itemId) + "】（" + currentCount + "/" + reqCount + "），无需购买！", 0);
        }

        int unitPrice = getMaterialUnitPrice(itemId);
        long totalCost = (long) unitPrice * neededCount;
        if (totalCost > Integer.MAX_VALUE || player.getMeso() < totalCost) {
            return new DeliveryResult(false, "您的金币不足！购买 #v" + itemId + "# 【#b" + getItemName(itemId) + "#k】 x" + neededCount + " 共需 #r" + totalCost + "#k 金币（单价: " + unitPrice + " 金币），您当前仅有 #b" + player.getMeso() + "#k 金币。", 0);
        }

        // 严格校验背包空间
        if (!org.gms.client.inventory.manipulator.InventoryManipulator.checkSpace(player.getClient(), itemId, neededCount, "")) {
            String invName = (iType != null && iType.getName() != null) ? iType.getName() : "对应";
            return new DeliveryResult(false, "您的【" + invName + "】背包空间不足，请清理出至少 1 个空闲格子后再试！", 0);
        }

        player.gainMeso(-(int) totalCost, true, false, true);
        boolean added = org.gms.client.inventory.manipulator.InventoryManipulator.addById(player.getClient(), itemId, (short) neededCount, "任务辅助购买普通材料", -1);
        if (!added) {
            // 回滚退还金币
            player.gainMeso((int) totalCost, true, false, true);
            return new DeliveryResult(false, "发放道具失败，已退还金币，请检查背包空间后重试。", 0);
        }

        return new DeliveryResult(true, "已扣除 #r" + totalCost + "#k 金币（单价: " + unitPrice + " 金币），成功为您购买补齐 #v" + itemId + "# 【#b" + getItemName(itemId) + "#k】 x" + neededCount + "！", neededCount);
    }

    /**
     * 一键向玩家出售并补齐当前任务所有符合条件的普通怪物材料（带总金币校验与渐进式背包空间校验）
     */
    public DeliveryResult deliverAllRegularMaterials(Character player, int questId) {
        if (player == null || player.getClient() == null) {
            return new DeliveryResult(false, "玩家状态异常。", 0);
        }
        QuestStatus qs = player.getQuest(Quest.getInstance(questId));
        if (qs == null || !qs.getStatus().equals(QuestStatus.Status.STARTED)) {
            return new DeliveryResult(false, "您尚未接取该任务或任务已结束。", 0);
        }
        Quest q = qs.getQuest();
        if (q == null) {
            return new DeliveryResult(false, "任务数据不存在。", 0);
        }

        Map<Integer, Integer> reqItems = q.getRequiredItems();
        if (reqItems.isEmpty()) {
            return new DeliveryResult(false, "该任务无需收集任何道具。", 0);
        }

        Map<Integer, Integer> toDeliver = new java.util.LinkedHashMap<>();
        int deliverableItemTypes = 0;
        int restrictedItemTypes = 0;

        for (Map.Entry<Integer, Integer> entry : reqItems.entrySet()) {
            int itemId = entry.getKey();
            int req = entry.getValue();
            if (isRegularMonsterMaterial(itemId)) {
                deliverableItemTypes++;
                InventoryType iType = ItemConstants.getInventoryType(itemId);
                int cur = 0;
                if (player.getInventory(iType) != null) {
                    cur = player.getInventory(iType).countById(itemId);
                }
                int diff = req - cur;
                if (diff > 0) {
                    toDeliver.put(itemId, diff);
                }
            } else {
                restrictedItemTypes++;
            }
        }

        if (deliverableItemTypes == 0) {
            return new DeliveryResult(false, "该任务所需道具均为特殊/剧情/Boss道具，不支持快捷购买，需手动探索获取！", 0);
        }

        if (toDeliver.isEmpty()) {
            String msg = "该任务所需的所有普通怪物材料您已全部集齐，无需购买！";
            if (restrictedItemTypes > 0) {
                msg += "\r\n#r注：尚有 " + restrictedItemTypes + " 种特殊/剧情道具需手动探索获取。#k";
            }
            return new DeliveryResult(true, msg, 0);
        }

        long totalCost = 0L;
        for (Map.Entry<Integer, Integer> entry : toDeliver.entrySet()) {
            int itemId = entry.getKey();
            int qty = entry.getValue();
            int unitPrice = getMaterialUnitPrice(itemId);
            totalCost += (long) unitPrice * qty;
        }

        if (totalCost > Integer.MAX_VALUE || player.getMeso() < totalCost) {
            return new DeliveryResult(false, "您的金币不足！一键购买本任务全部普通材料共需 #r" + totalCost + "#k 金币，您当前仅有 #b" + player.getMeso() + "#k 金币。", 0);
        }

        // 渐进式多物品背包空间模拟校验，按各个背包分类分别跟踪所需空闲槽位
        int[] simulatedUsedSlots = new int[6];
        for (Map.Entry<Integer, Integer> entry : toDeliver.entrySet()) {
            int itemId = entry.getKey();
            int qty = entry.getValue();
            InventoryType iType = ItemConstants.getInventoryType(itemId);
            int typeIdx = (iType != null) ? iType.getType() : 0;
            if (typeIdx < 0 || typeIdx >= simulatedUsedSlots.length) {
                typeIdx = 0;
            }
            int result = org.gms.client.inventory.manipulator.InventoryManipulator.checkSpaceProgressively(
                    player.getClient(), itemId, qty, "", simulatedUsedSlots[typeIdx], false);
            if (result < 0) {
                String invName = (iType != null && iType.getName() != null) ? iType.getName() : "对应";
                return new DeliveryResult(false, "您的【" + invName + "】背包空间不足以容纳全部购买材料，请清理出更多空闲格子后再试！", 0);
            }
            simulatedUsedSlots[typeIdx] = result;
        }

        // 扣除金币与安全发放全部材料
        player.gainMeso(-(int) totalCost, true, false, true);
        int totalCount = 0;
        StringBuilder sb = new StringBuilder("已扣除 #r").append(totalCost).append("#k 金币，成功为您购买并补齐以下普通怪物材料：\r\n\r\n");
        for (Map.Entry<Integer, Integer> entry : toDeliver.entrySet()) {
            int itemId = entry.getKey();
            int qty = entry.getValue();
            int unitPrice = getMaterialUnitPrice(itemId);
            long itemCost = (long) unitPrice * qty;
            org.gms.client.inventory.manipulator.InventoryManipulator.addById(player.getClient(), itemId, (short) qty, "任务辅助购买普通材料", -1);
            totalCount += qty;
            sb.append("#v").append(itemId).append("# 【#b").append(getItemName(itemId)).append("#k】 x").append(qty)
              .append(" (单价: ").append(unitPrice).append(" 金币, 小计: ").append(itemCost).append(" 金币)\r\n");
        }

        if (restrictedItemTypes > 0) {
            sb.append("\r\n#r注：该任务仍有 ").append(restrictedItemTypes).append(" 种特殊/剧情道具需手动探索获取。#k");
        }

        return new DeliveryResult(true, sb.toString(), totalCount);
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
    public static class DeliveryResult {
        private final boolean success;
        private final String message;
        private final int totalItemsDelivered;

        public DeliveryResult(boolean success, String message, int totalItemsDelivered) {
            this.success = success;
            this.message = message;
            this.totalItemsDelivered = totalItemsDelivered;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }

        public int getTotalItemsDelivered() {
            return totalItemsDelivered;
        }
    }

    public static class QuestSummary {
        private final int questId;
        private final String questName;
        private final boolean canComplete;

        public QuestSummary(int questId, String questName, boolean canComplete) {
            this.questId = questId;
            this.questName = questName;
            this.canComplete = canComplete;
        }

        public int getQuestId() {
            return questId;
        }

        public String getQuestName() {
            return questName;
        }

        public boolean isCanComplete() {
            return canComplete;
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
        private final boolean boss;
        private final List<MapLocation> maps;

        public DropMobInfo(int mobId, String mobName, int chance, String chanceText, boolean boss, List<MapLocation> maps) {
            this.mobId = mobId;
            this.mobName = mobName;
            this.chance = chance;
            this.chanceText = chanceText;
            this.boss = boss;
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

        public boolean isBoss() {
            return boss;
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
        private final boolean boss;
        private final List<MapLocation> maps;

        public MobObjective(int mobId, String mobName, int currentKills, int requiredKills, boolean boss, List<MapLocation> maps) {
            this.mobId = mobId;
            this.mobName = mobName;
            this.currentKills = currentKills;
            this.requiredKills = requiredKills;
            this.boss = boss;
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

        public boolean isBoss() {
            return boss;
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
        private final boolean deliverable;
        private final int unitPrice;
        private final long totalPrice;
        private final List<DropMobInfo> dropMobs;

        public ItemObjective(int itemId, String itemName, int currentCount, int requiredCount, boolean deliverable, int unitPrice, List<DropMobInfo> dropMobs) {
            this.itemId = itemId;
            this.itemName = itemName;
            this.currentCount = currentCount;
            this.requiredCount = requiredCount;
            this.deliverable = deliverable;
            this.unitPrice = unitPrice;
            this.totalPrice = (long) unitPrice * Math.max(0, requiredCount - currentCount);
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

        public boolean isDeliverable() {
            return deliverable;
        }

        public int getUnitPrice() {
            return unitPrice;
        }

        public long getTotalPrice() {
            return totalPrice;
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
        private final boolean canComplete;
        private final NpcLocationInfo startNpc;
        private final NpcLocationInfo completeNpc;
        private final List<MobObjective> mobObjectives;
        private final List<ItemObjective> itemObjectives;

        public QuestDetailInfo(int questId, String questName, boolean canComplete, NpcLocationInfo startNpc, NpcLocationInfo completeNpc,
                               List<MobObjective> mobObjectives, List<ItemObjective> itemObjectives) {
            this.questId = questId;
            this.questName = questName;
            this.canComplete = canComplete;
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

        public boolean isCanComplete() {
            return canComplete;
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

        public long getTotalRegularMaterialsCost() {
            long total = 0;
            for (ItemObjective obj : itemObjectives) {
                if (obj.isDeliverable() && !obj.isCompleted()) {
                    total += obj.getTotalPrice();
                }
            }
            return total;
        }
    }
}
