/**
 * @description 昨日小睡 - 装备商店子菜单
 * 支持 12 大部位装备商店分类、自动过滤非现金与非本职业装备、等级由低到高升序排列
 */

var status = -1;

function start() {
    status = -1;
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
        var text = "\t\t\t\t#e#b【 昨日小睡 - 装备商店 】#k#n\r\n\r\n";
        text += "欢迎光临装备商店！请选择您想要浏览或购买的装备分类：\r\n";
        text += "#d（系统已自动为您筛选适合当前职业的非现金装备，并按等级升序排列）#k\r\n\r\n";

        text += "#L1#武器商店#l \t #L2#帽子商店#l \t #L3#上衣商店#l\r\n";
        text += "#L4#裤裙商店#l \t #L5#套服商店#l \t #L6#手套商店#l\r\n";
        text += "#L7#鞋子商店#l \t #L8#盾牌商店#l \t #L9#披风商店#l\r\n";
        text += "#L10#耳环商店#l \t #L11#戒指商店#l \t #L12#其它饰品#l\r\n\r\n";
        text += "#L9999##b[返回昨日小睡主菜单]#k#l\r\n";

        cm.sendSimple(text);
    } else if (status === 1) {
        if (selection === 9999) {
            cm.dispose();
            cm.openNpc(9900001);
            return;
        }

        if (selection >= 1 && selection <= 12) {
            cm.dispose();
            cm.openEquipShop(selection);
        } else {
            cm.dispose();
        }
    } else {
        cm.dispose();
    }
}
