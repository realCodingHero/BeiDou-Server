package org.gms.client.command.commands.gm0;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.command.Command;
import org.gms.dao.entity.CharactersDO;
import org.gms.scripting.npc.NPCScriptManager;
import org.gms.server.companion.AccountCompanionManager;
import org.gms.server.companion.CompanionCharacter;
import org.gms.server.companion.CompanionTacticMode;

import java.util.List;

public class CompanionCommand extends Command {
    {
        setDescription("同账号伙伴随从协同管理与召唤系统");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (player == null) {
            return;
        }

        AccountCompanionManager manager = AccountCompanionManager.getInstance();

        if (params.length == 0) {
            // 打开随从管理 NPC 菜单 (使用官方通用 NPC 9010000 枫叶运营员，杜绝 WZ 缺失闪退)
            NPCScriptManager.getInstance().start(c, org.gms.constants.id.NpcId.MAPLE_ADMINISTRATOR, "companion_manager", null);
            return;
        }

        String sub = params[0].toLowerCase();
        switch (sub) {
            case "list": {
                List<CompanionCharacter> active = manager.getCompanions(player);
                player.yellowMessage("======= 当前出战随从 =======");
                if (active.isEmpty()) {
                    player.message("当前无出战随从。输入 @companion summon <角色ID> 召唤伙伴。");
                } else {
                    for (CompanionCharacter comp : active) {
                        player.message(" >> [ID:" + comp.getId() + "] " + comp.getName() + " (Lv." + comp.getLevel() + ") 模式:" + comp.getTacticMode().getDescription());
                    }
                }

                List<CharactersDO> available = manager.getAvailableAccountCharacters(player);
                player.yellowMessage("======= 可召唤同账号角色 =======");
                if (available.isEmpty()) {
                    player.message("同账号下暂无可召唤的其他角色。");
                } else {
                    for (CharactersDO cdo : available) {
                        player.message(" >> [ID:" + cdo.getId() + "] " + cdo.getName() + " (Lv." + cdo.getLevel() + ")");
                    }
                }
                break;
            }
            case "summon": {
                if (params.length < 2) {
                    player.dropMessage(5, "用法: @companion summon <角色ID>");
                    return;
                }
                try {
                    int targetId = Integer.parseInt(params[1]);
                    manager.summonCompanion(player, targetId);
                } catch (NumberFormatException e) {
                    player.dropMessage(5, "角色ID必须为数字！");
                }
                break;
            }
            case "dismiss": {
                if (params.length < 2) {
                    manager.dismissAllCompanions(player);
                } else {
                    try {
                        int targetId = Integer.parseInt(params[1]);
                        manager.dismissCompanion(player, targetId);
                    } catch (NumberFormatException e) {
                        player.dropMessage(5, "角色ID必须为数字！");
                    }
                }
                break;
            }
            case "switch": {
                if (params.length < 2) {
                    List<CompanionCharacter> companions = manager.getCompanions(player);
                    if (!companions.isEmpty()) {
                        manager.hotSwitchCharacter(player, companions.get(0).getId());
                    } else {
                        player.dropMessage(5, "当前没有随从可供切换！");
                    }
                } else {
                    try {
                        int targetId = Integer.parseInt(params[1]);
                        manager.hotSwitchCharacter(player, targetId);
                    } catch (NumberFormatException e) {
                        player.dropMessage(5, "角色ID必须为数字！");
                    }
                }
                break;
            }
            case "mode": {
                if (params.length < 3) {
                    player.dropMessage(5, "用法: @companion mode <角色ID> <0:辅助|1:战斗|2:防守>");
                    return;
                }
                try {
                    int targetId = Integer.parseInt(params[1]);
                    int modeCode = Integer.parseInt(params[2]);
                    List<CompanionCharacter> list = manager.getCompanions(player);
                    for (CompanionCharacter comp : list) {
                        if (comp.getId() == targetId) {
                            comp.setTacticMode(CompanionTacticMode.fromCode(modeCode));
                            player.dropMessage(5, "已将伙伴 【" + comp.getName() + "】 战术模式设置为: " + comp.getTacticMode().getDescription());
                            return;
                        }
                    }
                    player.dropMessage(5, "未找到出战中的伙伴！");
                } catch (NumberFormatException e) {
                    player.dropMessage(5, "参数必须为数字！");
                }
                break;
            }
            default:
                NPCScriptManager.getInstance().start(c, org.gms.constants.id.NpcId.MAPLE_ADMINISTRATOR, "companion_manager", null);
                break;
        }
    }
}
