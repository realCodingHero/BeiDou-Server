/**
 * @description 任务辅助 NPC 脚本
 * 提供任务进行状态、杀怪目标地图传送、道具掉落怪物反查传送、起止 NPC 城镇直达
 */

var status = -1;
var selectedQuestId = 0;
var currentDetail = null;
var selectedItem = null;
var previousStep = 0;

function start() {
    status = -1;
    action(1, 0, 0);
}

function action(mode, type, selection) {
    if (mode === -1) {
        cm.dispose();
        return;
    } else if (mode === 0) {
        if (status === 0) {
            cm.dispose();
            return;
        }
        status--;
    } else {
        status++;
    }

    if (status === 0) {
        showQuestList();
    } else if (status === 1) {
        selectedQuestId = selection;
        showQuestDetail(selectedQuestId);
    } else if (status === 2) {
        handleDetailSelection(selection);
    } else if (status === 3) {
        handleSubSelection(selection);
    } else if (status === 4) {
        handleMapWarp(selection);
    } else {
        cm.dispose();
    }
}

/**
 * 步骤 0：展示玩家所有进行中的任务
 */
function showQuestList() {
    var service = cm.getQuestHelp();
    if (!service) {
        cm.sendOk("任务辅助服务暂不可用。");
        cm.dispose();
        return;
    }

    var quests = service.getStartedQuestSummaries(cm.getPlayer());
    if (!quests || quests.size() === 0) {
        cm.sendOk("您当前没有任何正在进行中的任务。\r\n请在游戏中接取任务后再来使用任务辅助功能！");
        cm.dispose();
        return;
    }

    var text = "\t\t\t\t#e#b📜 任务辅助 - 进行中任务列表#k#n\r\n\r\n";
    text += "请选择您想要查看或快速传送的目标任务：\r\n\r\n";

    for (var i = 0; i < quests.size(); i++) {
        var q = quests.get(i);
        text += "#L" + q.getQuestId() + "# [任务 " + q.getQuestId() + "] #b" + q.getQuestName() + "#k#l\r\n";
    }

    cm.sendSimple(text);
}

/**
 * 步骤 1：展示选中任务的具体目标（杀怪、物品、NPC）
 */
function showQuestDetail(questId) {
    var service = cm.getQuestHelp();
    currentDetail = service.getQuestDetail(cm.getPlayer(), questId);

    if (!currentDetail) {
        cm.sendOk("获取任务目标详情失败，该任务可能已完成或放弃。");
        cm.dispose();
        return;
    }

    var text = "#e#b【任务】" + currentDetail.getQuestName() + " (ID: " + currentDetail.getQuestId() + ")#k#n\r\n\r\n";

    var mobObjs = currentDetail.getMobObjectives();
    var itemObjs = currentDetail.getItemObjectives();
    var startNpc = currentDetail.getStartNpc();
    var compNpc = currentDetail.getCompleteNpc();

    var hasContent = false;

    // 1. 击杀目标
    if (mobObjs && mobObjs.size() > 0) {
        hasContent = true;
        text += "#e🗡️ 击杀怪物目标：#n\r\n";
        for (var i = 0; i < mobObjs.size(); i++) {
            var mob = mobObjs.get(i);
            var statusTag = mob.isCompleted() ? " #g[已达成]#k" : " #r[未完成]#k";
            text += "#L10000" + i + "# 击杀 #o" + mob.getMobId() + "# (" + mob.getCurrentKills() + "/" + mob.getRequiredKills() + ")" + statusTag + " -> #b[查看地图/传送]#k#l\r\n";
        }
        text += "\r\n";
    }

    // 2. 收集道具目标
    if (itemObjs && itemObjs.size() > 0) {
        hasContent = true;
        text += "#e📦 收集道具目标：#n\r\n";
        for (var i = 0; i < itemObjs.size(); i++) {
            var item = itemObjs.get(i);
            var statusTag = item.isCompleted() ? " #g[已达成]#k" : " #r[未完成]#k";
            text += "#L20000" + i + "# 收集 #v" + item.getItemId() + "# #t" + item.getItemId() + "# (" + item.getCurrentCount() + "/" + item.getRequiredCount() + ")" + statusTag + " -> #b[掉落怪物/传送]#k#l\r\n";
        }
        text += "\r\n";
    }

    // 3. NPC 导航
    if (startNpc || compNpc) {
        hasContent = true;
        text += "#e📍 NPC 导航传送：#n\r\n";
        if (startNpc) {
            var startMapText = (startNpc.getMaps() && startNpc.getMaps().size() > 0) ? startNpc.getMaps().get(0).getDisplayName() : "未知地图";
            text += "#L300001# 接取NPC：#p" + startNpc.getNpcId() + "# (" + startMapText + ") -> #b[传送直达]#k#l\r\n";
        }
        if (compNpc) {
            var compMapText = (compNpc.getMaps() && compNpc.getMaps().size() > 0) ? compNpc.getMaps().get(0).getDisplayName() : "未知地图";
            text += "#L300002# 交付NPC：#p" + compNpc.getNpcId() + "# (" + compMapText + ") -> #b[传送直达]#k#l\r\n";
        }
        text += "\r\n";
    }

    if (!hasContent) {
        text += "该任务为纯对话/探索类任务，无需特定击杀或物品收集。\r\n\r\n";
    }

    text += "#L999999# ⬅️ 返回任务列表 #l";
    cm.sendSimple(text);
}

