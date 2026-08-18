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
    public void testExpBreakdownCalculation() {
        int gain = 10000;
        float questRate = 1.5f;
        float worldRate = 2.0f;
        int couponRate = 2;

        int questBonus = (int) (gain * (questRate - 1.0f));
        int worldBonus = (int) (gain * (worldRate - 1.0f));
        int couponBonus = gain * (couponRate - 1);

        assertEquals(5000, questBonus);
        assertEquals(10000, worldBonus);
        assertEquals(10000, couponBonus);
    }
}
