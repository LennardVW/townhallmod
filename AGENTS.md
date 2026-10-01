# Townhall – guide for agents and developers

Server-side Fabric mod: config-defined **locations** (Townhall, prison, ...), each with its own root command.
Players go there and return to the exact spot they came from. Operators can send players (optionally for N minutes of online time).
Non-escapable locations confine players (command whitelist, pull-back, respawn there). Also: per-dimension difficulty.
Vanilla clients must keep working: commands only, no client code, no custom content.
User-facing docs (German): `README.md`.

## Stack

| | Version |
|---|---|
| Minecraft | 26.3 (unobfuscated, **Mojang names**, no Yarn) |
| Fabric Loader | 0.19.5 |
| Fabric API | 0.161.0+26.3 |
| Loom | 1.18.2 (`net.fabricmc.fabric-loom`, `implementation` deps, no `mappings`) |
| Java | 25 |

Mixins (`townhall.mixins.json`, `defaultRequire: 1`; keep this list complete when you add one). Everything else uses Fabric API events.
- Locations and commands: `CommandsMixin` (command whitelist for confined and not-yet-onboarded players), `CommandSourceStackAccessor` (real sender for `isOperatorSelf`), `CommandActivityMixin` (commands end AFK).
- World rules: `LevelMixin` (per-dimension difficulty), `BlockItemMixin` (placement for `build: false`), `ProjectileMixin` (player projectiles vs. world objects/blocks for `build: false`), `PlayerMixin` (`causeFoodExhaustion` for `hunger: false`; also nickname in `getDisplayName`), `EnvironmentAttributeSystemMixin` + `ServerCommonPacketListenerImplMixin` + `TimeCheckMixin` (fixed `time`), `ServerLevelMixin` (fixed `weather`, sleep skip guards, `tickCustomSpawners`, skeleton horse traps in `tickThunder`), `BedSleepMixin` (no sleeping in fixed-time/thunder worlds), `mobs`: `NaturalSpawnerMixin`, `BaseSpawnerMixin`, `TrialSpawnerMixin`, `NetherPortalBlockMixin`, `RaidsMixin`, `RaidMixin`; `fire`: `FireBlockMixin`; `explosions`: `ServerExplosionMixin`; `leafDecay`: `LeavesBlockMixin`.
- Nicknames: `PlayerMixin` (chat name), `ServerPlayerMixin` (`getTabListDisplayName`, also `[AFK]`), `PlayerInfoUpdatePacketAccessor`, `ChunkMapAccessor`, `TrackedEntityMixin` (name above the head), in commands `EntitySelectorMixin`, `EntitySelectorParserMixin`, `GameProfileArgumentMixin`, `CachedUserNameToIdResolverMixin`, completion `CommandNodeInspectorMixin`, `CommandSourceStackMixin` (see Nicknames in the file table).
- Door keys: `DoorBlockMixin`, `BlockBehaviourMixin`, `PistonStructureResolverMixin`, `LockedDoorExplosionMixin`, `BreakDoorGoalMixin`, `CraftingMenuMixin`, `CrafterBlockMixin` (see Keys in the file table).

`fabric.mod.json` keeps `"environment": "*"`: the mod has no client code, and `*` lets it also run in singleplayer (integrated server) and in the dev client; dedicated servers are the target. `depends.fabric-api` is `>=0.161.0+26.3` because the code needs APIs of that version (e.g. `PermissionEvents` of fabric-permission-api-v1).

## Commands

```bash
./gradlew build          # compiles + runs all GameTests, jar in build/libs/
./gradlew runGameTest    # only the GameTests (expect "All 149 required tests passed")
./gradlew runServer      # dev server in run/ (needs run/eula.txt)
./gradlew runGameTest -Dtownhall.benchOnly=true # isolated PerfBench, 60 players; build/run/gameTest/perf-bench.txt
```

CI: `.github/workflows/build.yml` runs `./gradlew build` (with the GameTests) on every push and pull request and uploads the jar as an artifact.
The benchmark (`PerfBench`) is registered with `-Dtownhall.bench=true` alongside the tests, or in isolation with `-Dtownhall.benchOnly=true`: `build.gradle` then adds it to the gametest entrypoints (`processGametestResources`) and passes the property to the game JVM. Compare numbers only between runs on the same machine; run it before and after a change.

## Files

