package dev.townhall.test;

import com.mojang.brigadier.CommandDispatcher;
import dev.townhall.TownhallMod;
import dev.townhall.audit.AuditCommands;
import dev.townhall.audit.AuditEntry;
import dev.townhall.audit.AuditLog;
import dev.townhall.audit.AuditStorage;
import dev.townhall.city.CityAccess;
import dev.townhall.config.TownhallConfig;
import dev.townhall.dimension.DimensionSettings;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** Synchronous fixtures restore shared config/data before another GameTest can observe them. */
public final class AuditGameTests {
	private static final Identifier OVERWORLD = Identifier.parse("minecraft:overworld");
	private static final Identifier NETHER = Identifier.parse("minecraft:the_nether");

	private static void isolated(GameTestHelper h, Consumer<AuditStorage> test) {
		MinecraftServer server = h.getLevel().getServer();
		AuditStorage previous = server.getDataStorage().computeIfAbsent(AuditStorage.TYPE);
		var settings = TownhallMod.CONFIG.get().city;
		boolean enabled = settings.auditEnabled;
		int limit = settings.auditMaxEntries;
		boolean onboarding = TownhallMod.CONFIG.get().onboarding.enabled;
		AuditStorage fresh = new AuditStorage();
		server.getDataStorage().set(AuditStorage.TYPE, fresh);
		settings.auditEnabled = true;
		settings.auditMaxEntries = 50_000;
		TownhallMod.CONFIG.get().onboarding.enabled = false;
		try { test.accept(fresh); }
		finally {
			server.getDataStorage().set(AuditStorage.TYPE, previous);
			settings.auditEnabled = enabled;
			settings.auditMaxEntries = limit;
			TownhallMod.CONFIG.get().onboarding.enabled = onboarding;
		}
		h.succeed();
	}

	private static ServerPlayer player(GameTestHelper h) {
		ServerPlayer player = h.makeMockServerPlayerInLevel();
		Vec3 pos = h.absoluteVec(new Vec3(1.5, 1, 1.5));
		player.absSnapTo(pos.x, pos.y, pos.z, 0, 0);
		return player;
	}

	private static AuditEntry add(AuditStorage storage, UUID actor, Identifier dim, BlockPos pos, String detail, int limit) {
		return storage.append(123456789L, actor, "audit-test", "test.change", Optional.ofNullable(dim),
				Optional.ofNullable(pos), "minecraft:air", "minecraft:stone", detail, limit).orElseThrow();
	}

	@GameTest
	public void boundedEvictionCodecAndStableIds(GameTestHelper h) {
		AuditStorage storage = new AuditStorage();
		UUID actor = UUID.randomUUID();
		BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos(4, 5, 6);
		for (int i = 0; i < 105; i++) add(storage, actor, OVERWORLD, mutable, "x".repeat(900), 100);
		mutable.set(40, 50, 60);
		h.assertTrue(storage.size() == 100 && storage.nextId() == 106, "ring evicts without reusing ids");
		var newest = storage.newest(e -> true, 1000);
		h.assertTrue(newest.size() == 100 && newest.getFirst().id() == 105 && newest.getLast().id() == 6, "newest first and capped");
		h.assertTrue(newest.getFirst().position().orElseThrow().equals(new BlockPos(4, 5, 6)), "position copied from mutable input");
		h.assertTrue(newest.getFirst().detail().length() == 512, "detail bounded");
		var tag = AuditStorage.CODEC.encodeStart(NbtOps.INSTANCE, storage).getOrThrow();
		AuditStorage restored = AuditStorage.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
		h.assertTrue(restored.newest(e -> true, 100).equals(newest), "all entry fields survive native codec roundtrip");
		h.assertTrue(add(restored, actor, null, null, "after restart", 100).id() == 106, "counter resumes after codec restore");
		AuditEntry longText = restored.append(1, actor, "a".repeat(200), "b".repeat(200), Optional.empty(), Optional.empty(),
				"c".repeat(900), "d".repeat(900), "e\n\u00a7f", 100).orElseThrow();
		h.assertTrue(longText.actorName().length() == 64 && longText.action().length() == 64
				&& longText.before().length() == 512 && longText.after().length() == 512 && longText.detail().equals("e  f"), "all texts bounded/single line");
		h.succeed();
	}

