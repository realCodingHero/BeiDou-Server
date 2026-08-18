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

function start() {
    status = -1;
    selectedCategory = 1;
    selectedQuestId = 0;
    currentDetail = null;
    selectedItem = null;
    currentMapList = null;
    pendingNotice = null;
    action(1, 0, 0);
}

function action(mode, type, selection) {
    try {
        if (mode === -1) {
            cm.dispose();
            return;
        } else if (mode === 0) {
            if (status <= 0) {
                cm.dispose();
                return;
            }
            status--;
        } else {
            status++;
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

    var completable = service.getCompletableQuestSummaries(cm.getPlayer());
    var inProgress = service.getInProgressQuestSummaries(cm.getPlayer());
    var total = completable.size() + inProgress.size();

    if (total === 0) {
        cm.sendSimple("您当前没有任何正在进行中的任务。\r\n请在游戏中接取任务后再来使用任务辅助功能！\r\n\r\n#L999990##b[返回昨日小睡主菜单]#k#l");
        return;
    }

    var text = "\t\t\t\t#e#b【 任务辅助 - 任务分类 】#k#n\r\n\r\n";
    text += "请选择您要查看的任务分类：\r\n\r\n";
    text += "#L1##e#b【 可交付任务 】#k#n (共 #d" + completable.size() + "#k 个任务已达成全部条件)#l\r\n";
    text += "#L2##e#d【 进行中任务 】#k#n (共 #d" + inProgress.size() + "#k 个任务尚未达成目标)#l\r\n\r\n";
    text += "#L999990##b[返回昨日小睡主菜单]#k#l";

    cm.sendSimple(text);
}

/**
 * 步骤 1：处理主菜单点击，展示对应分类下的任务列表
 */
function handleMainMenuSelection(selection) {
    if (selection === 999990) {
        cm.dispose();
        cm.openNpc(9900001);
        return;
    }

    if (selection === 1 || selection === 2) {
        selectedCategory = selection;
        showQuestList();
        return;
    }

    cm.dispose();
}

/**
 * 展示指定分类下的任务列表
 */
function showQuestList() {
    var service = cm.getQuestHelp();
    if (!service) {
        cm.sendOk("任务辅助服务暂不可用。");
        return;
    }

    var quests = (selectedCategory === 1) ?
            service.getCompletableQuestSummaries(cm.getPlayer()) :
            service.getInProgressQuestSummaries(cm.getPlayer());

    var categoryTitle = (selectedCategory === 1) ? "可交付任务列表" : "进行中任务列表";

    if (!quests || quests.size() === 0) {
        var emptyMsg = (selectedCategory === 1) ?
                "当前没有已达成全部条件的可交付任务。\r\n请在【进行中任务】中查看当前目标并完成！" :
                "当前没有未完成的进行中任务！";
        cm.sendSimple(emptyMsg + "\r\n\r\n#L999999##b[返回分类菜单]#k#l");
        return;
    }

    var text = "\t\t\t\t#e#b【 任务辅助 - " + categoryTitle + " 】#k#n\r\n\r\n";
    text += "请选择您想要查看、补齐材料或快速传送的目标任务：\r\n\r\n";

    for (var i = 0; i < quests.size(); i++) {
        var q = quests.get(i);
        var tag = "";
        if (q.isCanComplete()) {
            tag = " #b[可交付]#k";
        } else if (q.isPurchasableComplete()) {
            tag = " #d[可购买交付]#k";
        } else {
            tag = " #d[进行中]#k";
        }
        text += "#L" + q.getQuestId() + "# [任务 " + q.getQuestId() + "] #b" + q.getQuestName() + "#k" + tag + "#l\r\n";
    }

    text += "\r\n#L999999##b[返回分类菜单]#k#l";
    cm.sendSimple(text);
}

/**
 * 步骤 2：处理从任务列表的选择
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
 * 展示选中任务的具体目标（杀怪、物品、NPC）与快捷补齐/传送选项
 */
function showQuestDetail(questId) {
    var service = cm.getQuestHelp();
    if (!service) {
        cm.sendOk("任务辅助服务暂不可用。");
        return;
    }

    currentDetail = service.getQuestDetail(cm.getPlayer(), questId);
    if (!currentDetail) {
        cm.sendOk("获取任务目标详情失败，该任务可能已完成或放弃。");
        return;
    }

    var text = "";
    if (pendingNotice) {
        text += pendingNotice + "\r\n\r\n--------------------------------\r\n\r\n";
        pendingNotice = null;
    }

    var statusHeader = "";
    if (currentDetail.isCanComplete()) {
        statusHeader = "#e#b【状态：已集齐全部条件，可直接前往交付！】#k#n";
    } else if (currentDetail.isPurchasableComplete()) {
        statusHeader = "#e#d【状态：所有缺失材料均可购买补齐，补齐后即可交付！】#k#n";
    } else {
        statusHeader = "#e#d【状态：进行中，需达成以下目标】#k#n";
    }

    text += "#e#b【任务】" + currentDetail.getQuestName() + " (ID: " + currentDetail.getQuestId() + ")#k#n\r\n";
    text += statusHeader + "\r\n\r\n";

    var mobObjs = currentDetail.getMobObjectives();
    var itemObjs = currentDetail.getItemObjectives();
    var startNpc = currentDetail.getStartNpc();
    var compNpc = currentDetail.getCompleteNpc();

    // 检查是否有可补齐的普通材料
    var hasDeliverableIncomplete = false;
    var totalCost = currentDetail.getTotalRegularMaterialsCost();
    if (itemObjs && itemObjs.size() > 0) {
        for (var i = 0; i < itemObjs.size(); i++) {
            var item = itemObjs.get(i);
            if (item.isDeliverable() && !item.isCompleted()) {
                hasDeliverableIncomplete = true;
                break;
            }
        }
    }

    if (hasDeliverableIncomplete) {
        text += "#L10000##k【 #d★ 一键购买补齐本任务全部普通怪物材料#k 】 #d(共需: " + totalCost + " 金币)#k#l\r\n\r\n";
    }

    var hasContent = false;

    // 1. 击杀目标
    if (mobObjs && mobObjs.size() > 0) {
        hasContent = true;
        text += "#e【 击杀怪物目标 】#n\r\n";
        for (var i = 0; i < mobObjs.size(); i++) {
            var mob = mobObjs.get(i);
            var statusTag = mob.isCompleted() ? " #b[已达成]#k" : " #r[未完成]#k";
            if (mob.isBoss()) {
                text += " 击杀 【#rBoss - " + mob.getMobName() + "#k】 (" + mob.getCurrentKills() + "/" + mob.getRequiredKills() + ")" + statusTag + " #d[Boss怪物，已关闭直达传送]#k\r\n";
            } else {
                text += "#L" + (100000 + i) + "# 击杀 【#b" + mob.getMobName() + "#k】 (" + mob.getCurrentKills() + "/" + mob.getRequiredKills() + ")" + statusTag + " -> #d[查看地图/传送]#k#l\r\n";
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
            var statusTag = item.isCompleted() ? " #b[已达成]#k" : " #r[未完成]#k";
            var diff = item.getRequiredCount() - item.getCurrentCount();

            if (item.isCompleted()) {
                text += " 收集 #v" + item.getItemId() + "#【#b" + item.getItemName() + "#k】 (" + item.getCurrentCount() + "/" + item.getRequiredCount() + ")" + statusTag + "\r\n";
            } else if (item.isDeliverable()) {
                text += "#L" + (200000 + i) + "# 收集 #v" + item.getItemId() + "#【#b" + item.getItemName() + "#k】 (" + item.getCurrentCount() + "/" + item.getRequiredCount() + ")" + statusTag + " -> #d[掉落怪物/传送]#k#l\r\n";
                text += "#L" + (250000 + i) + "#   #d└─ [购买补齐: 缺 " + diff + " 个 | 单价: " + item.getUnitPrice() + " 金币 | 共需: " + item.getTotalPrice() + " 金币]#k#l\r\n";
            } else {
                text += "#L" + (200000 + i) + "# 收集 #v" + item.getItemId() + "#【#b" + item.getItemName() + "#k】 (" + item.getCurrentCount() + "/" + item.getRequiredCount() + ")" + statusTag + " -> #d[查看掉落/传送]#k #r(特殊/剧情道具需手动获取)#k#l\r\n";
            }
        }
        text += "\r\n";
    }

    // 3. NPC 导航
    if (startNpc || compNpc) {
        hasContent = true;
        text += "#e【 NPC 导航传送 】#n\r\n";
        if (startNpc) {
            var startMapText = (startNpc.getMaps() && startNpc.getMaps().size() > 0) ? startNpc.getMaps().get(0).getDisplayName() : "未知地图";
            text += "#L300001# 接取NPC：#b" + startNpc.getNpcName() + "#k (" + startMapText + ") -> #d[传送直达]#k#l\r\n";
        }
        if (compNpc) {
            var compMapText = (compNpc.getMaps() && compNpc.getMaps().size() > 0) ? compNpc.getMaps().get(0).getDisplayName() : "未知地图";
            text += "#L300002# 交付NPC：#b" + compNpc.getNpcName() + "#k (" + compMapText + ") -> #d[传送直达]#k#l\r\n";
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

    // 一键补齐当前任务全部普通怪物材料
    if (selection === 10000) {
        var service = cm.getQuestHelp();
        var res = service.deliverAllRegularMaterials(cm.getPlayer(), selectedQuestId);
        pendingNotice = res.isSuccess() ? "#d" + res.getMessage() + "#k" : "#r" + res.getMessage() + "#k";
        status = 1;
        action(1, 0, selectedQuestId);
        return;
    }

    // 单项补齐指定普通怪物材料
    if (selection >= 250000 && selection < 300000) {
        var index = selection - 250000;
        var item = currentDetail.getItemObjectives().get(index);
        var service = cm.getQuestHelp();
        var res = service.deliverQuestMaterial(cm.getPlayer(), selectedQuestId, item.getItemId());
        pendingNotice = res.isSuccess() ? "#d" + res.getMessage() + "#k" : "#r" + res.getMessage() + "#k";
        status = 1;
        action(1, 0, selectedQuestId);
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
            text += "#L" + (500000 + i) + "# [地图] " + map.getDisplayName() + "#l\r\n";
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
                text += " " + dropMob.getMobName() + " #r[Boss]#k (掉率: " + dropMob.getChanceText() + ") #d[Boss怪物，已关闭直达传送]#k\r\n";
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
            text += "#L" + (500000 + i) + "# [地图] " + map.getDisplayName() + "#l\r\n";
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
            text += "#L" + (500000 + i) + "# [地图] " + map.getDisplayName() + "#l\r\n";
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
            text += "#L" + (500000 + i) + "# [地图] " + map.getDisplayName() + "#l\r\n";
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

/**
 * 统一传送扣费与金币校验方法
 */
function tryWarpPlayer(targetMap, noticePrefix) {
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
