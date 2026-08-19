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
import org.gms.constants.game.DelayedQuestUpdate;
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
import org.gms.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 任务辅助服务类：提供怪物、物品掉落、NPC 与地图之间的反查与分析服务
 */
public final class QuestHelpService {
    private static final Logger log = LoggerFactory.getLogger(QuestHelpService.class);
    private static final QuestHelpService instance = new QuestHelpService();

    public static final int DEFAULT_TOWN_PRICE = 5000;

    /**
     * 万能传送已配置的主城基准传送价格字典
     */
    public static final Map<Integer, Integer> TOWN_PRICES = Map.ofEntries(
            Map.entry(104000000, 500),   // 明珠港
            Map.entry(100000000, 800),   // 射手村
            Map.entry(101000000, 800),   // 魔法密林
            Map.entry(102000000, 800),   // 勇士部落
            Map.entry(103000000, 800),   // 废弃都市
            Map.entry(120000000, 800),   // 诺特勒斯号码头
            Map.entry(105040300, 1000),  // 林中之城
            Map.entry(140000000, 1000),  // 里恩
            Map.entry(200000000, 1000),  // 天空之城
            Map.entry(211000000, 5000),  // 冰峰雪域
            Map.entry(230000000, 1000),  // 水下世界
            Map.entry(222000000, 1000),  // 童话村
            Map.entry(220000000, 5000),  // 玩具城
            Map.entry(701000000, 5000),  // 东方神州
            Map.entry(250000000, 5000),  // 武陵
            Map.entry(702000000, 1000),  // 少林寺
            Map.entry(260000000, 500),   // 阿里安特
            Map.entry(600000000, 500),   // 新叶城
            Map.entry(240000000, 5000),  // 神木村
            Map.entry(261000000, 1000),  // 马加提亚
            Map.entry(221000000, 1000),  // 地球防御本部
            Map.entry(251000000, 1000),  // 百草堂
            Map.entry(701000200, 10000), // 上海豫园
            Map.entry(550000000, 10000), // 吉隆大都市
            Map.entry(130000000, 1000),  // 圣地
            Map.entry(551000000, 1000),  // 甘榜村
            Map.entry(801000000, 1000),  // 昭和村
            Map.entry(540010000, 1000),  // 新加坡机场
            Map.entry(541000000, 1000),  // 新加坡码头
            Map.entry(300000000, 1000),  // 艾林森林
            Map.entry(270000100, 10000), // 时间神殿
            Map.entry(702100000, 10000), // 藏经阁
            Map.entry(800000000, 10000), // 古代神社
            Map.entry(130000200, 10000), // 圣地岔路
            Map.entry(925020000, 1000),  // 武陵道场入口
            Map.entry(930000000, 5000),  // 毒雾森林
            Map.entry(930000010, 1000)   // 森林入口
    );

    private final Map<Integer, Set<Integer>> mobToMaps = new ConcurrentHashMap<>();
    private final Map<Integer, Set<Integer>> npcToMaps = new ConcurrentHashMap<>();
    private final Map<Integer, Set<Integer>> mapGraph = new ConcurrentHashMap<>();
    private final Map<String, Set<Integer>> nameToMobIds = new ConcurrentHashMap<>();
    private final Map<Integer, Set<Integer>> mobAliasMap = new ConcurrentHashMap<>();
    private final Map<Integer, String> mobNameCache = new ConcurrentHashMap<>();
    private final Map<Integer, String> npcNameCache = new ConcurrentHashMap<>();
    private final Map<Integer, String> itemNameCache = new ConcurrentHashMap<>();
    private final Map<Integer, MapLocation> mapLocationCache = new ConcurrentHashMap<>();
    private final Map<Integer, WarpCostInfo> warpCostCache = new ConcurrentHashMap<>();
    private final Map<Integer, List<MapLocation>> mobMapsCache = new ConcurrentHashMap<>();
    private final Map<Integer, List<MapLocation>> npcMapsCache = new ConcurrentHashMap<>();
    private final Map<Integer, List<DropMobInfo>> itemDropMobsCache = new ConcurrentHashMap<>();
    private final Map<Integer, Boolean> regularMaterialCache = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> materialUnitPriceCache = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> nativeShopItemPrices = new ConcurrentHashMap<>();
    private final Map<Integer, Set<Integer>> nativeShopItemNpcs = new ConcurrentHashMap<>();
    private final Map<Integer, Map<Integer, Long>> accountMobKillsCache = new ConcurrentHashMap<>();
    private final Set<Integer> worldMapMaps = ConcurrentHashMap.newKeySet();
    private final Map<Integer, Set<Integer>> characterVisitedMapsCache = new ConcurrentHashMap<>();
    private final Map<Integer, Boolean> hiddenMapCache = new ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService dbExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();

    private final AtomicBoolean initialized = new AtomicBoolean(false);

    public static QuestHelpService getInstance() {
        return instance;
    }

    private QuestHelpService() {
    }

    public void addMobAlias(int... ids) {
        if (ids == null || ids.length <= 1) {
            return;
        }
        Set<Integer> group = ConcurrentHashMap.newKeySet();
        for (int id : ids) {
            if (id > 0) {
                group.add(id);
            }
        }
        for (int id : group) {
            mobAliasMap.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet()).addAll(group);
        }
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

