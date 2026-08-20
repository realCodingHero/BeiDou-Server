/**
 * @description 任务辅助 NPC 脚本
 * 提供任务状态分类（可完成/进行中）、普通材料一键补齐（带背包空间安全校验）、
 * 杀怪目标地图传送、道具掉落怪物反查传送、起止 NPC 城镇直达。
 * 采用显式状态机架构，确保“结束对话/返回”在各级菜单中均能精准返回上一层界面。
 */

var STATE_MAIN_MENU = 0;    // 主菜单
var STATE_QUEST_LIST = 1;   // 任务列表（可交付 / 进行中）
var STATE_QUEST_DETAIL = 2; // 任务详情
var STATE_SUB_MENU = 3;     // 怪物地图列表 / 掉落怪物列表 / NPC地图列表
var STATE_MAP_LIST = 4;     // 掉落怪物的分布地图列表

var currentState = STATE_MAIN_MENU;
var selectedCategory = 1; // 1: 可交付任务, 2: 进行中任务
var selectedQuestId = 0;
var currentDetail = null;
var selectedItem = null;
var currentMapList = null;
var pendingNotice = null;
var pendingConfirmAction = null; // { type: 'ALL' | 'MOB' | 'ITEM', id: number, cost: number }

function start() {
    currentState = STATE_MAIN_MENU;
    selectedCategory = 1;
    selectedQuestId = 0;
    currentDetail = null;
    selectedItem = null;
    currentMapList = null;
    pendingNotice = null;
    pendingConfirmAction = null;
    showMainMenu();
}

function action(mode, type, selection) {
    try {
        // 1. 处理强行关闭 (mode === -1)
        if (mode === -1) {
            cm.dispose();
            return;
        }

        // 2. 处理“结束对话” / 取消 / 否 (mode === 0)
        if (mode === 0) {
            if (pendingConfirmAction != null) {
                // 二次确认弹窗中选择了“否” -> 返回任务详情页
                pendingConfirmAction = null;
                currentState = STATE_QUEST_DETAIL;
                showQuestDetail(selectedQuestId);
                return;
            }

            // 无论当前处于哪一层级，点击【结束对话】或按 Esc 均立即彻底退出
            cm.dispose();
            return;
        }

        // 3. 处理正常交互与二次确认“是” (mode === 1)
        if (pendingConfirmAction != null) {
            var confirmAction = pendingConfirmAction;
            pendingConfirmAction = null;
            var service = cm.getQuestHelp();
            var res = null;
            if (confirmAction.type === 'ALL') {
                res = service.deliverAllQuestObjectives(cm.getPlayer(), selectedQuestId);
            } else if (confirmAction.type === 'MOB') {
                res = service.syncQuestMobKill(cm.getPlayer(), selectedQuestId, confirmAction.id);
            } else if (confirmAction.type === 'ITEM') {
                res = service.deliverQuestMaterial(cm.getPlayer(), selectedQuestId, confirmAction.id);
            }
            if (res != null) {
                pendingNotice = res.isSuccess() ? "#d" + res.getMessage() + "#k" : "#r" + res.getMessage() + "#k";
            }
            currentState = STATE_QUEST_DETAIL;
            showQuestDetail(selectedQuestId);
            return;
        }

        // 4. 根据当前状态分发正常菜单选择
        if (currentState === STATE_MAIN_MENU) {
            handleMainMenuSelection(selection);
        } else if (currentState === STATE_QUEST_LIST) {
            handleQuestListSelection(selection);
        } else if (currentState === STATE_QUEST_DETAIL) {
            handleDetailSelection(selection);
        } else if (currentState === STATE_SUB_MENU) {
            handleSubSelection(selection);
        } else if (currentState === STATE_MAP_LIST) {
            handleMapListSelection(selection);
        } else {
            cm.dispose();
        }
    } catch (e) {
        cm.sendOk("任务辅助执行错误：" + e);
        cm.dispose();
    }
}

/**
 * 界面 0：主菜单（可交付任务 vs 进行中任务）
 */
