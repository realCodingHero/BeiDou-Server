import org.gms.server.quest.QuestHelpService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class QuestWarpAndExpTest {

    @Test
    public void testWarpCostCalculation() throws Exception {
        QuestHelpService service = QuestHelpService.getInstance();

        // 通过反射设置 initialized = true 并注入测试连通图: 100000000(射手村: 800) <-> 100000200(训练场1) <-> 100000201(训练场2)
        Field initField = QuestHelpService.class.getDeclaredField("initialized");
        initField.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicBoolean) initField.get(service)).set(true);

        Field mapGraphField = QuestHelpService.class.getDeclaredField("mapGraph");
        mapGraphField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, Set<Integer>> mapGraph = (Map<Integer, Set<Integer>>) mapGraphField.get(service);

        mapGraph.computeIfAbsent(100000000, k -> ConcurrentHashMap.newKeySet()).add(100000200);
        mapGraph.computeIfAbsent(100000200, k -> ConcurrentHashMap.newKeySet()).add(100000000);

        mapGraph.computeIfAbsent(100000200, k -> ConcurrentHashMap.newKeySet()).add(100000201);
        mapGraph.computeIfAbsent(100000201, k -> ConcurrentHashMap.newKeySet()).add(100000200);

        // 1. 测试射手村自身
        QuestHelpService.WarpCostInfo townInfo = service.calculateWarpCost(100000000);
        assertNotNull(townInfo);
        assertEquals(800, townInfo.getTotalCost());
        assertEquals(0, townInfo.getDistance());

        // 2. 测试训练场1 (距离射手村 1 跳) -> 800 + 400 * 1 = 1200
        QuestHelpService.WarpCostInfo f1Info = service.calculateWarpCost(100000200);
        assertNotNull(f1Info);
        assertEquals(1200, f1Info.getTotalCost());
        assertEquals(1, f1Info.getDistance());

        // 3. 测试训练场2 (距离射手村 2 跳) -> 800 + 400 * 2 = 1600
        QuestHelpService.WarpCostInfo f2Info = service.calculateWarpCost(100000201);
        assertNotNull(f2Info);
        assertEquals(1600, f2Info.getTotalCost());
        assertEquals(2, f2Info.getDistance());

        // 4. 测试孤立/未知地图 -> 5000
        QuestHelpService.WarpCostInfo isolatedInfo = service.calculateWarpCost(999999000);
        assertNotNull(isolatedInfo);
        assertEquals(5000, isolatedInfo.getTotalCost());
    }

    @Test
    public void testNativeShopPricingAndAccountMobKills() throws Exception {
        QuestHelpService service = QuestHelpService.getInstance();

        Field shopPricesField = QuestHelpService.class.getDeclaredField("nativeShopItemPrices");
        shopPricesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, Integer> shopPrices = (Map<Integer, Integer>) shopPricesField.get(service);

        Field regMatField = QuestHelpService.class.getDeclaredField("regularMaterialCache");
        regMatField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, Boolean> regMatCache = (Map<Integer, Boolean>) regMatField.get(service);
        regMatCache.put(2000000, false);

        // 注入原生商店道具: 2000000(红药水, 商店原价 50 金币)
        shopPrices.put(2000000, 50);

        assertEquals(true, service.isNativeShopItem(2000000));
        assertEquals(50, service.getNativeShopPrice(2000000));
        // 原生商店售价 10 倍: 50 * 10 = 500
        assertEquals(500, service.getMaterialUnitPrice(2000000));

        // 账号怪物击杀测试
        Field killsField = QuestHelpService.class.getDeclaredField("accountMobKillsCache");
        killsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, Map<Integer, Long>> killsCache = (Map<Integer, Map<Integer, Long>>) killsField.get(service);

        Field mobNameField = QuestHelpService.class.getDeclaredField("mobNameCache");
        mobNameField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, String> mobNameCache = (Map<Integer, String>) mobNameField.get(service);
        mobNameCache.put(1110100, "绿蘑菇");
        mobNameCache.put(1110101, "绿蘑菇");
        mobNameCache.put(999999, "未知怪");

        // 注入 1110100 (绿蘑菇) 150 次击杀
        killsCache.computeIfAbsent(1001, k -> new ConcurrentHashMap<>()).put(1110100, 150L);
        service.addMobAlias(1110100, 1110101, 9101000);

        // 查询 1110100 自身
        assertEquals(150L, service.getAccountMobKills(1001, 1110100));
        // 通过任务变种别名 1110101 查询，应智能聚合得到 150
        assertEquals(150L, service.getAccountMobKills(1001, 1110101));
        // 未击杀的怪物返回 0
        killsCache.get(1001).put(999999, 0L);
        assertEquals(0L, service.getAccountMobKills(1001, 999999));
    }

    @Test
    public void testMapWarpUnlockRules() throws Exception {
        QuestHelpService service = QuestHelpService.getInstance();

        Field visitedField = QuestHelpService.class.getDeclaredField("characterVisitedMapsCache");
        visitedField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, Set<Integer>> visitedCache = (Map<Integer, Set<Integer>>) visitedField.get(service);

        // 模拟角色 2001
        Set<Integer> visited2001 = ConcurrentHashMap.newKeySet();
        visitedCache.put(2001, visited2001);

        // 1. 模拟常规地图: 100000200(训练场1, 所属主城 100000000 射手村)
        int normalMapId = 100000200;
        int townId = 100000000;

        // 玩家未访问主城时，isMapVisited 应为 false
        assertEquals(false, service.isMapVisited(2001, townId));
        assertEquals(false, service.isMapVisited(2001, normalMapId));

        // 玩家访问主城 100000000
        visited2001.add(townId);
        assertEquals(true, service.isMapVisited(2001, townId));

        // 2. 模拟隐藏地图: 999999000(隐藏地图)
        int hiddenMapId = 999999000;
        Field hiddenCacheField = QuestHelpService.class.getDeclaredField("hiddenMapCache");
        hiddenCacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, Boolean> hiddenCache = (Map<Integer, Boolean>) hiddenCacheField.get(service);
        hiddenCache.put(hiddenMapId, true);

        assertEquals(true, service.isHiddenMap(hiddenMapId));
        // 隐藏地图未访问自身时为 false
        assertEquals(false, service.isMapVisited(2001, hiddenMapId));

        // 玩家探索并访问隐藏地图自身
        visited2001.add(hiddenMapId);
        assertEquals(true, service.isMapVisited(2001, hiddenMapId));
    }

    @Test
    public void testMobKillUnitPriceByLevel() {
        QuestHelpService service = QuestHelpService.getInstance();

        // 1. 验证各等级段细致阶梯单价
        assertEquals(50, service.getMobKillUnitPriceByLevel(5));    // Lv 1~10
        assertEquals(100, service.getMobKillUnitPriceByLevel(15));  // Lv 11~20
        assertEquals(250, service.getMobKillUnitPriceByLevel(25));  // Lv 21~30
        assertEquals(600, service.getMobKillUnitPriceByLevel(35));  // Lv 31~40
        assertEquals(1200, service.getMobKillUnitPriceByLevel(45)); // Lv 41~50
        assertEquals(2500, service.getMobKillUnitPriceByLevel(55)); // Lv 51~60
        assertEquals(4500, service.getMobKillUnitPriceByLevel(65)); // Lv 61~70
        assertEquals(7500, service.getMobKillUnitPriceByLevel(75)); // Lv 71~80
        assertEquals(12000, service.getMobKillUnitPriceByLevel(85));// Lv 81~90
        assertEquals(18000, service.getMobKillUnitPriceByLevel(95));// Lv 91~100
        assertEquals(26000, service.getMobKillUnitPriceByLevel(105));// Lv 101~110
        assertEquals(36000, service.getMobKillUnitPriceByLevel(115));// Lv 111~120
        assertEquals(48000, service.getMobKillUnitPriceByLevel(125));// Lv 121~130
        assertEquals(65000, service.getMobKillUnitPriceByLevel(140));// Lv 131+

        // 2. 验证 71级 vs 100级 100只任务总价对比 (75万 vs 180万)
        assertEquals(750000L, 7500L * 100);
        assertEquals(1800000L, 18000L * 100);
    }

    @Test
    public void testPartialMobObjectiveSync() {
        // 场景 1：需求 20 只，当前 0 只，历史 10 只 -> 可填充 10 只，费用 10 * 50 = 500
        QuestHelpService.MobObjective obj1 = new QuestHelpService.MobObjective(100100, "蜗牛", 5, 0, 20, false, 10L, 50, null);
        assertEquals(20, obj1.getRequiredKills());
        assertEquals(0, obj1.getCurrentKills());
        assertEquals(10L, obj1.getAccountKills());
        assertEquals(10, obj1.getTargetKills());
        assertEquals(10, obj1.getSyncCount());
        assertEquals(true, obj1.isSyncable());
        assertEquals(false, obj1.isFullSync());
        assertEquals(500L, obj1.getTotalCost());

        // 场景 2：需求 20 只，当前 0 只，历史 50 只 -> 可直接补满 20 只，费用 20 * 50 = 1000
        QuestHelpService.MobObjective obj2 = new QuestHelpService.MobObjective(100100, "蜗牛", 5, 0, 20, false, 50L, 50, null);
        assertEquals(20, obj2.getTargetKills());
        assertEquals(20, obj2.getSyncCount());
        assertEquals(true, obj2.isSyncable());
        assertEquals(true, obj2.isFullSync());
        assertEquals(1000L, obj2.getTotalCost());

        // 场景 3：需求 20 只，当前 12 只，历史 10 只 -> 当前进度已超过历史击杀，不可填充 (syncCount = 0)
        QuestHelpService.MobObjective obj3 = new QuestHelpService.MobObjective(100100, "蜗牛", 5, 12, 20, false, 10L, 50, null);
        assertEquals(10, obj3.getTargetKills());
        assertEquals(0, obj3.getSyncCount());
        assertEquals(false, obj3.isSyncable());
        assertEquals(0L, obj3.getTotalCost());

        // 场景 4：Boss 怪物 -> 无论击杀数多少，始终不可同步
        QuestHelpService.MobObjective obj4 = new QuestHelpService.MobObjective(999999, "Boss", 100, 0, 1, true, 10L, 0, null);
        assertEquals(false, obj4.isSyncable());
        assertEquals(0, obj4.getSyncCount());
    }
}