        // 1. 构建全量怪物名称倒排索引（从 String.wz/Mob.img 读取所有怪物 ID 与名称）
        try {
            DataProvider stringSource = DataProviderFactory.getDataProvider(WZFiles.STRING);
            if (stringSource != null) {
                Data mobData = stringSource.getData("Mob.img");
                if (mobData != null) {
                    for (Data mobNode : mobData.getChildren()) {
                        try {
                            int mobId = Integer.parseInt(mobNode.getName());
                            String name = DataTool.getString("name", mobNode, null);
                            if (name != null && !name.isBlank() && !name.startsWith("怪物 ") && !"MISSINGNO".equals(name)) {
                                nameToMobIds.computeIfAbsent(name, k -> ConcurrentHashMap.newKeySet()).add(mobId);
                                mobNameCache.put(mobId, name);
                            }
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load String.wz/Mob.img for mob name inverted index", e);
        }

        // 2. 补全地图刷怪名称反查
        for (int mobId : mobToMaps.keySet()) {
            String name = getMobName(mobId);
            if (name != null && !name.isBlank() && !name.startsWith("怪物 ") && !"MISSINGNO".equals(name)) {
                nameToMobIds.computeIfAbsent(name, k -> ConcurrentHashMap.newKeySet()).add(mobId);
            }
        }

        // 3. 注册常见任务变种怪物别名关联组（支持别名账号击杀跨ID直接互认聚合）
        addMobAlias(MobId.GREEN_MUSHROOM, MobId.DEJECTED_GREEN_MUSHROOM, MobId.GREEN_MUSHROOM_QUEST, 1110101);
        addMobAlias(MobId.ZOMBIE_MUSHROOM, MobId.ANNOYED_ZOMBIE_MUSHROOM, MobId.ZOMBIE_MUSHROOM_QUEST);
        addMobAlias(MobId.GHOST_STUMP, MobId.SMIRKING_GHOST_STUMP, MobId.GHOST_STUMP_QUEST);

        // 4. 加载原生 NPC 商店物品与价格（排除 GM 商店 1337 以及非地图 NPC）
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT s.npcid, si.itemid, si.price " +
                     "FROM shops s " +
                     "JOIN shopitems si ON s.shopid = si.shopid " +
                     "WHERE si.price > 0 AND s.shopid != 1337 AND s.npcid < 9000000");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int npcId = rs.getInt("npcid");
                int itemId = rs.getInt("itemid");
                int price = rs.getInt("price");
                if (price > 0 && npcToMaps.containsKey(npcId)) {
                    nativeShopItemPrices.merge(itemId, price, Math::min);
                    nativeShopItemNpcs.computeIfAbsent(itemId, k -> ConcurrentHashMap.newKeySet()).add(npcId);
                }
            }
        } catch (SQLException e) {
            log.warn("Failed to load native shops for quest help service", e);
        }

        // 5. 加载 WorldMap 世界地图包含的地图 ID 列表
        try {
            DataProvider mapSource = DataProviderFactory.getDataProvider(WZFiles.MAP);
            if (mapSource != null && mapSource.getRoot() != null) {
                for (DataDirectoryEntry subDir : mapSource.getRoot().getSubdirectories()) {
                    if ("WorldMap".equalsIgnoreCase(subDir.getName())) {
                        for (DataFileEntry file : subDir.getFiles()) {
                            if (file.getName().endsWith(".img")) {
                                String imgPath = "WorldMap/" + file.getName();
                                Data wmImg = mapSource.getData(imgPath);
                                if (wmImg != null) {
                                    Data mapList = wmImg.getChildByPath("MapList");
                                    if (mapList != null) {
                                        for (Data entry : mapList.getChildren()) {
                                            Data mapNo = entry.getChildByPath("mapNo");
                                            if (mapNo != null) {
                                                for (Data num : mapNo.getChildren()) {
                                                    int mid = DataTool.getInt(num, -1);
                                                    if (mid > 0) {
                                                        worldMapMaps.add(mid);
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to index WorldMap maps for quest help service", e);
        }

        log.info("QuestHelpService initialized life index in {}ms. Indexed {} mobs, {} NPCs, {} shop items, {} named mobs, {} worldmap maps",
                System.currentTimeMillis() - start, mobToMaps.size(), npcToMaps.size(), nativeShopItemPrices.size(), nameToMobIds.size(), worldMapMaps.size());
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
        if (lifeData != null) {
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

        // 解析传送门（Portal）连通拓扑图
        Data portalData = mapData.getChildByPath("portal");
        if (portalData != null) {
            for (Data child : portalData.getChildren()) {
                int tm = DataTool.getInt("tm", child, 999999999);
                if (tm != 999999999 && tm != mapId && tm > 0) {
                    mapGraph.computeIfAbsent(mapId, k -> ConcurrentHashMap.newKeySet()).add(tm);
                    mapGraph.computeIfAbsent(tm, k -> ConcurrentHashMap.newKeySet()).add(mapId);
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
            WarpCostInfo costInfo = calculateWarpCost(id);
            return new MapLocation(id, mapName, streetName, costInfo.getTotalCost(), costInfo.getNearestTownName(), costInfo.getDistance());
        });
    }

    /**
     * 计算目标地图的传送费用
     * 规则：
     * 1. 目标地图属于主城：费用为该主城基准价格（如射手村 800）；
     * 2. 目标地图在野外：通过 BFS 拓扑寻路找到最近的主城 T 及传送点跳数 D（distance）；
     *    费用 = basePrice + floor(basePrice * 0.5 * D)；
     * 3. 无法连通任何主城（孤立/副本）：默认主城价格 5000 金币。
     */
    public WarpCostInfo calculateWarpCost(int targetMapId) {
        ensureInitialized();
        return warpCostCache.computeIfAbsent(targetMapId, mapId -> {
            // 1. 若目标地图本身就是已定义主城
            Integer townPrice = TOWN_PRICES.get(mapId);
            if (townPrice != null) {
                String townName = getTownNameSafely(mapId);
                return new WarpCostInfo(mapId, mapId, townName, 0, townPrice, townPrice);
            }

            // 2. BFS 寻找距离最近的主城
            Queue<Integer> queue = new ArrayDeque<>();
            Map<Integer, Integer> visitedDist = new HashMap<>();
            queue.add(mapId);
            visitedDist.put(mapId, 0);

            int foundTownId = -1;
            int foundDist = -1;

            while (!queue.isEmpty()) {
                int curr = queue.poll();
                int dist = visitedDist.get(curr);

                if (curr != mapId && TOWN_PRICES.containsKey(curr)) {
                    foundTownId = curr;
                    foundDist = dist;
                    break;
                }

                Set<Integer> neighbors = mapGraph.get(curr);
                if (neighbors != null) {
                    for (int next : neighbors) {
                        if (!visitedDist.containsKey(next)) {
                            visitedDist.put(next, dist + 1);
                            queue.add(next);
                        }
                    }
                }
            }

            if (foundTownId != -1) {
                int basePrice = TOWN_PRICES.get(foundTownId);
                String townName = getTownNameSafely(foundTownId);
                int totalCost = basePrice + (int) Math.floor(basePrice * 0.5 * foundDist);
                return new WarpCostInfo(mapId, foundTownId, townName, foundDist, basePrice, totalCost);
            }

            // 3. 孤立/未知主城：默认 5000 金币
            return new WarpCostInfo(mapId, -1, "未知主城", 0, DEFAULT_TOWN_PRICE, DEFAULT_TOWN_PRICE);
        });
    }

    public String getTownNameSafely(int mapId) {
        try {
            String townName = MapFactory.loadPlaceName(mapId);
            if (townName == null || townName.isBlank()) {
                townName = MapFactory.loadStreetName(mapId);
            }
            if (townName != null && !townName.isBlank()) {
                return townName;
            }
        } catch (Throwable ignored) {
        }
        return "地图 " + mapId;
    }

    public void recordMapVisited(int characterId, int mapId) {
        if (characterId <= 0 || mapId <= 0) {
            return;
        }
        Set<Integer> visited = characterVisitedMapsCache.computeIfAbsent(characterId, this::loadCharacterVisitedMaps);
        if (visited.add(mapId)) {
            Thread.ofVirtual().start(() -> {
                try (Connection con = DatabaseConnection.getConnection();
                     PreparedStatement ps = con.prepareStatement(
                             "INSERT IGNORE INTO character_visited_maps (character_id, map_id) VALUES (?, ?)")) {
                    ps.setInt(1, characterId);
                    ps.setInt(2, mapId);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    log.warn("Failed to record visited map {} for character {}", mapId, characterId, e);
                }
            });
        }
    }

    public boolean isMapVisited(int characterId, int mapId) {
        return getVisitedMaps(characterId).contains(mapId);
    }

    public Set<Integer> getVisitedMaps(int characterId) {
        return characterVisitedMapsCache.computeIfAbsent(characterId, this::loadCharacterVisitedMaps);
    }

    private Set<Integer> loadCharacterVisitedMaps(int characterId) {
        Set<Integer> maps = ConcurrentHashMap.newKeySet();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT map_id FROM character_visited_maps WHERE character_id = ?")) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    maps.add(rs.getInt("map_id"));
                }
            }
        } catch (SQLException e) {
            log.warn("Failed to load visited maps for character {}", characterId, e);
        }
        return maps;
    }

    /**
     * 判断地图是否为隐藏地图（或在世界地图中无法通过按 W 键查看大地图的区域）
     */
    public boolean isHiddenMap(int mapId) {
        return hiddenMapCache.computeIfAbsent(mapId, id -> {
            ensureInitialized();
            if (worldMapMaps.contains(id)) {
                return false;
            }
            String street = MapFactory.loadStreetName(id);
            if (street != null && !street.isBlank()) {
                if (street.contains("隐藏地图") || street.contains("隐藏") || street.equalsIgnoreCase("Hidden Street")) {
                    return true;
                }
            }
            String place = MapFactory.loadPlaceName(id);
            if (place != null && !place.isBlank()) {
                if (place.contains("隐藏地图") || place.contains("隐藏") || place.equalsIgnoreCase("Hidden Street")) {
                    return true;
                }
            }
            // 若该地图未登记在任何世界地图中且不是主城本身，则判定为无法在世界地图查看的隐藏/特殊区域
            return !TOWN_PRICES.containsKey(id);
        });
    }

    public int getTownIdForMap(int targetMapId) {
        WarpCostInfo costInfo = calculateWarpCost(targetMapId);
        return costInfo.getNearestTownId();
    }

    public String getTownNameForMap(int targetMapId) {
        WarpCostInfo costInfo = calculateWarpCost(targetMapId);
        return costInfo.getNearestTownName();
    }

    /**
     * 判定指定角色是否已解锁传送至目标地图的权限
     * 规则：
     * 1. GM 角色全免，无限制；
     * 2. 隐藏地图（或未绑定任何主城的孤立地图）：当且仅当玩家亲自访问过该地图自身；
     * 3. 常规地图（野外地图 / 主城自身）：当且仅当玩家访问过该地图所属的主城。
     */
    public boolean isMapWarpUnlocked(Character player, int targetMapId) {
        if (player == null) {
            return false;
        }
        boolean isHidden = isHiddenMap(targetMapId);
        int townId = getTownIdForMap(targetMapId);
        if (isHidden || townId <= 0) {
            return isMapVisited(player.getId(), targetMapId);
        }
        return isMapVisited(player.getId(), townId);
    }

    /**
     * 获取目标地图未解锁的原因描述
     */
    public String getWarpLockReason(Character player, int targetMapId) {
        if (isMapWarpUnlocked(player, targetMapId)) {
            return null;
        }
        boolean isHidden = isHiddenMap(targetMapId);
        int townId = getTownIdForMap(targetMapId);
        if (isHidden || townId <= 0) {
            return "需先探索此隐藏地图";
        }
        return "需先访问主城";
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

    public boolean isNativeShopItem(int itemId) {
        ensureInitialized();
        return nativeShopItemPrices.containsKey(itemId);
    }

    public Integer getNativeShopPrice(int itemId) {
        ensureInitialized();
        return nativeShopItemPrices.get(itemId);
    }

    /**
     * 判断道具是否可由任务辅助系统购买补齐（普通怪物掉落材料 或 原生NPC商店售卖道具）
     * 严格排除：装备(1xxxxxx)、商城道具(5xxxxxx)、专属任务道具(403xxxx)、不可出售/不可交易/唯一道具
     */
    public boolean isPurchasableMaterial(int itemId) {
        if (isRegularMonsterMaterial(itemId)) {
            return true;
        }
        if (!isNativeShopItem(itemId)) {
            return false;
        }

        InventoryType invType = ItemConstants.getInventoryType(itemId);
        if (invType != InventoryType.ETC && invType != InventoryType.USE && invType != InventoryType.SETUP) {
            return false;
        }
        if (itemId >= 4030000 && itemId < 4040000) {
            return false;
        }

        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        if (ii.isQuestItem(itemId) || ii.isPartyQuestItem(itemId) || ii.isPickupRestricted(itemId) ||
            ii.isUntradeableRestricted(itemId) || ii.isAccountRestricted(itemId) || ii.isDropRestricted(itemId)) {
            return false;
        }

        Data itemData = ii.getItemData(itemId);
        if (itemData != null) {
            int notSale = DataTool.getIntConvert("info/notSale", itemData, 0);
            int tradeBlock = DataTool.getIntConvert("info/tradeBlock", itemData, 0);
            int only = DataTool.getIntConvert("info/only", itemData, 0);
            if (notSale == 1 || tradeBlock == 1 || only == 1) {
                return false;
            }
        }

        return true;
    }

    /**
     * 判断道具是否为单纯由普通怪物掉落的普通杂物/消耗品材料
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
     * 记录账号怪物击杀历史（严格排除 Boss）
     */
    public void recordMobKill(int accountId, int mobId, boolean isBoss) {
        if (accountId <= 0 || mobId <= 0 || isBoss) {
            return;
        }
        accountMobKillsCache.computeIfAbsent(accountId, k -> new ConcurrentHashMap<>()).merge(mobId, 1L, Long::sum);
        dbExecutor.submit(() -> {
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(
                         "INSERT INTO account_mob_kills (account_id, mob_id, kill_count) VALUES (?, ?, 1) " +
                         "ON DUPLICATE KEY UPDATE kill_count = kill_count + 1")) {
                ps.setInt(1, accountId);
                ps.setInt(2, mobId);
                ps.executeUpdate();
            } catch (Exception e) {
                log.warn("Failed to record account mob kill for acc: {}, mob: {}", accountId, mobId, e);
            }
        });
    }

    /**
     * 获取指定怪物的关联怪物 ID 集合（包含自身、硬编码别名变种、以及同名怪物）
     */
    public Set<Integer> getRelatedMobIds(int mobId) {
        if (mobId <= 0) {
            return Collections.emptySet();
        }
        Set<Integer> result = new HashSet<>();
        result.add(mobId);

        Set<Integer> aliases = mobAliasMap.get(mobId);
        if (aliases != null) {
            result.addAll(aliases);
        }

        String name = getMobName(mobId);
        if (name != null && !name.isBlank() && !name.startsWith("怪物 ") && !"MISSINGNO".equals(name)) {
            Set<Integer> sameNameIds = nameToMobIds.get(name);
            if (sameNameIds != null) {
                result.addAll(sameNameIds);
            }
        }
        return result;
    }

    /**
     * 获取账号累计消灭某怪物的总数（支持同名怪及任务变种怪智能聚合）
     */
    public long getAccountMobKills(int accountId, int mobId) {
        if (accountId <= 0 || mobId <= 0) {
            return 0L;
        }
        Set<Integer> relatedIds = getRelatedMobIds(mobId);
        long totalKills = 0L;
        for (int id : relatedIds) {
            totalKills += getDirectAccountMobKills(accountId, id);
        }
        return totalKills;
    }

    private long getDirectAccountMobKills(int accountId, int mobId) {
        Map<Integer, Long> map = accountMobKillsCache.get(accountId);
        if (map != null && map.containsKey(mobId)) {
            return map.get(mobId);
        }
        long count = 0L;
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT kill_count FROM account_mob_kills WHERE account_id = ? AND mob_id = ?")) {
            ps.setInt(1, accountId);
            ps.setInt(2, mobId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    count = rs.getLong("kill_count");
                }
            }
        } catch (Exception e) {
            log.warn("Failed to get account mob kills for acc: {}, mob: {}", accountId, mobId, e);
        }
        accountMobKillsCache.computeIfAbsent(accountId, k -> new ConcurrentHashMap<>()).put(mobId, count);
        return count;
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

    /**
     * 判断任务当前未达成的所有交付条件是否均可通过向辅助商店购买材料或同步账号击杀补齐解锁（可直接补齐交付）
     * 满足条件：
     * 1. 任务当前尚未满足全部直接交付条件（非 isCanComplete）；
     * 2. 怪物击杀目标中，所有未集齐的均满足账号历史击杀（非Boss且累计击杀>=需求）；
     * 3. 物品收集目标中，所有未集齐的道具均属于可购买材料（isPurchasableMaterial），无未完成的特殊/剧情/Boss专属道具；
     * 4. 至少存在一项未集齐的可同步击杀或可购买材料。
     */
    public boolean isQuestPurchasableCompletable(Character player, Quest q) {
        if (player == null || q == null) {
            return false;
        }
        if (isQuestCompletable(player, q)) {
            return false;
        }
        QuestStatus qs = player.getQuest(q);
        if (qs == null || !QuestStatus.Status.STARTED.equals(qs.getStatus())) {
            return false;
        }

        // 1. 击杀目标：所有未达成的目标必须满足账号历史共享条件（且非Boss）
        Map<Integer, Integer> reqMobs = new HashMap<>(q.getRequiredMobs());
        if (reqMobs.isEmpty() && !q.getRelevantMobs().isEmpty()) {
            for (int mobId : q.getRelevantMobs()) {
                int count = q.getMobAmountNeeded(mobId);
                if (count > 0) {
                    reqMobs.put(mobId, count);
                }
            }
        }
        boolean hasIncompleteSyncableOrPurchasable = false;
        MonsterInformationProvider mip = MonsterInformationProvider.getInstance();

        for (Map.Entry<Integer, Integer> entry : reqMobs.entrySet()) {
            int mobId = entry.getKey();
            int req = entry.getValue();
            int currentKills = parseProgress(qs.getProgress(mobId));
            if (currentKills < req) {
                if (mip.isBoss(mobId)) {
                    return false; // 有未完成的 Boss 目标，无法一键补齐
                }
                long accKills = getAccountMobKills(player.getAccountId(), mobId);
                if (accKills < req) {
                    return false; // 账号历史击杀不足
                }
                hasIncompleteSyncableOrPurchasable = true;
            }
        }

        // 2. 道具目标：所有未集齐的道具必须全是可购买材料（普通怪物材料或原生商店道具）
        Map<Integer, Integer> reqItems = q.getRequiredItems();
        if (reqItems != null) {
            for (Map.Entry<Integer, Integer> entry : reqItems.entrySet()) {
                int itemId = entry.getKey();
                int reqCount = entry.getValue();
                InventoryType iType = ItemConstants.getInventoryType(itemId);
                int currentCount = 0;
                if (iType != null && !iType.equals(InventoryType.UNDEFINED) && player.getInventory(iType) != null) {
                    currentCount = player.getInventory(iType).countById(itemId);
                }
                if (currentCount < reqCount) {
                    if (!isPurchasableMaterial(itemId)) {
                        return false; // 有未达成的不可购买道具
                    }
                    hasIncompleteSyncableOrPurchasable = true;
                }
            }
        }

        return hasIncompleteSyncableOrPurchasable;
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
            boolean purchasableComplete = isQuestPurchasableCompletable(player, q);
            long lastModifiedTime = qs.getLastModifiedTime();
            list.add(new QuestSummary(q.getId(), name, canComplete, purchasableComplete, lastModifiedTime));
        }
        list.sort((a, b) -> {
            int cmp = Long.compare(b.getLastModifiedTime(), a.getLastModifiedTime());
            if (cmp != 0) return cmp;
            return Integer.compare(b.getQuestId(), a.getQuestId());
        });
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
        boolean purchasableComplete = isQuestPurchasableCompletable(player, q);

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
            long accountKills = isBoss ? 0L : getAccountMobKills(player.getAccountId(), mobId);
            List<MapLocation> maps = getMapsForMob(mobId);
            mobObjectives.add(new MobObjective(mobId, getMobName(mobId), currentKills, reqCount, isBoss, accountKills, maps));
        }
        mobObjectives.sort(Comparator.comparingInt(MobObjective::getMobId));

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
            boolean deliverable = isPurchasableMaterial(itemId);
            boolean isShop = isNativeShopItem(itemId);
            int unitPrice = deliverable ? getMaterialUnitPrice(itemId) : 0;
            List<DropMobInfo> dropMobs = getDropMobsForItem(itemId);
            itemObjectives.add(new ItemObjective(itemId, getItemName(itemId), currentCount, reqCount, deliverable, isShop, unitPrice, dropMobs));
        }
        itemObjectives.sort(Comparator.comparingInt(ItemObjective::getItemId));

        return new QuestDetailInfo(questId, questName, canComplete, purchasableComplete, startNpc, completeNpc, mobObjectives, itemObjectives);
    }

    public int getMaterialUnitPrice(int itemId) {
        return materialUnitPriceCache.computeIfAbsent(itemId, id -> {
            Integer shopPrice = getNativeShopPrice(id);
            if (shopPrice != null && shopPrice > 0) {
                int shopUnitPrice = Math.max(10, shopPrice * 10);
                if (isRegularMonsterMaterial(id)) {
                    int dropUnitPrice = calculateDropUnitPrice(id);
                    return Math.min(shopUnitPrice, dropUnitPrice);
                }
                return shopUnitPrice;
            }
            return calculateDropUnitPrice(id);
        });
    }

    private int calculateDropUnitPrice(int itemId) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        int wholePrice = ii.getWholePrice(itemId);
        if (wholePrice <= 0) {
            wholePrice = 1;
        }

        int bestMobLevel = 10;
        int bestChance = 500000;
        boolean foundMob = false;

        List<DropMobInfo> dropMobs = getDropMobsForItem(itemId);
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
    }

    public DeliveryResult syncQuestMobKill(Character player, int questId, int mobId) {
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
        int reqCount = q.getMobAmountNeeded(mobId);
        if (reqCount <= 0) {
            return new DeliveryResult(false, "该任务不需要击杀此怪物。", 0);
        }
        if (MonsterInformationProvider.getInstance().isBoss(mobId)) {
            return new DeliveryResult(false, "该怪物属于 Boss 怪物，不支持账号历史共享，需亲自击杀！", 0);
        }
        long accountKills = getAccountMobKills(player.getAccountId(), mobId);
        if (accountKills < reqCount) {
            return new DeliveryResult(false, "您的账号历史击杀数不足（已击杀: " + accountKills + "/" + reqCount + "），无法同步！", 0);
        }
        int currentKills = parseProgress(qs.getProgress(mobId));
        if (currentKills >= reqCount) {
            return new DeliveryResult(true, "该怪物击杀目标已达成（" + currentKills + "/" + reqCount + "），无需同步！", 0);
        }

        qs.setProgress(mobId, StringUtil.getLeftPaddedStr(Integer.toString(reqCount), '0', 3));
        player.announceUpdateQuest(DelayedQuestUpdate.UPDATE, qs, false);
        if (qs.getInfoNumber() > 0) {
            player.announceUpdateQuest(DelayedQuestUpdate.UPDATE, qs, true);
        }

        return new DeliveryResult(true, "已成功应用账号历史击杀记录，【" + getMobName(mobId) + "】击杀目标已直接同步达成（" + reqCount + "/" + reqCount + "）！", reqCount - currentKills);
    }

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
        if (!isPurchasableMaterial(itemId)) {
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

        if (!org.gms.client.inventory.manipulator.InventoryManipulator.checkSpace(player.getClient(), itemId, neededCount, "")) {
            String invName = (iType != null && iType.getName() != null) ? iType.getName() : "对应";
            return new DeliveryResult(false, "您的【" + invName + "】背包空间不足，请清理出至少 1 个空闲格子后再试！", 0);
        }

        player.gainMeso(-(int) totalCost, true, false, true);
        boolean added = org.gms.client.inventory.manipulator.InventoryManipulator.addById(player.getClient(), itemId, (short) neededCount, "任务辅助购买材料", -1);
        if (!added) {
            player.gainMeso((int) totalCost, true, false, true);
            return new DeliveryResult(false, "发放道具失败，已退还金币，请检查背包空间后重试。", 0);
        }

        return new DeliveryResult(true, "已扣除 #r" + totalCost + "#k 金币（单价: " + unitPrice + " 金币），成功为您购买补齐 #v" + itemId + "# 【#b" + getItemName(itemId) + "#k】 x" + neededCount + "！", neededCount);
    }

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
        if (reqItems == null || reqItems.isEmpty()) {
            return new DeliveryResult(false, "该任务无需收集任何道具。", 0);
        }

        Map<Integer, Integer> toDeliver = new java.util.LinkedHashMap<>();
        int deliverableItemTypes = 0;
        int restrictedItemTypes = 0;

        for (Map.Entry<Integer, Integer> entry : reqItems.entrySet()) {
            int itemId = entry.getKey();
            int req = entry.getValue();
            if (isPurchasableMaterial(itemId)) {
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
            String msg = "该任务所需的所有普通/商店材料您已全部集齐，无需购买！";
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
            return new DeliveryResult(false, "您的金币不足！一键购买本任务全部可购材料共需 #r" + totalCost + "#k 金币，您当前仅有 #b" + player.getMeso() + "#k 金币。", 0);
        }

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

        player.gainMeso(-(int) totalCost, true, false, true);
        int totalCount = 0;
        StringBuilder sb = new StringBuilder("已扣除 #r").append(totalCost).append("#k 金币，成功为您购买并补齐以下材料：\r\n\r\n");
        for (Map.Entry<Integer, Integer> entry : toDeliver.entrySet()) {
            int itemId = entry.getKey();
            int qty = entry.getValue();
            int unitPrice = getMaterialUnitPrice(itemId);
            long itemCost = (long) unitPrice * qty;
            org.gms.client.inventory.manipulator.InventoryManipulator.addById(player.getClient(), itemId, (short) qty, "任务辅助购买材料", -1);
            totalCount += qty;
            sb.append("#v").append(itemId).append("# 【#b").append(getItemName(itemId)).append("#k】 x").append(qty)
              .append(" (单价: ").append(unitPrice).append(" 金币, 小计: ").append(itemCost).append(" 金币)\r\n");
        }

        if (restrictedItemTypes > 0) {
            sb.append("\r\n#r注：该任务仍有 ").append(restrictedItemTypes).append(" 种特殊/剧情道具需手动探索获取。#k");
        }

        return new DeliveryResult(true, sb.toString(), totalCount);
    }

    public DeliveryResult deliverAllQuestObjectives(Character player, int questId) {
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

        Map<Integer, Integer> reqMobs = new HashMap<>(q.getRequiredMobs());
        if (reqMobs.isEmpty() && !q.getRelevantMobs().isEmpty()) {
            for (int mobId : q.getRelevantMobs()) {
                int count = q.getMobAmountNeeded(mobId);
                if (count > 0) {
                    reqMobs.put(mobId, count);
                }
            }
        }
        int syncedMobCount = 0;
        int bossMobCount = 0;
        int unsyncedMobCount = 0;
        boolean questUpdated = false;

        MonsterInformationProvider mip = MonsterInformationProvider.getInstance();
        for (Map.Entry<Integer, Integer> entry : reqMobs.entrySet()) {
            int mobId = entry.getKey();
            int req = entry.getValue();
            int cur = parseProgress(qs.getProgress(mobId));
            if (cur < req) {
                if (mip.isBoss(mobId)) {
                    bossMobCount++;
                } else {
                    long accKills = getAccountMobKills(player.getAccountId(), mobId);
                    if (accKills >= req) {
                        qs.setProgress(mobId, StringUtil.getLeftPaddedStr(Integer.toString(req), '0', 3));
                        syncedMobCount++;
                        questUpdated = true;
                    } else {
                        unsyncedMobCount++;
                    }
                }
            }
        }

        if (questUpdated) {
            player.announceUpdateQuest(DelayedQuestUpdate.UPDATE, qs, false);
            if (qs.getInfoNumber() > 0) {
                player.announceUpdateQuest(DelayedQuestUpdate.UPDATE, qs, true);
            }
        }

        DeliveryResult itemResult = null;
        if (q.getRequiredItems() != null && !q.getRequiredItems().isEmpty()) {
            itemResult = deliverAllRegularMaterials(player, questId);
        }

        StringBuilder sb = new StringBuilder();
        if (syncedMobCount > 0) {
            sb.append("★ 成功为您同步达成 #b").append(syncedMobCount).append("#k 项账号历史怪物击杀目标！\r\n\r\n");
        }
        if (itemResult != null) {
            sb.append(itemResult.getMessage());
        }

        if (bossMobCount > 0) {
            sb.append("\r\n#r注：尚有 ").append(bossMobCount).append(" 项 Boss 击杀目标需亲自挑战。#k");
        }
        if (unsyncedMobCount > 0) {
            sb.append("\r\n#r注：尚有 ").append(unsyncedMobCount).append(" 项怪物击杀账号历史累计数量未达标，需手动消灭。#k");
        }

        int totalCount = syncedMobCount + (itemResult != null ? itemResult.getTotalItemsDelivered() : 0);
        boolean overallSuccess = syncedMobCount > 0 || (itemResult != null && itemResult.isSuccess());
        return new DeliveryResult(overallSuccess, sb.toString(), totalCount);
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
            try {
                String name = MonsterInformationProvider.getInstance().getMobNameFromId(id);
                return (name == null || name.isBlank()) ? "怪物 " + id : name;
            } catch (Throwable t) {
                return "怪物 " + id;
            }
        });
    }

    private String getNPCName(int npcId) {
        return npcNameCache.computeIfAbsent(npcId, id -> {
            String name = LifeFactory.getNPC(id).getName();
            return (name == null || name.isBlank()) ? "NPC " + id : name;
        });
    }

    private String getItemName(int itemId) {
        return itemNameCache.computeIfAbsent(itemId, id -> {
            String name = ItemInformationProvider.getInstance().getName(id);
            return (name == null || name.isBlank()) ? "道具 " + id : name;
        });
    }

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
        private final boolean purchasableComplete;
        private final long lastModifiedTime;

        public QuestSummary(int questId, String questName, boolean canComplete, boolean purchasableComplete, long lastModifiedTime) {
            this.questId = questId;
            this.questName = questName;
            this.canComplete = canComplete;
            this.purchasableComplete = purchasableComplete;
            this.lastModifiedTime = lastModifiedTime;
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

        public boolean isPurchasableComplete() {
            return purchasableComplete;
        }

        public long getLastModifiedTime() {
            return lastModifiedTime;
        }
    }

    public static class WarpCostInfo {
        private final int mapId;
        private final int nearestTownId;
        private final String nearestTownName;
        private final int distance;
        private final int basePrice;
        private final int totalCost;

        public WarpCostInfo(int mapId, int nearestTownId, String nearestTownName, int distance, int basePrice, int totalCost) {
            this.mapId = mapId;
            this.nearestTownId = nearestTownId;
            this.nearestTownName = nearestTownName != null ? nearestTownName : "";
            this.distance = distance;
            this.basePrice = basePrice;
            this.totalCost = totalCost;
        }

        public int getMapId() {
            return mapId;
        }

        public int getNearestTownId() {
            return nearestTownId;
        }

        public String getNearestTownName() {
            return nearestTownName;
        }

        public int getDistance() {
            return distance;
        }

        public int getBasePrice() {
            return basePrice;
        }

        public int getTotalCost() {
            return totalCost;
        }
    }

    public static class MapLocation {
        private final int mapId;
        private final String mapName;
        private final String streetName;
        private final int warpCost;
        private final String nearestTownName;
        private final int distance;

        public MapLocation(int mapId, String mapName, String streetName) {
            this(mapId, mapName, streetName, 0, "", 0);
        }

        public MapLocation(int mapId, String mapName, String streetName, int warpCost, String nearestTownName, int distance) {
            this.mapId = mapId;
            this.mapName = mapName != null ? mapName : "";
            this.streetName = streetName != null ? streetName : "";
            this.warpCost = warpCost;
            this.nearestTownName = nearestTownName != null ? nearestTownName : "";
            this.distance = distance;
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

        public int getWarpCost() {
            return warpCost;
        }

        public String getNearestTownName() {
            return nearestTownName;
        }

        public int getDistance() {
            return distance;
        }

        public String getDisplayName() {
            String baseDisplay;
            if (!streetName.isBlank() && !mapName.isBlank()) {
                if (streetName.equals(mapName)) {
                    baseDisplay = mapName + " (" + mapId + ")";
                } else {
                    baseDisplay = streetName + " - " + mapName + " (" + mapId + ")";
                }
            } else if (!mapName.isBlank()) {
                baseDisplay = mapName + " (" + mapId + ")";
            } else if (!streetName.isBlank()) {
                baseDisplay = streetName + " (" + mapId + ")";
            } else {
                baseDisplay = "地图 (" + mapId + ")";
            }

            if (warpCost > 0) {
                return baseDisplay + " [费用: " + warpCost + " 金币]";
            }
            return baseDisplay;
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
        private final long accountKills;
        private final boolean syncable;
        private final List<MapLocation> maps;

        public MobObjective(int mobId, String mobName, int currentKills, int requiredKills, boolean boss, long accountKills, List<MapLocation> maps) {
            this.mobId = mobId;
            this.mobName = mobName;
            this.currentKills = currentKills;
            this.requiredKills = requiredKills;
            this.boss = boss;
            this.accountKills = accountKills;
            this.syncable = !boss && currentKills < requiredKills && accountKills >= requiredKills;
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

        public long getAccountKills() {
            return accountKills;
        }

        public boolean isSyncable() {
            return syncable;
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
        private final boolean nativeShopItem;
        private final int unitPrice;
        private final long totalPrice;
        private final List<DropMobInfo> dropMobs;

        public ItemObjective(int itemId, String itemName, int currentCount, int requiredCount, boolean deliverable, boolean nativeShopItem, int unitPrice, List<DropMobInfo> dropMobs) {
            this.itemId = itemId;
            this.itemName = itemName;
            this.currentCount = currentCount;
            this.requiredCount = requiredCount;
            this.deliverable = deliverable;
            this.nativeShopItem = nativeShopItem;
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

        public boolean isNativeShopItem() {
            return nativeShopItem;
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
        private final boolean purchasableComplete;
        private final NpcLocationInfo startNpc;
        private final NpcLocationInfo completeNpc;
        private final List<MobObjective> mobObjectives;
        private final List<ItemObjective> itemObjectives;

        public QuestDetailInfo(int questId, String questName, boolean canComplete, boolean purchasableComplete,
                               NpcLocationInfo startNpc, NpcLocationInfo completeNpc,
                               List<MobObjective> mobObjectives, List<ItemObjective> itemObjectives) {
            this.questId = questId;
            this.questName = questName;
            this.canComplete = canComplete;
            this.purchasableComplete = purchasableComplete;
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

        public boolean isPurchasableComplete() {
            return purchasableComplete;
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

        public boolean hasSyncableMobKills() {
            for (MobObjective mob : mobObjectives) {
                if (mob.isSyncable()) {
                    return true;
                }
            }
            return false;
        }

        public boolean hasDeliverableIncompleteItems() {
            for (ItemObjective item : itemObjectives) {
                if (item.isDeliverable() && !item.isCompleted()) {
                    return true;
                }
            }
            return false;
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