	@GameTest
	public void newestQueriesRespectUuidDimensionAndBounds(GameTestHelper h) {
		AuditStorage storage = new AuditStorage();
		UUID offline = UUID.randomUUID(), other = UUID.randomUUID();
		BlockPos center = new BlockPos(10, 60, 10);
		AuditEntry exact = add(storage, offline, OVERWORLD, center, "target", 50000);
		add(storage, other, NETHER, center, "other dimension", 50000);
		add(storage, offline, OVERWORLD, center.offset(65, 0, 0), "outside capped radius", 50000);
		h.assertTrue(storage.at(OVERWORLD, center, 10).equals(List.of(exact)), "inspect location/dimension positive control");
		h.assertTrue(storage.near(OVERWORLD, center, 10000, 10).equals(List.of(exact)), "radius internally capped at 64");
		h.assertTrue(storage.player(offline, 1).size() == 1 && storage.player(offline, 1).getFirst().detail().equals("outside capped radius"), "offline UUID newest match");
		h.assertTrue(storage.player(UUID.randomUUID(), 100).isEmpty() && storage.newest(e -> true, 0).isEmpty(), "empty and zero limit");
		h.succeed();
	}

	@GameTest
	public void actorScopesRestoreAfterExceptionsAndDiscardUncommittedChanges(GameTestHelper h) {
		isolated(h, storage -> {
			MinecraftServer server = h.getLevel().getServer();
			ServerPlayer player = player(h);
			h.assertFalse(AuditLog.hasActorScope(), "no leaked actor at entry");
			try (var outer = AuditLog.commandScope(server.createCommandSourceStack())) {
				try (var inner = AuditLog.playerScope(player, "block.place")) {
					AuditLog.changed(h.getLevel(), h.absolutePos(new BlockPos(2, 1, 2)), Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState());
					throw new IllegalStateException("test cancellation");
				} catch (IllegalStateException expected) {
					h.assertTrue(AuditLog.hasActorScope(), "exception restored outer actor");
				}
				h.assertTrue(storage.size() == 0, "failed player scope discarded all pending success entries");
				AuditLog.changed(h.getLevel(), h.absolutePos(new BlockPos(3, 1, 2)), Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState());
			}
			h.assertFalse(AuditLog.hasActorScope(), "outer scope removed thread local");
			var entry = storage.newest(e -> true, 1).getFirst();
			h.assertTrue(entry.actorId().equals(AuditLog.SYSTEM_ACTOR) && entry.action().equals("block.command"), "restored actor positive control");
			try (var scope = AuditLog.playerScope(player, "block.break")) {
				AuditLog.changed(h.getLevel(), h.absolutePos(new BlockPos(2, 1, 2)), Blocks.STONE.defaultBlockState(), Blocks.AIR.defaultBlockState());
				scope.commit();
			}
			h.assertTrue(storage.player(player.getUUID(), 10).size() == 1, "committed player scope recorded");
		});
	}

