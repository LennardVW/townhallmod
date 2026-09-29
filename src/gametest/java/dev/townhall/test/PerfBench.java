package dev.townhall.test;

import com.mojang.serialization.Codec;
import dev.townhall.TownhallMod;
import dev.townhall.activity.Afk;
import dev.townhall.activity.Playtime;
import dev.townhall.config.TownhallConfig;
import dev.townhall.dimension.DimensionSettings;
import dev.townhall.display.TabList;
import dev.townhall.key.DoorLocks;
import dev.townhall.key.Keys;
import dev.townhall.nick.NickPackets;
import dev.townhall.nick.Nicknames;
import dev.townhall.protection.Protection;
import dev.townhall.storage.PlayerState;
import dev.townhall.storage.ReturnLocation;
import dev.townhall.storage.ReturnPositionStorage;
import dev.townhall.teleport.ConfinementService;
import dev.townhall.teleport.SafeLocationFinder;
import dev.townhall.util.Text;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.permission.v1.PermissionEvents;
import net.fabricmc.fabric.api.permission.v1.PermissionNode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

/**
 * Micro benchmark of the mod's hot paths with 60 mock players. Off by default: it only measures when the game runs
 * with -Dtownhall.bench=true ({@code ./gradlew runGameTest -Dtownhall.bench=true}, see AGENTS.md), otherwise it
 * passes at once. Optional test, so it never counts towards "All N required tests passed". Writes perf-bench.txt
 * (in build/run/gameTest).
 */
public class PerfBench {

	static volatile Object sink;

	static String time(String name, int iterations, Runnable r) {
		return time(name, Math.max(1000, iterations / 10), iterations, r);
	}

	static String time(String name, int warmup, int iterations, Runnable r) {
		for (int i = 0; i < warmup; i++) r.run();
		long best = Long.MAX_VALUE, total = 0;
		for (int round = 0; round < 5; round++) {
			long t0 = System.nanoTime();
			for (int i = 0; i < iterations; i++) r.run();
			long ns = System.nanoTime() - t0;
			best = Math.min(best, ns);
			total += ns;
		}
		return String.format(Locale.ROOT, "%-62s best %12.1f ns/op   avg %12.1f ns/op   (%d ops x5)", name, best / (double) iterations, total / 5.0 / iterations, iterations);
	}