function showMainMenu() {
    currentState = STATE_MAIN_MENU;
    var service = cm.getQuestHelp();
    if (!service) {
        cm.sendOk("任务辅助服务暂不可用。");
        return;
    }

    var canCompleteQuests = service.getCanCompleteQuests(cm.getPlayer());
    var inProgressQuests = service.getInProgressQuests(cm.getPlayer());

    var text = "\t\t\t\t#e#r★ BeiDou 任务辅助助手 ★#k#n\r\n\r\n";
    text += "在这里您可以查看当前所有已接取的任务进度、一键导航至起止 NPC、快捷传送至怪物地图，以及一键补齐普通任务材料。\r\n\r\n";

    if (canCompleteQuests.size() > 0) {
        text += "#L1##b★ 查看当前可直接交付的任务#k #r(" + canCompleteQuests.size() + " 个已达成)#k#l\r\n";
    } else {
        text += "#L1##d★ 查看当前可交付的任务 (暂无可交付任务)#k#l\r\n";
    }

    text += "#L2##b★ 查看当前进行中的任务列表#k #d(" + inProgressQuests.size() + " 个进行中)#k#l\r\n\r\n";
    text += "#L999999##b[返回昨日小睡主菜单]#k#l";

    cm.sendSimple(text);
}

function handleMainMenuSelection(selection) {
    if (selection === 999999) {
        cm.dispose();
        cm.openNpc(9900001);
        return;
    }

    selectedCategory = selection;
    showQuestList(selectedCategory);
}

/**
 * 界面 1：展示任务列表（分类 1: 可交付, 分类 2: 进行中）
 */
function showQuestList(category) {
    currentState = STATE_QUEST_LIST;
    var service = cm.getQuestHelp();
    var list = (category === 1) ? service.getCanCompleteQuests(cm.getPlayer()) : service.getInProgressQuests(cm.getPlayer());

    var categoryTitle = (category === 1) ? "可直接交付的任务" : "进行中的任务";
    var text = "#e#b【 " + categoryTitle + " 】 (共 " + list.size() + " 个)#k#n\r\n\r\n";

    if (list.size() === 0) {
        text += (category === 1) ? "当前没有任何已达成全部条件的任务。\r\n" : "您当前尚未接取任何任务。\r\n";
        text += "\r\n#L999999##b◀ [返回主菜单]#k#l";
        cm.sendSimple(text);
        return;
    }

    for (var i = 0; i < list.size(); i++) {
        var item = list.get(i);
        var tag = "";
        if (item.isCanComplete()) {
            tag = " #r[可交付]#k";
        } else if (item.isPurchasableComplete()) {
            tag = " #d[可一键补齐]#k";
        }
        text += "#L" + item.getQuestId() + "# [Lv." + item.getMinLevel() + "] #b" + item.getQuestName() + "#k" + tag + "#l\r\n";
    }

    text += "\r\n#L999999##b◀ [返回主菜单]#k#l";
    cm.sendSimple(text);
}

function handleQuestListSelection(selection) {
    if (selection === 999999) {
        showMainMenu();
        return;
    }

    selectedQuestId = selection;
    showQuestDetail(selectedQuestId);
}

/**
 * 界面 2：展示指定任务的详细进度与各项操作
 */
