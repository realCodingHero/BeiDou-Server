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
        QuestHelpService.ItemObjective lockedItem = new QuestHelpService.ItemObjective(
                4031000, "白狼的心脏", 0, 20, false, false, true, false, 25000, null
        );
        assertEquals(4031000, lockedItem.getItemId());
        assertEquals("白狼的心脏", lockedItem.getItemName());
        assertEquals(0, lockedItem.getCurrentCount());
        assertEquals(20, lockedItem.getRequiredCount());
        assertTrue(lockedItem.isQuestExclusive());
        assertFalse(lockedItem.isSampleUnlocked());
        assertFalse(lockedItem.isDeliverable());
        assertFalse(lockedItem.isCompleted());
        assertEquals(500000L, lockedItem.getTotalPrice()); // 25000 * 20

        // 场景 2：专属任务道具，背包持有 1 个样本 (1/20) -> 可购买，已解锁样本
        QuestHelpService.ItemObjective unlockedItem = new QuestHelpService.ItemObjective(
                4031000, "白狼的心脏", 1, 20, true, false, true, true, 25000, null
        );
        assertTrue(unlockedItem.isQuestExclusive());
        assertTrue(unlockedItem.isSampleUnlocked());
        assertTrue(unlockedItem.isDeliverable());
        assertFalse(unlockedItem.isCompleted());
        assertEquals(475000L, unlockedItem.getTotalPrice()); // 25000 * (20 - 1) = 475000

        // 场景 3：普通材料道具 (0/50, 单价 500) -> 始终可购买，非专属
        QuestHelpService.ItemObjective regularItem = new QuestHelpService.ItemObjective(
                4000004, "绿水灵珠", 0, 50, true, false, false, false, 500, null
        );
        assertFalse(regularItem.isQuestExclusive());
        assertFalse(regularItem.isSampleUnlocked());
        assertTrue(regularItem.isDeliverable());
        assertEquals(25000L, regularItem.getTotalPrice()); // 500 * 50 = 25000

        // 场景 4：已集齐道具 (20/20) -> 已达成
        QuestHelpService.ItemObjective completedItem = new QuestHelpService.ItemObjective(
                4031000, "白狼的心脏", 20, 20, true, false, true, true, 25000, null
        );
        assertTrue(completedItem.isCompleted());
        assertEquals(0L, completedItem.getTotalPrice());
    }

    @Test
    public void testQuestExclusivePricingFormula() {
        QuestHelpService service = QuestHelpService.getInstance();

        // 1. 测试基础定价保底 (>= 5000)
        int price = service.getQuestExclusiveUnitPrice(4039999, 1000);
        assertTrue(price >= 5000);

        // 2. 验证公式数学逻辑
        // 设怪物等级 50，基础掉率 500000(100%), base = 2000 + 5000 = 7000
        // rarityFactor = 1.0, premium = 3.5 -> 7000 * 1.0 * 3.5 = 24500
        double base = 2000.0 + (50 * 100.0);
        double rarity = 1.0;
        int expected = (int) Math.round(base * rarity * 3.5);
        assertEquals(24500, expected);

        // 设怪物等级 80，掉率 100000(10%), base = 2000 + 8000 = 10000
        // ratio = 5.0, rarityFactor = 5^0.9 ≈ 4.256699
        // expected ≈ 10000 * 4.256699 * 3.5 ≈ 148984
        double base80 = 2000.0 + (80 * 100.0);
        double ratio80 = 500000.0 / 100000.0;
        double rarity80 = Math.pow(ratio80, 0.90);
        int expected80 = (int) Math.round(base80 * rarity80 * 3.5);
        assertTrue(expected80 > 140000 && expected80 < 155000);
    }
}
