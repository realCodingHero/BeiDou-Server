package org.gms.client.command.commands.gm0;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.command.Command;
import org.gms.server.companion.AccountCompanionManager;
import org.gms.server.companion.CompanionCharacter;

import java.util.List;

public class CompanionSwitchCommand extends Command {
    {
        setDescription("原地即时热切换主控角色至指定随从");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (player == null) {
            return;
        }

        AccountCompanionManager manager = AccountCompanionManager.getInstance();
        List<CompanionCharacter> companions = manager.getCompanions(player);
        if (companions.isEmpty()) {
            player.dropMessage(5, "当前未出战任何伙伴，请先使用 @companion 召唤！");
            return;
        }

        if (params.length == 0) {
            // 默认切换到第一个随从
            manager.hotSwitchCharacter(player, companions.get(0).getId());
        } else {
            try {
                int targetId = Integer.parseInt(params[0]);
                manager.hotSwitchCharacter(player, targetId);
            } catch (NumberFormatException e) {
                player.dropMessage(5, "角色ID必须为数字！用法: @switch <角色ID>");
            }
        }
    }
}
