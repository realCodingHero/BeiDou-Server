package org.gms.client.command.commands.gm0;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.command.Command;
import org.gms.server.companion.AccountCompanionManager;

public class CompanionDismissCommand extends Command {
    {
        setDescription("解散并保存当前出战的伙伴角色");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (player == null) {
            return;
        }

        AccountCompanionManager manager = AccountCompanionManager.getInstance();
        if (params.length == 0) {
            manager.dismissAllCompanions(player);
        } else {
            try {
                int targetId = Integer.parseInt(params[0]);
                manager.dismissCompanion(player, targetId);
            } catch (NumberFormatException e) {
                player.dropMessage(5, "角色ID必须为数字！用法: @dismiss [角色ID]");
            }
        }
    }
}
