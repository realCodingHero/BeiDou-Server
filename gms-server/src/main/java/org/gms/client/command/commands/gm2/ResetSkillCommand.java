/*
    This file is part of the HeavenMS MapleStory Server, commands OdinMS-based
    Copyleft (L) 2016 - 2019 RonanLana

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

/*
   @Author: Arthur L - Refactored command content into modules
*/
package org.gms.client.command.commands.gm2;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.Skill;
import org.gms.client.command.Command;
import org.gms.constants.game.GameConstants;
import org.gms.util.I18nUtil;

import java.util.ArrayList;

public class ResetSkillCommand extends Command {
    {
        setDescription(I18nUtil.getMessage("ResetSkillCommand.message1"));
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        int jobId = player.getJob().getId();

        // Only reset skills already belonging to this character. Iterating Skill.img
        // creates an entry for every skill in the WZ data, including skills the
        // character never learned. Beginner/account skills must remain untouched.
        for (Skill skill : new ArrayList<>(player.getEditableSkills().keySet())) {
            if (skill == null || skill.isBeginnerSkill()) {
                continue;
            }

            if (!GameConstants.isInJobTree(skill.getId(), jobId)) {
                // Remove stale entries left by older/broken versions of this command.
                player.changeSkillLevel(skill, (byte) -1, -1, -1);
            } else {
                player.changeSkillLevel(skill, (byte) 0, skill.getMaxLevel(), -1);
            }
        }

        player.yellowMessage(I18nUtil.getMessage("ResetSkillCommand.message2"));
    }
}
