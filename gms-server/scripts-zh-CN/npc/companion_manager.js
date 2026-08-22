/**
 * companion_manager.js
 * 同账号伙伴协同召唤与即时热切换管理
 */

var status = -1;
var selectedOption = -1;
var selectedCharId = -1;
var manager = org.gms.server.companion.AccountCompanionManager.getInstance();

function start() {
    status = -1;
    action(1, 0, 0);
}

function action(mode, type, selection) {
    if (mode == 1) {
        status++;
    } else {
        if (status <= 0) {
            cm.dispose();
            return;
        }
        status--;
    }

    var player = cm.getPlayer();
    if (player == null) {
        cm.dispose();
        return;
    }

    var companions = manager.getCompanions(player);

    if (status == 0) {
        var text = "#e#b★ 同账号伙伴协同管理中心 ★#n#k\r\n";
        text += "当前主控: #r" + player.getName() + "#k (Lv." + player.getLevel() + ")\r\n";
        text += "出战伙伴 (" + companions.size() + "/3):\r\n";

        if (companions.size() == 0) {
            text += "  #7(暂无出战伙伴)#k\r\n";
        } else {
            for (var i = 0; i < companions.size(); i++) {
                var comp = companions.get(i);
                text += "  #g[" + (i + 1) + "] " + comp.getName() + " (Lv." + comp.getLevel() + ")#k - 模式: #d" + comp.getTacticMode().getDescription() + "#k\r\n";
            }
        }
        text += "\r\n请选择操作:\r\n";
        text += "#L1##b1. 召唤同账号伙伴出战#l\r\n";
        if (companions.size() > 0) {
            text += "#L2##r2. 原地即时热切换 / 灵魂接管#l\r\n";
            text += "#L3##d3. 调整伙伴战术模式#l\r\n";
            text += "#L4##k4. 解散伙伴并保存数据#l\r\n";
        }

        cm.sendSimple(text);
    } else if (status == 1) {
        selectedOption = selection;

        if (selectedOption == 1) {
            // 召唤伙伴
            var available = manager.getAvailableAccountCharacters(player);
            if (available.size() == 0) {
                cm.sendOk("同账号下暂无可召唤的其他角色（或已全部出战）！");
                cm.dispose();
                return;
            }
            var text = "#e请选择要召唤的同账号伙伴:#n\r\n\r\n";
            for (var i = 0; i < available.size(); i++) {
                var cdo = available.get(i);
                text += "#L" + cdo.getId() + "# #b" + cdo.getName() + "#k (Lv." + cdo.getLevel() + ")#l\r\n";
            }
            cm.sendSimple(text);
        } else if (selectedOption == 2) {
            // 切换主控
            var text = "#e请选择要直接接管的主控伙伴:#n\r\n\r\n";
            for (var i = 0; i < companions.size(); i++) {
                var comp = companions.get(i);
                text += "#L" + comp.getId() + "# #r" + comp.getName() + "#k (Lv." + comp.getLevel() + ")#l\r\n";
            }
            cm.sendSimple(text);
        } else if (selectedOption == 3) {
            // 战术模式
            var text = "#e请选择要配置战术的伙伴:#n\r\n\r\n";
            for (var i = 0; i < companions.size(); i++) {
                var comp = companions.get(i);
                text += "#L" + comp.getId() + "# #d" + comp.getName() + "#k (Lv." + comp.getLevel() + ")#l\r\n";
            }
            cm.sendSimple(text);
        } else if (selectedOption == 4) {
            manager.dismissAllCompanions(player);
            cm.sendOk("全部出战伙伴已安全解散，获得的经验和等级已写回数据库！");
            cm.dispose();
        }
    } else if (status == 2) {
        selectedCharId = selection;

        if (selectedOption == 1) {
            // 执行召唤
            var success = manager.summonCompanion(player, selectedCharId);
            if (success) {
                cm.sendOk("伙伴已成功召唤出战，并已自动加入您的队伍！");
            } else {
                cm.sendOk("召唤失败，请检查队伍状态或稍后重试。");
            }
            cm.dispose();
        } else if (selectedOption == 2) {
            // 执行切人
            cm.dispose();
            manager.hotSwitchCharacter(player, selectedCharId);
        } else if (selectedOption == 3) {
            // 选择战术模式
            var text = "#e请选择战术模式:#n\r\n\r\n";
            text += "#L0##g[纯辅助模式]#k - 仅跟随主人、施加神圣祈祷/圣火/加速/治疗等Buff，不主动拉怪#l\r\n";
            text += "#L1##r[平衡战斗模式]#k - 跟随主人、维持Buff并在身边自动协同攻击怪物#l\r\n";
            text += "#L2##b[定点防守模式]#k - 留在当前平台清理周边怪物#l\r\n";
            cm.sendSimple(text);
        }
    } else if (status == 3) {
        var modeCode = selection;
        var list = manager.getCompanions(player);
        for (var i = 0; i < list.size(); i++) {
            var comp = list.get(i);
            if (comp.getId() == selectedCharId) {
                comp.setTacticMode(org.gms.server.companion.CompanionTacticMode.fromCode(modeCode));
                cm.sendOk("伙伴 #b" + comp.getName() + "#k 的战术模式已变更为: #r" + comp.getTacticMode().getDescription() + "#k！");
                break;
            }
        }
        cm.dispose();
    }
}
