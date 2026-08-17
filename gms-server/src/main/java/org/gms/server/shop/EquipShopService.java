/*
 This file is part of the OdinMS Maple Story Server
 Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
 Matthias Butz <matze@odinms.de>
 Jan Christian Meyer <vimes@odinms.de>

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU Affero General Public License as
 published by the Free Software Foundation version 3 as published by
 the Free Software Foundation.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.gms.server.shop;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.Job;
import org.gms.provider.Data;
import org.gms.provider.DataDirectoryEntry;
import org.gms.provider.DataFileEntry;
import org.gms.provider.DataProvider;
import org.gms.provider.DataProviderFactory;
import org.gms.provider.DataTool;
import org.gms.provider.wz.WZFiles;
import org.gms.server.ItemInformationProvider;
import org.gms.server.Shop;
import org.gms.server.ShopItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 装备商店服务：提供分类装备管理、非现金过滤、职业匹配与升序等级排序
 */
public class EquipShopService {
    private static final Logger log = LoggerFactory.getLogger(EquipShopService.class);
    private static final EquipShopService instance = new EquipShopService();

    public static EquipShopService getInstance() {
        return instance;
    }

    public static final int CATEGORY_WEAPON = 1;
    public static final int CATEGORY_CAP = 2;
    public static final int CATEGORY_COAT = 3;
    public static final int CATEGORY_PANTS = 4;
    public static final int CATEGORY_LONGCOAT = 5;
    public static final int CATEGORY_GLOVE = 6;
    public static final int CATEGORY_SHOES = 7;
    public static final int CATEGORY_SHIELD = 8;
    public static final int CATEGORY_CAPE = 9;
    public static final int CATEGORY_EARRING = 10;
    public static final int CATEGORY_RING = 11;
    public static final int CATEGORY_ACCESSORY = 12;

    public static class EquipEntry {
        private final int itemId;
        private final String name;
        private final int reqLevel;
        private final int reqJob;
        private final int price;
        private final int category;

        public EquipEntry(int itemId, String name, int reqLevel, int reqJob, int price, int category) {
            this.itemId = itemId;
            this.name = name;
            this.reqLevel = reqLevel;
            this.reqJob = reqJob;
            this.price = price;
            this.category = category;
        }

        public int getItemId() {
            return itemId;
        }

        public String getName() {
            return name;
        }

        public int getReqLevel() {
            return reqLevel;
        }

        public int getReqJob() {
            return reqJob;
        }

        public int getPrice() {
            return price;
        }

        public int getCategory() {
            return category;
        }
    }

    private final Map<Integer, List<EquipEntry>> categoryEquips = new HashMap<>();
    private volatile boolean initialized = false;

    private EquipShopService() {
    }

    public synchronized void initialize() {
        if (initialized) {
            return;
        }
        long startTime = System.currentTimeMillis();
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        DataProvider equipData = DataProviderFactory.getDataProvider(WZFiles.CHARACTER);
        DataDirectoryEntry root = equipData.getRoot();

        Map<Integer, List<EquipEntry>> tempMap = new HashMap<>();
        for (int i = 1; i <= 12; i++) {
            tempMap.put(i, new ArrayList<>());
        }

        int totalIndexed = 0;
        for (DataDirectoryEntry subDir : root.getSubdirectories()) {
            String dirName = subDir.getName();
            if (!isEquipFolder(dirName)) {
                continue;
            }
            for (DataFileEntry file : subDir.getFiles()) {
                String fileName = file.getName();
                if (!fileName.endsWith(".img")) {
                    continue;
                }
                String idStr = fileName.substring(0, fileName.length() - 4);
                try {
                    int itemId = Integer.parseInt(idStr);
                    int category = getCategoryByItemId(itemId);
                    if (category == 0) {
                        continue;
                    }

                    Data itemNode = equipData.getData(subDir.getName() + "/" + fileName);
                    if (itemNode == null) {
                        continue;
                    }
                    Data info = itemNode.getChildByPath("info");
                    if (info == null) {
                        continue;
                    }

                    int cash = org.gms.provider.DataTool.getInt("cash", info, 0);
                    if (cash == 1) {
                        continue;
                    }

                    String name = ii.getName(itemId);
                    if (name == null || name.trim().isEmpty() || name.equals("NO-NAME") || name.startsWith("??")) {
                        continue;
                    }

                    int reqLevel = org.gms.provider.DataTool.getInt("reqLevel", info, 0);
                    int reqJob = org.gms.provider.DataTool.getInt("reqJob", info, 0);
                    int wholePrice = org.gms.provider.DataTool.getInt("price", info, 0);
                    int price;
                    if (wholePrice > 0) {
                        price = wholePrice;
                    } else {
                        price = Math.max(1000, reqLevel * reqLevel * 80 + reqLevel * 500 + 1000);
                    }

                    EquipEntry entry = new EquipEntry(itemId, name, reqLevel, reqJob, price, category);
                    tempMap.get(category).add(entry);
                    totalIndexed++;
                } catch (NumberFormatException ignored) {
                } catch (Exception e) {
                    log.debug("Failed parsing equip file {}", fileName, e);
                }
            }
        }

        // 按需求等级升序排列，相同等级按 itemId 排序
        for (Map.Entry<Integer, List<EquipEntry>> e : tempMap.entrySet()) {
            e.getValue().sort(Comparator.comparingInt(EquipEntry::getReqLevel).thenComparingInt(EquipEntry::getItemId));
            categoryEquips.put(e.getKey(), Collections.unmodifiableList(e.getValue()));
        }

        initialized = true;
        log.info("EquipShopService initialized {} non-cash equipment items across 12 categories in {}ms",
                totalIndexed, System.currentTimeMillis() - startTime);
    }

