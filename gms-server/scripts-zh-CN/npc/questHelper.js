/**
 * @description 任务辅助 NPC 脚本
 * 提供任务状态分类（可完成/进行中）、普通材料一键补齐（带背包空间安全校验）、
 * 杀怪目标地图传送、道具掉落怪物反查传送、起止 NPC 城镇直达
 */

var status = -1;
var selectedCategory = 1; // 1: 可交付任务, 2: 进行中任务
var selectedQuestId = 0;
var currentDetail = null;
var selectedItem = null;
var currentMapList = null;
var pendingNotice = null;
var pendingConfirmAction = null; // { type: 'ALL' | 'MOB' | 'ITEM', id: number, cost: number }

function start() {
    status = -1;
    selectedCategory = 1;
    selectedQuestId = 0;
    currentDetail = null;
    selectedItem = null;
    currentMapList = null;
    pendingNotice = null;
    pendingConfirmAction = null;
    action(1, 0, 0);
}

function action(mode, type, selection) {
    try {
        if (mode === -1) {
            cm.dispose();
            return;
        } else if (mode === 0) {
            if (pendingConfirmAction != null) {
                // 二次确认中选择了 "否"
                pendingConfirmAction = null;
                status = 1;
                action(1, 0, selectedQuestId);
                return;
            }
            if (status <= 0) {
                cm.dispose();
                return;
            }
            status--;
        } else {
            status++;
        }

        // 处理二次确认中点击了 "是" (mode === 1)
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
            status = 1;
            action(1, 0, selectedQuestId);
            return;
        }

        if (status === 0) {
            showMainMenu();
        } else if (status === 1) {
            handleMainMenuSelection(selection);
        } else if (status === 2) {
            handleQuestListSelection(selection);
        } else if (status === 3) {
            handleDetailSelection(selection);
        } else if (status === 4) {
            handleSubSelection(selection);
        } else if (status === 5) {
            handleMapWarp(selection);
        } else {
            cm.dispose();
        }
    } catch (e) {
        cm.sendOk("任务辅助执行错误：" + e);
        cm.dispose();
    }
}

/**
 * 步骤 0：主菜单（可交付任务 vs 进行中任务）
 */
