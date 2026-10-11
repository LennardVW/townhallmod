package dev.townhall.test;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.townhall.TownhallMod;
import dev.townhall.city.Plot;
import dev.townhall.city.PlotStorage;
import dev.townhall.config.TownhallConfig;
import dev.townhall.dimension.DimensionSettings;
import dev.townhall.key.DoorLocks;
import dev.townhall.key.Keys;
import dev.townhall.onboarding.Onboarding;
import dev.townhall.protection.BuilderSchematics;
import dev.townhall.shop.Shop;
import dev.townhall.shop.ShopData;
import dev.townhall.storage.PlayerState;
import dev.townhall.storage.ReturnPositionStorage;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.blocks.BlockInput;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.*;
import java.util.function.Consumer;

public final class BuilderSchematicGameTests {
	private static void fixture(GameTestHelper h, Consumer<ServerPlayer> test) {
		var config = TownhallMod.CONFIG.get();
		boolean onboarding = config.onboarding.enabled, claims = config.city.claimsEnabled;
		config.onboarding.enabled = false; config.city.claimsEnabled = false;
		var player = h.makeMockServerPlayerInLevel();
		String dim = player.level().dimension().identifier().toString();
		var previous = config.dimensions.get(dim);
		var rules = new TownhallConfig.DimensionRules();
		rules.build = false;
		rules.builders = new LinkedHashMap<>(Map.of(player.getUUID().toString(), player.getGameProfile().name()));
		config.dimensions.put(dim, rules); DimensionSettings.rebuild(config);
		player.setGameMode(GameType.CREATIVE);
		try { test.accept(player); }
		finally {
			if (previous == null) config.dimensions.remove(dim); else config.dimensions.put(dim, previous);
			config.onboarding.enabled = onboarding; config.city.claimsEnabled = claims;
			DimensionSettings.rebuild(config);
			ReturnPositionStorage.get(h.getLevel().getServer()).remove(player.getUUID());
			Onboarding.onDisconnect(player);
		}
		h.succeed();
	}
	private static String xyz(BlockPos p) { return p.getX() + " " + p.getY() + " " + p.getZ(); }
	private static void run(CommandSourceStack s, String cmd) { s.getServer().getCommands().performPrefixedCommand(s, cmd); }
	private static boolean visible(ServerPlayer p, String root) {
		return p.level().getServer().getCommands().getDispatcher().getRoot().getChild(root).canUse(p.createCommandSourceStack());
	}
	private static TownhallConfig.DimensionRules rules(ServerPlayer p) {
		return TownhallMod.CONFIG.get().dimensions.get(p.level().dimension().identifier().toString());
	}
	private static void assertDenied(GameTestHelper h, CommandSourceStack source, BoundingBox bounds, String reason) {
		try {
			BuilderSchematics.check(source, bounds, new BlockInput(Blocks.STONE.defaultBlockState(), Set.of(), null));
			throw new AssertionError("Expected denial: " + reason);
		} catch (CommandSyntaxException e) { h.assertTrue(e.getRawMessage().getString().contains(reason), "specific denial: " + reason); }
	}
	@GameTest
	public void networkPasteDoesNotDisableOtherSpamChecks(GameTestHelper h) {
		fixture(h, p -> {
			var network = (dev.townhall.test.mixin.PasteNetworkTestAccess)p.connection;
			var commandCounter = (dev.townhall.test.mixin.ThrottlerTestAccess)network.townhall$commandThrottler();
			var chatCounter = (dev.townhall.test.mixin.ThrottlerTestAccess)network.townhall$chatThrottler();
			int before = commandCounter.townhall$count();
			BlockPos at = h.absolutePos(new BlockPos(2, 2, 2));
			for (int i = 0; i < 32; i++) {
				network.townhall$performUnsigned("setblock " + xyz(at) + (i % 2 == 0 ? " stone" : " glass"));
				network.townhall$commandSpam();
			}
			h.assertTrue(p.level().getBlockState(at).is(Blocks.GLASS), "actual network command execution succeeds without op");
			h.assertTrue(commandCounter.townhall$count() == before, "paste burst does not accumulate vanilla command spam");
			network.townhall$performUnsigned("builder survival"); network.townhall$commandSpam();
			h.assertTrue(commandCounter.townhall$count() > before, "ordinary command still charged");
			int chatBefore = chatCounter.townhall$count(); network.townhall$chatSpam();
			h.assertTrue(chatCounter.townhall$count() > chatBefore, "chat spam remains independently charged");
		});
	}
	@GameTest
	public void networkPasteBudgetStopsExtraWrites(GameTestHelper h) {
		fixture(h, p -> {
			var network = (dev.townhall.test.mixin.PasteNetworkTestAccess)p.connection;
			var counter = (dev.townhall.test.mixin.ThrottlerTestAccess)network.townhall$commandThrottler();
			BlockPos at = h.absolutePos(new BlockPos(2, 2, 2));
			for (int i = 0; i < BuilderSchematics.MAX_COMMANDS_PER_TICK; i++) {
				network.townhall$performUnsigned("setblock " + xyz(at) + (i % 2 == 0 ? " stone" : " glass")); network.townhall$commandSpam();
			}
			network.townhall$performUnsigned("setblock " + xyz(at) + " diamond_block"); network.townhall$commandSpam();
			h.assertTrue(p.level().getBlockState(at).is(Blocks.GLASS), "65th command did not write before detection");
			h.assertTrue(counter.townhall$count() > 0, "excess commands retain vanilla spam detection");
		});
	}
	@GameTest
	public void nativePasteWithoutOperator(GameTestHelper h) {
		fixture(h, p -> {
			h.assertFalse(p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER), "builder has no vanilla permission level");
			h.assertTrue(visible(p, "setblock") && visible(p, "fill"), "native paste roots available");
			for (String cmd : List.of("op", "gamemode", "execute", "gamerule", "data", "summon")) h.assertFalse(visible(p, cmd), "no unrelated permission: " + cmd);
			BlockPos a = h.absolutePos(new BlockPos(2, 2, 2)), b = a.east(2);
			run(p.createCommandSourceStack(), "setblock " + xyz(a) + " minecraft:stone strict");
			h.assertTrue(p.level().getBlockState(a).is(Blocks.STONE), "Litematica strict setblock actually placed");
			run(p.createCommandSourceStack(), "fill " + xyz(a) + " " + xyz(b) + " minecraft:glass replace");
			for (BlockPos pos : BlockPos.betweenClosed(a, b)) h.assertTrue(p.level().getBlockState(pos).is(Blocks.GLASS), "Litematica fill actually placed");
		});
	}
	@GameTest
	public void ordinaryAndSurvivalPlayersCannotPaste(GameTestHelper h) {
		fixture(h, p -> {
			BlockPos at = h.absolutePos(new BlockPos(2, 2, 2));
			p.setGameMode(GameType.SURVIVAL);
			run(p.createCommandSourceStack(), "setblock " + xyz(at) + " stone");
			h.assertTrue(p.level().getBlockState(at).isAir(), "actual server survival mode blocks paste even though mock gameMode() reports creative");
			p.setGameMode(GameType.CREATIVE);
			rules(p).builders.clear(); DimensionSettings.rebuild(TownhallMod.CONFIG.get());
			h.assertFalse(visible(p, "setblock"), "ordinary creative players get no paste rights");
			run(p.createCommandSourceStack(), "setblock " + xyz(at) + " stone");
			h.assertTrue(p.level().getBlockState(at).isAir(), "ordinary player cannot place");
		});
	}
	@GameTest
	public void dimensionAndRevocationApplyImmediately(GameTestHelper h) {
		fixture(h, p -> {
			var foreign = p.level().getServer().getLevel(Level.NETHER);
			h.assertFalse(BuilderSchematics.mayUse(p.createCommandSourceStack().withLevel(foreign)), "source cannot target another world");
			var position = h.absolutePos(new BlockPos(2, 2, 2));
			assertDenied(h, p.createCommandSourceStack().withLevel(foreign), BoundingBox.fromCorners(position, position), "Bauwelt");
			rules(p).builders.clear(); DimensionSettings.rebuild(TownhallMod.CONFIG.get());
			h.assertFalse(BuilderSchematics.mayUse(p.createCommandSourceStack()), "revocation has no stale permission cache");
		});
	}
	@GameTest
	public void fillChecksEntireClaimBeforeAnyChange(GameTestHelper h) {
		fixture(h, p -> {
			var plots = PlotStorage.get(p.level().getServer());
			String id = "schem_" + UUID.randomUUID().toString().substring(0, 8), dim = p.level().dimension().identifier().toString();
			BlockPos start = h.absolutePos(new BlockPos(2, 2, 2)), end = start.east(2);
			p.level().setBlockAndUpdate(end, Blocks.BARREL.defaultBlockState());
			var barrel = (net.minecraft.world.level.block.entity.BarrelBlockEntity)p.level().getBlockEntity(end);
			barrel.setItem(0, new ItemStack(net.minecraft.world.item.Items.DIAMOND));
			try {
				plots.put(id, new Plot(dim, end.getX(), end.getZ(), end.getX(), end.getZ(), Optional.of(UUID.randomUUID().toString()), List.of(), List.of(), true));
				TownhallMod.CONFIG.get().city.claimsEnabled = true;
				run(p.createCommandSourceStack(), "fill " + xyz(start) + " " + xyz(end) + " stone destroy");
				h.assertTrue(p.level().getBlockState(start).isAir(), "no partial fill outside foreign plot");
				h.assertTrue(p.level().getBlockState(end).is(Blocks.BARREL) && barrel.getItem(0).getCount() == 1, "foreign container not cleared or destroyed");
				plots.put(id, new Plot(dim, end.getX(), end.getZ(), end.getX(), end.getZ(), Optional.of(p.getUUID().toString()), List.of(), List.of(), true));
				run(p.createCommandSourceStack(), "fill " + xyz(start) + " " + xyz(end) + " glass strict");
				h.assertTrue(p.level().getBlockState(start).is(Blocks.GLASS) && p.level().getBlockState(end).is(Blocks.GLASS), "same command allowed on own plot");
			} finally { plots.remove(id); }
		});
	}
	@GameTest
	public void shopAndUrnSurviveNativePaste(GameTestHelper h) {
		fixture(h, p -> {
			var server = p.level().getServer(); var data = ShopData.get(server);
			String id = "paste_" + UUID.randomUUID().toString().substring(0, 8);
			BlockPos at = h.absolutePos(new BlockPos(3, 2, 3));
			p.level().setBlockAndUpdate(at, Blocks.BARREL.defaultBlockState());
			try {
				data.put(id, new Shop(p.getUUID(), p.level().dimension().identifier(), at, ItemStack.EMPTY, 1, 1, Shop.Currency.DIAMOND, 0, 0, 0));
				run(p.createCommandSourceStack(), "setblock " + xyz(at) + " stone");
				h.assertTrue(p.level().getBlockState(at).is(Blocks.BARREL), "reserved shop not replaced, even by owner builder");
			} finally { data.removeEmpty(id); }
			// Create a real urn using the actual op setup commands; remove OP before attempting the paste.
			var profile = new NameAndId(p.getUUID(), p.getGameProfile().name());
			server.getPlayerList().op(profile, Optional.of(LevelBasedPermissionSet.GAMEMASTER), Optional.empty());
			try {
				p.absSnapTo(at.getX() + .5, at.getY() + 1, at.getZ() + .5, 0, 90); p.setYHeadRot(0);
				run(p.createCommandSourceStack(), "wahl create " + id + " PasteTest");
				run(p.createCommandSourceStack(), "wahl room " + id + " 8");
				run(p.createCommandSourceStack(), "wahl urn " + id);
			} finally { server.getPlayerList().deop(profile); }
			try {
				h.assertTrue(dev.townhall.election.ElectionService.protectedAt(p.level(), at), "real urn registered positive control");
				run(p.createCommandSourceStack(), "fill " + xyz(at.west()) + " " + xyz(at) + " stone");
				h.assertTrue(p.level().getBlockState(at.west()).isAir() && p.level().getBlockState(at).is(Blocks.BARREL), "urn prevents whole fill before mutation");
			} finally { run(server.createCommandSourceStack(), "wahl releaseurn " + id); }
		});
	}
	@GameTest
	public void lockedDoorBreakVetoIsRespected(GameTestHelper h) {
		fixture(h, p -> {
			BlockPos at = h.absolutePos(new BlockPos(3, 2, 3));
			p.level().setBlockAndUpdate(at.below(), Blocks.STONE.defaultBlockState());
			p.level().setBlockAndUpdate(at, Blocks.OAK_DOOR.defaultBlockState());
			p.level().setBlockAndUpdate(at.above(), Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
			var key = Keys.newKey("PasteTest"); var keyId = Keys.keyId(key).orElseThrow();
			var locks = DoorLocks.get(p.level().getServer());
			try {
				locks.put(p.level().dimension(), at, new DoorLocks.Lock(keyId.toString(), "PasteTest", UUID.randomUUID().toString(), "Owner"));
				run(p.createCommandSourceStack(), "setblock " + xyz(at) + " stone destroy");
				h.assertTrue(p.level().getBlockState(at).is(Blocks.OAK_DOOR), "locked door cannot be overwritten without key");
				p.getInventory().add(key);
				run(p.createCommandSourceStack(), "setblock " + xyz(at) + " stone");
				h.assertTrue(p.level().getBlockState(at).is(Blocks.STONE), "same command allowed with actual matching key");
			} finally { locks.remove(p.level().dimension(), at); }
		});
	}
	@GameTest
	public void dangerousBlocksAndNbtRemainAdminOnly(GameTestHelper h) {
		fixture(h, p -> {
			BlockPos at = h.absolutePos(new BlockPos(2, 2, 2));
			for (String block : List.of("command_block", "structure_block", "jigsaw", "barrel{CustomName:'Test'}")) {
				run(p.createCommandSourceStack(), "setblock " + xyz(at) + " " + block);
				h.assertTrue(p.level().getBlockState(at).isAir(), "dangerous paste rejected: " + block);
			}
			run(p.createCommandSourceStack(), "setblock " + xyz(at) + " barrel");
			h.assertTrue(p.level().getBlockState(at).is(Blocks.BARREL), "ordinary empty containers may be pasted");
			run(p.level().getServer().createCommandSourceStack(), "setblock " + xyz(at) + " command_block");
			h.assertTrue(p.level().getBlockState(at).is(Blocks.COMMAND_BLOCK), "console native permissions unaffected");
		});
	}
	@GameTest
	public void prisonAndRulesBlockBuilderPaste(GameTestHelper h) {
		fixture(h, p -> {
			var states = ReturnPositionStorage.get(p.level().getServer());
			BlockPos at = h.absolutePos(new BlockPos(2, 2, 2));
			states.set(p.getUUID(), new PlayerState(Optional.empty(), Optional.of("gefaengnis"), true, Optional.empty()));
			h.assertFalse(BuilderSchematics.mayUse(p.createCommandSourceStack()), "confined builder gets no paste right");
			run(p.createCommandSourceStack(), "setblock " + xyz(at) + " stone");
			h.assertTrue(p.level().getBlockState(at).isAir(), "prison command guard still effective");
			states.remove(p.getUUID());
			TownhallMod.CONFIG.get().onboarding.enabled = true; Onboarding.onJoin(p);
			h.assertTrue(Onboarding.isRestricted(p), "rules actually pending positive control");
			h.assertFalse(BuilderSchematics.mayUse(p.createCommandSourceStack()), "pending rules prevent paste");
		});
	}
	@GameTest
	public void builderPasteRespectsWorldBorder(GameTestHelper h) {
		fixture(h, p -> {
			var border = p.level().getWorldBorder();
			double x = border.getCenterX(), z = border.getCenterZ(), size = border.getSize();
			BlockPos at = h.absolutePos(new BlockPos(2, 2, 2));
			try {
				border.setCenter(at.getX(), at.getZ()); border.setSize(10);
				assertDenied(h, p.createCommandSourceStack(), BoundingBox.fromCorners(at, at.east(6)), "Weltgrenze");
				run(p.createCommandSourceStack(), "setblock " + xyz(at) + " stone");
				h.assertTrue(p.level().getBlockState(at).is(Blocks.STONE), "allowed within border positive control");
			} finally { border.setCenter(x, z); border.setSize(size); }
		});
	}
	@GameTest
	public void limitsCheckedBeforeChunkOrBlockSearch(GameTestHelper h) {
		fixture(h, p -> {
			BlockPos at = h.absolutePos(new BlockPos(2, 2, 2));
			assertDenied(h, p.createCommandSourceStack(), BoundingBox.fromCorners(at, at.east(BuilderSchematics.MAX_BLOCKS)), "32768");
			assertDenied(h, p.createCommandSourceStack(), new BoundingBox(-30000000, -2000000000, -30000000, 30000000, 2000000000, 30000000), "32768");
			BlockPos unloaded = new BlockPos(1234567, 80, -1234567);
			h.assertFalse(p.level().hasChunkAt(unloaded), "remote test target initially unloaded");
			assertDenied(h, p.createCommandSourceStack(), BoundingBox.fromCorners(unloaded, unloaded), "geladen");
			h.assertFalse(p.level().hasChunkAt(unloaded), "guard loads no remote chunks");
		});
	}
	@GameTest
	public void elevatedSourceDoesNotBypassBuilderPolicy(GameTestHelper h) {
		fixture(h, p -> {
			BlockPos at = h.absolutePos(new BlockPos(2, 2, 2));
			rules(p).builders.clear(); DimensionSettings.rebuild(TownhallMod.CONFIG.get());
			var forged = p.level().getServer().createCommandSourceStack().withEntity(p);
			run(forged, "setblock " + xyz(at) + " stone");
			h.assertTrue(p.level().getBlockState(at).isAir(), "source permissions do not override actor's own role and world");
		});
	}
}