	@GameTest(maxTicks = 1200, required = false)
	public void perfBench(GameTestHelper h) {
		if (!Boolean.getBoolean("townhall.bench")) {
			h.succeed();
			return;
		}
		MinecraftServer server = h.getLevel().getServer();
		ServerLevel level = h.getLevel();
		for (int x = 0; x < 7; x++) for (int z = 0; z < 7; z++) h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
		TownhallMod.CONFIG.get().onboarding.enabled = false;
		List<ServerPlayer> players = new ArrayList<>();
		for (int i = 0; i < 60; i++) {
			ServerPlayer p = h.makeMockServerPlayerInLevel();
			Vec3 abs = h.absoluteVec(new Vec3(1.5 + (i % 5), 1, 1.5 + (i / 12)));
			p.absSnapTo(abs.x, abs.y, abs.z, 0f, 0f);
			players.add(p);
		}
		int online = server.getPlayerList().getPlayerCount();
		Nicknames nicks = Nicknames.get(server);
		for (int i = 0; i < 20; i++) nicks.set(server, players.get(i).getUUID(), "bench" + i, "&6Nick " + i);
		ServerPlayer viewer = players.get(59);
		Packet<?> motion = new ClientboundSetEntityMotionPacket(players.get(0).getId(), Vec3.ZERO);
		var info = ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(players);
		var levelKey = level.dimension();

		List<String> out = new ArrayList<>();
		out.add("players online: " + online + ", nicknamed: 20, JVM " + Runtime.version());
		out.add(time("mixin path: NickPackets.rewrite(non-matching packet)", 2_000_000, () -> sink = NickPackets.rewrite(viewer, motion)));
		out.add(time("mixin path: NickPackets.rewrite(player info, 60 entries)", 20_000, () -> sink = NickPackets.rewrite(viewer, info)));
		out.add(time("DimensionSettings.of(dimension)", 2_000_000, () -> sink = DimensionSettings.of(levelKey)));
		out.add(time("Protection.mayBuild(player)", 2_000_000, () -> sink = Protection.mayBuild(viewer)));
		out.add(time("Nicknames.of(uuid) (chat/tab display name)", 2_000_000, () -> sink = Nicknames.of(players.get(3).getUUID())));
		out.add(time("Afk.tabName(player)", 1_000_000, () -> sink = Afk.tabName(viewer)));
		long[] clock = {System.currentTimeMillis()};
		out.add(time("Afk.check(server) - 1x per second, 60 players", 20_000, () -> Afk.check(server, clock[0] += 1)));
		out.add(time("TabList.update(server) - every 2 s, 60 players", 2_000, () -> TabList.update(server)));

		// F8 Text.of
		out.add(time("Text.of(\"&6Hello &lWorld &rand more text\")", 1_000_000, () -> sink = Text.of("&6Hello &lWorld &rand more text")));

		// F9 WorldEdit permission node for a non-builder (the whole Fabric permission event)
		PermissionNode<Boolean> node = PermissionNode.of(Identifier.fromNamespaceAndPath("worldedit", "region.set"));
		PermissionNode<Boolean> denied = PermissionNode.of(Identifier.fromNamespaceAndPath("worldedit", "limit.unrestricted"));
		PermissionNode<Boolean> other = PermissionNode.of(Identifier.fromNamespaceAndPath("othermod", "feature.use"));
		var context = viewer.getPermissionContext();
		out.add(time("permission event: worldedit:region.set, non-builder", 1_000_000, () -> sink = PermissionEvents.ON_REQUEST.invoker().handlePermissionRequest(context, node)));
		out.add(time("permission event: worldedit:limit.unrestricted, non-builder", 1_000_000, () -> sink = PermissionEvents.ON_REQUEST.invoker().handlePermissionRequest(context, denied)));
		out.add(time("permission event: othermod:feature.use, non-builder", 1_000_000, () -> sink = PermissionEvents.ON_REQUEST.invoker().handlePermissionRequest(context, other)));

		// F6 time packet vanilla sends every 20 ticks (empty clock map), in a fixed-time world and in a normal one
		TownhallConfig config = TownhallMod.CONFIG.get();
		TownhallConfig.DimensionRules fixed = new TownhallConfig.DimensionRules();
		fixed.time = "midnight";
		config.dimensions.put("townhall:bench", fixed);
		DimensionSettings.rebuild(config);
		ResourceKey<Level> benchWorld = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("townhall", "bench"));
		try {
			ClientboundSetTimePacket empty = new ClientboundSetTimePacket(123L, Map.of());
			ClientboundSetTimePacket full = server.clockManager().createFullSyncPacket();
			out.add(time("timePacketFor(fixed-time world, empty clock map)", 2_000_000, () -> sink = DimensionSettings.timePacketFor(benchWorld, empty)));
			out.add(time("timePacketFor(normal world, empty clock map)", 2_000_000, () -> sink = DimensionSettings.timePacketFor(levelKey, empty)));
			out.add(time("timePacketFor(fixed-time world, full sync)", 1_000_000, () -> sink = DimensionSettings.timePacketFor(benchWorld, full)));
		} finally {
			config.dimensions.remove("townhall:bench");
			DimensionSettings.rebuild(config);
		}

		// F4 door lock lookup: standalone store with 0 and 1000 locks, and the live path used by DoorBlockMixin
		BlockPos probe = h.absolutePos(new BlockPos(3, 1, 3));
		DoorLocks emptyLocks = new DoorLocks();
		DoorLocks thousand = new DoorLocks();
		for (int i = 0; i < 1000; i++) thousand.put(levelKey, new BlockPos(100_000 + i, 64, 100_000 - i), new DoorLocks.Lock(UUID.randomUUID().toString(), "k", UUID.randomUUID().toString(), "o"));
		out.add(time("DoorLocks.get (0 locks, miss)", 2_000_000, () -> sink = emptyLocks.get(levelKey, probe)));
		out.add(time("DoorLocks.get (1000 locks, miss)", 2_000_000, () -> sink = thousand.get(levelKey, probe)));
		BlockState door = Blocks.OAK_DOOR.defaultBlockState();
		DoorLocks live = DoorLocks.get(server);
		out.add(time("Keys.isLocked (live store, " + live.size() + " locks)", 2_000_000, () -> sink = Keys.isLocked(level, probe, door)));
		List<BlockPos> added = new ArrayList<>();
		try {
			for (int i = 0; i < 1000; i++) {
				BlockPos p = new BlockPos(200_000 + i, 64, 200_000 - i);
				live.put(levelKey, p, new DoorLocks.Lock(UUID.randomUUID().toString(), "k", UUID.randomUUID().toString(), "o"));
				added.add(p);
			}
			out.add(time("Keys.isLocked (live store, " + live.size() + " locks)", 2_000_000, () -> sink = Keys.isLocked(level, probe, door)));
		} finally {
			for (BlockPos p : added) live.remove(levelKey, p);
		}
		sink = door.getValue(DoorBlock.HALF);