function showQuestDetail(questId) {
    currentState = STATE_QUEST_DETAIL;
    selectedQuestId = questId;
    var service = cm.getQuestHelp();
    currentDetail = service.getQuestDetailInfo(cm.getPlayer(), questId);

    if (!currentDetail) {
        cm.sendOk("未获取到任务详细信息，可能任务已被放弃或已完成。");
        currentState = STATE_MAIN_MENU;
        return;
    }

    var text = "#e#b【 任务详情 】 " + currentDetail.getQuestName() + " (ID: " + questId + ")#k#n\r\n";

    if (pendingNotice) {
        text += "\r\n" + pendingNotice + "\r\n\r\n";
        pendingNotice = null;
    } else {
        text += "\r\n";
    }

    var mobObjs = currentDetail.getMobObjectives();
    var itemObjs = currentDetail.getItemObjectives();
    var startNpc = currentDetail.getStartNpc();
    var compNpc = currentDetail.getCompleteNpc();

    // 检查是否有可同步的怪物击杀与可补齐的普通材料
    var hasSyncableMobs = currentDetail.hasSyncableMobKills();
    var hasDeliverableIncomplete = currentDetail.hasDeliverableIncompleteItems();
    var totalMobCost = currentDetail.getTotalSyncableMobsCost();
    var totalMatCost = currentDetail.getTotalRegularMaterialsCost();
    var combinedCost = currentDetail.getTotalCostWithMobsAndMaterials();

    if (hasSyncableMobs && hasDeliverableIncomplete) {
        text += "#L10000##k【 #d★ 一键同步账号击杀并购买全部普通材料#k 】 #d(" + combinedCost + " 金币)#k#l\r\n\r\n";
    } else if (hasSyncableMobs) {
        text += "#L10000##k【 #d★ 一键同步本任务全部满足条件的账号怪物击杀#k 】 #d(" + totalMobCost + " 金币)#k#l\r\n\r\n";
    } else if (hasDeliverableIncomplete) {
        text += "#L10000##k【 #d★ 一键购买补齐本任务全部普通/商店材料#k 】 #d(" + totalMatCost + " 金币)#k#l\r\n\r\n";
    }

    var hasContent = false;

    // 1. 击杀目标
    if (mobObjs && mobObjs.size() > 0) {
        hasContent = true;
        text += "#e【 击杀怪物目标 】#n\r\n";
        for (var i = 0; i < mobObjs.size(); i++) {
            var mob = mobObjs.get(i);
            var isDone = mob.isCompleted();
            var progTag = isDone ? "#b(" + mob.getCurrentKills() + "/" + mob.getRequiredKills() + ") [已达成]#k" : "#r(" + mob.getCurrentKills() + "/" + mob.getRequiredKills() + ") [未完成]#k";

            if (mob.isBoss()) {
                if (isDone) {
                    text += " 消灭 【#rBoss - " + mob.getMobName() + "#k】 " + progTag + "\r\n\r\n";
                } else {
                    text += " 消灭 【#rBoss - " + mob.getMobName() + "#k】 " + progTag + " #r[Boss需亲自消灭]#k\r\n\r\n";
                }
            } else if (isDone) {
                text += " 消灭 【#b" + mob.getMobName() + " (Lv." + mob.getMobLevel() + ")#k】 " + progTag + "\r\n\r\n";
            } else {
                text += "#L" + (100000 + i) + "# 消灭 【#b" + mob.getMobName() + " (Lv." + mob.getMobLevel() + ")#k】 (#r" + mob.getCurrentKills() + "/" + mob.getRequiredKills() + "#k) -> #d[传送]#k#l\r\n";
                if (mob.isPurchasable()) {
                    var needed = mob.getRequiredKills() - mob.getCurrentKills();
                    if (mob.getPurchasableKills() >= needed) {
                        text += "#L" + (150000 + i) + "#   └─ #d[可用/历史总击杀数:" + mob.getAvailableKills() + "/" + mob.getTotalAccountKills() + " ,消耗 " + mob.getTotalCost() + "金币补齐余下全部" + mob.getPurchasableKills() + "只]#k#l\r\n";
                    } else {
                        text += "#L" + (150000 + i) + "#   └─ #d[可用/历史总击杀数:" + mob.getAvailableKills() + "/" + mob.getTotalAccountKills() + " ,消耗 " + mob.getTotalCost() + "金币获得" + mob.getPurchasableKills() + "只击杀]#k#l\r\n";
                    }
                } else {
                    var needed = mob.getRequiredKills() - mob.getCurrentKills();
                    text += "#L" + (100000 + i) + "#   └─ #d(可用/历史总击杀数:0/" + mob.getTotalAccountKills() + " ,需手动消灭剩余 " + needed + "只)#k#l\r\n";
                }
                text += "\r\n";
            }
        }
    }

    // 2. 收集道具目标
    if (itemObjs && itemObjs.size() > 0) {
        hasContent = true;
        text += "#e【 收集道具目标 】#n\r\n";
        for (var i = 0; i < itemObjs.size(); i++) {
            var item = itemObjs.get(i);
            var isDone = item.isCompleted();
            var progTag = isDone ? "#b(" + item.getCurrentCount() + "/" + item.getRequiredCount() + ") [已达成]#k" : "#r(" + item.getCurrentCount() + "/" + item.getRequiredCount() + ") [未完成]#k";
            var diff = item.getRequiredCount() - item.getCurrentCount();

            if (isDone) {
                text += " 收集 #v" + item.getItemId() + "# 【#b" + item.getItemName() + "#k】 " + progTag + "\r\n\r\n";
            } else {
                text += "#L" + (200000 + i) + "# 收集 #v" + item.getItemId() + "# 【#b" + item.getItemName() + "#k】 (#r" + item.getCurrentCount() + "/" + item.getRequiredCount() + "#k) -> #d[掉落]#k#l\r\n";
                if (item.isDeliverable()) {
                    text += "#L" + (250000 + i) + "#   └─ #d[购买补齐: 缺 " + diff + "个 -> 消耗 " + item.getTotalPrice() + " 金币]#k#l\r\n";
                } else {
                    text += "#L" + (200000 + i) + "#   └─ #r(剧情/特殊道具需手动获取)#k#l\r\n";
                }
                text += "\r\n";
            }
        }
    }

    // 3. NPC 导航
    if (startNpc || compNpc) {
        hasContent = true;
        text += "#e【 NPC 导航传送 】#n\r\n";
        if (startNpc) {
            var startMap = (startNpc.getMaps() && startNpc.getMaps().size() > 0) ? startNpc.getMaps().get(0) : null;
            var locStr = formatLocationName(startMap);
            var costStr = startMap && startMap.getWarpCost() > 0 ? " [费用: " + startMap.getWarpCost() + "金币]" : "";
            text += "#L300001# 接取NPC：#b" + startNpc.getNpcName() + "#k (" + locStr + ")" + costStr + "#l\r\n";
        }
        if (compNpc) {
            var compMap = (compNpc.getMaps() && compNpc.getMaps().size() > 0) ? compNpc.getMaps().get(0) : null;
            var locStr = formatLocationName(compMap);
            var costStr = compMap && compMap.getWarpCost() > 0 ? " [费用: " + compMap.getWarpCost() + "金币]" : "";
            text += "#L300002# 交付NPC：#b" + compNpc.getNpcName() + "#k (" + locStr + ")" + costStr + "#l\r\n";
        }
        text += "\r\n";
    }

    if (!hasContent) {
        text += "该任务为纯对话/探索类任务，无需特定击杀或物品收集。\r\n\r\n";
    }

    text += "#L999999##b◀ [返回任务列表]#k#l";
    cm.sendSimple(text);
}

