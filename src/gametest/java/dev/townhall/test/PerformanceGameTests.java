package dev.townhall.test;

import dev.townhall.TownhallMod;
import dev.townhall.activity.Playtime;
import dev.townhall.config.TownhallConfig;
import dev.townhall.dimension.DimensionSettings;
import dev.townhall.key.Keys;
import dev.townhall.storage.PlayerState;
import dev.townhall.storage.ReturnPositionStorage;
import dev.townhall.teleport.SafeLocationFinder;
import dev.townhall.util.Text;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.permission.v1.PermissionEvents;
import net.fabricmc.fabric.api.permission.v1.PermissionNode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

/** 1.14.1: the faster hot paths give exactly the results of the code they replaced. */
public class PerformanceGameTests {

	// ---- the replaced implementations, kept here as the reference ----

	private static boolean oldIsSafe(ServerLevel level, ServerPlayer player, Vec3 pos) {
		if (!level.isInWorldBounds(BlockPos.containing(pos))) return false;
		AABB body = player.getDimensions(Pose.STANDING).makeBoundingBox(pos);
		if (!level.getWorldBorder().isWithinBounds(body)) return false;
		if (!level.noCollision(player, body)) return false;
		for (BlockPos p : BlockPos.betweenClosed(
				Mth.floor(body.minX), Mth.floor(body.minY) - 1, Mth.floor(body.minZ),
				Mth.floor(body.maxX - 1.0E-6), Mth.floor(body.maxY - 1.0E-6), Mth.floor(body.maxZ - 1.0E-6))) {
			BlockState state = level.getBlockState(p);
			if (state.is(BlockTags.DANGEROUS_FOR_TELEPORTATION) || state.getFluidState().is(FluidTags.LAVA)) return false;
		}
		if (level.getFluidState(BlockPos.containing(pos)).is(FluidTags.WATER)) return true;
		AABB ground = new AABB(body.minX, body.minY - 1.0, body.minZ, body.maxX, body.minY, body.maxZ);
		return !level.noCollision(player, ground);
	}

