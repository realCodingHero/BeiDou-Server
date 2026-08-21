---
name: maplestory-186-to-083-migration
description: Safely migrate selected MapleStory v186 WZ, cosmetic, cash-shop, effect, and client-compatibility assets into a GMS v0.83/v0.79-style Docker server and client. Use when importing v186 hair, face, equipment, capes, rings, cash weapons, ItemEff/SetEff/CharacterEff data, shop entries, String.wz names, or when diagnosing crashes, missing previews, incorrect cape layering, or inventory/equipment failures caused by a cross-version migration.
---

# MapleStory 186-to-083 Migration

Use this skill to perform selective, reversible migrations from a v186 MapleStory client/server dataset into a v083-era BeiDou-style Docker project. Treat the v083 client/server as the compatibility boundary: migrate only the requested assets and add compatibility code only when the old client cannot consume the newer resource model.

Read [references/migration-reference.md](references/migration-reference.md) when the task involves WZ conversion, ItemEff/SetEff, cape layering, cash-shop effects, database synchronization, crash diagnosis, or formal deployment.

## Operating rules

1. Keep research and formal environments separate. Never use research output as the formal source of truth; regenerate formal staging from the original v186 source and the formal base.
2. Do not copy all v186 WZ files wholesale. Select IDs and resource branches, because newer property shapes can crash an old client or server.
3. Stage every change outside the live client first. Parse, serialize, verify, compare hashes, then deploy.
4. Back up the exact files being overwritten before formal deployment. Prefer a timestamped rollback directory over an ad-hoc copy.
5. Do not modify the formal database or server merely to fix a client rendering problem. Distinguish client resources, client code, server WZ, shop rows, and character data.
6. A client DLL/resource change requires a complete client exit and restart. A pure client rendering change normally does not require a game-server restart.
7. Never remove a legacy `CharacterEff` reference unless the corresponding native `Effect/ItemEff.img/<id>` node and all of its external dependencies are present.

## Workflow

### 1. Establish the migration matrix

Record the source version, target version, client path, server/Docker path, source WZ path, target environment, affected IDs, and whether the request concerns:

- appearance data: hair, face, skin, equipment, weapon, cape;
- shop data: item ID, SN, price, category, quantity, period, gender, sale state;
- visual effects: `ItemEff.img`, `SetEff.img`, `CharacterEff.img`, item-side `info/effect`;
- names/icons: `String.wz`, `Item.wz`, client `Data`/`EN` layers;
- server behavior: WZ providers, item stats, equip type, inventory handling, or database rows.

If the executable is not the known x86 v083 build, stop and re-discover hook addresses before using any client DLL patch.

### 2. Inspect before editing

For each requested ID, verify all of the following in the v186 source and target base:

- the source image exists and parses with the correct IV/key;
- the target category/path is correct, including five- or seven-digit zero-padded filenames;
- required animation branches and canvases exist;
- `info/effect` paths and external links resolve;
- the server knows the item type and equip slot;
- the shop has a row if the item must be purchasable;
- the client has the matching String/icon/preview data.

Use WZ inspection/export tools before altering binary `.img` files. Keep a manifest of requested top-level IDs and a separate manifest of external dependencies.

### 3. Build a staged resource patch

Use the project WZ bridge or an equivalent parser to serialize target-compatible GMS `.img` files. The safe pattern is:

```text
target-base.img + selected v186 top-level nodes -> staging/output.img
staging/output.img -> parse/structure/hash verification
```

Use replacement only for the explicitly selected node; preserve unrelated target nodes. For ItemEff nodes with valid external UOL/outlink references, either:

- preserve the links and also merge every referenced target resource; or
- resolve/flatten them using the source external images before serialization.

Do not leave a link to `SetEff.img`/another effect image that is absent from the target client.

For equipment appearance images, keep the target server/client naming convention and flatten links only when the old client cannot resolve them. Apply gender/unlimited-period changes deliberately and record them in the migration manifest; do not silently rewrite unrelated equipment attributes.

### 4. Choose the correct effect path

This is the critical v186-to-v083 distinction:

- Old `CharacterEff` metadata with `z=-2`, `fixed=0`, or a changed `pos` does not reliably move an effect behind the avatar in the v083 client. The legacy compatibility path can still render it as a foreground layer.
- Adding `Effect/ItemEff.img/<id>` data alone is inert if the v083 client has no native ItemEff loader.
- For capes whose v186 data is native `ItemEff`, use a client compatibility module that loads `Effect/ItemEff.img/<id>/effect/<action-or-default>` through the avatar's normal `LoadLayer` path. This preserves authored z/pos/action behavior.
- Remove the old item-side `info/effect` reference only after the native node and external resources are verified. Keep exceptional IDs on the legacy path when no native ItemEff node exists in the source.

For the known BeiDou v083 executable, the validated native cape module is restricted to `110xxxx` capes. It stores per-avatar effect state, updates it on avatar modification and action changes, and uses the existing v083 `LoadLayer` path. Do not broaden it to rings or weapons without a separate test.

### 5. Synchronize server and shop data

Import server WZ/JSON/database data only after the client resource is valid. For a shop item, validate independently:

1. server item template and equip stats;
2. client appearance and String/icon data;
3. cash-shop row, SN, price, category, quantity, period, gender, and sale flag;
4. preview packet and purchase packet;
5. inventory insertion, equip, unequip, relog, and action rendering.

Do not deduplicate rows solely by item ID. Same-ID rows can intentionally differ by SN, price, quantity, period, promotion, or category. Remove only confirmed duplicate sale variants according to the requested pricing rule.

### 6. Validate in research

Use a fresh client process after every DLL/resource change. Test the smallest representative set first, then expand:

- stand, walk, jump, attack, prone, ladder/rope, and both facing directions for capes;
- preview, purchase, inventory open, equip, unequip, relog, and character selection for cash equipment;
- name/icon/tooltip and shop visibility;
- no crash dump, no silent equip failure, and no server-side item-stat exception.

If the game crashes, classify the stage before changing anything: startup, channel selection, entering the map, opening inventory, preview, equip, attack, or relog. Inspect the client dump and the exact equipped item list; repair incompatible database/character data before changing code when the crash originates in server `getEquipStats` or an invalid equipped item.

### 7. Deploy formal only after research passes

Before formal deployment:

- confirm the formal executable hash/architecture matches the research-tested client;
- ensure the formal client process is fully closed;
- create a timestamped backup of every DLL, effect image, and item image being overwritten;
- copy from v186-derived staging, not from research;
- verify deployed hashes and file counts;
- record known exceptions and the rollback path.

Do not restart the formal server unless server code/WZ/database data changed. Ask the user to fully restart the formal client and test the representative IDs. Roll back by restoring the timestamped files if startup, channel selection, inventory, or equip behavior regresses.

## Deliverable format

Report the source/target paths, affected IDs and counts, files changed, external dependencies, validation results, exceptions, backup path, whether the server was restarted, and the exact next user test. Do not claim success until the user has tested the client behavior.