function handleDetailSelection(selection) {
    if (selection === 999999) {
        showQuestList(selectedCategory);
        return;
    }

    // 一键同步账号击杀并购买全部普通材料 -> 二次确认弹窗
    if (selection === 10000) {
        var totalMobCost = currentDetail.getTotalSyncableMobsCost();
        var totalMatCost = currentDetail.getTotalRegularMaterialsCost();
        var combinedCost = currentDetail.getTotalCostWithMobsAndMaterials();

        var confirmText = "#e#b【一键完成任务目标确认】#k#n\r\n\r\n"
            + "本次一键操作将包含：\r\n"
            + " - 同步本任务所有可用的账号历史怪物击杀\r\n"
            + " - 购买补齐本任务所有可购买的普通/商店材料\r\n\r\n"
            + "本次一键结算共计消耗：\r\n"
            + " - 怪物同步金币：#r" + totalMobCost + "#k 金币\r\n"
            + " - 材料购买金币：#r" + totalMatCost + "#k 金币\r\n"
            + " - 合计所需金币：#r" + combinedCost + "#k 金币\r\n"
            + " - 当前持有金币：#b" + cm.getPlayer().getMeso() + "#k 金币\r\n\r\n"
            + "#e是否确认支付金币并立即一键完成？#n";

        pendingConfirmAction = { type: 'ALL', id: 0, cost: combinedCost };
        cm.sendYesNo(confirmText);
        return;
    }

    // 单项购买指定怪物的账号历史击杀 -> 二次确认弹窗
    if (selection >= 150000 && selection < 200000) {
        var index = selection - 150000;
        var mob = currentDetail.getMobObjectives().get(index);
        var buyCount = mob.getPurchasableKills();

        var confirmText = "#e#b【快速完成怪物击杀确认】#k#n\r\n\r\n"
            + "本次任务目标：\r\n"
            + " - 目标怪物：#b" + mob.getMobName() + " (Lv." + mob.getMobLevel() + ")#k\r\n"
            + " - 当前任务进度：#b" + mob.getCurrentKills() + "/" + mob.getRequiredKills() + "#k 只\r\n"
            + " - 账号历史总数：#b" + mob.getTotalAccountKills() + "#k 只\r\n"
            + " - 本次购买注入：#b" + buyCount + "#k 只\r\n\r\n"
            + "本次快速结算将消耗：\r\n"
            + " - 击杀单价：#r" + mob.getUnitPrice() + "#k 金币\r\n"
            + " - 所需金币：#r" + buyCount + "只 * " + mob.getUnitPrice() + "金币/只 = " + mob.getTotalCost() + " 金币#k\r\n"
            + " - 当前持有金币：#b" + cm.getPlayer().getMeso() + "#k 金币\r\n\r\n"
            + "#e是否确认支付金币购买并注入 " + buyCount + " 只击杀？#n";

        pendingConfirmAction = { type: 'MOB', id: mob.getMobId(), cost: mob.getTotalCost() };
        cm.sendYesNo(confirmText);
        return;
    }

    // 单项补齐指定普通材料/商店道具 -> 二次确认弹窗
    if (selection >= 250000 && selection < 300000) {
        var index = selection - 250000;
        var item = currentDetail.getItemObjectives().get(index);
        var diff = item.getRequiredCount() - item.getCurrentCount();

        var confirmText = "#e#b【购买补齐材料确认】#k#n\r\n\r\n"
            + "本次任务目标：\r\n"
            + " - 收集道具：#v" + item.getItemId() + "# 【#b" + item.getItemName() + "#k】 x " + diff + " 个\r\n\r\n"
            + "本次购买补齐将消耗：\r\n"
            + " - 道具单价：#r" + item.getUnitPrice() + "#k 金币\r\n"
            + " - 所需金币：#r" + item.getTotalPrice() + "#k 金币\r\n"
            + " - 当前持有金币：#b" + cm.getPlayer().getMeso() + "#k 金币\r\n\r\n"
            + "#e是否确认支付金币并购买补齐？#n";

        pendingConfirmAction = { type: 'ITEM', id: item.getItemId(), cost: item.getTotalPrice() };
        cm.sendYesNo(confirmText);
        return;
    }

    // 击杀怪物 -> 显示怪物地图列表
    if (selection >= 100000 && selection < 200000) {
        var index = selection - 100000;
        var mob = currentDetail.getMobObjectives().get(index);
        showMobMapList(mob);
        return;
    }

    // 道具收集 -> 显示掉落怪物列表
    if (selection >= 200000 && selection < 250000) {
        var index = selection - 200000;
        selectedItem = currentDetail.getItemObjectives().get(index);
        showDropMobList(selectedItem);
        return;
    }

    // 接取 NPC 传送
    if (selection === 300001) {
        var startNpc = currentDetail.getStartNpc();
        showNpcMapList(startNpc, "接取");
        return;
    }

    // 交付 NPC 传送
    if (selection === 300002) {
        var compNpc = currentDetail.getCompleteNpc();
        showNpcMapList(compNpc, "交付");
        return;
    }

    cm.dispose();
}

