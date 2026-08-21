/*
 This file is part of the OdinMS Maple Story Server
 Copyright (C) 2008 ~ 2010 Patrick Huy <patrick.huy@frz.cc>
 Matthias Butz <matze@odinms.de>
 Jan Christian Meyer <vimes@odinms.de>

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU Affero General Public License version 3
 as published by the Free Software Foundation. You may not use, modify
 or distribute this program under any other version of the
 GNU Affero General Public License.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.gms.net.packet.logging;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.net.opcodes.RecvOpcode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.gms.util.HexTool;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Logs packets from monitored characters to a file.
 *
 * @author Alan (SharpAceX)
 */
public class MonitoredChrLogger {
    private static final Logger log = LoggerFactory.getLogger(MonitoredChrLogger.class);
    private static final Set<Integer> monitoredChrIds = ConcurrentHashMap.newKeySet();
    private static final Map<Integer, RecvOpcode> opcodeLookup = new HashMap<>();

    static {
        for (RecvOpcode op : RecvOpcode.values()) {
            opcodeLookup.put(op.getValue(), op);
        }
    }

    /**
     * Toggle monitored status for a character id
     *
     * @return new status. true if the chrId is now monitored, otherwise false.
     */
    public static boolean toggleMonitored(int chrId) {
        if (monitoredChrIds.contains(chrId)) {
            monitoredChrIds.remove(chrId);
            return false;
        } else {
            monitoredChrIds.add(chrId);
            return true;
        }
    }

    public static boolean isMonitored(Character chr) {
        return chr != null && !monitoredChrIds.isEmpty() && monitoredChrIds.contains(chr.getId());
    }

    public static Collection<Integer> getMonitoredChrIds() {
        return monitoredChrIds;
    }

    public static void logPacketIfMonitored(Client c, short packetId, byte[] packetContent) {
        Character chr = c.getPlayer();
        if (!isMonitored(chr)) {
            return;
        }
        RecvOpcode op = getOpcodeFromValue(packetId);
        if (isRecvBlocked(op)) {
            return;
        }

        String packet = packetContent != null && packetContent.length > 0 ? HexTool.toHexString(packetContent) : "<empty>";
        log.info("{}-{} {}-{}", c.getAccountName(), chr.getName(), packetId, packet);
    }

    private static boolean isRecvBlocked(RecvOpcode op) {
        if (op == null) {
            return false;
        }
        return switch (op) {
            case MOVE_PLAYER, GENERAL_CHAT, TAKE_DAMAGE, MOVE_PET, MOVE_LIFE, NPC_ACTION, FACE_EXPRESSION -> true;
            default -> false;
        };
    }

    private static RecvOpcode getOpcodeFromValue(int value) {
        return opcodeLookup.get(value);
    }
}
