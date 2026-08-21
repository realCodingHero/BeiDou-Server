# v186 -> v083 Migration Reference

This reference records the non-obvious facts recovered during the BeiDou migration. Treat paths and hashes as examples from one installation; verify them on the current machine before acting.

## Environment map

The tested installation used:

| Role | Path |
|---|---|
| Formal client | `C:\Game\BeiDou-Client` |
| Formal Docker/server project | `C:\Game\BeiDou-docker` |
| Research client | `C:\Game\BeiDou-Client-research` |
| Research Docker/server project | `C:\Game\Beidou-docker-research` |
| v186 source/client data | `C:\Game\MS186` |
| Source/server workbench | `C:\Game\BeiDou-Server` |

The client is not inside the Docker container. Docker changes can provide server WZ/scripts/database behavior, but client `Data`, `EN`, `ijl15.dll`, and executable patches must be handled in the client directory.

For a new machine, discover these paths from `docker-compose*.yml`, the client launcher/config, and the user's actual v186 source. Never infer a database port or account from a previous machine without reading the compose environment.

## Parallel Docker and network setup

When formal and research containers must run simultaneously, give them separate Compose project names, container names, published host ports, and data volumes. Keep the client-facing game port unchanged when the client has a hardcoded port; use a distinct loopback address or another explicitly published host endpoint instead of changing the client binary casually. In the tested setup, loopback aliases such as `127.0.0.1` and `127.0.0.2` were used for separate client configurations while retaining the same game port.

Do not use a container address such as `172.0.0.2` as though it were a host endpoint. A container IP may not be reachable from the Windows host and a failed ping does not prove that the game service is down. Test the published host port with the actual client endpoint and inspect `docker compose ps`, logs, and port mappings.

For local MySQL tools:

- `Connection refused` means the selected host port is not listening or is mapped to the wrong container; verify the Compose port mapping and database readiness first.
- `Public Key Retrieval is not allowed` is a JDBC authentication/URL issue, not an item-data issue. For a local trusted setup, use the connector's explicit `allowPublicKeyRetrieval=true` and appropriate SSL setting, or configure the account/plugin correctly; do not change character/item rows while this error remains.
- Keep research and formal databases separate. Never point a research SQL repair at the formal database by relying on a default schema name.

## Resource mapping

Common v186-to-v083 mappings are:

| v186 source | v083 client target | Notes |
|---|---|---|
| `Character.wz/<Category>/<id>.img` | `Data/Character/<Category>/0<id>.img` | Usually zero-padded; preserve the target category. |
| `Effect.wz/ItemEff.img` | `Data/Effect/ItemEff.img` | Native item/cape/ring effect roots; old client needs code support. |
| `Effect.wz/SetEff.img` | `Data/Effect/SetEff.img` | Merge referenced roots if ItemEff uses external links. |
| `Effect.wz/CharacterEff.img` | `Data/Effect/CharacterEff.img` | Legacy effect path; do not assume its z semantics match v186. |
| `String.wz` | target client's corresponding String image/layer | Needed for names/tooltips; a valid item can still display English or blank names. |
| `Item.wz/Cash` and server item data | Docker server WZ/database/shop tables | Needed for purchase, stats, equip type, period, gender, and inventory behavior. |

The item ID alone is not sufficient. Validate the WZ node, server item template, String entry, icon/preview, shop row, and equip slot together.

## WZ conversion rules

1. Parse the v186 image with the v186 key/IV, then serialize a new target-compatible GMS `.img`; do not copy the raw encrypted block into the v083 client.
2. Build an explicit manifest of top-level nodes. Use `replace` only for selected nodes and keep the rest of the target image unchanged.
3. Keep canvases, origins, delays, UOLs, outlinks, z, pos, fixed, action, and animate fields unless there is a documented target-compatibility reason to transform them.
4. If a canvas links to `Effect/SetEff.img/<n>` or another external image, either flatten it while the source external image is loaded or merge the referenced node into the target. A preserved link to a missing target image produces an invisible effect.
5. Verify the serialized result by parsing it again. Do not treat a successful serializer exit as proof that the client can render it.

