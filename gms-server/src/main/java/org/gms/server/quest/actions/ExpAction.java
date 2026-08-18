/*
 This file is part of the OdinMS Maple Story Server
 Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
 Matthias Butz <matze@odinms.de>
 Jan Christian Meyer <vimes@odinms.de>

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
package org.gms.server.quest.actions;

import org.gms.client.Character;
import org.gms.config.GameConfig;
import org.gms.provider.Data;
import org.gms.provider.DataTool;
import org.gms.server.quest.Quest;
import org.gms.server.quest.QuestActionType;
import org.gms.util.NumberTool;

/**
 * @author Tyler (Twdtwd)
 */
public class ExpAction extends AbstractQuestAction {
    int exp;

    public ExpAction(Quest quest, Data data) {
        super(QuestActionType.EXP, quest);
        processData(data);
    }


    @Override
    public void processData(Data data) {
        exp = DataTool.getInt(data);
    }

    @Override
    public void run(Character chr, Integer extSelection) {
        runAction(chr, exp);
    }

    public static void runAction(Character chr, int gain) {
        if (gain <= 0) {
            return;
        }

        boolean useQuestRate = GameConfig.getServerBoolean("use_quest_rate");
        float finalRate = useQuestRate ? chr.getQuestExpRate() : chr.getExpRate();
        int totalExp = NumberTool.floatToInt(gain * finalRate);

        chr.gainExp(totalExp, true, true);

        // 如果存在经验加成且非新手保护限制
        if (totalExp > gain && !chr.hasNoviceExpRate()) {
            chr.dropMessage(5, "得到经验值 (+" + gain + ")");

            boolean hasSpecificBonus = false;

            // 1. 任务专属倍率加成 (quest_rate)
            if (useQuestRate) {
                float questRate = chr.getWorldServer().getQuestRate();
                if (questRate > 1.0f) {
                    int questBonus = NumberTool.floatToInt(gain * (questRate - 1.0f));
                    if (questBonus > 0) {
                        chr.dropMessage(5, "任务倍率奖励 (+" + questBonus + ")");
                        hasSpecificBonus = true;
                    }
                }
            }

            // 2. 世界/服务器活动基础经验倍率 (world exp rate)
            float worldRate = chr.getWorldServer().getExpRate();
            if (worldRate > 1.0f) {
                int worldBonus = NumberTool.floatToInt(gain * (worldRate - 1.0f));
                if (worldBonus > 0) {
                    chr.dropMessage(5, "活动倍率奖励 (+" + worldBonus + ")");
                    hasSpecificBonus = true;
                }
            }

            // 3. 双倍经验卡加成 (exp coupon)
            int couponRate = chr.getCouponExpRate();
            if (couponRate > 1) {
                int couponBonus = NumberTool.floatToInt(gain * (couponRate - 1));
                if (couponBonus > 0) {
                    chr.dropMessage(5, "双倍经验卡奖励 (+" + couponBonus + ")");
                    hasSpecificBonus = true;
                }
            }

            // 4. 角色特权/VIP加成 (raw exp rate)
            float rawRate = chr.getRawExpRate();
            if (rawRate > 1.0f) {
                int rawBonus = NumberTool.floatToInt(gain * (rawRate - 1.0f));
                if (rawBonus > 0) {
                    chr.dropMessage(5, "特权经验奖励 (+" + rawBonus + ")");
                    hasSpecificBonus = true;
                }
            }

            // 5. 家族特权加成 (family exp)
            float familyRate = chr.getFamilyExp();
            if (familyRate > 1.0f) {
                int familyBonus = NumberTool.floatToInt(gain * (familyRate - 1.0f));
                if (familyBonus > 0) {
                    chr.dropMessage(5, "家族特权奖励 (+" + familyBonus + ")");
                    hasSpecificBonus = true;
                }
            }

            // 若有未命名的复合加成兜底
            if (!hasSpecificBonus && (totalExp - gain) > 0) {
                chr.dropMessage(5, "任务倍率奖励 (+" + (totalExp - gain) + ")");
            }
        }
    }
} 
