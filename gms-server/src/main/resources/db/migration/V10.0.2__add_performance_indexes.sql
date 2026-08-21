-- -------------------------------------------------------------
-- Flyway Migration: V10.0.2__add_performance_indexes.sql
-- Description: Add missing performance indexes for high-frequency queries
-- -------------------------------------------------------------

-- 1. 任务系统索引（支持角色登录与任务状态快速检索/删除）
CREATE INDEX IF NOT EXISTS idx_queststatus_char_quest ON queststatus (characterid, quest);
CREATE INDEX IF NOT EXISTS idx_questprogress_char_status ON questprogress (characterid, queststatusid);

-- 2. 商店系统索引（支持按 NPC 与 ShopID 快速定位商品）
CREATE INDEX IF NOT EXISTS idx_shops_npcid ON shops (npcid);
CREATE INDEX IF NOT EXISTS idx_shopitems_shop_pos ON shopitems (shopid, position);

-- 3. 扭蛋机奖励池索引
CREATE INDEX IF NOT EXISTS idx_gachapon_reward_pool_item ON gachapon_reward (pool_id, item_id);