The workbench used a custom `WzBridge` with these useful operations:

```text
inspect <file.img>
export-xml <file.img> <destination.xml>
merge-img-top <base.img> <source.img> <names.json> <destination.img>
replace-img-top <base.img> <source.img> <names.json> <destination.img>
replace-img-top ... --preserve-links
remove-legacy-effect-info <source.img> <destination.img>
remove-legacy-effect-info-tree <source-root> <manifest.json> <destination-root>
```

The tested source was `C:\Game\BeiDou-Server\tools\WzBridge`. If these commands are not available in another checkout, locate the equivalent WZ parser or port the operations; do not edit binary IMG files with a text editor.

## Cape layering: the key lesson

The initial attempts changed legacy item metadata such as `z`, `fixed`, and `pos`. This did not solve 1102630 and 1102766 because the v083 client was still taking the old `CharacterEff` compatibility path. The old path could render a later-version effect as a foreground layer regardless of the copied values.

The successful design was:

1. Add the v186 node under `Effect/ItemEff.img/<capeId>`.
2. Load its `effect/<current-action-or-default>` branch through the avatar's existing `CUser::LoadLayer` path.
3. Maintain per-avatar item-effect state and refresh it after avatar modification and move/action changes.
4. Remove the old `CharacterEff` item-side `info/effect` reference to prevent duplicate foreground drawing.
5. Keep legacy handling for IDs without a native ItemEff node.

In the tested data, 408 formal capes had legacy references. 407 had v186 native ItemEff nodes and were converted. `1102232` had no corresponding v186 ItemEff node, so its legacy reference was intentionally kept. This is a data exception, not a universal rule.

## Native ItemEff client module

The low-risk integration was a separate `BeiDouItemEff.dll` loaded by the existing BeiDou `ijl15.dll`, rather than replacing the full proxy with an unrelated DLL. The existing proxy retained its Chinese input, network, resolution, login, and other patches.

The tested v083 executable was PE32 x86, image base `0x00400000`, relocations stripped. Its SHA-256 was:

```text
1198FA57CA5A7C489BAE43EC13C69681D9CABE0F96762F3DC0357FACF2E7D4DF
```

These addresses are valid only for that exact executable family. Re-disassemble and compare bytes before applying them to another build:

```text
CItemInfo::IterateItemInfo       0x005CA71C
CUser::UpdateAdditionalLayer     0x00940EB7
CUser::OnAvatarModified          0x0092E916
CUser::SetMoveAction             0x0092ECD1
CUser::LoadLayer                 0x00941417
CAvatar::Constructor             0x0044FE6C
CAvatar::Destructor              0x0045011C
CAvatar::RegisterNextBlink       0x00453AA2
CAvatar update compatibility     0x004534CC, 0x00453612
```

The recovered layout assumptions were:

```text
CUser -> CAvatar offset          0x88
CAvatar custom storage           +0x484 (hijacked legacy blink field)
```

The module used a `CustomData` allocation containing the blink state, body movement data, riding state, and 60 `ITEMEFFECTLAYER` slots. It was deliberately restricted to `itemId / 10000 == 110` so ring and weapon effects were not silently altered during cape testing.

Before installing a native module:

- confirm the executable is 32-bit and the absolute hook addresses match;
- keep the module x86, not x64;
- preserve the existing `ijl15.dll` exports (`NMCO_CallNMFunc`, `NMCO_CallNMFunc2`, `NMCO_MemoryFree`, and the six IJL exports);
- load the optional module from the client directory and allow a missing module to fall back cleanly during research;
- build with an x86 MSVC environment and verify PE architecture/dependencies with `dumpbin`;
- test full client startup, channel selection, map entry, inventory, equip, and relog before expanding the module scope.