function showMainMenu() {
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

/**
 * 步骤 1：处理主菜单选择
 */
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
 * 展示任务列表（分类 1: 可交付, 分类 2: 进行中）
 */
function showQuestList(category) {
    var service = cm.getQuestHelp();
    var list = (category === 1) ? service.getCanCompleteQuests(cm.getPlayer()) : service.getInProgressQuests(cm.getPlayer());

    var categoryTitle = (category === 1) ? "可直接交付的任务" : "进行中的任务";
    var text = "#e#b【 " + categoryTitle + " 】 (共 " + list.size() + " 个)#k#n\r\n\r\n";

    if (list.size() === 0) {
        text += (category === 1) ? "当前没有任何已达成全部条件的任务。\r\n" : "您当前尚未接取任何任务。\r\n";
        text += "\r\n#L999999##b[返回上一层]#k#l";
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

    text += "\r\n#L999999##b[返回上一层]#k#l";
    cm.sendSimple(text);
}

/**
 * 步骤 2：处理从任务列表点击某个任务
 */
function handleQuestListSelection(selection) {
    if (selection === 999999) {
        status = -1;
        action(1, 0, 0);
        return;
    }

    selectedQuestId = selection;
    showQuestDetail(selectedQuestId);
}

/**
 * 展示指定任务的详细进度与各项操作（采用标题与操作分行、短按钮设计，彻底杜绝换行重叠）
 */
function showQuestDetail(questId) {
    var service = cm.getQuestHelp();
    currentDetail = service.getQuestDetailInfo(cm.getPlayer(), questId);

    if (!currentDetail) {
        cm.sendOk("未获取到任务详细信息，可能任务已被放弃或已完成。");
        status = 0;
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
        text += "#L10000##k【 #d★ 一键同步/填充本任务全部账号怪物击杀#k 】 #d(" + totalMobCost + " 金币)#k#l\r\n\r\n";
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
                text += " 目标：【#rBoss - " + mob.getMobName() + "#k】 " + progTag + "\r\n";
                if (!isDone) {
                    text += "   #r└─ [Boss怪物，需亲自挑战消灭]#k\r\n";
                }
            } else if (isDone) {
                text += " 目标：【#b" + mob.getMobName() + " (Lv." + mob.getMobLevel() + ")#k】 " + progTag + "\r\n";
            } else {
                text += " 目标：【#b" + mob.getMobName() + " (Lv." + mob.getMobLevel() + ")#k】 " + progTag + "\r\n";
                text += "#L" + (100000 + i) + "#   #b[传送]#k #d查看野外分布地图并传送#k#l\r\n";
                if (mob.isSyncable()) {
                    if (mob.isFullSync()) {
                        text += "#L" + (150000 + i) + "#   #b[补满]#k #d账号击杀 " + mob.getAccountKills() + "只 -> 消耗 " + mob.getTotalCost() + "金币补满#k#l\r\n";
                    } else {
                        text += "#L" + (150000 + i) + "#   #b[填充]#k #d账号击杀 " + mob.getAccountKills() + "只 -> 消耗 " + mob.getTotalCost() + "金币填充" + mob.getSyncCount() + "只#k#l\r\n";
                    }
                } else if (mob.getAccountKills() > 0) {
                    text += "   #d└─ (账号历史累计: " + mob.getAccountKills() + "/" + mob.getRequiredKills() + " 只)#k\r\n";
                }
            }
        }
        text += "\r\n";
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

            text += " 目标：#v" + item.getItemId() + "# 【#b" + item.getItemName() + "#k】 " + progTag + "\r\n";
            if (!isDone) {
                text += "#L" + (200000 + i) + "#   #b[掉落]#k #d查看掉落怪物并传送#k#l\r\n";
                if (item.isDeliverable()) {
                    text += "#L" + (250000 + i) + "#   #b[购买]#k #d购买补齐 " + diff + "个 (需 " + item.getTotalPrice() + " 金币)#k#l\r\n";
                } else {
                    text += "   #r└─ (剧情/特殊道具需手动获取)#k\r\n";
                }
            }
        }
        text += "\r\n";
    }

    // 3. NPC 导航
    if (startNpc || compNpc) {
        hasContent = true;
        text += "#e【 NPC 导航传送 】#n\r\n";
        if (startNpc) {
            var startMap = (startNpc.getMaps() && startNpc.getMaps().size() > 0) ? startNpc.getMaps().get(0) : null;
            var locStr = formatLocationName(startMap);
            var costStr = startMap && startMap.getWarpCost() > 0 ? " (费用: " + startMap.getWarpCost() + "金币)" : "";
            text += " 接取NPC：#b" + startNpc.getNpcName() + "#k (" + locStr + ")\r\n";
            text += "#L300001#   #b[传送]#k #d传送至接取NPC所在地图" + costStr + "#k#l\r\n";
        }
        if (compNpc) {
            var compMap = (compNpc.getMaps() && compNpc.getMaps().size() > 0) ? compNpc.getMaps().get(0) : null;
            var locStr = formatLocationName(compMap);
            var costStr = compMap && compMap.getWarpCost() > 0 ? " (费用: " + compMap.getWarpCost() + "金币)" : "";
            text += " 交付NPC：#b" + compNpc.getNpcName() + "#k (" + locStr + ")\r\n";
            text += "#L300002#   #b[传送]#k #d传送至交付NPC所在地图" + costStr + "#k#l\r\n";
        }
        text += "\r\n";
    }

    if (!hasContent) {
        text += "该任务为纯对话/探索类任务，无需特定击杀或物品收集。\r\n\r\n";
    }

    text += "#L999999##b[返回任务列表]#k#l";
    cm.sendSimple(text);
}

/**
 * 步骤 3：处理从任务目标页面的点击
 */