		// F5 key inventory scan: 36 foreign keys in the inventory, looking for a key that isn't there
		ServerPlayer keyHolder = players.get(58);
		for (int slot = 0; slot < 36; slot++) keyHolder.getInventory().setItem(slot, Keys.newKey("Bench " + slot));
		UUID missing = UUID.randomUUID();
		DoorLocks.Lock missingLock = new DoorLocks.Lock(missing.toString(), "none", UUID.randomUUID().toString(), "o");
		out.add(time("Keys.carriesKey (36 keys, miss)", 200_000, () -> sink = Keys.carriesKey(keyHolder, missing)));
		out.add(time("Keys.mayOpen (36 keys, miss, not op)", 200_000, () -> sink = Keys.mayOpen(keyHolder, missingLock)));
		out.add(time("inventory contains admin key (36 keys, miss)", 200_000, () -> sink = keyHolder.getInventory().contains(Keys::isAdminKey)));

		// F7 ConfinementService.check with 500 stored non-active states (return position only)
		ReturnPositionStorage storage = ReturnPositionStorage.get(server);
		List<UUID> stored = new ArrayList<>();
		try {
			PlayerState withPosition = PlayerState.EMPTY.withReturnPosition(ReturnLocation.of(viewer));
			for (int i = 0; i < 500; i++) {
				UUID id = UUID.randomUUID();
				storage.set(id, withPosition);
				stored.add(id);
			}
			out.add(time("ConfinementService.check (500 stored, 0 active)", 20_000, () -> ConfinementService.check(server, 0)));
		} finally {
			stored.forEach(storage::remove);
		}

		// F3 play time with 10k entries: /playtime top, and encoding (autosave)
		Playtime playtime = new Playtime();
		Random random = new Random(42);
		for (int i = 0; i < 10_000; i++) playtime.set(new UUID(random.nextLong(), random.nextLong()), "Player" + i, random.nextInt(1_000_000) * 1000L);
		out.add(time("Playtime.top(10) (10k entries)", 20, 200, () -> sink = playtime.top(10)));
		Codec<Playtime> codec = Playtime.CODEC;
		out.add(time("Playtime encode to NBT (10k entries)", 20, 100, () -> sink = codec.encodeStart(NbtOps.INSTANCE, playtime).getOrThrow()));
		out.add(time("Playtime.tick (60 players online)", 20_000, () -> playtime.tick(server, 1000)));

		// F2 safe spot search, worst case: radius 16/16 in open air (no ground anywhere, nothing safe)
		Vec3 sky = h.absoluteVec(new Vec3(3.5, 150, 3.5));
		out.add(time("SafeLocationFinder.find worst case (16/16, no safe spot)", 3, 10, () -> sink = SafeLocationFinder.find(level, viewer, sky, 16, 16)));
		Optional<Vec3> none = SafeLocationFinder.find(level, viewer, sky, 16, 16);
		out.add("  (worst case found: " + none + ")");
		Vec3 onFloor = h.absoluteVec(new Vec3(3.5, 3, 3.5));
		out.add(time("SafeLocationFinder.find typical (5/5, floor 2 below)", 100, 1000, () -> sink = SafeLocationFinder.find(level, viewer, onFloor, 5, 5)));

		try {
			Files.write(Path.of("perf-bench.txt"), out);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		out.forEach(System.out::println);
		h.succeed();
	}
}