The portable build used MSVC 14.44, Windows SDK 10.0.26100, CMake, and Ninja. The GBK/CP936 legacy C++ source required compiler options equivalent to `/source-charset:.936 /execution-charset:.936`; mechanically converting those source files to UTF-8 can corrupt Chinese literals.

## Effects beyond capes

Cash rings are more fragile than ordinary appearance equipment. Name-card, chat-frame, speech-bubble, and other ring effects can involve `ItemEff`, `CharacterEff`, follow layers, or client code branches. A ring that previews successfully can still crash when opening the inventory or after relog.

For every ring effect:

1. Test preview without buying.
2. Test inventory open before equip.
3. Equip, close/reopen inventory, relog, and test again.
4. Test the effect display and the corresponding item slot separately.
5. If one ring crashes, remove only that character's item first; do not delete all effect resources or assume another ring caused it.

If a native ItemEff loader is being used for capes only, do not assume it supports ring effect ownership or action branches. Expand the module with a separate manifest and regression set.

## Appearance equipment and crash triage

An item obtained by GM command but absent from the shop usually means the server item exists but the cash-shop row is missing or filtered. A shop item with no name usually indicates a String/client-language mismatch. A previewable item that cannot equip may have a server equip-type mismatch, missing client category data, or an item template that the v083 server cannot construct.

When a client crashes:

| Symptom | First hypothesis | First action |
|---|---|---|
| Crash during channel selection or new-character creation | Invalid equipped item or server `getEquipStats` failure | Inspect/repair character equipment and server item stats before changing the client. |
| Crash when opening inventory | Item effect/equipment node or client layer type is incompatible | Identify the newly acquired/equipped item; remove it from the character data and retest. |
| Crash several seconds after login | Persistent effect, action update, or relog-loaded resource | Test with the item unequipped and inspect the dump/timing. |
| Cape renders in front | Legacy CharacterEff path, not just wrong z value | Use native ItemEff loading and remove the duplicate legacy reference after validation. |
| ItemEff data exists but has no effect | v083 client has no ItemEff loader or branch path is missing | Confirm the compatibility module is loaded and the action/default branch exists. |
| GM item works but shop item is absent | Missing/filtered cash-shop row | Check SN, price, category, sale flag, period, and database row. |
| English or blank item name | String resource not imported or wrong client layer | Compare String.wz and target `Data`/`EN` mapping. |

Prioritize database/character repair when the server cannot build equip stats. Prioritize client resource/code repair when the server successfully sends the item but the client renders, equips, or opens inventory incorrectly.

## Shop and database cautions

Same-ID cash-shop rows can be intentional variants. They may differ by SN, price, quantity, period, promotion, or category. Do not collapse them by item ID alone. When cleaning duplicate products, identify the normal-price row and preserve the requested sale/event variants unless the user explicitly asks to remove them.

For appearance migrations, common server-side changes include:

- adding or correcting the item template;
- adding the cash-shop entry;
- setting gender to universal only when the request requires it;
- removing expiration only when the user explicitly requests permanent items;
- preserving existing items and rows rather than replacing the whole table.

Use the Docker project's actual MySQL port, credentials, and schema from compose/environment files. Do not hardcode the default development credentials from another project.

## Formal deployment and rollback

The validated formal pattern was:

1. Fully close the formal client and verify no process is using its DLL.
2. Back up `ijl15.dll`, the optional native module if present, affected `Data/Character/Cape/*.img`, and any changed `Data/Effect/*.img` into a timestamped directory under the formal Docker project's rollback area.
3. Copy only the v186-derived staging outputs into the formal client. Do not copy research files as the source.
4. Compare SHA-256 hashes for core files and count the affected item images.
5. Do not restart the game server for a client-only change.
6. Restart the client completely and test the representative IDs.
7. If regression occurs, restore the exact backup files and retest before investigating a new theory.

Keep the staging manifest, source paths, affected count, exception IDs, deployed hashes, and rollback directory in the handoff. A migration is not complete until the user confirms the client behavior after a fresh process start.