| File | Job |
|---|---|
| `TownhallMod.java` | Entrypoint: loads config, registers commands and events (server tick, respawn in one handler with a fixed order, join, level change), logs dimension status; `SERVER_STOPPED` calls `reset()` on the in-memory state (`TownhallCommand` cooldown, `KeyCommand` cooldowns, `Onboarding` pending map, `Afk`) |
| `command/Feedback.java` | Command replies: `ok` (sender only), `okAdmin` (also other operators + log, used for config changes by `/location`, `/builder`, `/nick`, `/joinmessage`, `/tablist`), `fail` |
| `command/TownhallCommand.java` | Builds one Brigadier root per location (`build(command)`), all subcommands, cooldown, permission check; `sendCommandsToAll(server)` resends everyone's command tree; `sleep [0-100]` sets vanilla `GameRules.PLAYERS_SLEEPING_PERCENTAGE` via `server.getGameRules().set(rule, value, server)` (same path as `/gamerule`, saved with the world, not our config) |
| `config/TownhallConfig.java` | Config model + `validate()` (returns a list of errors) |
| `config/ConfigManager.java` | Load/reload/save `config/townhall.json`; atomic save (plain move if the FS can't); invalid file never replaces the active config; `save()` returns false and writes nothing while the last load failed (`canSave()`), so the admin's broken file is never overwritten |
| `storage/ReturnLocation.java` | Record: dimension, x/y/z, yaw, pitch, timestamp, optional name (shown by `/townhall debug` only). Codec |
| `storage/PlayerState.java` | Record: `returnPosition`, `location` (last location id), `confined`, `remainingMillis` (timed stay) |
| `storage/ReturnPositionStorage.java` | `SavedData` with `Map<UUID, PlayerState>`, via `server.getDataStorage()` → `<world>/data/townhall/players.dat`; keeps an index of active (confined/timed) UUIDs in step in `set`/`remove`/`releaseConfinedAt`/load, so `activePlayers()` doesn't scan all states |
| `teleport/TeleportService.java` | `sendTo`, `returnPlayer`, `teleportToSpawn`, `sendToFallback`; the only place that teleports (failures are logged) |
| `teleport/SafeLocationFinder.java` | Safety check and nearest-safe-spot search in a small box: walks a cached nearest-first offset table (same order as the old sorted list, ties dx→dz→dy), `AirMap` (one bit per block, loaded chunks only) lets all-air ground boxes skip the block part of `noCollision` |
| `teleport/ConfinementService.java` | Once per second (only active players): count down timers, release, pull escaped confined players back; respawn handling; elapsed time from `System.nanoTime`, clamped to 0–5 s; `reset()` on `SERVER_STARTED` (registered by `ActivityService`) |
| `dimension/DimensionSettings.java` | Prebuilt `ResourceKey → Rules(difficulty, pvp, build, hunger, fallDamage, time, weather)` map from `dimensions`; difficulty + time packets (`syncClient`); weather overrides and packet filter; food refill on enter |
| `dimension/FixedClockManager.java` | Wraps a server world's clocks; stopped at the fixed time when the world has a `time` rule |
| `protection/Protection.java` | Fabric events: ALLOW_DAMAGE (pvp, fall, onboarding), block break, use block/item/entity, attack entity, chat |
| `onboarding/Onboarding.java` | First-join texts, accept button (`ClickEvent.RunCommand`), pending map (online only), freeze + reminders in the 1 s check |
| `onboarding/OnboardingStorage.java` | `SavedData`: UUID → accepted rules version (`data/townhall/onboarding.dat`) |
| `command/LocationCommand.java` | `/location` (op): list, info, create [prison], delete, setspawn, set &lt;id&gt; &lt;setting&gt; &lt;value&gt;; saves config and calls `TownhallCommand.refreshCommands` |
| `command/BuilderCommand.java` | `/builder add|remove <dim> <player>`, `list` (op; add resolves offline names via `GameProfileArgument` → name cache → Mojang), `creative|survival` (builders, only in their world) |
| `protection/BuilderPermissions.java` | `PermissionEvents.ON_REQUEST` (fabric-permission-api-v1): `worldedit:*` → TRUE for builders standing in their builder world, except `DENIED` |
| `display/DeathsInTab.java` | Scoreboard objective `townhall_deaths` (deathCount criterion, red `StyledFormat`) in `DisplaySlot.LIST`; score set from `Stats.DEATHS` on join/respawn; `apply(server)` on start, reload and `/townhall deathsintab on|off` |
| `nick/Nicknames.java` | `SavedData` UUID → (nick, real name) in `data/townhall/nicknames.dat`; static maps for hot paths: components (`of`), raw text (`raw`, tab list), plain nick → owner (`ownerOf`, commands), online profile name → token (`headNamesByRealName`, rebuilt on join/leave/change). `isKnownRealName`, `onlineByNickname`, `profileByNickname`, `displayName` (nick + real name on hover, for lists), `suggestions` |
| `nick/NickPackets.java` | Name above the head = team prefix + profile name. For other viewers the profile name becomes an invisible per-UUID token (`§r` + 6 color codes, 14 chars) and a client-only team `th_nick_<uuid8>` carries the full nickname as prefix (32 chars, colors). Score packets of **online** nicknamed players are renamed real name → token; vanilla team packets are never touched. Teams sent on join/leave/`refresh`; `refresh` re-sends the player (TrackedEntityMixin despawn/respawn) only to viewers in the same world |
| `nick/KnownNames.java` | Interface on the name cache (`CachedUserNameToIdResolverMixin`, test: `MockUserNameToIdResolverMixin`): is a name stored, without a Mojang lookup |
| `command/NickCommand.java` | `/nick set|reset|list` (op); rejects empty, > 32 chars, another player's nickname and every known real name (`Nicknames.isKnownRealName`); color codes, case and outer spaces ignored; own real name allowed |
| `mixin/EntitySelectorMixin.java`, `mixin/EntitySelectorParserMixin.java` | Plain names in entity arguments: fallback to an online player's nickname when `PlayerList.getPlayerByName` finds nobody and the name is no known real name; parser reads unquoted names with any letters/digits (ü, ß) and quoted names up to 32 chars |
| `mixin/GameProfileArgumentMixin.java` | Plain names in profile arguments (`/playtime`, `/nick`, `/builder`, `/op`): nickname (also offline) → owner, before the name cache (offline mode invents a profile for every unknown name) |
| `mixin/CommandNodeInspectorMixin.java`, `mixin/CommandSourceStackMixin.java` | Command tree marks entity/profile arguments without own suggestions as `ask_server`; server-side `getOnlinePlayerNames` adds typable nicknames of online players |
| `key/Keys.java`, `key/DoorLocks.java`, `command/KeyCommand.java` | Door keys: tripwire hook with `custom_data.townhall_key` (UUID); locks in `SavedData` keyed "dim|x|y|z" of the lower half; `UseBlockCallback` links/unlinks/blocks (resends both halves), break guard; admin key only for operators; `DoorBlockMixin` blocks redstone (`neighborChanged`) and mobs (`setOpen`) on locked doors and keeps a locked lower half when its support goes (`updateShape` DOWN); linking needs `Protection.mayBuild`; break guard also covers the block under a locked door; `PistonStructureResolverMixin` (resolve fails if a locked door is in toPush/toDestroy – doors are POPPED, so `isPushable` alone doesn't help), `LockedDoorExplosionMixin` (own `@ModifyVariable` on `interactWithBlocks`, chains with `ServerExplosionMixin`), `BreakDoorGoalMixin` (zombies), `BlockBehaviourMixin` (`onPlace` of a new door drops a stale lock, `affectNeighborsAfterRemoval` deletes the lock); `/key new|copy` 10 s cooldown per UUID (ops exempt); `CraftingMenuMixin` + `CrafterBlockMixin`: keys are no crafting ingredient |
| `activity/Afk.java`, `activity/Playtime.java`, `activity/ActivityService.java`, `command/ActivityCommands.java` | AFK once per second (look, or position change while a movement key is held in `ServerPlayer.getLastClientInput()` – pushing by water/pistons/mobs/players/knockback has no input and doesn't count; chat via `CHAT_MESSAGE`, every command via `CommandActivityMixin` at `Commands.performCommand` HEAD except `/afk` itself), `[AFK]` in `getTabListDisplayName`; play time `SavedData` (active time only, seeded from `Stats.PLAY_TIME`; marked dirty only when a value or name changed), `/playtime [player|top]` (`top(n)` keeps only the best n while walking the map, same order as the old stable sort; n ≥ size sorts all), `/afk`. Encoding 10k play time entries costs ≈ 1.2 ms per autosave, so the DFU codec stays |
| `display/JoinMessages.java`, `display/TabList.java`, `command/ChatDisplayCommands.java` | Vanilla join/leave suppressed via `ALLOW_GAME_MESSAGE` (translation keys), own templates with `{player}`; first join = `Stats.LEAVE_GAME == 0`; tab header/footer every 2 s with placeholders; `enabled: false` sends one empty header/footer when switching off, then nothing (other mods' tab lists survive); `/joinmessage set <player> -` stores "" = no join message |
| `command/RulesCommand.java` | `/rules`, `/regeln`, `accept`/`akzeptieren`, op `reset <player>` |
| `util/Text.java` | `&` color codes → legacy § (char loop, same result as the old regex), title packets |
| `mixin/BlockItemMixin.java`, `mixin/PlayerMixin.java` | Placement and hunger hooks (no Fabric event for them) |
| `mixin/CommandsMixin.java` | `Commands.performCommand` HEAD: confined non-op players may only run `confinement.allowedCommands` (op = `TownhallCommand.isOperatorSelf`) |
| `mixin/CommandSourceStackAccessor.java` | Reads the private `CommandSourceStack.source` (player, console, sign = `CommandSource.NULL`, command block) for `isOperatorSelf` |
| `mixin/LevelMixin.java` | Adds `Level.getDifficulty()` override (vanilla only has the `LevelAccessor` default) |
| `src/gametest/.../TownhallGameTests.java` | 66 GameTests |
| `src/gametest/.../WorldRulesGameTests.java` | 9 GameTests for 1.14.0 (sleep percentage, sleeping in fixed worlds, weather leak, all mob spawn paths, explosion fire/triggers, AFK pushing/commands, `time_check`, join message `-`, tab list off) |
| `src/gametest/.../PerformanceGameTests.java` | 5 GameTests for 1.14.1: the faster paths give the old results (safe-spot search vs. the old implementation, `/playtime top` ties, active index, `Text.of`, empty time packet, key scan, WorldEdit nodes) |
| `src/gametest/.../PerfBench.java` | Micro benchmark of the hot paths (60 mock players), only with `-Dtownhall.bench=true` |
| `src/gametest/.../PacketLog.java` + `mixin/PacketLogMixin` | Records packets sent to watched players (`PacketLog.watch(uuid)`), for packet-level assertions |
| `src/gametest/.../FreshTestRun.java` | `preLaunch` entrypoint of the test mod: on the GameTest server only (`fabric-api.gametest` set) it deletes `world/` and `config/townhall.json` in `build/run/gameTest`, so every run starts clean |

## Core rules

**Entering (`sendTo`)**
1. Confined players can't enter anything unless the source is an operator (self-commands: `isOperatorSelf`, see Commands).
2. Already in this location's world and last location is this one (or unknown) → `ALREADY_THERE`, nothing saved. Operator `send` skips this check.
3. The target dimension is looked up on **every** call (the multiworld mod may register it late). Missing → `UNAVAILABLE`, no crash.
4. Teleport first; only then store state.
5. The return position is saved **only if the player was outside all location worlds**. Moving between locations keeps the original spot. A stored position is also kept while the state names a location (the location may have moved to another world meanwhile).
6. `confined = !location.isEscapable() && !TownhallMod.isOperator(player.permissions())` – the **target's** own op status decides, so operators are never confined (also when they send themselves). Leash, respawn and command whitelist skip operators too.
7. `durationMillis` (from `send <player> <minutes>`) is stored as `remainingMillis`.

**Returning (`returnPlayer`)**
1. Confined + not operator → `CONFINED`.
2. No stored position → `NO_POSITION`. If the player is stuck (in a location world or still has state), both `return` and operator `return <player>` send them to the fallback and clear their state. Otherwise it's just an error message.
3. Original spot safe → `EXACT`; else nearest safe spot within the radius → `NEARBY`; else fallback → `FALLBACK`.
4. After success: clear state (or keep only the position if `clearAfterSuccessfulReturn` is false).

**Confinement and timers** (`ConfinementService`)
- `END_SERVER_TICK`, every 20 ticks, iterates `storage.activePlayers()` (confined or timed) only. `ActivityService` (AFK, play time, tab list) runs 10 ticks later, so the two per-second passes never share a tick.
- Offline players are skipped, so their clock stops. Elapsed real time per pass comes from `System.nanoTime` (a changed system clock doesn't count) and is clamped to 0–5 s (lag spikes).
- Timer ≤ 0 → `release`: `returnPlayer(op)`, fallback if there is no position; message; state cleared.
- Confined and outside the location (other dimension, or farther than `confineRadius` from spawn) → teleport back to spawn.
- `AFTER_RESPAWN` puts dead confined players back right away.
- `CommandsMixin` blocks everything except the whitelist, which also covers teleport commands of other mods.

**Per-dimension difficulty** (`LevelMixin`, `DimensionSettings`)
- `config.dimensions["<dim id>"].difficulty` overrides `Level.getDifficulty()` on server levels; missing = vanilla value.
- Hot path: the mixin reads a prebuilt map. Call `DimensionSettings.rebuild(config)` after every config change.
- Clients only know one difficulty, so the packet is resent on join, level change, respawn and after changes.
- Known gap: a few vanilla spots read `LevelData.getDifficulty()` directly (ender pearl endermites, nether portal piglins, creaking heart). Vanilla `/difficulty` resends the global value to clients (display only).

**World rules** (`dimensions.<id>`: `difficulty`, `pvp`, `build`, `hunger`, `fallDamage`, `mobs`, `fire`, `explosions`, `leafDecay`, `time`, `weather`; null = vanilla)
- `build: false`: operators exempt. Blocks break, `BlockItem.place`, world-changing items on blocks (buckets, flint, bone meal, spawn eggs, hanging/armor stand/crystal/minecart/boat items, dye, ink sacs, honeycomb, shears, brush, ender eye, potions, `#axes/#shovels/#hoes`), right-clicking signs, flower pots, repeaters, comparators, note blocks, daylight detectors (any hand), hitting non-living entities or armor stands, using item frames/armor stands, projectiles of non-building players on world objects and blocks (`ProjectileMixin`).
- Usable blocks (`Protection.isUsableBlock`: hand-openable doors/trapdoors, fence gates, buttons, levers, beds, anything with `getMenuProvider`) return PASS when not sneaking, whatever the item: vanilla `ServerPlayerGameMode.useItemOn` runs the block's use first and these always consume the click, so the item never runs. Iron doors/trapdoors pass the click on, so they are not in that list.
- Commands: `worldrule <dimension> [rule] [true|false|default]`, `worldrule <dimension> time [day|noon|night|midnight|<0-23999>|default]`, `worldrule <dimension> weather [clear|rain|thunder|default]`; saved to config, `DimensionSettings.rebuild` after every change.

**Fixed time and weather** (`dimensions.<id>.time` / `.weather`)
- 26.x has no per-world time: all worlds read shared `WorldClock`s (`ServerClockManager`, one per server) through their dimension type's `defaultClock`, and one shared `WeatherData`.
- Server side time: `EnvironmentAttributeSystemMixin` wraps `Level.clockManager()` in `addDynamicLayers` with a `FixedClockManager` (sky light, monster spawning, darkness for beds etc. are sampled through it); `TimeCheckMixin` pins the clock read of loot `time_check`. Nothing calls `Level.getDefaultClockTime()` on the server (villagers read `clockManager()` directly and are not pinned). The rule is read per call, so changes apply next tick.
- Sleeping: a fixed night (or fixed thunder) makes the world dark, and a night skip moves the **shared** clock and ends the **shared** rain. So `BedSleepMixin` makes `BedRule.canSleep` false in worlds where `DimensionSettings.maySleep` is false (fixed `time`, or `weather: thunder`); the spawn point is set before that check. `ServerLevelMixin` also skips `moveToTimeMarker` (those worlds) and `resetWeatherCycle` (any fixed-weather world) in `ServerLevel.tick`.
- Sleep percentage is vanilla: per world, sleepers in that world vs. non-spectator players in that world (`SleepStatus`). Players in other worlds don't count.
- `DimensionSettings.pin(ticks, timeOfDay)` keeps the day count (villager restocks, moon phase still advance daily).
- Client side time: `ServerCommonPacketListenerImplMixin` rewrites every `ClientboundSetTimePacket` to a player in a fixed-time world (all clocks pinned, rate 0). `syncClient` resends the full sync on join, world change, respawn and rule changes.
- Weather: `ServerLevelMixin` replaces the second `WeatherData.isRaining()/isThundering()` read in `advanceWeatherCycle` (the one that moves the world's rain/thunder level), so rain fades in/out naturally and the shared timers keep running. Vanilla sends a world's start/stop-rain + rain/thunder level to *all* players when its rain flips (per-tick levels only go to that world). The `broadcastAll` wrap sends a fixed-weather world's packets only to its own players; after any other world's broadcast, `resendOwnWeather` gives every player in another world their own world's value where it differs (also covers a world still fading after its weather rule was removed).
- Known gap: the dimension must be able to have weather (`canHaveWeather`, false for Nether/End); `time` works everywhere.

**Onboarding**
- `JOIN`: if accepted version < `rulesVersion`, add to in-memory pending map (with join position) and send texts.
- Restricted = pending + `restrictUntilAccepted` + not operator + `needsToAccept` (so `enabled: false` or a lowered `rulesVersion` frees pending players at once): commands except `rules`/`regeln` blocked (`CommandsMixin`), chat blocked, no damage, no building/using, pulled back if > 3 blocks from the anchor, reminder every `reminderSeconds`.
- The anchor is the join position; every teleport through `TeleportService` moves it (`Onboarding.moved`), so a pending player sent to the prison stays there. `accept()` always removes the player from the pending map.
- Operators get the texts but no restrictions.

**Safe spot** (`SafeLocationFinder.isSafe`): inside world bounds and world border, no collision for the standing hitbox, no `BlockTags.DANGEROUS_FOR_TELEPORTATION` or lava in/below the body, and ground within 1 block below (or water at the feet).
The search box is at most (2·16+1)² × (2·16+1) positions, loaded through `level.getChunk` (normal chunk system, no forced chunks).
`isSafe` is a conjunction of side-effect-free checks; the ground check runs before the body check (fails first in open air). Worst case (16/16, open air, nothing safe) ≈ 3 ms; keep `PerformanceGameTests.safeSpotSearchMatchesOldSearch` green after changing the search.

**Commands**
- Per location root: `/<cmd>`, `return`, `return <player>` (op), `send <player> [minutes]` (op), `setspawn` (op), `reload`, `status`, `debug <player>`, `clearreturn <player>`, `difficulty <dimension> [peaceful|easy|normal|hard|default]` (all op).
- `adminOnly` roots are hidden from non-operators (`requires`), except for a player whose stored state names that location (sent there, so `return` works). `TeleportService.resendCommands` resends the tree after every state change.
- Operator = `source.permissions().hasPermission(...)`, mapped from `commands.operatorPermissionLevel` (1–4). Op-only subcommands (`send`, `return <player>`, `setspawn`, ...) use the source's permissions.
- Self-actions (enter, `return`, cooldown, adminOnly, command whitelist) use `isOperatorSelf`: for a player the **player's own** permissions decide, because sign click commands run with GAMEMASTER source permissions (`CommandSource.NULL`). Only the console acting through a player (`withEntity`, tests' `opAt`) keeps its own rights.
- Cooldown is in memory, per UUID, shared by all player commands.
- Root nodes are built per **command name** (`build(command)`) and resolve their location id on every use (`idFor`). Renamed/deleted locations: the old node's `requires` fails, so it disappears after `sendCommands`. New names: `refreshCommands(server)` registers missing roots live and resends the command tree (called by `/location` and `/townhall reload`).
- `isForeignCommand` blocks taking names of vanilla/other-mod commands. Our roots are recognized by their requirement class (`MayUse`), not by name, so a reused name stays correct after renames and datapack reloads. `register`/`refreshCommands` skip (and log) a config name that is already a foreign command instead of letting Brigadier merge into it; `/townhall reload` rejects such a file.
- `/location delete` refuses while any active (confined/timed) player is held there; `/townhall reload` refuses a file that drops such a location (`reloadProblems`).
- `/location create|setspawn|set dimension` refuse the overworld and the fallback world; `validate()` reports them too.
- `escapable true` (command or reload) clears `confined` of everyone stored at that location; return position and timer stay.

**Builders** (`dimensions.<id>.builders`: UUID → name)
- `Protection.mayBuild` = build rule || builder in this world || operator.
- `/builder creative` sets the `townhall.builder_creative` tag. `Protection.enforceBuilderMode` (join, world change, respawn, after `remove`) puts non-op players back to survival if they have the tag or are a builder anywhere but not here, and resends builders' command tree.
- `/townhall reload` calls `enforceBuilderMode` for all online players (builders removed in the file).
- WorldEdit asks `FabricPermissionsProvider` first (Fabric permission API, `worldedit.a.b` → `worldedit:a.b`), then lucko v0, then op level. Our handler answers only for builders in their world, `null` otherwise.
- Since 1.16.0, `BuilderSchematicCommandMixin` widens only the native setblock/fill root predicates. Never widen GAMEMASTER globally. `BuilderSchematics` checks the actor's own builder/world/Creative state, onboarding, confinement, complete bounded footprint, civic/plot protection and Fabric BEFORE break vetoes before native mutations. Nonempty BlockInput NBT and GameMasterBlock are denied; console/actual ops retain native behavior.
- `BuilderSetBlockMixin`/`BuilderFillMixin` guard the actual command implementations, including an elevated sign/forged source. `BlockInputTagAccessor` reads the private tag. Limit: 32768 target blocks, loaded chunks only; validation scans per command, never per tick.
- `BuilderPasteNetworkMixin` works inside the server-thread unsigned/signed command methods, after vanilla packet validation. It exempts only direct setblock/fill requests from eligible Creative builders, up to 64 per connection per server tick. Excess requests are cancelled before execution and charged by vanilla; ordinary commands and chat are unchanged. No Servux/custom packet grants. User guide: `docs/LITEMATICA.md`.
- World rules `mobs` (NaturalSpawner.spawnForChunk + spawnMobsForChunkGeneration, BaseSpawner.serverTick, TrialSpawner.canSpawnInLevel/spawnMob, ServerLevel.tickCustomSpawners, skeleton horse in ServerLevel.tickThunder, NetherPortalBlock.randomTick, Raids.createOrExtendRaid → null, Raid.tick → stop), `fire` (FireBlock.tick removes the fire), `explosions` (ServerExplosion.interactWithBlocks list → empty only for DESTROY/DESTROY_WITH_DECAY, so wind charges still trigger buttons/doors; createFire list → empty; both `@ModifyVariable`, chaining with other mods and LockedDoorExplosionMixin), `leafDecay` (LeavesBlock.randomTick).
- GameProfileArgument rejects `@s` ("selector includes entities"); tests use `@p[distance=..0.5]` there (see Testing).

## Rules – don't break these

- Store by **UUID**, never by name.
- Nicknames: real names always win over nicknames when a typed name is resolved. Never put the nick token into a vanilla team packet (the client scoreboard throws on a REMOVE for a team the name isn't in and disconnects the viewer). Never re-track (`updatePlayer`) a player for a viewer in another world.
- Never save a return position while the player stands in a location world.
- All world access and teleports stay on the server thread (commands and events already are).
- Tick work: only the two `END_SERVER_TICK` handlers (`ConfinementService`, `ActivityService`), and they return at once except once per second. Everything else is command- or event-driven; mixins on hot paths read prebuilt maps (`DimensionSettings`, `Nicknames`).
- `ConfigManager.reload()` must stay all-or-nothing.
- Every command that changes the config calls `TownhallCommand.saveConfig(source)` and reports "Config not saved" instead of success when it returns false.
- Texts filled in with `.formatted(...)` (`messages.*`, `alreadyHereMessage`) are checked in `validate()` with sample args of the right types; add new ones there.
- **Never `@Redirect`** a vanilla call: the live server runs mc-worlds, c2me, lithium, worldedit, and two redirects on one call crash the server on start (1.4.0 did this with mc-worlds' weather broadcast). Use MixinExtras `@WrapOperation` / `@ModifyExpressionValue`, which chain. `src/gametest/.../mixin/OtherModWeatherRedirectMixin` keeps a foreign redirect on that call as a regression test.
- Keep `PlayerState` codec fields optional with defaults, so old `players.dat` files still load.
- `Location.confineSentPlayers` is the legacy name (read only through `isEscapable()`); write `escapable`.
- The per-second confinement check touches only active (confined/timed) players and pending onboarding players; the per-second activity check (AFK, play time, tab list every 2 s) is the only pass over all online players.

## Versioning

`version` in `gradle.properties` (the jar is `townhall-<version>.jar`). Bump it with every change you ship and add an entry to `CHANGELOG.md` (German, simple):
new feature → minor (1.3.0 → 1.4.0), bug fix only → patch (1.3.0 → 1.3.1), breaking config/data change → major. Update the version line in `README.md`.

## How to extend

- **New location:** config only (see README). No code change.
- **New per-location setting:** add a field to `TownhallConfig.Location` with a default, validate it in `validate()`, use it in `TeleportService`, document it in README.
- **New subcommand:** add it in `TownhallCommand.build`; operator-only ones get `.requires(op)`; use `requirePlayer` when a position is needed.
- **Command replies:** `Feedback.ok` (sender only), `Feedback.okAdmin` (config changes, also to other operators and the log), `Feedback.fail`; no own copies per command class. `TownhallConfig.Spot.of(player)` for "where the player stands".
- **New in-memory static state** (maps keyed by UUID etc.): add a `reset()` and call it from `TownhallMod.onServerStopped`.
- **Optional permission mod:** keep it optional (no hard dependency); the user's server has fabric-permissions-api 0.7.0.

## Testing

- The GameTest server has no multiworld mod, and datapack dimensions from the test mod are **not** loaded. Tests therefore set every location's `dimension` to `minecraft:the_nether`.
- Commands run through `server.getCommands().performPrefixedCommand(...)`; they are synchronous, so assert right after.
- Mock players all share one name, and `EntityArgument.player()` rejects UUIDs. Operator commands in tests use a console source with `.withEntity(player)` plus `@s` (`opAt` helper). Don't use a plain `@p`: several mock players end up on the same location spawn and `@p` picks the wrong one. Only where `@s` is rejected (`GameProfileArgument`: `/nick`, `/builder add`, `/joinmessage set`) use `@p[distance=..0.5]` from `opAt`, which is exactly the player at the source position.
- Every GameTest run starts with a new world and a default `config/townhall.json` (`FreshTestRun`), so tests may change both; nothing carries over to the next run. Tests still share the live config and world *during* a run: restore what you change in `finally`, also inside `runAfterDelay` callbacks.
- `capture(source, command)` runs a command and returns a `Capture` with every success/failure text; assert the specific reason, not only that nothing happened.
- Operators in tests: `playerList.op(nameAndId, Optional.of(LevelBasedPermissionSet.GAMEMASTER), Optional.empty())`. Plain `op(...)` gives level 0 on the GameTest server (`LevelBasedPermissionSet.ALL` means "all players", not "all permissions").
- Mock players are always creative (overridden `gameMode()`), so real damage can't be tested: call `ServerLivingEntityEvents.ALLOW_DAMAGE.invoker()` instead. Hunger is only checked at rule level for the same reason.
- Old tests turn `onboarding.enabled` off in `playerOnFloor` (each mock "joins" and would otherwise be restricted).
- Timers and pull-back are tested by calling `ConfinementService.check(server, elapsedMillis)` directly (deterministic, no waiting).
- Command blocking is tested with a stand-in command (`escapetest`) registered on the live dispatcher.
- Nickname tests use random nicknames and reset them in `finally` (tests run in parallel on one world). All mock players share one profile name, and `NickPackets.rewrite` never renames the viewer's own name, so rename-map checks read `Nicknames.headNamesByRealName()` directly. Team packets are replayed against a plain `Scoreboard` that mirrors `ClientPacketListener.handleSetPlayerTeamPacket`.
- Sign clicks are simulated with the same source vanilla builds (`clickSign`: `CommandSource.NULL`, GAMEMASTER, player as entity).
- Tests that reload the live config first `save()`, keep the file text, and in `finally` write it back and run `/townhall reload` (a failed reload pauses saving). `Capture` collects what a command tells its sender.
- Tests that change overworld rules (`WorldRulesGameTests.withRules`) do it synchronously and restore in the same tick, because `fixedTimePerWorld`/`fixedWeatherPerWorld` run in parallel on the overworld. Environment attributes are cached per tick: call `environmentAttributes().invalidateTickCache()` + `updateSkyBrightness()` to see a change at once. The GameTest server has `spawn_mobs` off; mob tests set it on temporarily.
- Lazily loaded mixin targets only the GameTests exercise: `TrialSpawner`, `NetherPortalBlock`, `Raids`/`Raid`, `TimeCheck`, `ServerExplosion` (the dedicated-server boot doesn't load them).
- Not covered: real vanilla client, real death/respawn (the test calls `keepConfined` directly), difficulty display on a real client.


## Azubi city (1.15.0)

User/admin guide: `docs/STADT.md`. Accepted scope: `docs/city-design.md`.

- `city/`: `CitySettings` is the backwards-compatible `city` config section. `CityAccess.isAdmin` uses the actual player's permissions, even when a sign/forged command source has GAMEMASTER. Console can administer. Always use this guard for new admin roots.
- `RoleStorage` saves definitions + UUID memberships; defaults are only created for a new store. Removing a default role is intentional and must survive reload. Capabilities are whitelisted by `RoleService`: city.announce, police.jail, police.release, shop.manage, election.count. No vanilla permission levels/wildcards/Creative/WorldEdit are granted.
- `RoleService.decorate` adds the highest-priority role prefix to chat/tab names, preserving nicknames. `refresh` updates command trees and tab display names after role changes. The nickname above the head remains unchanged.
- `PoliceCommand` permits only the configured non-escapable prison, an admin-configured duration limit (default 15 online minutes), and release from that same prison. It preserves active sentences and refuses op targets. Use TeleportService/ReturnPositionStorage, never parallel teleport state.
- `PlotStorage` has a per-dimension chunk index. Claims cover the entire build height, reject overlap, are limited to 256x256 per plot, 2000 plots and 32000 indexed chunks. Codec validation rejects malformed bounds before building the index. `claimsEnabled` defaults false; unclaimed areas fall back to world rules.
- `Protection.mayBuildAt` must be called with the actual affected block, not merely the player's position. Owner/trusted UUIDs and explicitly allowed role IDs can build; claim permissions do not give Creative. Civic marker blocks are immutable until administrative release, including for ops.
- `CivicSites` combines election/shop reservations. `Civic*Mixin` classes cover container access, foreign chest merging, piston boundaries, fluid boundaries, fire destruction, explosion blocks/fire, hopper extraction/insertion/dropper paths, and copper golems. This is not a generic interception of every mod's direct world writes. Keep filtered explosion lists mutable: vanilla shuffles them.
- Active claims deny Townhall's Fabric WorldEdit permission grant to non-ops. There is no fine-grained WorldEdit integration and external permissions/direct writes are outside this guarantee.
- `election/`: native `ElectionStorage`, registered writable/signed vanilla books, token UUID per voter UUID. Reissuing invalidates the previous token; failed inventory insertion does not. Only pages are archived; voter UUID set is separate, archive order is randomly inserted, no issuance/submission auditing. Manual helper reading is only after close. Helpers replace tally values; publishing is op-only, requires all candidates + invalid count and exact sum. `releaseurn` preserves historical data and frees the marker after close/cancel/publish. CLOSED/PUBLISHED codecs allow released urns.
- Register election/shop UseBlock callbacks BEFORE `Protection.register()`, as a valid vote may occur in a protected election room. Handlers/services must check onboarding, confinement and spectators themselves. `ElectionUrnContainerMixin` denies all urn menus; `ElectionUrnMenuMixin` closes a pre-existing menu. Archive copies remove submitted metadata, executable components and author/title.
- `shop/`: one offer per reserved empty barrel, physical diamond/emerald currency, copied exact-component inventory simulation before committing goods/payment + SavedData. Owner UUID offline proceeds; trader capability only required for offers/stock. Owners keep withdrawal rights after role revocation. Admin recovery is explicit, logged, into the executing admin's own inventory. No deleting/changing owners with nonempty stock/earnings. Protect marker reservations even while shops are disabled/missing.
- Reject civic custom data recursively inside item containers, with depth/count bounds. Keys and ballots cannot be crafted into other items (ShopCraftingMixin/ShopCrafterMixin chain with existing key guards). Shop buy/offer/stock use the authoritative gameMode controller and reject Creative; shared builder inventories still let Creative-origin goods reach Survival, so do not claim anti-laundering/provenance.
- `audit/`: native bounded SavedData ring, default 50000, min 100/max 100000; query radius <=64, output <=100. Level.setBlock is attributed only inside server-thread actor scope; placement/break buffered scope commits on success, command scope tracks successful synchronous writes. No command transcripts, chat, ballots or NBT inventory snapshots. Async/deferred/direct chunk writes cannot reliably be attributed. No rollback.
- Storage: `<world>/data/townhall/{roles,plots,elections,shops,audit}.dat`, autosave + normal shutdown. Native player saves and SavedData aren't a hard-crash atomic transaction; disclose it.

New tests: `CityGameTests`, `ElectionGameTests`, `ShopGameTests`, `AuditGameTests`. Fixtures restore config/op/storage synchronously. Command raycasts use partial tick 1 (current view); mock players have old rotation/position defaults at tick 0. Police tests use unique nicknames because all mocks share a real name and cross-world mock selectors cannot always find a just-teleported player.

Delivery: complete build and GameTests, optional benchmark, dedicated server boot; copy the distributable jar to Downloads, attach one PR, do not auto-merge. No Co-Author or generated-by attribution. A real client click/book-edit session remains a separate manual acceptance check.
