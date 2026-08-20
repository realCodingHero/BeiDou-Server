/**
 * @description 枫叶助手 - 装备商店子菜单
 * 支持 12 大部位装备商店分类、自动过滤非现金与非本职业装备、分段/分类型极速加载无卡顿
 */

var status = -1;
var selectedCategory = -1;

function start() {
    status = -1;
    selectedCategory = -1;
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
        var text = "\t\t\t\t#e#b【 枫叶助手 - 装备商店 】#k#n\r\n\r\n";
        text += "欢迎光临装备商店！请选择您想要浏览或购买的装备分类：\r\n";
        text += "#d（系统已自动为您筛选适合当前职业的非现金装备，并按等级升序排列）#k\r\n\r\n";

        text += "#L1#武器商店#l \t #L2#帽子商店#l \t #L3#上衣商店#l\r\n";
        text += "#L4#裤裙商店#l \t #L5#套服商店#l \t #L6#手套商店#l\r\n";
        text += "#L7#鞋子商店#l \t #L8#盾牌商店#l \t #L9#披风商店#l\r\n";
        text += "#L10#耳环商店#l \t #L11#戒指商店#l \t #L12#其它饰品#l\r\n\r\n";
        text += "#L9999##b[返回枫叶助手主菜单]#k#l\r\n";

        cm.sendSimple(text);
    } else if (status === 1) {
        if (selection === 9999) {
            cm.dispose();
            cm.openNpc(9900001);
            return;
        }

        selectedCategory = selection;

        // 武器商店：展示根据职业细分的武器种类
        if (selectedCategory === 1) {
            var job = cm.getPlayer().getJob();
            var jobType = Math.floor(job.getId() / 100);
            var isGMJob = (job.getId() === 900 || job.getId() === 910 || job.getId() === 0 || job.getId() === 1000 || job.getId() === 2000);

            var text = "\t\t\t\t#e#b【 武器商店 - 武器种类选择 】#k#n\r\n\r\n";
            text += "请选择您要查看的武器类型：\r\n\r\n";

            if (isGMJob || jobType === 1 || jobType === 11 || jobType === 21) {
                text += "#L1#单手剑 / 双手剑#l\r\n";
                text += "#L2#单手斧 / 双手斧#l\r\n";
                text += "#L3#单手钝器 / 双手钝器#l\r\n";
                text += "#L4#枪 / 矛#l\r\n";
            }
            if (isGMJob || jobType === 2 || jobType === 12 || jobType === 22 || job.getId() === 2001) {
                text += "#L5#短杖 / 长杖#l\r\n";
            }
            if (isGMJob || jobType === 3 || jobType === 13) {
                text += "#L6#弓 / 弩#l\r\n";
            }
            if (isGMJob || jobType === 4 || jobType === 14) {
                text += "#L7#短刀 / 拳套#l\r\n";
            }
            if (isGMJob || jobType === 5 || jobType === 15) {
                text += "#L8#指虎 / 火枪#l\r\n";
            }

            text += "#L9#全职业通用趣味武器#l\r\n";
            text += "#L0#全部职业武器 (全量浏览)#l\r\n\r\n";
            text += "#L9999##b[返回装备商店主菜单]#k#l\r\n";

            cm.sendSimple(text);
        }
        // 防具大类（帽子、上衣、裤裙、套服、手套、鞋子）：提供等级段选择，实现秒开无卡顿
        else if (selectedCategory >= 2 && selectedCategory <= 7) {
            var catNames = ["", "武器", "帽子", "上衣", "裤裙", "套服", "手套", "鞋子"];
            var catName = catNames[selectedCategory] || "防具";

            var text = "\t\t\t\t#e#b【 " + catName + "商店 - 等级区间选择 】#k#n\r\n\r\n";
            text += "请选择您要查看的装备等级阶段（分段浏览秒开无卡顿）：\r\n\r\n";
            text += "#L201#新手入门 (1 ~ 40 级)#l\r\n";
            text += "#L202#中阶勇者 (41 ~ 70 级)#l\r\n";
            text += "#L203#高阶勇士 (71 ~ 100 级)#l\r\n";
            text += "#L204#顶级神装 (101 级以上)#l\r\n";
            text += "#L200#全量浏览 (包含全部等级)#l\r\n\r\n";
            text += "#L9999##b[返回装备商店主菜单]#k#l\r\n";

            cm.sendSimple(text);
        }
        // 饰品与盾牌/披风类（盾牌、披风、耳环、戒指、其它饰品）：道具总量适中，直接秒开
        else if (selectedCategory >= 8 && selectedCategory <= 12) {
            cm.dispose();
            cm.openEquipShop(selectedCategory);
        } else {
            cm.dispose();
        }
    } else if (status === 2) {
        if (selection === 9999) {
            status = -1;
            selectedCategory = -1;
            action(1, 0, 0);
            return;
        }

        // 武器子类型选择
        if (selectedCategory === 1) {
            var subType = selection;
            cm.dispose();
            cm.openEquipShop(1, subType, 0, 0);
        }
        // 防具等级区间选择
        else if (selectedCategory >= 2 && selectedCategory <= 7) {
            var minLvl = 0;
            var maxLvl = 0;
            if (selection === 201) {
                minLvl = 1;
                maxLvl = 40;
            } else if (selection === 202) {
                minLvl = 41;
                maxLvl = 70;
            } else if (selection === 203) {
                minLvl = 71;
                maxLvl = 100;
            } else if (selection === 204) {
                minLvl = 101;
                maxLvl = 255;
            } else {
                minLvl = 0;
                maxLvl = 0;
            }
            cm.dispose();
            cm.openEquipShop(selectedCategory, minLvl, maxLvl);
        } else {
            cm.dispose();
        }
    } else {
        cm.dispose();
    }
}
