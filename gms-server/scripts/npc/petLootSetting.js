/**
 * 宠物杂物拾取与自动出售设置
 * NPC: petLootSetting
 */

var status = -1;

function start() {
    action(1, 0, 0);
}

function action(mode, type, selection) {
    if (mode === 1) {
        status++;
    } else if (mode === -1) {
        status--;
    } else {
        cm.dispose();
        return;
    }

    if (status === 0) {
        showSettingMenu();
    } else if (status === 1) {
        handleSelection(selection);
    } else {
        cm.dispose();
    }
}

function showSettingMenu() {
    var currentMode = cm.getPetEtcLootMode();
    var modeDesc = "";
    if (currentMode === 1) {
        modeDesc = "#r【忽略杂物 (跳过不捡)】#k";
    } else if (currentMode === 2) {
        modeDesc = "#g【自动出售 (折算为金币)】#k";
    } else {
        modeDesc = "#b【正常拾取 (放入背包)】#k";
    }

    var text = "#e#b【 宠物杂物拾取与自动出售设置 】#k#n\r\n\r\n";
    text += "当前生效策略：" + modeDesc + "\r\n\r\n";
    text += "#d[安全保护说明]#k\r\n";
    text += "#k• 任务道具(403xxxx/任务标记) #r无论任何模式均正常拾取#k，不影响做任务。\r\n";
    text += "• 矿石/母矿/宝石/魔法石/召唤石等珍贵材料 #r始终正常拾取#k。\r\n";
    text += "• 本设置仅针对普通怪物掉落杂物(蜗牛壳/绿水灵珠等)生效。\r\n\r\n";
    text += "请选择您希望设置的宠物杂物策略：\r\n\r\n";

    text += "#L0##b模式 0：正常拾取#k (默认行为，所有杂物正常放入背包)#l\r\n";
    text += "#L1##b模式 1：忽略杂物#k (宠物跳过普通杂物不捡，背包零负担)#l\r\n";
    text += "#L2##b模式 2：自动出售#k (宠物拾取杂物自动按店价折算为金币，不占背包)#l\r\n\r\n";
    text += "#L999999##b[返回昨日小睡主菜单]#k#l";

    cm.sendSimple(text);
}

function handleSelection(selection) {
    if (selection === 999999) {
        cm.dispose();
        cm.openNpc(9900001);
        return;
    }

    if (selection === 0) {
        cm.setPetEtcLootMode(0);
        cm.sendOk("设置成功！当前宠物策略已切换为：#b【正常拾取 (放入背包)】#k。");
    } else if (selection === 1) {
        cm.setPetEtcLootMode(1);
        cm.sendOk("设置成功！当前宠物策略已切换为：#r【忽略杂物 (跳过不捡)】#k。\r\n宠物在打怪时将自动跳过普通怪物杂物，不再占用背包！");
    } else if (selection === 2) {
        cm.setPetEtcLootMode(2);
        cm.sendOk("设置成功！当前宠物策略已切换为：#g【自动出售 (折算为金币)】#k。\r\n宠物拾取普通怪物杂物时将自动按店价折算金币增加给您，无需背包空间！");
    } else {
        cm.dispose();
    }
}