/**
 * 步骤 2：处理从任务目标页面的点击
 */
function handleDetailSelection(selection) {
    if (selection === 999999) {
        status = -1;
        action(1, 0, 0);
        return;
    }

    // 击杀怪物
    if (selection >= 100000 && selection < 200000) {
        var index = selection - 100000;
        var mob = currentDetail.getMobObjectives().get(index);
        var maps = mob.getMaps();

        if (!maps || maps.size() === 0) {
            cm.sendOk("未检索到怪物 #o" + mob.getMobId() + "# 的野外分布地图。");
            cm.dispose();
            return;
        }

        var text = "#e#b怪物 #o" + mob.getMobId() + "# 出现在以下地图：#k#n\r\n请选择传送目的地：\r\n\r\n";
        for (var i = 0; i < maps.size(); i++) {
            var map = maps.get(i);
            text += "#L50000" + map.getMapId() + "# 📍 " + map.getDisplayName() + "#l\r\n";
        }
        text += "\r\n#L999998# ⬅️ 返回任务详情 #l";
        cm.sendSimple(text);
        return;
    }

    // 道具收集
    if (selection >= 200000 && selection < 300000) {
        var index = selection - 200000;
        selectedItem = currentDetail.getItemObjectives().get(index);
        var dropMobs = selectedItem.getDropMobs();

        if (!dropMobs || dropMobs.size() === 0) {
            cm.sendOk("道具 #v" + selectedItem.getItemId() + "# #t" + selectedItem.getItemId() + "# 暂无野外怪物直接掉落数据（可能为任务剧情专有道具或商店购买）。");
            cm.dispose();
            return;
        }

        var text = "#e#b掉落道具 #v" + selectedItem.getItemId() + "# #t" + selectedItem.getItemId() + "# 的怪物列表：#k#n\r\n点击怪物查看分布地图并传送：\r\n\r\n";
        for (var i = 0; i < dropMobs.size(); i++) {
            var dropMob = dropMobs.get(i);
            text += "#L60000" + i + "# #o" + dropMob.getMobId() + "# (掉率: " + dropMob.getChanceText() + ", 分布地图: " + dropMob.getMaps().size() + "张)#l\r\n";
        }
        text += "\r\n#L999998# ⬅️ 返回任务详情 #l";
        cm.sendSimple(text);
        return;
    }

    // 接取 NPC 传送
    if (selection === 300001) {
        var startNpc = currentDetail.getStartNpc();
        var maps = startNpc.getMaps();
        if (!maps || maps.size() === 0) {
            cm.sendOk("未找到 NPC #p" + startNpc.getNpcId() + "# 的所在地图数据。");
            cm.dispose();
            return;
        }
        if (maps.size() === 1) {
            var mapId = maps.get(0).getMapId();
            cm.warp(mapId);
            cm.playerMessage(5, "已传送至接取 NPC #p" + startNpc.getNpcId() + "# 所在地图！");
            cm.dispose();
            return;
        }
        var text = "#e#b接取 NPC #p" + startNpc.getNpcId() + "# 所在地图：#k#n\r\n请选择目的地：\r\n\r\n";
        for (var i = 0; i < maps.size(); i++) {
            var map = maps.get(i);
            text += "#L50000" + map.getMapId() + "# 📍 " + map.getDisplayName() + "#l\r\n";
        }
        text += "\r\n#L999998# ⬅️ 返回任务详情 #l";
        cm.sendSimple(text);
        return;
    }

    // 交付 NPC 传送
    if (selection === 300002) {
        var compNpc = currentDetail.getCompleteNpc();
        var maps = compNpc.getMaps();
        if (!maps || maps.size() === 0) {
            cm.sendOk("未找到 NPC #p" + compNpc.getNpcId() + "# 的所在地图数据。");
            cm.dispose();
            return;
        }
        if (maps.size() === 1) {
            var mapId = maps.get(0).getMapId();
            cm.warp(mapId);
            cm.playerMessage(5, "已传送至交付 NPC #p" + compNpc.getNpcId() + "# 所在地图！");
            cm.dispose();
            return;
        }
        var text = "#e#b交付 NPC #p" + compNpc.getNpcId() + "# 所在地图：#k#n\r\n请选择目的地：\r\n\r\n";
        for (var i = 0; i < maps.size(); i++) {
            var map = maps.get(i);
            text += "#L50000" + map.getMapId() + "# 📍 " + map.getDisplayName() + "#l\r\n";
        }
        text += "\r\n#L999998# ⬅️ 返回任务详情 #l";
        cm.sendSimple(text);
        return;
    }

    cm.dispose();
}