	@GameTest
	public void workerThreadsDoNotInheritActorsOrWriteAuditData(GameTestHelper h) {
		isolated(h, storage -> {
			MinecraftServer server = h.getLevel().getServer();
			UUID actor = UUID.randomUUID();
			try (var scope = AuditLog.commandScope(server.createCommandSourceStack())) {
				var work = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
					boolean inherited = AuditLog.hasActorScope();
					AuditLog.record(server, actor, "worker", "test.worker", "must be ignored");
					return inherited;
				});
				try { h.assertFalse(work.get(2, java.util.concurrent.TimeUnit.SECONDS), "ordinary ThreadLocal is not inherited"); }
				catch (Exception e) { throw new IllegalStateException("worker check failed", e); }
				h.assertTrue(storage.size() == 0 && AuditLog.hasActorScope(), "worker does not write or disturb caller scope");
			}
			AuditLog.record(server, actor, "worker", "test.main", "server thread control");
			h.assertTrue(storage.player(actor, 10).size() == 1 && !AuditLog.hasActorScope(), "server thread explicit record positive control");
		});
	}

	@GameTest
	public void auditDisabledHasNoRecordsAndUnscopedChangesAreIgnored(GameTestHelper h) {
		isolated(h, storage -> {
			MinecraftServer server = h.getLevel().getServer();
			BlockPos pos = h.absolutePos(new BlockPos(2, 1, 2));
			h.getLevel().setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
			h.assertTrue(storage.size() == 0, "natural/setup writes without an actor ignored");
			TownhallMod.CONFIG.get().city.auditEnabled = false;
			AuditLog.record(server.createCommandSourceStack(), "test.admin", "redacted");
			try (var scope = AuditLog.commandScope(server.createCommandSourceStack())) {
				h.getLevel().setBlock(pos, Blocks.DIRT.defaultBlockState(), 3);
				AuditLog.changed(h.getLevel(), pos, Blocks.STONE.defaultBlockState(), Blocks.DIRT.defaultBlockState());
			}
			h.assertTrue(storage.size() == 0, "disabled API and hooks add no entries");
			TownhallMod.CONFIG.get().city.auditEnabled = true;
			AuditLog.record(server.createCommandSourceStack(), "test.admin", "redacted");
			h.assertTrue(storage.size() == 1, "explicit record enabled positive control");
		});
	}

	@GameTest
	public void successfulCommandChangesAndNoOps(GameTestHelper h) {
		isolated(h, storage -> {
			MinecraftServer server = h.getLevel().getServer();
			BlockPos pos = h.absolutePos(new BlockPos(2, 1, 2));
			h.getLevel().setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
			String coordinates = pos.getX() + " " + pos.getY() + " " + pos.getZ();
			CommandSourceStack source = server.createCommandSourceStack().withLevel(h.getLevel());
			server.getCommands().performPrefixedCommand(source, "setblock " + coordinates + " minecraft:stone");
			h.assertTrue(h.getLevel().getBlockState(pos).is(Blocks.STONE), "setblock changed actual world");
			var found = storage.at(h.getLevel().dimension().identifier(), pos, 10);
			h.assertTrue(found.size() == 1 && found.getFirst().before().contains("air") && found.getFirst().after().contains("stone"), "setblock hook before/after");
			h.assertTrue(found.getFirst().action().equals("block.command") && found.getFirst().detail().isEmpty(), "no typed command captured");
			int before = storage.size();
			server.getCommands().performPrefixedCommand(source, "setblock " + coordinates + " minecraft:stone");
			server.getCommands().performPrefixedCommand(source, "audit_does_not_exist");
			h.assertTrue(storage.size() == before && !AuditLog.hasActorScope(), "no-op/failed command no entry; cleanup");
			BlockPos end = pos.offset(1, 0, 0);
			String endCoordinates = end.getX() + " " + end.getY() + " " + end.getZ();
			server.getCommands().performPrefixedCommand(source, "fill " + coordinates + " " + endCoordinates + " minecraft:dirt");
			h.assertTrue(h.getLevel().getBlockState(pos).is(Blocks.DIRT) && h.getLevel().getBlockState(end).is(Blocks.DIRT), "fill changed both blocks");
			h.assertTrue(storage.at(h.getLevel().dimension().identifier(), pos, 10).size() == 2
					&& storage.at(h.getLevel().dimension().identifier(), end, 10).size() == 1, "fill logs each changed block");
		});
	}

	@GameTest
	public void canceledPlacementBreakingAndSuccessfulControls(GameTestHelper h) {
		isolated(h, storage -> {
			ServerPlayer player = player(h);
			BlockPos floor = h.absolutePos(new BlockPos(3, 0, 3));
			h.getLevel().setBlock(floor, Blocks.STONE.defaultBlockState(), 3);
			h.getLevel().setBlock(floor.above(), Blocks.AIR.defaultBlockState(), 3);
			String dimension = h.getLevel().dimension().identifier().toString();
			TownhallConfig config = TownhallMod.CONFIG.get();
			TownhallConfig.DimensionRules previous = config.dimensions.get(dimension);
			TownhallConfig.DimensionRules restricted = new TownhallConfig.DimensionRules();
			restricted.build = false;
			config.dimensions.put(dimension, restricted);
			DimensionSettings.rebuild(config);
			try {
				BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(floor).add(0, .5, 0), Direction.UP, floor, false);
				InteractionResult denied = ((BlockItem) Items.STONE).place(new BlockPlaceContext(player, InteractionHand.MAIN_HAND, new ItemStack(Items.STONE), hit));
				boolean broken = player.gameMode.destroyBlock(floor);
				h.assertFalse(denied.consumesAction() || broken, "protected placement and Fabric break cancellation fail");
				h.assertTrue(h.getLevel().getBlockState(floor).is(Blocks.STONE) && h.getLevel().getBlockState(floor.above()).isAir(), "cancelled actions did not change blocks");
				h.assertTrue(storage.size() == 0 && !AuditLog.hasActorScope(), "cancelled actions have no success logs/leaked actor");
				restricted.build = true;
				DimensionSettings.rebuild(config);
				InteractionResult placed = ((BlockItem) Items.STONE).place(new BlockPlaceContext(player, InteractionHand.MAIN_HAND, new ItemStack(Items.STONE), hit));
				h.assertTrue(placed.consumesAction() && h.getLevel().getBlockState(floor.above()).is(Blocks.STONE), "placement positive control");
				h.assertTrue(player.gameMode.destroyBlock(floor.above()) && h.getLevel().getBlockState(floor.above()).isAir(), "break positive control");
				var entries = storage.player(player.getUUID(), 10);
				h.assertTrue(entries.size() == 2 && entries.getFirst().action().equals("block.break")
						&& entries.getLast().action().equals("block.place"), "only successful place/break entries");
			} finally {
				if (previous == null) config.dimensions.remove(dimension); else config.dimensions.put(dimension, previous);
				DimensionSettings.rebuild(config);
			}
		});
	}

	@GameTest
	public void auditAuthorizationOfflineQueriesAndPositiveControls(GameTestHelper h) {
		isolated(h, storage -> {
			MinecraftServer server = h.getLevel().getServer();
			ServerPlayer player = player(h);
			CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
			AuditCommands.register(dispatcher);
			CommandSourceStack normal = player.createCommandSourceStack();
			CommandSourceStack sign = new CommandSourceStack(CommandSource.NULL, player.position(), Vec2.ZERO,
					h.getLevel(), LevelBasedPermissionSet.GAMEMASTER, server, player);
			h.assertFalse(CityAccess.isAdmin(normal) || CityAccess.isAdmin(sign), "mock player and forged sign are not admins");
			for (String command : List.of("audit", "audit status", "audit inspect", "audit near 5 10", "audit player Nobody 10", "audit enabled false", "audit limit 100")) {
				h.assertTrue(execute(dispatcher, normal, command) == -1 && execute(dispatcher, sign, command) == -1, "ordinary/forged sign denied: " + command);
			}
			Capture rejected = new Capture();
			h.assertTrue(AuditCommands.status(sign.withSource(rejected)) == 0 && rejected.said("Nur Administratoren"), "handler independently guards authorization");
			UUID offline = UUID.randomUUID();
			String name = "Audit" + offline.toString().replace("-", "").substring(0, 10);
			server.services().nameToIdCache().add(new NameAndId(offline, name));
			AuditEntry entry = add(storage, offline, h.getLevel().dimension().identifier(), player.blockPosition(), "offline-control", 50000);
			CommandSourceStack console = server.createCommandSourceStack().withLevel(h.getLevel()).withPosition(player.position());
			Capture capture = new Capture();
			h.assertTrue(execute(dispatcher, console.withSource(capture), "audit player " + name + " 10") == 1
					&& capture.said("#" + entry.id()) && capture.said("offline-control"), "offline GameProfile resolves UUID and outputs matching entry");
			h.assertTrue(execute(dispatcher, console, "audit near 64 100") == 1 && execute(dispatcher, console, "audit status") == 1, "console query positive controls");
			for (String command : List.of("audit near 65", "audit near 4 101", "audit player " + name + " 101", "audit limit 99", "audit limit 100001")) {
				h.assertTrue(execute(dispatcher, console, command) == -1, "bounds rejected: " + command);
			}
		});
	}

	@GameTest
	public void inspectRayTraceAuthorizationAndStates(GameTestHelper h) {
		isolated(h, storage -> {
			MinecraftServer server = h.getLevel().getServer();
			ServerPlayer player = player(h);
			BlockPos target = h.absolutePos(new BlockPos(1, 2, 4));
			for (int z = 1; z <= 3; z++) h.getLevel().setBlock(h.absolutePos(new BlockPos(1, 2, z)), Blocks.AIR.defaultBlockState(), 3);
			h.getLevel().setBlock(target, Blocks.STONE.defaultBlockState(), 3);
			add(storage, UUID.randomUUID(), h.getLevel().dimension().identifier(), target, "ray-control", 50000);
			NameAndId profile = new NameAndId(player.getGameProfile());
			server.getPlayerList().op(profile, Optional.of(LevelBasedPermissionSet.GAMEMASTER), Optional.empty());
			try {
				Capture capture = new Capture();
				CommandSourceStack source = player.createCommandSourceStack().withSource(capture);
				h.assertTrue(CityAccess.isAdmin(source), "actual operator own permissions positive control");
				h.assertTrue(AuditCommands.inspect(source) == 1 && capture.said("ray-control")
						&& capture.said("minecraft:air → minecraft:stone"), "ray targets block and shows before/after");
			} finally { server.getPlayerList().deop(profile); }
		});
	}

	private static int execute(CommandDispatcher<CommandSourceStack> dispatcher, CommandSourceStack source, String command) {
		try { return dispatcher.execute(command, source); }
		catch (com.mojang.brigadier.exceptions.CommandSyntaxException expected) { return -1; }
	}

	private static final class Capture implements CommandSource {
		private final List<String> messages = new ArrayList<>();
		@Override public void sendSystemMessage(Component message) { messages.add(message.getString()); }
		@Override public boolean acceptsSuccess() { return true; }
		@Override public boolean acceptsFailure() { return true; }
		@Override public boolean shouldInformAdmins() { return false; }
		boolean said(String text) { return messages.stream().anyMatch(message -> message.contains(text)); }
	}
}