/**
 * 界面 3-A：展示怪物的野外分布地图
 */
function showMobMapList(mob) {
    if (!mob) {
        showQuestDetail(selectedQuestId);
        return;
    }
    if (mob.isBoss()) {
        cm.sendOk("怪物 【" + mob.getMobName() + "】 为 Boss 怪物，为了游戏平衡与挑战流程，已关闭直接传送至 Boss 房间的功能，请自行前往挑战！");
        return;
    }
    currentMapList = mob.getMaps();

    if (!currentMapList || currentMapList.size() === 0) {
        cm.sendOk("未检索到怪物 【" + mob.getMobName() + "】 的野外分布地图。");
        return;
    }

    currentState = STATE_SUB_MENU;
    var text = "#e#b怪物 【" + mob.getMobName() + "】 出现在以下地图：#k#n\r\n请选择传送目的地：\r\n\r\n";
    for (var i = 0; i < currentMapList.size(); i++) {
        var map = currentMapList.get(i);
        text += "#L" + (500000 + i) + "# " + getMapDisplayWithLock(map) + "#l\r\n";
    }
    text += "\r\n#L999998##b◀ [返回任务详情]#k#l";
    cm.sendSimple(text);
}

/**
 * 界面 3-B：展示道具的掉落怪物列表
 */