function handleDetailSelection(selection) {
    if (selection === 999999) {
        status = 0;
        action(1, 0, selectedCategory);
        return;
    }

    // 一键同步账号击杀并购买全部普通材料 -> 二次确认弹窗
    if (selection === 10000) {
        var totalMobCost = currentDetail.getTotalSyncableMobsCost();
        var totalMatCost = currentDetail.getTotalRegularMaterialsCost();
        var combinedCost = currentDetail.getTotalCostWithMobsAndMaterials();

        var confirmText = "#e#b【一键完成任务目标确认】#k#n\r\n\r\n"
            + "本次一键操作将包含：\r\n"
            + " - 同步/填充本任务所有可用账号历史怪物击杀\r\n"
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

    // 单项同步指定怪物的账号历史击杀 -> 二次确认弹窗
    if (selection >= 150000 && selection < 200000) {
        var index = selection - 150000;
        var mob = currentDetail.getMobObjectives().get(index);
        var confirmText = "";

        if (mob.isFullSync()) {
            confirmText = "#e#b【快速完成怪物击杀确认】#k#n\r\n\r\n"
                + "本次任务目标：\r\n"
                + " - 目标怪物：#b" + mob.getMobName() + " (Lv." + mob.getMobLevel() + ")#k (当前进度: " + mob.getCurrentKills() + "/" + mob.getRequiredKills() + ")\r\n"
                + " - 账号历史击杀：#b" + mob.getAccountKills() + "#k 只 (本次可直接补满: #r" + mob.getSyncCount() + "#k 只)\r\n"
                + " - 填充后进度：#b" + mob.getRequiredKills() + "/" + mob.getRequiredKills() + " [已达成]#k\r\n\r\n"
                + "本次快速结算将消耗：\r\n"
                + " - 击杀单价：#r" + mob.getUnitPrice() + "#k 金币\r\n"
                + " - 所需金币：#r" + mob.getTotalCost() + "#k 金币\r\n"
                + " - 当前持有金币：#b" + cm.getPlayer().getMeso() + "#k 金币\r\n\r\n"
                + "#e是否确认支付金币并立即达成该击杀目标？#n";
        } else {
            var remaining = mob.getRequiredKills() - mob.getTargetKills();
            confirmText = "#e#b【快速填充怪物击杀确认】#k#n\r\n\r\n"
                + "本次任务目标：\r\n"
                + " - 目标怪物：#b" + mob.getMobName() + " (Lv." + mob.getMobLevel() + ")#k (当前进度: " + mob.getCurrentKills() + "/" + mob.getRequiredKills() + ")\r\n"
                + " - 账号历史击杀：#b" + mob.getAccountKills() + "#k 只 (本次可先填充: #r" + mob.getSyncCount() + "#k 只)\r\n"
                + " - 填充后进度：#b" + mob.getTargetKills() + "/" + mob.getRequiredKills() + "#k (尚需手动击杀 " + remaining + " 只)\r\n\r\n"
                + "本次快速结算将消耗：\r\n"
                + " - 击杀单价：#r" + mob.getUnitPrice() + "#k 金币\r\n"
                + " - 所需金币：#r" + mob.getTotalCost() + "#k 金币\r\n"
                + " - 当前持有金币：#b" + cm.getPlayer().getMeso() + "#k 金币\r\n\r\n"
                + "#e是否确认支付金币并立即填充该击杀数据？#n";
        }

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

    // 击杀怪物 -> 显示怪物地图
    if (selection >= 100000 && selection < 200000) {
        var index = selection - 100000;
        var mob = currentDetail.getMobObjectives().get(index);
        if (mob.isBoss()) {
            cm.sendOk("怪物 【" + mob.getMobName() + "】 为 Boss 怪物，为了游戏平衡与挑战流程，已关闭直接传送至 Boss 房间的功能，请自行前往挑战！");
            return;
        }
        currentMapList = mob.getMaps();

        if (!currentMapList || currentMapList.size() === 0) {
            cm.sendOk("未检索到怪物 【" + mob.getMobName() + "】 的野外分布地图。");
            return;
        }

        var text = "#e#b怪物 【" + mob.getMobName() + "】 出现在以下地图：#k#n\r\n请选择传送目的地：\r\n\r\n";
        for (var i = 0; i < currentMapList.size(); i++) {
            var map = currentMapList.get(i);
            text += "#L" + (500000 + i) + "# [地图] " + getMapDisplayWithLock(map) + "#l\r\n";
        }
        text += "\r\n#L999998##b[返回任务详情]#k#l";
        cm.sendSimple(text);
        return;
    }

    // 道具收集 -> 显示掉落怪物
    if (selection >= 200000 && selection < 250000) {
        var index = selection - 200000;
        selectedItem = currentDetail.getItemObjectives().get(index);
        var dropMobs = selectedItem.getDropMobs();

        if (!dropMobs || dropMobs.size() === 0) {
            cm.sendOk("道具 【#b" + selectedItem.getItemName() + "#k】 暂无野外怪物直接掉落数据（可能为任务剧情专有道具、箱子掉落或商店购买）。");
            return;
        }

        var text = "#e#b掉落道具 【" + selectedItem.getItemName() + "】 的怪物列表：#k#n\r\n点击怪物查看分布地图并传送：\r\n\r\n";
        for (var i = 0; i < dropMobs.size(); i++) {
            var dropMob = dropMobs.get(i);
            if (dropMob.isBoss()) {
                text += " " + dropMob.getMobName() + " #r[Boss]#k (掉率: " + dropMob.getChanceText() + ") #r[Boss怪物，已关闭直达传送]#k\r\n";
            } else {
                text += "#L" + (400000 + i) + "# " + dropMob.getMobName() + " (掉率: " + dropMob.getChanceText() + ", 地图数: " + dropMob.getMaps().size() + ")#l\r\n";
            }
        }
        text += "\r\n#L999998##b[返回任务详情]#k#l";
        cm.sendSimple(text);
        return;
    }

    // 接取 NPC 传送
    if (selection === 300001) {
        var startNpc = currentDetail.getStartNpc();
        currentMapList = startNpc.getMaps();
        if (!currentMapList || currentMapList.size() === 0) {
            cm.sendOk("未找到 NPC 【" + startNpc.getNpcName() + "】 的所在地图数据。");
            return;
        }
        if (currentMapList.size() === 1) {
            var targetMap = currentMapList.get(0);
            tryWarpPlayer(targetMap, "已传送至接取 NPC 【" + startNpc.getNpcName() + "】 所在地图：");
            cm.dispose();
            return;
        }
        var text = "#e#b接取 NPC 【" + startNpc.getNpcName() + "】 所在地图：#k#n\r\n请选择目的地：\r\n\r\n";
        for (var i = 0; i < currentMapList.size(); i++) {
            var map = currentMapList.get(i);
            text += "#L" + (500000 + i) + "# [地图] " + getMapDisplayWithLock(map) + "#l\r\n";
        }
        text += "\r\n#L999998##b[返回任务详情]#k#l";
        cm.sendSimple(text);
        return;
    }

    // 交付 NPC 传送
    if (selection === 300002) {
        var compNpc = currentDetail.getCompleteNpc();
        currentMapList = compNpc.getMaps();
        if (!currentMapList || currentMapList.size() === 0) {
            cm.sendOk("未找到 NPC 【" + compNpc.getNpcName() + "】 的所在地图数据。");
            return;
        }
        if (currentMapList.size() === 1) {
            var targetMap = currentMapList.get(0);
            tryWarpPlayer(targetMap, "已传送至交付 NPC 【" + compNpc.getNpcName() + "】 所在地图：");
            cm.dispose();
            return;
        }
        var text = "#e#b交付 NPC 【" + compNpc.getNpcName() + "】 所在地图：#k#n\r\n请选择目的地：\r\n\r\n";
        for (var i = 0; i < currentMapList.size(); i++) {
            var map = currentMapList.get(i);
            text += "#L" + (500000 + i) + "# [地图] " + getMapDisplayWithLock(map) + "#l\r\n";
        }
        text += "\r\n#L999998##b[返回任务详情]#k#l";
        cm.sendSimple(text);
        return;
    }

    cm.dispose();
}

/**
 * 步骤 4：处理地图传送或掉落怪物详情点击
 */
function handleSubSelection(selection) {
    if (selection === 999998) {
        status = 1;
        action(1, 0, selectedQuestId);
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

    // 点击了掉落怪物 -> 展示该怪物的地图列表
    if (selection >= 400000 && selection < 500000) {
        var index = selection - 400000;
        var dropMob = selectedItem.getDropMobs().get(index);
        if (dropMob.isBoss()) {
            cm.sendOk("怪物 【" + dropMob.getMobName() + "】 为 Boss 怪物，为了游戏平衡与挑战流程，已关闭直接传送至 Boss 房间的功能，请自行前往挑战！");
            return;
        }
        currentMapList = dropMob.getMaps();

        if (!currentMapList || currentMapList.size() === 0) {
            cm.sendOk("未找到怪物 【" + dropMob.getMobName() + "】 的野外地图数据。");
            return;
        }

        var text = "#e#b怪物 【" + dropMob.getMobName() + "】 (掉落: " + selectedItem.getItemName() + ") 分布地图：#k#n\r\n请选择传送目的地：\r\n\r\n";
        for (var i = 0; i < currentMapList.size(); i++) {
            var map = currentMapList.get(i);
            text += "#L" + (500000 + i) + "# [地图] " + getMapDisplayWithLock(map) + "#l\r\n";
        }
        text += "\r\n#L999997##b[返回掉落怪物列表]#k#l";
        cm.sendSimple(text);
        return;
    }

    cm.dispose();
}

/**
 * 步骤 5：处理从掉落怪物地图选择时的传送
 */
function handleMapWarp(selection) {
    if (selection === 999997) {
        // 返回掉落怪物列表
        status = 2;
        action(1, 0, 200000);
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
        tag = " #r[" + (reason ? reason : "需先访问主城") + "]#k";
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
            cm.sendOk("您尚未探索并访问过该区域的主城！\r\n请先亲自前往探索该主城后，方可解锁直达传送。");
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
