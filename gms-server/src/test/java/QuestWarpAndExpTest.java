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

        killsCache.computeIfAbsent(1001, k -> new ConcurrentHashMap<>()).put(100100, 150L);
        assertEquals(150L, service.getAccountMobKills(1001, 100100));
        assertEquals(0L, service.getAccountMobKills(1001, 999999));
    }
}