function showDropMobList(item) {
    if (!item) {
        showQuestDetail(selectedQuestId);
        return;
    }
    var dropMobs = item.getDropMobs();

    if (!dropMobs || dropMobs.size() === 0) {
        cm.sendOk("道具 【#b" + item.getItemName() + "#k】 暂无野外怪物直接掉落数据（可能为任务剧情专有道具、箱子掉落或商店购买）。");
        return;
    }

    currentState = STATE_SUB_MENU;
    var text = "#e#b掉落道具 【" + item.getItemName() + "】 的怪物列表：#k#n\r\n点击怪物查看分布地图并传送：\r\n\r\n";
    for (var i = 0; i < dropMobs.size(); i++) {
        var dropMob = dropMobs.get(i);
        if (dropMob.isBoss()) {
            text += " " + dropMob.getMobName() + " #r[Boss]#k (掉率: " + dropMob.getChanceText() + ") #r[Boss怪物，已关闭直达传送]#k\r\n";
        } else {
            text += "#L" + (400000 + i) + "# " + dropMob.getMobName() + " (掉率: " + dropMob.getChanceText() + ", 地图数: " + dropMob.getMaps().size() + ")#l\r\n";
        }
    }
    text += "\r\n#L999998##b◀ [返回任务详情]#k#l";
    cm.sendSimple(text);
}

/**
 * 界面 3-C：展示 NPC 的所在地图列表
 */
function showNpcMapList(npc, npcType) {
    if (!npc) {
        showQuestDetail(selectedQuestId);
        return;
    }
    currentMapList = npc.getMaps();
    if (!currentMapList || currentMapList.size() === 0) {
        cm.sendOk("未找到 NPC 【" + npc.getNpcName() + "】 的所在地图数据。");
        return;
    }
    if (currentMapList.size() === 1) {
        var targetMap = currentMapList.get(0);
        tryWarpPlayer(targetMap, "已传送至" + npcType + " NPC 【" + npc.getNpcName() + "】 所在地图：");
        cm.dispose();
        return;
    }

    currentState = STATE_SUB_MENU;
    var text = "#e#b" + npcType + " NPC 【" + npc.getNpcName() + "】 所在地图：#k#n\r\n请选择目的地：\r\n\r\n";
    for (var i = 0; i < currentMapList.size(); i++) {
        var map = currentMapList.get(i);
        text += "#L" + (500000 + i) + "# " + getMapDisplayWithLock(map) + "#l\r\n";
    }
    text += "\r\n#L999998##b◀ [返回任务详情]#k#l";
    cm.sendSimple(text);
}

function handleSubSelection(selection) {
    if (selection === 999998) {
        showQuestDetail(selectedQuestId);
        return;
    }

    // 直接地图传送 (来自怪物地图或 NPC 地图)
    if (selection >= 500000 && selection < 600000) {
        var mapIndex = selection - 500000;
        if (currentMapList && mapIndex < currentMapList.size()) {
            var targetMap = currentMapList.get(mapIndex);
            tryWarpPlayer(targetMap);
        }
        cm.dispose();
        return;
    }

    // 点击了掉落怪物 -> 进入界面 4：展示该怪物的分布地图列表
    if (selection >= 400000 && selection < 500000) {
        var index = selection - 400000;
        if (selectedItem && selectedItem.getDropMobs() && index < selectedItem.getDropMobs().size()) {
            var dropMob = selectedItem.getDropMobs().get(index);
            showDropMobMapList(dropMob);
        } else {
            showQuestDetail(selectedQuestId);
        }
        return;
    }

    cm.dispose();
}

/**
 * 界面 4：展示具体掉落怪物的分布地图列表
 */
