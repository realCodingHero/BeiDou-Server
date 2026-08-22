/**
 * companion_manager.js
 * Account Companion & Hot-Switch NPC script
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
        var text = "#e#b★ Account Companion Management Center ★#n#k\r\n";
        text += "Main Character: #r" + player.getName() + "#k (Lv." + player.getLevel() + ")\r\n";
        text += "Active Companions (" + companions.size() + "/3):\r\n";

        if (companions.size() == 0) {
            text += "  #7(No active companions)#k\r\n";
        } else {
            for (var i = 0; i < companions.size(); i++) {
                var comp = companions.get(i);
                text += "  #g[" + (i + 1) + "] " + comp.getName() + " (Lv." + comp.getLevel() + ")#k - Mode: #d" + comp.getTacticMode().getDescription() + "#k\r\n";
            }
        }
        text += "\r\nPlease select an action:\r\n";
        text += "#L1##b1. Summon Account Companion#l\r\n";
        if (companions.size() > 0) {
            text += "#L2##r2. In-Place Hot-Switch Character#l\r\n";
            text += "#L3##d3. Configure Companion Tactics#l\r\n";
            text += "#L4##k4. Dismiss All Companions & Save Data#l\r\n";
        }

        cm.sendSimple(text);
    } else if (status == 1) {
        selectedOption = selection;

        if (selectedOption == 1) {
            var available = manager.getAvailableAccountCharacters(player);
            if (available.size() == 0) {
                cm.sendOk("No other characters available under this account (or all already summoned)!");
                cm.dispose();
                return;
            }
            var text = "#eSelect a character to summon as companion:#n\r\n\r\n";
            for (var i = 0; i < available.size(); i++) {
                var cdo = available.get(i);
                text += "#L" + cdo.getId() + "# #b" + cdo.getName() + "#k (Lv." + cdo.getLevel() + ")#l\r\n";
            }
            cm.sendSimple(text);
        } else if (selectedOption == 2) {
            var text = "#eSelect companion to switch control to:#n\r\n\r\n";
            for (var i = 0; i < companions.size(); i++) {
                var comp = companions.get(i);
                text += "#L" + comp.getId() + "# #r" + comp.getName() + "#k (Lv." + comp.getLevel() + ")#l\r\n";
            }
            cm.sendSimple(text);
        } else if (selectedOption == 3) {
            var text = "#eSelect companion to configure:#n\r\n\r\n";
            for (var i = 0; i < companions.size(); i++) {
                var comp = companions.get(i);
                text += "#L" + comp.getId() + "# #d" + comp.getName() + "#k (Lv." + comp.getLevel() + ")#l\r\n";
            }
            cm.sendSimple(text);
        } else if (selectedOption == 4) {
            manager.dismissAllCompanions(player);
            cm.sendOk("All companions dismissed and progress saved to database!");
            cm.dispose();
        }
    } else if (status == 2) {
        selectedCharId = selection;

        if (selectedOption == 1) {
            var success = manager.summonCompanion(player, selectedCharId);
            if (success) {
                cm.sendOk("Companion summoned and joined your party!");
            } else {
                cm.sendOk("Failed to summon companion. Please check your party status.");
            }
            cm.dispose();
        } else if (selectedOption == 2) {
            cm.dispose();
            manager.hotSwitchCharacter(player, selectedCharId);
        } else if (selectedOption == 3) {
            var text = "#eSelect tactic mode:#n\r\n\r\n";
            text += "#L0##g[Support Only]#k - Follows, maintains buffs and heals, does not attack.#l\r\n";
            text += "#L1##r[Balanced Combat]#k - Follows, maintains buffs and attacks nearby mobs.#l\r\n";
            text += "#L2##b[Stationary Guard]#k - Guards current area.#l\r\n";
            cm.sendSimple(text);
        }
    } else if (status == 3) {
        var modeCode = selection;
        var list = manager.getCompanions(player);
        for (var i = 0; i < list.size(); i++) {
            var comp = list.get(i);
            if (comp.getId() == selectedCharId) {
                comp.setTacticMode(org.gms.server.companion.CompanionTacticMode.fromCode(modeCode));
                cm.sendOk("Companion #b" + comp.getName() + "#k tactic mode updated to: #r" + comp.getTacticMode().getDescription() + "#k!");
                break;
            }
        }
        cm.dispose();
    }
}