    private boolean isEquipFolder(String dirName) {
        return "Accessory".equalsIgnoreCase(dirName)
                || "Cap".equalsIgnoreCase(dirName)
                || "Cape".equalsIgnoreCase(dirName)
                || "Coat".equalsIgnoreCase(dirName)
                || "Glove".equalsIgnoreCase(dirName)
                || "Longcoat".equalsIgnoreCase(dirName)
                || "Pants".equalsIgnoreCase(dirName)
                || "Ring".equalsIgnoreCase(dirName)
                || "Shield".equalsIgnoreCase(dirName)
                || "Shoes".equalsIgnoreCase(dirName)
                || "Weapon".equalsIgnoreCase(dirName);
    }

    public static int getCategoryByItemId(int itemId) {
        if (itemId >= 1300000 && itemId < 1700000) {
            return CATEGORY_WEAPON;
        } else if (itemId >= 1000000 && itemId < 1010000) {
            return CATEGORY_CAP;
        } else if (itemId >= 1040000 && itemId < 1050000) {
            return CATEGORY_COAT;
        } else if (itemId >= 1060000 && itemId < 1070000) {
            return CATEGORY_PANTS;
        } else if (itemId >= 1050000 && itemId < 1060000) {
            return CATEGORY_LONGCOAT;
        } else if (itemId >= 1080000 && itemId < 1090000) {
            return CATEGORY_GLOVE;
        } else if (itemId >= 1070000 && itemId < 1080000) {
            return CATEGORY_SHOES;
        } else if (itemId >= 1090000 && itemId < 1100000) {
            return CATEGORY_SHIELD;
        } else if (itemId >= 1100000 && itemId < 1110000) {
            return CATEGORY_CAPE;
        } else if (itemId >= 1030000 && itemId < 1040000) {
            return CATEGORY_EARRING;
        } else if (itemId >= 1110000 && itemId < 1120000) {
            return CATEGORY_RING;
        } else if ((itemId >= 1010000 && itemId < 1030000) || (itemId >= 1120000 && itemId < 1150000)) {
            return CATEGORY_ACCESSORY;
        }
        return 0;
    }

    public static int getJobBit(Job job) {
        if (job == null) return 0;
        int jobType = job.getId() / 100;
        if (jobType == 1 || jobType == 11 || jobType == 21) {
            return 1; // 战士
        } else if (jobType == 2 || jobType == 12 || jobType == 22 || job.getId() == 2001) {
            return 2; // 魔法师
        } else if (jobType == 3 || jobType == 13) {
            return 4; // 弓箭手
        } else if (jobType == 4 || jobType == 14) {
            return 8; // 飞侠
        } else if (jobType == 5 || jobType == 15) {
            return 16; // 海盗
        }
        return 0; // 新手 / 全职业
    }