function showDropMobMapList(dropMob) {
    if (!dropMob) {
        showDropMobList(selectedItem);
        return;
    }
    if (dropMob.isBoss()) {
        cm.sendOk("怪物 【" + dropMob.getMobName() + "】 为 Boss 怪物，为了游戏平衡与挑战流程，已关闭直接传送至 Boss 房间的功能，请自行前往挑战！");
        return;
    }
    currentMapList = dropMob.getMaps();

    if (!currentMapList || currentMapList.size() === 0) {
        cm.sendOk("未找到怪物 【" + dropMob.getMobName() + "】 的野外地图数据。");
        return;
    }

    currentState = STATE_MAP_LIST;
    var itemName = selectedItem ? selectedItem.getItemName() : "";
    var text = "#e#b怪物 【" + dropMob.getMobName() + "】 (掉落: " + itemName + ") 分布地图：#k#n\r\n请选择传送目的地：\r\n\r\n";
    for (var i = 0; i < currentMapList.size(); i++) {
        var map = currentMapList.get(i);
        text += "#L" + (500000 + i) + "# " + getMapDisplayWithLock(map) + "#l\r\n";
    }
    text += "\r\n#L999997##b◀ [返回掉落怪物列表]#k#l";
    cm.sendSimple(text);
}

function handleMapListSelection(selection) {
    if (selection === 999997) {
        showDropMobList(selectedItem);
        return;
    }

    if (selection >= 500000 && selection < 600000) {
        var mapIndex = selection - 500000;
        if (currentMapList && mapIndex < currentMapList.size()) {
            var targetMap = currentMapList.get(mapIndex);
            tryWarpPlayer(targetMap);
        }
        cm.dispose();
        return;
    }

    cm.dispose();
}

function formatLocationName(map) {
    if (!map) return "未知地图";
    var street = map.getStreetName() ? ("" + map.getStreetName()).trim() : "";
    var name = map.getMapName() ? ("" + map.getMapName()).trim() : "";
    if (street !== "" && name !== "" && street !== name) {
        var combined = street + " - " + name;
        return (combined.length <= 16) ? combined : name;
    }
    return (name !== "") ? name : (street !== "" ? street : "地图 (" + map.getMapId() + ")");
}

function getMapDisplayWithLock(map) {
    var service = cm.getQuestHelp();
    var tag = "";
    if (service && !service.isMapWarpUnlocked(cm.getPlayer(), map.getMapId())) {
        var reason = service.getWarpLockReason(cm.getPlayer(), map.getMapId());
        var townName = service.getTownNameForMap(map.getMapId());
        if (reason === "需先访问主城" && townName && townName !== "未知主城") {
            tag = " #r[需先访问" + townName + "]#k";
        } else {
            tag = " #r[" + (reason ? reason : "需先访问主城") + "]#k";
        }
    }
    return map.getDisplayName() + tag;
}

/**
 * 统一传送扣费与金币校验方法
 */
function tryWarpPlayer(targetMap, noticePrefix) {
    var service = cm.getQuestHelp();
    var mapId = targetMap.getMapId();
    if (service && !service.isMapWarpUnlocked(cm.getPlayer(), mapId)) {
        if (service.isHiddenMap(mapId) || service.getTownIdForMap(mapId) <= 0) {
            cm.sendOk("目的地 【#b" + targetMap.getDisplayName() + "#k】 为隐藏/特殊区域，您尚未亲自探索过！\r\n必须先亲自找到并前往该地图一次后，方可使用直达传送。");
        } else {
            var townName = service.getTownNameForMap(mapId);
            var townStr = (townName && townName !== "未知主城") ? "【#b" + townName + "#k】" : "该区域的主城";
            cm.sendOk("您尚未探索并访问过 " + townStr + "！\r\n请先亲自前往探索该主城后，方可解锁直达传送。");
        }
        return false;
    }

    var cost = targetMap.getWarpCost();
    if (cost > 0 && cm.getPlayer().getMeso() < cost) {
        cm.sendOk("您的金币不足，无法进行传送！\r\n\r\n目的地：#b" + targetMap.getDisplayName() + "#k\r\n需要费用：#r" + cost + " 金币#k\r\n当前持有：#d" + cm.getPlayer().getMeso() + " 金币#k\r\n\r\n请准备好足够的金币后再来使用传送功能！");
        return false;
    }

    if (cost > 0) {
        cm.gainMeso(-cost);
    }
    cm.warp(targetMap.getMapId());
    var costMsg = cost > 0 ? "，扣除传送费用 " + cost + " 金币" : "";
    var prefix = noticePrefix ? noticePrefix : "已传送至 ";
    cm.playerMessage(5, prefix + targetMap.getDisplayName() + costMsg + "！祝你任务顺利！");
    return true;
}