	private static List<BlockPos> oldCandidates(BlockPos base, int horizontalRadius, int verticalRadius) {
		List<BlockPos> candidates = new ArrayList<>();
		for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
			for (int dz = -horizontalRadius; dz <= horizontalRadius; dz++) {
				for (int dy = -verticalRadius; dy <= verticalRadius; dy++) {
					candidates.add(base.offset(dx, dy, dz));
				}
			}
		}
		candidates.sort(Comparator.comparingDouble(p -> p.distSqr(base)));
		return candidates;
	}

	private static Optional<Vec3> oldFind(ServerLevel level, ServerPlayer player, Vec3 original, int horizontalRadius, int verticalRadius) {
		SafeLocationFinder.loadChunks(level, original, horizontalRadius);
		if (oldIsSafe(level, player, original)) return Optional.of(original);
		for (BlockPos p : oldCandidates(BlockPos.containing(original), horizontalRadius, verticalRadius)) {
			Vec3 candidate = Vec3.atBottomCenterOf(p);
			if (oldIsSafe(level, player, candidate)) return Optional.of(candidate);
		}
		return Optional.empty();
	}

	private static List<Map.Entry<UUID, Playtime.Entry>> oldTop(Playtime playtime, int count) {
		// the old top(): stable sort of all entries by time, most first (still what top() does for count >= size)
		return playtime.top(Integer.MAX_VALUE).stream().limit(count).toList();
	}

	// ---- tests ----

	@GameTest(maxTicks = 100)
	public void safeSpotSearchMatchesOldSearch(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		// Search order: same list as the old candidate list for every radius the config allows (spot checks).
		BlockPos origin = new BlockPos(0, 0, 0);
		for (int[] r : new int[][] {{0, 0}, {1, 0}, {0, 3}, {3, 1}, {5, 5}, {7, 2}, {16, 16}}) {
			List<BlockPos> old = oldCandidates(origin, r[0], r[1]);
			for (int round = 0; round < 2; round++) { // second round comes from the cache
				int[] offsets = SafeLocationFinder.offsets(r[0], r[1]);
				h.assertTrue(offsets.length == old.size(), "same number of candidates for " + r[0] + "/" + r[1]);
				for (int i = 0; i < offsets.length; i++) {
					BlockPos now = new BlockPos(SafeLocationFinder.unpackX(offsets[i]), SafeLocationFinder.unpackY(offsets[i]), SafeLocationFinder.unpackZ(offsets[i]));
					if (!now.equals(old.get(i))) throw h.assertionException("order differs at " + i + " for " + r[0] + "/" + r[1] + ": " + now + " vs " + old.get(i));
				}
			}
		}

		// Crafted terrain: floor with holes, lava, a fence, water, a roof. Same answer as the old search everywhere.
		for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) h.setBlock(new BlockPos(x, 0, z), (x + z) % 3 == 0 ? Blocks.AIR : Blocks.STONE);
		h.setBlock(new BlockPos(2, 1, 2), Blocks.LAVA);
		h.setBlock(new BlockPos(4, 1, 4), Blocks.OAK_FENCE);
		h.setBlock(new BlockPos(5, 1, 1), Blocks.WATER);
		h.setBlock(new BlockPos(1, 1, 5), Blocks.MAGMA_BLOCK);
		for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) h.setBlock(new BlockPos(x, 3, z), z == 7 ? Blocks.AIR : Blocks.STONE);
		h.setBlock(new BlockPos(3, 2, 3), Blocks.STONE);
		ServerPlayer player = h.makeMockServerPlayerInLevel();
		int checked = 0, found = 0;
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				for (double y : new double[] {0.5, 1.0, 1.3, 2.0, 4.0, 6.0}) {
					Vec3 pos = h.absoluteVec(new Vec3(x + 0.37, y, z + 0.81));
					for (int[] r : new int[][] {{0, 0}, {1, 1}, {2, 1}, {3, 2}}) {
						Optional<Vec3> expected = oldFind(level, player, pos, r[0], r[1]);
						Optional<Vec3> actual = SafeLocationFinder.find(level, player, pos, r[0], r[1]);
						if (!expected.equals(actual)) throw h.assertionException("search differs at " + pos + " r=" + r[0] + "/" + r[1] + ": " + actual + " vs old " + expected);
						checked++;
						if (actual.isPresent()) found++;
					}
					h.assertTrue(SafeLocationFinder.isSafe(level, player, pos) == oldIsSafe(level, player, pos), "isSafe differs at " + pos);
				}
			}
		}
		h.assertTrue(found > 0 && found < checked, "the crafted cases cover found and not found: " + found + "/" + checked);
		h.succeed();
	}

	@GameTest
	public void playtimeTopMatchesFullSort(GameTestHelper h) {
		Playtime playtime = new Playtime();
		Random random = new Random(7);
		for (int i = 0; i < 3000; i++) {
			playtime.set(new UUID(random.nextLong(), random.nextLong()), "P" + i, random.nextInt(40) * 1000L); // many equal times
		}
		for (int count : new int[] {1, 3, 10, 57, 2999, 3000, 5000}) {
			List<UUID> expected = oldTop(playtime, count).stream().map(Map.Entry::getKey).toList();
			List<UUID> actual = playtime.top(count).stream().map(Map.Entry::getKey).toList();
			h.assertTrue(expected.equals(actual), "top(" + count + ") in the old order (ties too)");
		}
		h.assertTrue(playtime.top(0).isEmpty(), "top(0) is empty");
		h.assertTrue(new Playtime().top(10).isEmpty(), "empty table");
		h.succeed();
	}

	@GameTest
	public void activePlayersIndexFollowsStates(GameTestHelper h) {
		ReturnPositionStorage storage = new ReturnPositionStorage();
		UUID timed = UUID.randomUUID(), confined = UUID.randomUUID(), plain = UUID.randomUUID(), both = UUID.randomUUID();
		PlayerState located = PlayerState.EMPTY.withStay("prison", false, Optional.empty());
		storage.set(timed, located.withRemaining(60_000));
		storage.set(confined, PlayerState.EMPTY.withStay("prison", true, Optional.empty()));
		storage.set(plain, located);
		storage.set(both, PlayerState.EMPTY.withStay("prison", true, Optional.of(5_000L)));
		h.assertTrue(new HashSet<>(storage.activePlayers()).equals(new HashSet<>(List.of(timed, confined, both))), "timed and confined are active");

		h.assertTrue(storage.releaseConfinedAt("prison") == 2, "two freed");
		h.assertTrue(new HashSet<>(storage.activePlayers()).equals(new HashSet<>(List.of(timed, both))), "freed without timer is no longer active");
		storage.set(timed, located);
		storage.remove(both);
		h.assertTrue(storage.activePlayers().isEmpty(), "timer gone and removed");
		storage.set(plain, PlayerState.EMPTY.withStay("prison", true, Optional.empty()));
		storage.set(confined, PlayerState.EMPTY);
		h.assertTrue(storage.activePlayers().equals(List.of(plain)), "set() both ways");

		// Loading a saved file builds the index too.
		var saved = ReturnPositionStorage.CODEC.encodeStart(NbtOps.INSTANCE, storage).getOrThrow();
		ReturnPositionStorage loaded = ReturnPositionStorage.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
		h.assertTrue(loaded.activePlayers().equals(List.of(plain)), "index after load");
		h.succeed();
	}

	@GameTest
	public void smallHotPathsKeepResults(GameTestHelper h) {
		// Text.of: same text as the old replaceAll
		for (String s : List.of("", "plain", "&6Gold &lbold &rreset", "&&x &z &G &K", "100% & more", "&", "&&6", "&6&", "&\u212A &\u0130 &R&r&O&p", "§&a$1\\")) {
			Component c = Text.of(s);
			h.assertTrue(c.getString().equals(s.replaceAll("&([0-9a-fk-orA-FK-OR])", "§$1")), "Text.of(" + s + ")");
		}

		// Time packets: vanilla's regular packet without clocks passes unchanged, also in a fixed-time world
		TownhallConfig config = TownhallMod.CONFIG.get();
		TownhallConfig.DimensionRules fixed = new TownhallConfig.DimensionRules();
		fixed.time = "noon";
		config.dimensions.put("townhall:perf_test", fixed);
		DimensionSettings.rebuild(config);
		try {
			ResourceKey<Level> world = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("townhall", "perf_test"));
			ClientboundSetTimePacket empty = new ClientboundSetTimePacket(99L, Map.of());
			h.assertTrue(DimensionSettings.timePacketFor(world, empty) == empty, "empty clock update passes unchanged");
			var full = h.getLevel().getServer().clockManager().createFullSyncPacket();
			var pinned = DimensionSettings.timePacketFor(world, full);
			h.assertTrue(pinned != full && pinned.clockUpdates().size() == full.clockUpdates().size(), "full sync is still pinned");
		} finally {
			config.dimensions.remove("townhall:perf_test");
			DimensionSettings.rebuild(config);
		}

		// Keys: matching without copying custom data finds exactly the own key
		ServerPlayer player = h.makeMockServerPlayerInLevel();
		ItemStack key = Keys.newKey("Perf");
		UUID id = Keys.keyId(key).orElseThrow();
		player.getInventory().setItem(3, Keys.newKey("Other"));
		h.assertFalse(Keys.carriesKey(player, id), "another key doesn't count");
		player.getInventory().setItem(7, key);
		h.assertTrue(Keys.carriesKey(player, id), "own key found");
		h.assertTrue(Keys.carriesKey(player, Keys.keyId(Keys.copy(key)).orElseThrow()), "copy has the same id");
		player.getInventory().setItem(7, Keys.adminKey());
		h.assertFalse(Keys.carriesKey(player, id), "admin key is no normal key");
		h.succeed();
	}

	@GameTest
	public void builderPermissionsAfterReorder(GameTestHelper h) {
		ServerPlayer player = h.makeMockServerPlayerInLevel();
		TownhallConfig config = TownhallMod.CONFIG.get();
		String dim = h.getLevel().dimension().identifier().toString();
		TownhallConfig.DimensionRules existing = config.dimensions.get(dim);
		Map<String, String> buildersBefore = existing == null || existing.builders == null ? null : new LinkedHashMap<>(existing.builders);
		var context = player.getPermissionContext();
		try {
			h.assertTrue(ask(context, "worldedit", "region.set") == null, "non-builder: no opinion");
			TownhallConfig.DimensionRules rules = existing != null ? existing : new TownhallConfig.DimensionRules();
			if (rules.builders == null) rules.builders = new LinkedHashMap<>();
			rules.builders.put(player.getUUID().toString(), "Perf");
			config.dimensions.put(dim, rules);
			DimensionSettings.rebuild(config);
			h.assertTrue(Boolean.TRUE.equals(ask(context, "worldedit", "region.set")), "builder gets worldedit:region.set");
			h.assertTrue(Boolean.TRUE.equals(ask(context, "minecraft", "worldedit.region.set")), "builder gets worldedit.region.set");
			h.assertTrue(ask(context, "worldedit", "limit.unrestricted") == null, "denied node");
			h.assertTrue(ask(context, "worldedit", "limit.unrestricted.more") == null, "below a denied node");
			h.assertTrue(Boolean.TRUE.equals(ask(context, "worldedit", "limit.unrestrictedx")), "only whole path parts are denied");
			h.assertTrue(Boolean.TRUE.equals(ask(context, "worldedit", "limit")), "parent of a denied node");
			h.assertTrue(ask(context, "othermod", "feature") == null, "other mods: no opinion");
		} finally {
			if (existing == null) config.dimensions.remove(dim);
			else existing.builders = buildersBefore;
			DimensionSettings.rebuild(config);
		}
		h.succeed();
	}

	private static Boolean ask(net.fabricmc.fabric.api.permission.v1.PermissionContext context, String namespace, String path) {
		return PermissionEvents.ON_REQUEST.invoker().handlePermissionRequest(context, PermissionNode.of(Identifier.fromNamespaceAndPath(namespace, path)));
	}
}