    private boolean isWeaponSuitableForJob(int itemId, int jobBit, int reqJob) {
        // 通用武器（reqJob == 0）对所有职业开放，不过滤
        if (reqJob == 0) {
            return true;
        }
        if (jobBit == 0) {
            return true;
        }
        if ((reqJob & jobBit) == 0) {
            return false;
        }
        int weaponTypePrefix = (itemId / 10000) % 100;
        switch (jobBit) {
            case 1: // 战士 (单手剑、单手斧、单手钝器、双手剑、双手斧、双手钝器、枪、矛)
                return (weaponTypePrefix >= 30 && weaponTypePrefix <= 32)
                        || (weaponTypePrefix >= 40 && weaponTypePrefix <= 44);
            case 2: // 魔法师 (短杖、长杖)
                return weaponTypePrefix == 37 || weaponTypePrefix == 38;
            case 4: // 弓箭手 (弓、弩)
                return weaponTypePrefix == 45 || weaponTypePrefix == 46;
            case 8: // 飞侠 (短刀、拳套)
                return weaponTypePrefix == 33 || weaponTypePrefix == 47;
            case 16: // 海盗 (指虎、火枪)
                return weaponTypePrefix == 48 || weaponTypePrefix == 49;
            default:
                return true;
        }
    }

    public static final int SUB_WEAPON_ALL = 0;
    public static final int SUB_WEAPON_SWORD = 1;     // 单手剑(130), 双手剑(140)
    public static final int SUB_WEAPON_AXE = 2;       // 单手斧(131), 双手斧(141)
    public static final int SUB_WEAPON_BLUNT = 3;     // 单手钝器(132), 双手钝器(142)
    public static final int SUB_WEAPON_SPEAR_POLEARM = 4; // 枪(143), 矛(144)
    public static final int SUB_WEAPON_WAND_STAFF = 5;// 短杖(137), 长杖(138)
    public static final int SUB_WEAPON_BOW_CROSSBOW = 6;// 弓(145), 弩(146)
    public static final int SUB_WEAPON_DAGGER_CLAW = 7; // 短刀(133), 拳套(147)
    public static final int SUB_WEAPON_KNUCKLE_GUN = 8; // 指虎(148), 火枪(149)
    public static final int SUB_WEAPON_COMMON = 9;    // 全职业通用趣味武器 (reqJob == 0)

    private boolean isWeaponMatchingSubType(int itemId, int reqJob, int subType) {
        if (subType == SUB_WEAPON_ALL) {
            return true;
        }
        if (subType == SUB_WEAPON_COMMON) {
            return reqJob == 0;
        }
        int prefix = (itemId / 10000) % 100;
        switch (subType) {
            case SUB_WEAPON_SWORD:
                return prefix == 30 || prefix == 40;
            case SUB_WEAPON_AXE:
                return prefix == 31 || prefix == 41;
            case SUB_WEAPON_BLUNT:
                return prefix == 32 || prefix == 42;
            case SUB_WEAPON_SPEAR_POLEARM:
                return prefix == 43 || prefix == 44;
            case SUB_WEAPON_WAND_STAFF:
                return prefix == 37 || prefix == 38;
            case SUB_WEAPON_BOW_CROSSBOW:
                return prefix == 45 || prefix == 46;
            case SUB_WEAPON_DAGGER_CLAW:
                return prefix == 33 || prefix == 47;
            case SUB_WEAPON_KNUCKLE_GUN:
                return prefix == 48 || prefix == 49;
            default:
                return true;
        }
    }

    public boolean openShop(Client c, int categoryId) {
        return openShop(c, categoryId, 0, 0, 0);
    }

    public boolean openShop(Client c, int categoryId, int subType, int minLevel, int maxLevel) {
        if (!initialized) {
            initialize();
        }

        List<EquipEntry> allEntries = categoryEquips.get(categoryId);
        if (allEntries == null || allEntries.isEmpty()) {
            return false;
        }

        Character chr = c.getPlayer();
        int jobBit = getJobBit(chr.getJob());

        int shopId = 99000000 + categoryId * 10000 + subType * 100 + jobBit;
        Shop shop = new Shop(shopId, 9900001);

        for (EquipEntry entry : allEntries) {
            if (minLevel > 0 && entry.getReqLevel() < minLevel) {
                continue;
            }
            if (maxLevel > 0 && entry.getReqLevel() > maxLevel) {
                continue;
            }

            if (categoryId == CATEGORY_WEAPON) {
                if (subType != SUB_WEAPON_COMMON && !isWeaponSuitableForJob(entry.getItemId(), jobBit, entry.getReqJob())) {
                    continue;
                }
                if (!isWeaponMatchingSubType(entry.getItemId(), entry.getReqJob(), subType)) {
                    continue;
                }
            } else {
                // 防具、饰品等部位：仅显示本职业装备及全职业通用装备 (reqJob == 0)
                if (entry.getReqJob() != 0 && (entry.getReqJob() & jobBit) == 0) {
                    continue;
                }
            }

            shop.addItem(new ShopItem((short) 1000, entry.getItemId(), entry.getPrice(), 0));
        }

        shop.sendShop(c);
        return true;
    }
}