/**
 * 步骤 3：处理地图传送或掉落怪物详情点击
 */
function handleSubSelection(selection) {
    if (selection === 999998) {
        status = 0;
        action(1, 0, selectedQuestId);
        return;
    }

    // 直接地图传送
    if (selection >= 500000 && selection < 600000) {
        var mapId = selection - 500000;
        cm.warp(mapId);
        cm.playerMessage(5, "已传送至地图 (" + mapId + ")！祝你任务顺利！");
        cm.dispose();
        return;
    }

    // 点击了掉落怪物
    if (selection >= 600000 && selection < 700000) {
        var index = selection - 600000;
        var dropMob = selectedItem.getDropMobs().get(index);
        var maps = dropMob.getMaps();

        if (!maps || maps.size() === 0) {
            cm.sendOk("未找到怪物 #o" + dropMob.getMobId() + "# 的野外地图数据。");
            cm.dispose();
            return;
        }

        var text = "#e#b怪物 #o" + dropMob.getMobId() + "# (掉落: #v" + selectedItem.getItemId() + "#) 分布地图：#k#n\r\n请选择传送目的地：\r\n\r\n";
        for (var i = 0; i < maps.size(); i++) {
            var map = maps.get(i);
            text += "#L50000" + map.getMapId() + "# 📍 " + map.getDisplayName() + "#l\r\n";
        }
        text += "\r\n#L999997# ⬅️ 返回掉落怪物列表 #l";
        cm.sendSimple(text);
        return;
    }

    cm.dispose();
}

/**
 * 步骤 4：处理从掉落怪物地图选择时的传送
 */
function handleMapWarp(selection) {
    if (selection === 999997) {
        // 返回掉落怪物列表
        status = 1;
        action(1, 0, 200000);
        return;
    }

    if (selection >= 500000 && selection < 600000) {
        var mapId = selection - 500000;
        cm.warp(mapId);
        cm.playerMessage(5, "已传送至地图 (" + mapId + ")！祝你任务顺利！");
        cm.dispose();
        return;
    }

    cm.dispose();
}
