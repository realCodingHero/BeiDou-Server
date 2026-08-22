import org.gms.server.quest.QuestHelpService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class QuestExclusiveItemPurchaseTest {

    @BeforeEach
    public void setUp() throws Exception {
        QuestHelpService service = QuestHelpService.getInstance();
        Field initField = QuestHelpService.class.getDeclaredField("initialized");
        initField.setAccessible(true);
        ((AtomicBoolean) initField.get(service)).set(true);
    }

    @Test
    public void testItemObjectiveSampleUnlockLogic() {
        // 场景 1：专属任务道具，背包无样本 (0/20) -> 不可购买，未解锁
        // 缺 20 个，单价 1000: 10 * 1000 + 10 * 1600 = 26000
        QuestHelpService.ItemObjective lockedItem = new QuestHelpService.ItemObjective(
                4031000, "白狼的心脏", 0, 20, false, false, true, false, 1000, null
        );
        assertEquals(4031000, lockedItem.getItemId());
        assertEquals("白狼的心脏", lockedItem.getItemName());
        assertEquals(0, lockedItem.getCurrentCount());
        assertEquals(20, lockedItem.getRequiredCount());
        assertTrue(lockedItem.isQuestExclusive());
        assertFalse(lockedItem.isSampleUnlocked());
        assertFalse(lockedItem.isDeliverable());
        assertFalse(lockedItem.isCompleted());
        assertEquals(26000L, lockedItem.getTotalPrice());

        // 场景 2：专属任务道具，背包持有 1 个样本 (1/20) -> 可购买，已解锁样本
        // 缺 19 个，单价 1000: 10 * 1000 + 9 * 1600 = 24400
        QuestHelpService.ItemObjective unlockedItem = new QuestHelpService.ItemObjective(
                4031000, "白狼的心脏", 1, 20, true, false, true, true, 1000, null
        );
        assertTrue(unlockedItem.isQuestExclusive());
        assertTrue(unlockedItem.isSampleUnlocked());
        assertTrue(unlockedItem.isDeliverable());
        assertFalse(unlockedItem.isCompleted());
        assertEquals(24400L, unlockedItem.getTotalPrice());

        // 场景 3：普通材料道具 (0/50, 单价 500) -> 线性计价，50 * 500 = 25000（不适用专属阶梯加价）
        QuestHelpService.ItemObjective regularItem = new QuestHelpService.ItemObjective(
                4000004, "绿水灵珠", 0, 50, true, false, false, false, 500, null
        );
        assertFalse(regularItem.isQuestExclusive());
        assertFalse(regularItem.isSampleUnlocked());
        assertTrue(regularItem.isDeliverable());
        assertEquals(25000L, regularItem.getTotalPrice());

        // 场景 4：已集齐道具 (20/20) -> 已达成
        QuestHelpService.ItemObjective completedItem = new QuestHelpService.ItemObjective(
                4031000, "白狼的心脏", 20, 20, true, false, true, true, 1000, null
        );
        assertTrue(completedItem.isCompleted());
        assertEquals(0L, completedItem.getTotalPrice());

        // 场景 5：无怪物掉落道具（例如仅通过敲箱子反应堆掉落的 4031092 机器配件） -> 严禁购买，无论是否持有样本
        QuestHelpService.ItemObjective reactorOnlyItem = new QuestHelpService.ItemObjective(
                4031092, "机器配件", 1, 10, false, false, false, false, 0, java.util.Collections.emptyList()
        );
        assertFalse(reactorOnlyItem.isQuestExclusive());
        assertFalse(reactorOnlyItem.isSampleUnlocked());
        assertFalse(reactorOnlyItem.isDeliverable());
        assertEquals(0L, reactorOnlyItem.getTotalPrice());
        assertTrue(reactorOnlyItem.getDropMobs().isEmpty());

        // 场景 6：仅要求 1 个的任务材料（例如任务 2017 艾温的玻璃鞋 4000014 需 1 个） -> 严禁购买
        QuestHelpService.ItemObjective singleQuantityItem = new QuestHelpService.ItemObjective(
                4000014, "玻璃鞋", 0, 1, true, false, false, false, 59600, null
        );
        assertFalse(singleQuantityItem.isDeliverable());
        assertEquals(0, singleQuantityItem.getUnitPrice());
        assertEquals(0L, singleQuantityItem.getTotalPrice());
    }

    @Test
    public void testCalculateTieredCostEscalation() {
        int baseUnitPrice = 1000;

        // 1. 买 5 个（1~10阶梯，1.0x）: 5 * 1000 = 5000
        assertEquals(5000L, QuestHelpService.calculateTieredCost(baseUnitPrice, 5));

        // 2. 买 10 个（1~10阶梯，1.0x）: 10 * 1000 = 10000
        assertEquals(10000L, QuestHelpService.calculateTieredCost(baseUnitPrice, 10));

        // 3. 买 20 个（10个 1.0x + 10个 1.6x）: 10000 + 16000 = 26000
        assertEquals(26000L, QuestHelpService.calculateTieredCost(baseUnitPrice, 20));

        // 4. 买 50 个（10个 1.0x + 20个 1.6x + 20个 2.5x）: 10000 + 32000 + 50000 = 92000
        assertEquals(92000L, QuestHelpService.calculateTieredCost(baseUnitPrice, 50));

        // 5. 买 85 个（10个 1.0x + 20个 1.6x + 30个 2.5x + 25个 4.0x）: 10000 + 32000 + 75000 + 100000 = 217000
        assertEquals(217000L, QuestHelpService.calculateTieredCost(baseUnitPrice, 85));

        // 6. 验证阶梯递增惩罚效果：买 85 个的单价均价 (2552) 远高于买 5 个的单价 (1000)
        long cost5 = QuestHelpService.calculateTieredCost(baseUnitPrice, 5);
        long cost85 = QuestHelpService.calculateTieredCost(baseUnitPrice, 85);
        assertTrue((double) cost85 / 85 > (double) cost5 / 5 * 2.5);
    }

    @Test
    public void testAnimalFossilAnchorPricingFormula() {
        // 验证绿蘑菇动物化石锚定计算
        // 绿蘑菇常规杂物回收价 10 金币 -> 基础价 10 * 150 = 1500 金币
        // 掉率 100% (1000000) -> rarityFactor = (500000 / 1000000)^0.85 = 0.5^0.85 ≈ 0.5547
        // 预期基础单价 = 1500 * 0.5547 ≈ 832 金币
        double basePrice = 10 * 150.0;
        double ratio = 500000.0 / 1000000.0;
        double rarityFactor = Math.pow(ratio, 0.85);
        int expectedUnitPrice = (int) Math.round(basePrice * rarityFactor);
        assertEquals(832, expectedUnitPrice);

        // 缺 5 个化石时只需: 5 * 832 = 4160 金币
        assertEquals(4160L, QuestHelpService.calculateTieredCost(expectedUnitPrice, 5));

        // 缺 85 个化石时（惩罚全额逃课）:
        // 10 * 832 + 20 * 1331 + 30 * 2080 + 25 * 3328 = 8320 + 26620 + 62400 + 83200 = 180540
        assertEquals(180540L, QuestHelpService.calculateTieredCost(expectedUnitPrice, 85));
    }

    @Test
    public void testDropReactorInfoAndItemObjective() {
        QuestHelpService.MapLocation map1 = new QuestHelpService.MapLocation(101010000, "魔法密林南郊", "魔法密林");
        QuestHelpService.DropReactorInfo dropReactor = new QuestHelpService.DropReactorInfo(
                1012000, "魔法密林花草/植物", 3, "1/3", 2067, java.util.List.of(map1)
        );

        assertEquals(1012000, dropReactor.getReactorId());
        assertEquals("魔法密林花草/植物", dropReactor.getReactorName());
        assertEquals(3, dropReactor.getChance());
        assertEquals("1/3", dropReactor.getChanceText());
        assertEquals(2067, dropReactor.getQuestId());
        assertEquals(1, dropReactor.getMaps().size());
        assertEquals(101010000, dropReactor.getMaps().get(0).getMapId());

        QuestHelpService.ItemObjective plantSample = new QuestHelpService.ItemObjective(
                4031150, "植物样品", 0, 20, false, false, false, false, 0,
                java.util.Collections.emptyList(), java.util.List.of(dropReactor)
        );

        assertEquals(4031150, plantSample.getItemId());
        assertEquals("植物样品", plantSample.getItemName());
        assertTrue(plantSample.getDropMobs().isEmpty());
        assertFalse(plantSample.getDropReactors().isEmpty());
        assertEquals(1, plantSample.getDropReactors().size());
        assertEquals(1012000, plantSample.getDropReactors().get(0).getReactorId());
    }

    @Test
    public void testPropagateMobReviveMapsAndKeychainsPurchasable() throws Exception {
        QuestHelpService service = QuestHelpService.getInstance();

        // 模拟向 mobToMaps 中注入母体怪物刷新地图
        Field mobToMapsField = QuestHelpService.class.getDeclaredField("mobToMaps");
        mobToMapsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<Integer, java.util.Set<Integer>> mobToMaps =
                (java.util.Map<Integer, java.util.Set<Integer>>) mobToMapsField.get(service);

        mobToMaps.computeIfAbsent(3400003, k -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(103040200); // 娃娃机 1
        mobToMaps.computeIfAbsent(3400005, k -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(103040201); // 娃娃机 2
        mobToMaps.computeIfAbsent(6130201, k -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(222010400); // 初始小鬼怪

        // 模拟注入 mapLocationCache（单测环境下无需初始化 Spring/MapFactory）
        Field mapLocationCacheField = QuestHelpService.class.getDeclaredField("mapLocationCache");
        mapLocationCacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<Integer, QuestHelpService.MapLocation> mapLocationCache =
                (java.util.Map<Integer, QuestHelpService.MapLocation>) mapLocationCacheField.get(service);
        mapLocationCache.put(103040200, new QuestHelpService.MapLocation(103040200, "废弃广场5层6层", "废弃都市"));
        mapLocationCache.put(103040201, new QuestHelpService.MapLocation(103040201, "废弃广场5层6层", "废弃都市"));
        mapLocationCache.put(222010400, new QuestHelpService.MapLocation(222010400, "童话村隐藏地图", "童话村"));

        // 构造母子怪 revive 映射关系
        java.util.Map<Integer, java.util.Set<Integer>> parentToChildren = new java.util.HashMap<>();
        parentToChildren.put(3400003, java.util.Set.of(3400004));
        parentToChildren.put(3400005, java.util.Set.of(3400006));
        parentToChildren.put(6130201, java.util.Set.of(6130202));

        // 执行复活地图传播
        service.propagateMobReviveMaps(parentToChildren);

        // 校验子怪成功继承母怪的野外刷新地图
        java.util.List<QuestHelpService.MapLocation> yetiMaps = service.getMapsForMob(3400004);
        assertFalse(yetiMaps.isEmpty());
        assertEquals(103040200, yetiMaps.get(0).getMapId());

        java.util.List<QuestHelpService.MapLocation> pepeMaps = service.getMapsForMob(3400006);
        assertFalse(pepeMaps.isEmpty());
        assertEquals(103040201, pepeMaps.get(0).getMapId());

        java.util.List<QuestHelpService.MapLocation> ghostMaps = service.getMapsForMob(6130202);
        assertFalse(ghostMaps.isEmpty());
        assertEquals(222010400, ghostMaps.get(0).getMapId());

        // 模拟 2275 号任务目标：白雪人钥匙链(4000542 x50) 与 小企鹅王钥匙链(4000543 x50)
        QuestHelpService.DropMobInfo yetiDrop = new QuestHelpService.DropMobInfo(
                3400004, "白雪人娃娃", 400000, "40.00%", false, yetiMaps
        );
        QuestHelpService.ItemObjective yetiKeychain = new QuestHelpService.ItemObjective(
                4000542, "白雪人钥匙链", 0, 50, true, false, false, false, 320,
                java.util.List.of(yetiDrop)
        );

        assertTrue(yetiKeychain.isDeliverable());
        assertFalse(yetiKeychain.isQuestExclusive());
        assertEquals(16000L, yetiKeychain.getTotalPrice());
        assertFalse(yetiKeychain.getDropMobs().isEmpty());
        assertEquals(103040200, yetiKeychain.getDropMobs().get(0).getMaps().get(0).getMapId());
    }
}

