package dev.townhall.test;

import dev.townhall.TownhallMod;
import dev.townhall.config.ConfigManager;
import dev.townhall.config.TownhallConfig;
import dev.townhall.command.TownhallCommand;
import dev.townhall.storage.PlayerState;
import dev.townhall.storage.ReturnLocation;
import dev.townhall.teleport.ConfinementService;
import dev.townhall.onboarding.Onboarding;
import dev.townhall.onboarding.OnboardingStorage;
import dev.townhall.protection.Protection;
import dev.townhall.dimension.DimensionSettings;
import net.minecraft.world.Difficulty;
import net.minecraft.commands.Commands;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSourceStack;
import dev.townhall.storage.ReturnPositionStorage;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Numbers refer to the test list in the original task. */
public class TownhallGameTests {

	private static final double EPS = 1.0E-6;

	/** Floor of stone, player standing on it at a fractional position with a distinct view direction. */
	private static ServerPlayer playerOnFloor(GameTestHelper h, Vec3 relative, float yaw, float pitch) {
		for (int x = 0; x < 7; x++) for (int z = 0; z < 7; z++) h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
		TownhallMod.CONFIG.get().onboarding.enabled = false; // only the onboarding tests turn it on
		ServerPlayer player = h.makeMockServerPlayerInLevel();
		Vec3 abs = h.absoluteVec(relative);
		player.absSnapTo(abs.x, abs.y, abs.z, yaw, pitch);
		// The test server has no multiworld mod, so the Nether plays the Townhall world (and the prison in it).
		TownhallMod.CONFIG.get().locations.values().forEach(l -> l.dimension = "minecraft:the_nether");
		TownhallMod.CONFIG.get().commands.cooldownSeconds = 0;
		return player;
	}

	private static void run(ServerPlayer player, String command) {
		player.level().getServer().getCommands().performPrefixedCommand(player.createCommandSourceStack(), command);
	}

	private static void console(MinecraftServer server, String command) {
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
	}

	/** Operator source acting as the player, so @s is exactly that mock player (names are all the same, UUIDs are rejected). */
	private static void opAt(ServerPlayer p, String command) {
		MinecraftServer server = p.level().getServer();
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withEntity(p).withLevel(p.level()).withPosition(p.position()), command);
	}

	private static boolean inTownhall(ServerPlayer p) {
		return p.level().dimension().equals(TownhallMod.CONFIG.get().location("townhall").orElseThrow().dimensionKey());
	}

	private static void assertAt(GameTestHelper h, ServerPlayer p, Level level, Vec3 pos, float yaw, float pitch) {
		h.assertTrue(p.level() == level, "back in " + level.dimension().identifier() + " but is in " + p.level().dimension().identifier());
		h.assertTrue(p.position().distanceTo(pos) < EPS, "exact position " + pos + " but was " + p.position());
		h.assertTrue(Math.abs(p.getYRot() - yaw) < 1.0E-3 && Math.abs(p.getXRot() - pitch) < 1.0E-3, "yaw/pitch restored");
	}

	@GameTest
	public void enterAndReturnExactly(GameTestHelper h) { // 1 + 2
		ServerPlayer p = playerOnFloor(h, new Vec3(3.35, 1, 3.81), 127.4f, 8.2f);
		Level home = p.level();
		Vec3 start = p.position();

		run(p, "townhall");
		h.assertTrue(inTownhall(p), "player is in the Townhall");
		TownhallConfig.Spot spawn = TownhallMod.CONFIG.get().location("townhall").orElseThrow().spawn;
		h.assertTrue(p.position().distanceTo(new Vec3(spawn.x, spawn.y, spawn.z)) < EPS, "at Townhall spawn");
		ReturnLocation stored = ReturnPositionStorage.get(h.getLevel().getServer()).get(p.getUUID()).orElseThrow();
		h.assertTrue(stored.dimension().equals(home.dimension()) && stored.yaw() == 127.4f && stored.pitch() == 8.2f, "stored dimension/yaw/pitch");

		run(p, "townhall return");
		assertAt(h, p, home, start, 127.4f, 8.2f);
		h.assertTrue(ReturnPositionStorage.get(h.getLevel().getServer()).get(p.getUUID()).isEmpty(), "cleared after return");
		h.succeed();
	}

	@GameTest
	public void secondTownhallDoesNotOverwrite(GameTestHelper h) { // 4
		ServerPlayer p = playerOnFloor(h, new Vec3(2.5, 1, 2.5), 10f, 5f);
		Vec3 start = p.position();
		Level home = p.level();
		run(p, "townhall");
		p.absSnapTo(p.getX() + 3, p.getY() + 1, p.getZ() + 3, 90f, 0f); // wander around the Townhall
		run(p, "townhall");
		h.assertTrue(inTownhall(p), "still in Townhall");
		run(p, "townhall return");
		assertAt(h, p, home, start, 10f, 5f);
		h.succeed();
	}

	@GameTest
	public void operatorSendAndReturn(GameTestHelper h) { // 5 + 6
		ServerPlayer p = playerOnFloor(h, new Vec3(4.2, 1, 1.7), -45f, 12f);
		Vec3 start = p.position();
		Level home = p.level();
		MinecraftServer server = h.getLevel().getServer();
		opAt(p, "townhall send @s");
		h.assertTrue(inTownhall(p), "op sent player to Townhall");
		h.assertTrue(ReturnPositionStorage.get(server).get(p.getUUID()).isPresent(), "return position saved on admin send");
		opAt(p, "townhall return @s");
		assertAt(h, p, home, start, -45f, 12f);
		h.succeed();
	}

	@GameTest
	public void returnWithoutDataDoesNothing(GameTestHelper h) { // 7
		ServerPlayer p = playerOnFloor(h, new Vec3(2.5, 1, 2.5), 0f, 0f);
		Vec3 start = p.position();
		run(p, "townhall return");
		opAt(p, "townhall return @s");
		h.assertTrue(p.level() == h.getLevel() && p.position().distanceTo(start) < EPS, "player not moved");
		h.succeed();
	}

	@GameTest
	public void missingDimensionFailsCleanly(GameTestHelper h) { // 8
		ServerPlayer p = playerOnFloor(h, new Vec3(2.5, 1, 2.5), 0f, 0f);
		TownhallConfig config = TownhallMod.CONFIG.get();
		TownhallConfig.Location townhall = config.location("townhall").orElseThrow();
		String real = townhall.dimension;
		townhall.dimension = "minecraft:does_not_exist";
		try {
			run(p, "townhall");
			h.assertTrue(p.level() == h.getLevel(), "player stays");
			h.assertTrue(ReturnPositionStorage.get(h.getLevel().getServer()).get(p.getUUID()).isEmpty(), "nothing stored");
		} finally {
			townhall.dimension = real;
		}
		h.succeed();
	}

	@GameTest
	public void invalidConfigKeepsPrevious(GameTestHelper h) throws IOException { // 9
		Path dir = Files.createTempDirectory("townhall-test");
		Path file = dir.resolve("townhall.json");
		ConfigManager manager = new ConfigManager(file);
		manager.loadOrCreate();
		manager.get().locations.get("townhall").spawn.x = 42.5;
		manager.save();
		h.assertTrue(manager.reload().isEmpty(), "valid config loads");

		Files.writeString(file, "{ \"locations\": { \"townhall\": ");
		h.assertFalse(manager.reload().isEmpty(), "syntax error reported");
		h.assertTrue(manager.get().locations.get("townhall").spawn.x == 42.5, "old config still active after syntax error");

		Files.writeString(file, "{ \"locations\": { \"townhall\": { \"dimension\": \"NOT A DIM!\", \"spawn\": {\"x\": 1, \"y\": 2, \"z\": 3} } } }");
		List<String> errors = manager.reload();
		h.assertFalse(errors.isEmpty(), "invalid dimension reported");
		h.assertTrue(manager.get().locations.get("townhall").spawn.x == 42.5, "old config still active after invalid values");
		h.succeed();
	}

	@GameTest
	public void storageSurvivesSaveAndLoadByUuid(GameTestHelper h) { // 3 + 10
		UUID id = UUID.randomUUID();
		ReturnPositionStorage storage = new ReturnPositionStorage();
		storage.set(id, PlayerState.EMPTY.withReturnPosition(
				new ReturnLocation(Level.OVERWORLD, 1842.35, 68.0, -735.81, 127.4f, 8.2f, 123L, Optional.of("OldName"))).withStay("gefaengnis", true, Optional.of(90_000L)));
		Tag tag = ReturnPositionStorage.CODEC.encodeStart(NbtOps.INSTANCE, storage).getOrThrow();
		ReturnPositionStorage loaded = ReturnPositionStorage.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
		ReturnLocation loc = loaded.get(id).orElseThrow();
		h.assertTrue(loc.x() == 1842.35 && loc.y() == 68.0 && loc.z() == -735.81 && loc.yaw() == 127.4f && loc.pitch() == 8.2f
				&& loc.dimension().equals(Level.OVERWORLD) && loc.timestamp() == 123L, "round trip keeps every field");
		h.assertTrue(loaded.state(id).confined() && loaded.state(id).location().orElseThrow().equals("gefaengnis") && loaded.state(id).remainingMillis().orElse(0L) == 90_000L, "location, confinement and timer kept");
		h.succeed();
	}

	@GameTest
	public void blockedSpotUsesNearbySafeSpot(GameTestHelper h) { // 11
		ServerPlayer p = playerOnFloor(h, new Vec3(3.5, 1, 3.5), 0f, 0f);
		Vec3 start = p.position();
		run(p, "townhall");
		h.setBlock(new BlockPos(3, 1, 3), Blocks.STONE); // walled in while away
		h.setBlock(new BlockPos(3, 2, 3), Blocks.STONE);
		run(p, "townhall return");
		h.assertTrue(p.level() == h.getLevel(), "back in the overworld");
		h.assertTrue(p.position().distanceTo(start) > 0.5 && p.position().distanceTo(start) < 6, "moved to a nearby spot: " + p.position());
		h.assertTrue(p.level().noCollision(p, p.getBoundingBox()), "not inside blocks");
		h.succeed();
	}

	@GameTest
	public void noSafeSpotUsesFallback(GameTestHelper h) { // 11 fallback
		ServerPlayer p = playerOnFloor(h, new Vec3(3.5, 1, 3.5), 0f, 0f);
		run(p, "townhall");
		h.setBlock(new BlockPos(3, 1, 3), Blocks.LAVA);
		TownhallConfig config = TownhallMod.CONFIG.get();
		int radius = config.safeTeleport.horizontalRadius;
		int vertical = config.safeTeleport.verticalRadius;
		config.safeTeleport.horizontalRadius = 0;
		config.safeTeleport.verticalRadius = 0;
		try {
			run(p, "townhall return");
		} finally {
			config.safeTeleport.horizontalRadius = radius;
			config.safeTeleport.verticalRadius = vertical;
		}
		h.assertFalse(inTownhall(p), "left the Townhall");
		h.assertFalse(p.position().distanceTo(h.absoluteVec(new Vec3(3.5, 1, 3.5))) < 1, "not dropped into the lava");
		h.succeed();
	}

	@GameTest
	public void cooldownBlocksSpam(GameTestHelper h) { // 21
		ServerPlayer p = playerOnFloor(h, new Vec3(2.5, 1, 2.5), 0f, 0f);
		TownhallMod.CONFIG.get().commands.cooldownSeconds = 3;
		try {
			run(p, "townhall");
			h.assertTrue(inTownhall(p), "first use works");
			run(p, "townhall return");
			h.assertTrue(inTownhall(p), "second use within cooldown is blocked");
		} finally {
			TownhallMod.CONFIG.get().commands.cooldownSeconds = 0;
		}
		h.succeed();
	}

	@GameTest
	public void setspawnOnlyInTownhall(GameTestHelper h) {
		ServerPlayer p = playerOnFloor(h, new Vec3(2.5, 1, 2.5), 0f, 0f);
		TownhallConfig.Spot before = TownhallMod.CONFIG.get().location("townhall").orElseThrow().spawn;
		// the mock player is not an operator, so the command is not even available to it
		run(p, "townhall setspawn");
		h.assertTrue(TownhallMod.CONFIG.get().location("townhall").orElseThrow().spawn == before, "non-op can't set spawn");
		console(h.getLevel().getServer(), "townhall setspawn"); // console: must be a player, must not crash
		h.assertTrue(TownhallMod.CONFIG.get().location("townhall").orElseThrow().spawn == before, "console can't set spawn");
		h.succeed();
	}

	private static TownhallConfig.Spot prisonSpawn() {
		return TownhallMod.CONFIG.get().location("gefaengnis").orElseThrow().spawn;
	}

	private static boolean atPrison(ServerPlayer p) {
		TownhallConfig.Spot s = prisonSpawn();
		return p.level().dimension().equals(Level.NETHER) && p.position().distanceTo(new Vec3(s.x, s.y, s.z)) < EPS;
	}

	@GameTest
	public void prisonConfinesUntilReleased(GameTestHelper h) {
		ServerPlayer p = playerOnFloor(h, new Vec3(1.5, 1, 5.5), 33f, -4f);
		Vec3 start = p.position();
		Level home = p.level();
		opAt(p, "gefaengnis send @s");
		h.assertTrue(atPrison(p), "sent to prison spawn");
		h.assertTrue(ReturnPositionStorage.get(h.getLevel().getServer()).state(p.getUUID()).confined(), "confined");

		run(p, "townhall return");
		h.assertTrue(atPrison(p), "can't return on their own");
		run(p, "townhall");
		h.assertTrue(atPrison(p), "can't escape to the Townhall");

		// died and respawned somewhere else: put back into the prison
		Vec3 abs = h.absoluteVec(new Vec3(2.5, 1, 2.5));
		p.teleportTo(h.getLevel(), abs.x, abs.y, abs.z, java.util.Set.of(), 0f, 0f, true);
		ConfinementService.keepConfined(p, TownhallMod.CONFIG.get());
		h.assertTrue(atPrison(p), "respawn puts them back into the prison");

		opAt(p, "gefaengnis return @s");
		assertAt(h, p, home, start, 33f, -4f);
		h.assertTrue(ReturnPositionStorage.get(h.getLevel().getServer()).state(p.getUUID()).isEmpty(), "released and cleared");
		h.succeed();
	}

	@GameTest
	public void townhallThenPrisonKeepsOriginalPosition(GameTestHelper h) {
		ServerPlayer p = playerOnFloor(h, new Vec3(5.5, 1, 1.5), -90f, 20f);
		Vec3 start = p.position();
		Level home = p.level();
		run(p, "townhall");
		h.assertTrue(inTownhall(p), "in Townhall");
		opAt(p, "gefaengnis send @s");
		h.assertTrue(atPrison(p), "moved to prison");
		opAt(p, "townhall return @s");
		assertAt(h, p, home, start, -90f, 20f);
		h.succeed();
	}

	@GameTest
	public void playersCantUseAdminOnlyLocations(GameTestHelper h) {
		ServerPlayer p = playerOnFloor(h, new Vec3(2.5, 1, 2.5), 0f, 0f);
		Vec3 start = p.position();
		run(p, "gefaengnis");
		h.assertTrue(p.level() == h.getLevel() && p.position().distanceTo(start) < EPS, "player not moved");
		h.assertTrue(ReturnPositionStorage.get(h.getLevel().getServer()).state(p.getUUID()).isEmpty(), "nothing stored");
		h.succeed();
	}

	@GameTest
	public void configurableCommandNamesParse(GameTestHelper h) {
		CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
		TownhallConfig.Location naming = new TownhallConfig.Location();
		naming.command = "townhall:gefängnis";
		TownhallMod.CONFIG.get().locations.put("namingtest", naming);
		try {
			dispatcher.register(TownhallCommand.build("townhall:gefängnis"));
			CommandSourceStack console = h.getLevel().getServer().createCommandSourceStack();
			for (String cmd : List.of("townhall:gefängnis", "townhall:gefängnis setspawn", "townhall:gefängnis send Max", "townhall:gefängnis send Max 30", "townhall:gefängnis return Max")) {
				ParseResults<CommandSourceStack> parsed = dispatcher.parse(cmd, console);
				h.assertTrue(!parsed.getReader().canRead() && parsed.getExceptions().isEmpty(), "parses: " + cmd);
			}
		} finally {
			TownhallMod.CONFIG.get().locations.remove("namingtest");
		}
		h.succeed();
	}

	/** Mock players are always creative, so real damage can't be tested; ask the damage event the mod hooks into. */
	private static boolean allowDamage(ServerPlayer victim, net.minecraft.world.damagesource.DamageSource source) {
		return net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DAMAGE.invoker().allowDamage(victim, source, 4f);
	}

	private static int escapeCount;

	/** A stand-in for another mod's teleport command (/home, /spawn, ...): anyone may run it. */
	private static void registerEscapeCommand(MinecraftServer server) {
		var dispatcher = server.getCommands().getDispatcher();
		if (dispatcher.getRoot().getChild("escapetest") == null) {
			dispatcher.register(Commands.literal("escapetest").executes(ctx -> ++escapeCount));
		}
	}

	@GameTest
	public void confinedPlayersCantRunOtherCommands(GameTestHelper h) {
		ServerPlayer p = playerOnFloor(h, new Vec3(2.5, 1, 2.5), 0f, 0f);
		MinecraftServer server = h.getLevel().getServer();
		registerEscapeCommand(server);
		int before = escapeCount;
		run(p, "escapetest");
		h.assertTrue(escapeCount == before + 1, "free players can use other commands");
		opAt(p, "gefaengnis send @s");
		run(p, "escapetest");
		h.assertTrue(escapeCount == before + 1, "confined player's other commands are blocked");
		opAt(p, "gefaengnis return @s");
		run(p, "escapetest");
		h.assertTrue(escapeCount == before + 2, "commands work again after release");
		h.succeed();
	}

	@GameTest
	public void escapedPrisonerIsPulledBack(GameTestHelper h) {
		ServerPlayer p = playerOnFloor(h, new Vec3(2.5, 1, 2.5), 0f, 0f);
		opAt(p, "gefaengnis send @s");
		TownhallConfig.Spot s = prisonSpawn();
		p.teleportTo((net.minecraft.server.level.ServerLevel) p.level(), s.x + 100, s.y, s.z, java.util.Set.of(), 0f, 0f, true); // e.g. ender pearl or another mod
		ConfinementService.check(h.getLevel().getServer(), 0);
		h.assertTrue(atPrison(p), "pulled back into the prison");
		opAt(p, "gefaengnis return @s");
		h.succeed();
	}

	@GameTest
	public void timedStayEndsAfterOnlineTime(GameTestHelper h) {
		ServerPlayer p = playerOnFloor(h, new Vec3(3.25, 1, 4.75), 12f, 6f);
		Vec3 start = p.position();
		Level home = p.level();
		MinecraftServer server = h.getLevel().getServer();
		opAt(p, "gefaengnis send @s 2");
		ReturnPositionStorage storage = ReturnPositionStorage.get(server);
		h.assertTrue(storage.state(p.getUUID()).remainingMillis().orElse(0L) == 120_000L, "2 minutes stored");
		run(p, "townhall return");
		h.assertTrue(atPrison(p), "can't leave early");

		ConfinementService.check(server, 60_000);
		h.assertTrue(atPrison(p) && storage.state(p.getUUID()).remainingMillis().orElse(0L) == 60_000L, "one minute left");
		ConfinementService.check(server, 60_000);
		assertAt(h, p, home, start, 12f, 6f);
		h.assertTrue(storage.state(p.getUUID()).isEmpty(), "released and cleared");
		h.succeed();
	}

	@GameTest
	public void clockStopsWhileOffline(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		UUID offline = UUID.randomUUID();
		ReturnPositionStorage storage = ReturnPositionStorage.get(server);
		storage.set(offline, PlayerState.EMPTY.withStay("gefaengnis", true, Optional.of(5_000L)));
		ConfinementService.check(server, 2_000);
		h.assertTrue(storage.state(offline).remainingMillis().orElse(0L) == 5_000L, "offline player's time doesn't run");
		storage.remove(offline);
		h.succeed();
	}

	@GameTest
	public void escapableLocationsDontConfine(GameTestHelper h) {
		ServerPlayer p = playerOnFloor(h, new Vec3(2.5, 1, 2.5), 0f, 0f);
		opAt(p, "townhall send @s");
		h.assertFalse(ReturnPositionStorage.get(h.getLevel().getServer()).state(p.getUUID()).confined(), "Townhall is escapable");
		run(p, "townhall return");
		h.assertFalse(inTownhall(p), "player left on their own");
		h.succeed();
	}

	@GameTest
	public void perDimensionDifficulty(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		Difficulty overworldBefore = server.overworld().getDifficulty();
		Difficulty target = overworldBefore == Difficulty.PEACEFUL ? Difficulty.HARD : Difficulty.PEACEFUL;
		console(server, "townhall difficulty minecraft:the_end " + target.getSerializedName());
		try {
			h.assertTrue(server.getLevel(Level.END).getDifficulty() == target, "End has its own difficulty");
			h.assertTrue(server.overworld().getDifficulty() == overworldBefore, "overworld unchanged");
		} finally {
			console(server, "townhall difficulty minecraft:the_end default");
		}
		h.assertTrue(server.getLevel(Level.END).getDifficulty() == overworldBefore, "back to the server default");
		h.succeed();
	}

	@GameTest
	public void adminIsNeverConfined(GameTestHelper h) {
		ServerPlayer p = playerOnFloor(h, new Vec3(2.5, 1, 2.5), 0f, 0f);
		MinecraftServer server = h.getLevel().getServer();
		// the GameTest server gives ops level 0 by default, so set the vanilla /tp level explicitly
		server.getPlayerList().op(p.nameAndId(), Optional.of(net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER), Optional.empty());
		try {
			h.assertTrue(TownhallMod.isOperator(p.permissions()), "mock player is operator");
			// the reported case: admin already in the prison world (no saved spot) sends themselves to the prison
			p.teleportTo(server.getLevel(Level.NETHER), 50.5, 4, 50.5, java.util.Set.of(), 0f, 0f, true);
			run(p, "gefaengnis send @s");
			h.assertTrue(atPrison(p), "admin is in the prison");
			h.assertFalse(ReturnPositionStorage.get(server).state(p.getUUID()).confined(), "admin is not confined");
			TownhallConfig.Spot s = prisonSpawn();
			p.teleportTo(server.getLevel(Level.NETHER), s.x + 100, s.y, s.z, java.util.Set.of(), 0f, 0f, true);
			ConfinementService.check(server, 0);
			h.assertFalse(atPrison(p), "admin is not pulled back");
			run(p, "gefaengnis return");
			h.assertFalse(p.level().dimension().equals(Level.NETHER), "admin can leave with return (sent to spawn)");
			h.assertTrue(ReturnPositionStorage.get(server).state(p.getUUID()).isEmpty(), "nothing left stored");
		} finally {
			server.getPlayerList().deop(p.nameAndId());
		}
		h.succeed();
	}

	@GameTest
	public void adminReleasesPrisonerWithoutSavedSpot(GameTestHelper h) {
		ServerPlayer p = playerOnFloor(h, new Vec3(2.5, 1, 2.5), 0f, 0f);
		MinecraftServer server = h.getLevel().getServer();
		p.teleportTo(server.getLevel(Level.NETHER), 60.5, 4, 60.5, java.util.Set.of(), 0f, 0f, true); // came in another way
		opAt(p, "gefaengnis send @s");
		h.assertTrue(ReturnPositionStorage.get(server).state(p.getUUID()).confined(), "player is confined");
		h.assertTrue(ReturnPositionStorage.get(server).get(p.getUUID()).isEmpty(), "no saved spot");
		opAt(p, "gefaengnis return @s");
		h.assertFalse(p.level().dimension().equals(Level.NETHER), "released to spawn");
		h.assertTrue(ReturnPositionStorage.get(server).state(p.getUUID()).isEmpty(), "confinement cleared");
		h.succeed();
	}

	@GameTest
	public void firstJoinMustAcceptRules(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		registerEscapeCommand(server);
		TownhallConfig.Onboarding cfg = TownhallMod.CONFIG.get().onboarding;
		cfg.enabled = true;
		try {
			ServerPlayer p = h.makeMockServerPlayerInLevel(); // joins -> welcome, tutorial, rules
			h.assertTrue(Onboarding.isRestricted(p), "new player must accept first");

			int before = escapeCount;
			run(p, "escapetest");
			h.assertTrue(escapeCount == before, "other commands blocked");
			h.assertFalse(Protection.mayChangeWorld(p), "can't build yet");
			h.assertFalse(allowDamage(p, p.level().damageSources().fall()), "can't be hurt while reading");

			Vec3 anchor = p.position();
			p.absSnapTo(anchor.x + 10, anchor.y, anchor.z, 0f, 0f);
			Onboarding.check(server);
			h.assertTrue(p.position().distanceTo(anchor) < 0.01, "kept in place until accepted");

			run(p, "regeln akzeptieren");
			h.assertFalse(Onboarding.isRestricted(p), "free after accepting");
			h.assertTrue(OnboardingStorage.get(server).acceptedVersion(p.getUUID()) == cfg.rulesVersion, "acceptance stored");
			run(p, "escapetest");
			h.assertTrue(escapeCount == before + 1, "commands work after accepting");

			cfg.rulesVersion++;
			try {
				h.assertTrue(Onboarding.needsToAccept(p), "new rules version must be accepted again");
			} finally {
				cfg.rulesVersion--;
			}
		} finally {
			cfg.enabled = false;
		}
		h.succeed();
	}

	@GameTest
	public void worldRules(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer victim = playerOnFloor(h, new Vec3(2.5, 1, 2.5), 0f, 0f);
		ServerPlayer attacker = h.makeMockServerPlayerInLevel();
		net.minecraft.server.level.ServerLevel end = server.getLevel(Level.END);
		victim.teleportTo(end, 40.5, 80, 40.5, java.util.Set.of(), 0f, 0f, true);
		attacker.teleportTo(end, 41.5, 80, 40.5, java.util.Set.of(), 0f, 0f, true);
		for (String rule : List.of("pvp", "build", "hunger", "fallDamage")) console(server, "townhall worldrule minecraft:the_end " + rule + " false");
		try {
			h.assertFalse(allowDamage(victim, end.damageSources().playerAttack(attacker)), "no PvP");
			h.assertFalse(allowDamage(victim, end.damageSources().fall()), "no fall damage");
			h.assertFalse(Protection.mayChangeWorld(victim), "no building");
			h.assertFalse(DimensionSettings.of(Level.END).hunger(), "hunger rule off");

			server.getPlayerList().op(victim.nameAndId(), Optional.of(net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER), Optional.empty());
			h.assertTrue(Protection.mayChangeWorld(victim), "operators can still build");
			server.getPlayerList().deop(victim.nameAndId());
		} finally {
			for (String rule : List.of("pvp", "build", "hunger", "fallDamage")) console(server, "townhall worldrule minecraft:the_end " + rule + " default");
		}
		h.assertTrue(allowDamage(victim, end.damageSources().fall()), "fall damage back after default");
		h.assertTrue(allowDamage(victim, end.damageSources().playerAttack(attacker)), "PvP back after default");
		h.assertTrue(Protection.mayChangeWorld(victim), "building back after default");
		h.succeed();
	}

	@GameTest(maxTicks = 100)
	public void fixedTimePerWorld(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		net.minecraft.server.level.ServerLevel overworld = server.overworld();
		console(server, "townhall worldrule minecraft:overworld time midnight");
		try {
			h.assertTrue(Math.floorMod(overworld.getDefaultClockTime(), 24000L) == 18000L, "overworld logic sees midnight");
			var packet = DimensionSettings.timePacketFor(Level.OVERWORLD, server.clockManager().createFullSyncPacket());
			h.assertFalse(packet.clockUpdates().isEmpty(), "time packet has clocks");
			packet.clockUpdates().values().forEach(state -> {
				h.assertTrue(Math.floorMod(state.totalTicks(), 24000L) == 18000L, "client clock shows midnight");
				h.assertTrue(state.rate() == 0f, "client clock is stopped");
			});
			var nether = server.clockManager().createFullSyncPacket();
			h.assertTrue(DimensionSettings.timePacketFor(Level.NETHER, nether) == nether, "other worlds get the real time");
		} catch (RuntimeException e) {
			console(server, "townhall worldrule minecraft:overworld time default");
			throw e;
		}
		// Sky light is sampled through the world's clocks once per tick: compare it at midnight and at noon.
		h.runAfterDelay(3, () -> {
			float midnight = overworld.environmentAttributes().getDimensionValue(net.minecraft.world.attribute.EnvironmentAttributes.SKY_LIGHT_LEVEL);
			console(server, "townhall worldrule minecraft:overworld time noon");
			h.runAfterDelay(3, () -> {
				float noon = overworld.environmentAttributes().getDimensionValue(net.minecraft.world.attribute.EnvironmentAttributes.SKY_LIGHT_LEVEL);
				console(server, "townhall worldrule minecraft:overworld time default");
				h.assertTrue(noon > midnight, "sky is brighter at fixed noon (" + noon + ") than at fixed midnight (" + midnight + ")");
				h.assertTrue(DimensionSettings.fixedTime(Level.OVERWORLD) == null, "time runs normally after default");
				h.succeed();
			});
		});
	}

	@GameTest(maxTicks = 150)
	public void fixedWeatherPerWorld(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		net.minecraft.server.level.ServerLevel overworld = server.overworld();
		h.assertTrue(DimensionSettings.receivesWeatherFrom(Level.OVERWORLD, Level.OVERWORLD), "own world's weather arrives");
		console(server, "townhall worldrule minecraft:overworld weather rain");
		h.assertFalse(DimensionSettings.receivesWeatherFrom(Level.END, Level.OVERWORLD), "fixed weather stays in its world");
		h.assertFalse(DimensionSettings.receivesWeatherFrom(Level.OVERWORLD, Level.END), "other worlds' rain packets don't reach it");
		h.runAfterDelay(40, () -> {
			boolean raining = overworld.isRaining(), thundering = overworld.isThundering();
			console(server, "townhall worldrule minecraft:overworld weather clear");
			h.assertTrue(raining && !thundering, "rain set: raining, no thunder");
			h.runAfterDelay(40, () -> {
				boolean stillRaining = overworld.isRaining();
				console(server, "townhall worldrule minecraft:overworld weather default");
				h.assertFalse(stillRaining, "clear set: rain stopped");
				h.assertTrue(DimensionSettings.receivesWeatherFrom(Level.END, Level.OVERWORLD), "normal weather after default");
				h.succeed();
			});
		});
	}

	/** True if the command parses completely for this source (exists and its requirements pass). */
	private static boolean usable(MinecraftServer server, CommandSourceStack source, String command) {
		ParseResults<CommandSourceStack> parsed = server.getCommands().getDispatcher().parse(command, source);
		return !parsed.getReader().canRead() && parsed.getExceptions().isEmpty() && parsed.getContext().getCommand() != null;
	}

	@GameTest
	public void locationsAreManagedByCommands(GameTestHelper h) {
		ServerPlayer admin = playerOnFloor(h, new Vec3(2.5, 1, 4.5), 45f, 0f);
		MinecraftServer server = h.getLevel().getServer();
		TownhallConfig config = TownhallMod.CONFIG.get();
		CommandSourceStack player = server.createCommandSourceStack().withEntity(admin).withPermission(net.minecraft.server.permissions.PermissionSet.NO_PERMISSIONS);
		try {
			opAt(admin, "location create cmdtest prison");
			TownhallConfig.Location l = config.locations.get("cmdtest");
			h.assertTrue(l != null, "created");
			h.assertTrue(l.dimension.equals(admin.level().dimension().identifier().toString()), "world = where the admin stands");
			h.assertTrue(Math.abs(l.spawn.x - admin.getX()) < EPS && Math.abs(l.spawn.z - admin.getZ()) < EPS && l.spawn.yaw == 45f, "spawn = admin position");
			h.assertTrue(l.adminOnly && !l.isEscapable(), "prison preset: admins only, no escape");
			h.assertTrue(usable(server, server.createCommandSourceStack(), "cmdtest send @s 5"), "/cmdtest works at once, no /reload");
			h.assertFalse(usable(server, player, "cmdtest"), "players can't use an admin-only place");

			opAt(admin, "location set cmdtest adminOnly false");
			opAt(admin, "location set cmdtest escapable true");
			opAt(admin, "location set cmdtest radius 10");
			opAt(admin, "location set cmdtest title &aHallo Welt");
			opAt(admin, "location set cmdtest subtitle -");
			h.assertTrue(!l.adminOnly && l.isEscapable() && l.confineRadius == 10, "settings changed");
			h.assertTrue(l.title.equals("&aHallo Welt") && l.subtitle.isEmpty(), "text settings, '-' clears");
			h.assertTrue(usable(server, player, "cmdtest"), "players can use it now");

			opAt(admin, "location set cmdtest command \"cmdtest:neu\"");
			h.assertTrue(l.command.equals("cmdtest:neu"), "renamed");
			h.assertTrue(usable(server, player, "cmdtest:neu"), "new name works at once");
			h.assertFalse(usable(server, player, "cmdtest"), "old name is gone");

			opAt(admin, "location set cmdtest command tp");
			h.assertTrue(l.command.equals("cmdtest:neu"), "can't take a vanilla command name");
			opAt(admin, "location set cmdtest command gefaengnis");
			h.assertTrue(l.command.equals("cmdtest:neu"), "can't take another location's command");
			opAt(admin, "location create townhall");
			h.assertTrue(config.locations.get("townhall").command.equals("townhall"), "existing location untouched");

			admin.absSnapTo(admin.getX() + 1, admin.getY(), admin.getZ(), 90f, 0f);
			opAt(admin, "location setspawn cmdtest");
			h.assertTrue(Math.abs(l.spawn.x - admin.getX()) < EPS && l.spawn.yaw == 90f, "setspawn by id");

			h.assertFalse(usable(server, player, "location list"), "players can't manage locations");
			opAt(admin, "location delete cmdtest");
			h.assertFalse(config.locations.containsKey("cmdtest"), "deleted");
			h.assertFalse(usable(server, server.createCommandSourceStack(), "cmdtest:neu"), "deleted command is gone");
		} finally {
			config.locations.remove("cmdtest");
		}
		h.succeed();
	}

	@GameTest
	public void buildersMayBuildAndUseCreative(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer builder = playerOnFloor(h, new Vec3(2.5, 1, 2.5), 0f, 0f);
		net.minecraft.server.level.ServerLevel nether = server.getLevel(Level.NETHER);
		builder.teleportTo(nether, 60.5, 80, 60.5, java.util.Set.of(), 0f, 0f, true);
		// An offline player the server has never seen online: known only by name lookup.
		UUID offlineId = UUID.randomUUID();
		server.services().nameToIdCache().add(new net.minecraft.server.players.NameAndId(offlineId, "OfflineBob"));
		CommandSourceStack asBuilder = server.createCommandSourceStack().withEntity(builder).withLevel(nether)
				.withPermission(net.minecraft.server.permissions.PermissionSet.NO_PERMISSIONS);
		console(server, "townhall worldrule minecraft:the_nether build false");
		try {
			h.assertFalse(Protection.mayBuild(builder), "no builder right yet");
			h.assertFalse(usable(server, asBuilder, "builder creative"), "/builder hidden for normal players");

			// GameProfileArgument rejects @s ("selector includes entities"); @p right at the builder picks exactly them.
			opAt(builder, "builder add minecraft:the_nether @p[distance=..0.5]");
			console(server, "builder add minecraft:the_nether OfflineBob");
			h.assertTrue(Protection.mayBuild(builder), "builder may build with build=false");
			h.assertTrue(DimensionSettings.of(Level.NETHER).builders().contains(offlineId), "offline player added by name");
			h.assertFalse(usable(server, asBuilder, "builder add minecraft:the_nether OfflineBob"), "builders can't hand out the right");

			var perms = (net.fabricmc.fabric.api.permission.v1.PermissionContextOwner) builder;
			for (String node : List.of("worldedit:region.set", "worldedit:worldedit.region.set", "minecraft:worldedit.wand")) {
				h.assertTrue(perms.checkPermission(net.minecraft.resources.Identifier.parse(node), false), "WorldEdit permission " + node);
			}
			h.assertFalse(perms.checkPermission(net.minecraft.resources.Identifier.parse("worldedit:reload"), false), "no admin WorldEdit");
			h.assertFalse(perms.checkPermission(net.minecraft.resources.Identifier.parse("worldedit:world"), false), "can't switch WorldEdit to another world");
			for (String node : List.of("worldedit:scripting.execute", "worldedit:schematic.delete", "worldedit:setnbt")) {
				h.assertFalse(perms.checkPermission(net.minecraft.resources.Identifier.parse(node), false), "admin-only WorldEdit node " + node);
			}
			h.assertTrue(perms.checkPermission(net.minecraft.resources.Identifier.parse("worldedit:schematic.save"), false), "schematics can still be saved");
			h.assertFalse(perms.checkPermission(net.minecraft.resources.Identifier.parse("othermod:fly"), false), "other mods' permissions untouched");
			server.getCommands().performPrefixedCommand(asBuilder, "builder creative");
			h.assertTrue(builder.entityTags().contains(Protection.BUILDER_CREATIVE_TAG), "creative via /builder");

			builder.teleportTo(server.overworld(), h.absoluteVec(new Vec3(2.5, 1, 2.5)).x, h.absoluteVec(new Vec3(2.5, 1, 2.5)).y, h.absoluteVec(new Vec3(2.5, 1, 2.5)).z, java.util.Set.of(), 0f, 0f, true);
			Protection.enforceBuilderMode(builder);
			h.assertFalse(builder.entityTags().contains(Protection.BUILDER_CREATIVE_TAG), "leaving the builder world ends creative");
			h.assertFalse(perms.checkPermission(net.minecraft.resources.Identifier.parse("worldedit:region.set"), false), "no WorldEdit outside the builder world");
			server.getCommands().performPrefixedCommand(asBuilder.withLevel(server.overworld()), "builder creative");
			h.assertFalse(builder.entityTags().contains(Protection.BUILDER_CREATIVE_TAG), "no creative outside the builder world");

			console(server, "builder remove minecraft:the_nether OfflineBob");
			console(server, "builder remove minecraft:the_nether test-mock-player");
			h.assertTrue(DimensionSettings.of(Level.NETHER).builders().isEmpty(), "both removed");
		} finally {
			TownhallConfig.DimensionRules rules = TownhallMod.CONFIG.get().dimensions.get("minecraft:the_nether");
			if (rules != null) rules.builders = null;
			console(server, "townhall worldrule minecraft:the_nether build default");
		}
		builder.teleportTo(nether, 60.5, 80, 60.5, java.util.Set.of(), 0f, 0f, true);
		h.assertTrue(Protection.mayBuild(builder), "everyone may build again after default");
		h.succeed();
	}

	@GameTest
	public void deathsShownInTabInRed(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer p = playerOnFloor(h, new Vec3(2.5, 1, 2.5), 0f, 0f);
		var scoreboard = server.getScoreboard();
		dev.townhall.display.DeathsInTab.apply(server);
		var objective = scoreboard.getObjective(dev.townhall.display.DeathsInTab.OBJECTIVE);
		h.assertTrue(objective != null && scoreboard.getDisplayObjective(net.minecraft.world.scores.DisplaySlot.LIST) == objective, "objective shown in the tab list");
		h.assertTrue(objective.getCriteria() == net.minecraft.world.scores.criteria.ObjectiveCriteria.DEATH_COUNT, "counts deaths");
		h.assertTrue(objective.numberFormatOrDefault(null) instanceof net.minecraft.network.chat.numbers.StyledFormat f
				&& f.style().getColor() != null && f.style().getColor().getValue() == 0xFF5555, "number is red");

		p.getStats().setValue(p, net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.DEATHS), 7);
		dev.townhall.display.DeathsInTab.sync(p);
		h.assertTrue(scoreboard.getOrCreatePlayerScore(p, objective).get() == 7, "earlier deaths from the statistics count");
		scoreboard.forAllObjectives(net.minecraft.world.scores.criteria.ObjectiveCriteria.DEATH_COUNT, p, net.minecraft.world.scores.ScoreAccess::increment);
		h.assertTrue(scoreboard.getOrCreatePlayerScore(p, objective).get() == 8, "a death adds one (vanilla criterion)");

		console(server, "townhall deathsintab off");
		try {
			h.assertTrue(scoreboard.getDisplayObjective(net.minecraft.world.scores.DisplaySlot.LIST) == null, "hidden after off");
		} finally {
			console(server, "townhall deathsintab on");
		}
		h.assertTrue(scoreboard.getDisplayObjective(net.minecraft.world.scores.DisplaySlot.LIST) == objective, "shown again after on");
		h.succeed();
	}

	@GameTest
	public void operatorsSetNicknames(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer p = playerOnFloor(h, new Vec3(4.5, 1, 1.5), 0f, 0f);
		String real = p.getGameProfile().name();
		CommandSourceStack asPlayer = server.createCommandSourceStack().withEntity(p).withPermission(net.minecraft.server.permissions.PermissionSet.NO_PERMISSIONS);
		UUID offlineId = UUID.randomUUID();
		server.services().nameToIdCache().add(new net.minecraft.server.players.NameAndId(offlineId, "NickOffline"));
		try {
			h.assertFalse(usable(server, asPlayer, "nick set @s Boss"), "players can't set nicknames");
			opAt(p, "nick set @p[distance=..0.5] &6Bürgermeister");
			h.assertTrue(p.getDisplayName().getString().contains("Bürgermeister"), "chat name is the nickname: " + p.getDisplayName().getString());
			h.assertFalse(p.getDisplayName().getString().contains(real), "real name not shown in chat");
			h.assertTrue(p.getTabListDisplayName() != null && p.getTabListDisplayName().getString().contains("Bürgermeister"), "tab list shows the nickname");
			h.assertTrue(p.getName().getString().equals(real), "real name stays the name (commands, selectors)");

			// Name above the head: other viewers get a profile with the nickname, the player keeps the real one.
			ServerPlayer viewer = h.makeMockServerPlayerInLevel();
			var info = net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(p));
			var forViewer = (net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket) dev.townhall.nick.NickPackets.rewrite(viewer, info);
			String token = forViewer.entries().getFirst().profile().name();
			h.assertTrue(token.equals(dev.townhall.nick.NickPackets.token(p.getUUID())) && token.length() <= 16, "invisible profile name for others: " + token);
			h.assertTrue(token.replaceAll("§.", "").isEmpty(), "token renders as nothing");
			var team = dev.townhall.nick.NickPacketsTestAccess.addTeam(p.getUUID(), dev.townhall.nick.Nicknames.of(p.getUUID()).orElseThrow());
			h.assertTrue(team.getPlayers().contains(token), "nickname team holds the token");
			h.assertTrue(team.getParameters().orElseThrow().playerPrefix().getString().contains("Bürgermeister"), "full nickname is the prefix above the head");
			h.assertTrue(forViewer.entries().getFirst().profile().properties().equals(p.getGameProfile().properties()), "skin kept");
			h.assertTrue(info.entries().getFirst().profile().name().equals(real), "shared packet not modified");
			var forSelf = (net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket) dev.townhall.nick.NickPackets.rewrite(p, info);
			h.assertTrue(forSelf.entries().getFirst().profile().name().equals(real), "own client keeps the real profile");
			dev.townhall.nick.NickPackets.refresh(p); // must not throw with live trackers
			opAt(p, "nick set @p[distance=..0.5] &6Der große Bürgermeister vom Amt"); // 31 characters
			h.assertTrue(dev.townhall.nick.Nicknames.of(p.getUUID()).orElseThrow().getString().contains("Der große Bürgermeister vom Amt"), "long nicknames up to 32 characters work");
			opAt(p, "nick set @p[distance=..0.5] &6Der große Bürgermeister vom Rathaus"); // 35 characters
			h.assertFalse(dev.townhall.nick.Nicknames.of(p.getUUID()).orElseThrow().getString().contains("Rathaus"), "more than 32 characters are rejected");
			opAt(p, "nick set @p[distance=..0.5] &6Bürgermeister");

			console(server, "nick set NickOffline Offliner");
			h.assertTrue(dev.townhall.nick.Nicknames.of(offlineId).isPresent(), "offline players can get a nickname");
			var score = new net.minecraft.network.protocol.game.ClientboundSetScorePacket("NickOffline", "townhall_deaths", 3, Optional.empty(), Optional.empty());
			h.assertTrue(((net.minecraft.network.protocol.game.ClientboundSetScorePacket) dev.townhall.nick.NickPackets.rewrite(viewer, score)).owner().equals(dev.townhall.nick.NickPackets.token(offlineId)),
					"tab list scores follow the renamed profile");
			console(server, "nick set NickOffline &aBürgermeister");
			h.assertTrue(dev.townhall.nick.Nicknames.of(offlineId).orElseThrow().getString().contains("Offliner"), "no second player with the same nickname");

			opAt(p, "nick reset @p[distance=..0.5]");
			h.assertTrue(p.getTabListDisplayName() == null && p.getDisplayName().getString().contains(real), "real name after reset");
		} finally {
			dev.townhall.nick.Nicknames.get(server).reset(server, p.getUUID());
			dev.townhall.nick.Nicknames.get(server).reset(server, offlineId);
		}
		h.succeed();
	}

	private static net.minecraft.world.InteractionResult useDoor(GameTestHelper h, ServerPlayer p, BlockPos rel) {
		BlockPos abs = h.absolutePos(rel);
		var hit = new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(abs), net.minecraft.core.Direction.NORTH, abs, false);
		return net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.invoker().interact(p, h.getLevel(), net.minecraft.world.InteractionHand.MAIN_HAND, hit);
	}

	private static net.minecraft.world.item.ItemStack findKey(ServerPlayer p) {
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
			var stack = p.getInventory().getItem(i);
			if (dev.townhall.key.Keys.keyId(stack).isPresent()) return stack;
		}
		return net.minecraft.world.item.ItemStack.EMPTY;
	}

	@GameTest
	public void keysLockDoors(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer owner = playerOnFloor(h, new Vec3(3.5, 1, 3.5), 0f, 0f);
		ServerPlayer stranger = h.makeMockServerPlayerInLevel();
		BlockPos lower = new BlockPos(1, 1, 1), upper = new BlockPos(1, 2, 1), beside = new BlockPos(2, 1, 1);
		var door = net.minecraft.world.level.block.Blocks.OAK_DOOR.defaultBlockState();
		h.setBlock(lower, door);
		h.setBlock(upper, door.setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
		java.util.function.BooleanSupplier open = () -> h.getBlockState(lower).getValue(net.minecraft.world.level.block.DoorBlock.OPEN);
		try {
			run(owner, "key new Haus");
			var key = findKey(owner);
			h.assertFalse(key.isEmpty(), "got a key");
			h.assertTrue(dev.townhall.key.Keys.keyName(key).equals("Haus"), "key has the name");
			owner.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, key.copy());
			h.assertTrue(useDoor(h, owner, lower).consumesAction(), "right-click links the door");
			h.assertTrue(dev.townhall.key.Keys.lockAt(h.getLevel(), h.absolutePos(lower)).isPresent(), "door is locked");

			h.assertTrue(useDoor(h, stranger, upper) == net.minecraft.world.InteractionResult.FAIL, "no key: can't open (upper half too)");
			h.assertFalse(net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.invoker()
					.beforeBlockBreak(h.getLevel(), stranger, h.absolutePos(lower), h.getBlockState(lower), null), "no key: can't break the door");

			h.setBlock(beside, net.minecraft.world.level.block.Blocks.REDSTONE_BLOCK);
			h.assertFalse(open.getAsBoolean(), "redstone can't open a locked door");
			h.setBlock(beside, net.minecraft.world.level.block.Blocks.AIR);

			stranger.getInventory().add(dev.townhall.key.Keys.copy(owner.getMainHandItem()));
			h.assertTrue(useDoor(h, stranger, lower) == net.minecraft.world.InteractionResult.PASS, "a copy of the key opens it");
			run(owner, "key copy");
			int copies = 0;
			for (int i = 0; i < owner.getInventory().getContainerSize(); i++) if (dev.townhall.key.Keys.keyId(owner.getInventory().getItem(i)).isPresent()) copies += owner.getInventory().getItem(i).getCount();
			h.assertTrue(copies >= 2, "/key copy gives another key (same keys stack)");

			ServerPlayer admin = h.makeMockServerPlayerInLevel();
			admin.getInventory().add(dev.townhall.key.Keys.adminKey());
			h.assertTrue(useDoor(h, admin, lower) == net.minecraft.world.InteractionResult.FAIL, "admin key does nothing for non-operators");
			server.getPlayerList().op(admin.nameAndId(), Optional.of(net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER), Optional.empty());
			h.assertTrue(useDoor(h, admin, lower) == net.minecraft.world.InteractionResult.PASS, "operators with the admin key open every door");
			server.getPlayerList().deop(admin.nameAndId());

			owner.setShiftKeyDown(true);
			h.assertTrue(useDoor(h, owner, lower).consumesAction(), "sneak + key unlinks");
			owner.setShiftKeyDown(false);
			h.assertTrue(dev.townhall.key.Keys.lockAt(h.getLevel(), h.absolutePos(lower)).isEmpty(), "door unlocked");
			h.setBlock(beside, net.minecraft.world.level.block.Blocks.REDSTONE_BLOCK);
			h.assertTrue(open.getAsBoolean(), "redstone works again on unlocked doors");
		} finally {
			dev.townhall.key.DoorLocks.get(server).remove(h.getLevel().dimension(), h.absolutePos(lower));
		}
		h.succeed();
	}

	@GameTest
	public void afkAndPlaytime(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer p = playerOnFloor(h, new Vec3(3.5, 1, 3.5), 0f, 0f);
		long now = System.currentTimeMillis();
		int before = TownhallMod.CONFIG.get().afkMinutes;
		try {
			TownhallMod.CONFIG.get().afkMinutes = 1;
			dev.townhall.activity.Afk.check(server, now);
			dev.townhall.activity.Afk.check(server, now + 61_000);
			h.assertTrue(dev.townhall.activity.Afk.isAfk(p.getUUID()), "AFK after a minute without moving");
			h.assertTrue(p.getTabListDisplayName() != null && p.getTabListDisplayName().getString().endsWith("[AFK]"), "tab list shows [AFK]");

			var playtime = dev.townhall.activity.Playtime.get(server);
			playtime.tick(server, 0);
			long start = playtime.of(p.getUUID()).orElseThrow().millis();
			playtime.tick(server, 1000);
			h.assertTrue(playtime.of(p.getUUID()).orElseThrow().millis() == start, "AFK time doesn't count");

			p.setYRot(p.getYRot() + 45f);
			dev.townhall.activity.Afk.check(server, now + 62_000);
			h.assertFalse(dev.townhall.activity.Afk.isAfk(p.getUUID()), "looking around ends AFK");
			h.assertTrue(p.getTabListDisplayName() == null, "normal name again");
			playtime.tick(server, 1000);
			h.assertTrue(playtime.of(p.getUUID()).orElseThrow().millis() == start + 1000, "active time counts");

			run(p, "afk");
			h.assertTrue(dev.townhall.activity.Afk.isAfk(p.getUUID()), "/afk");
			dev.townhall.activity.Afk.active(p);
			h.assertTrue(dev.townhall.activity.Afk.isAfk(p.getUUID()), "the /afk command itself doesn't end AFK");

			UUID a = UUID.randomUUID(), b = UUID.randomUUID();
			playtime.set(a, "zzAlice", 1_000L * 3600 * 1000);
			playtime.set(b, "zzBob", 1_000L * 3600 * 2000);
			var top = playtime.top(Integer.MAX_VALUE).stream().map(java.util.Map.Entry::getKey).toList();
			h.assertTrue(top.indexOf(b) >= 0 && top.indexOf(b) < top.indexOf(a), "leaderboard sorted by time");
			h.assertTrue(dev.townhall.activity.Playtime.format((3 * 60 + 5) * 60_000L).equals("3h 5m"), "format");
			h.assertTrue(usable(server, p.createCommandSourceStack(), "playtime top"), "/playtime top for everyone");
		} finally {
			TownhallMod.CONFIG.get().afkMinutes = before;
			dev.townhall.activity.Afk.onLeave(p);
		}
		h.succeed();
	}

	@GameTest
	public void joinMessagesAndTabList(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer p = playerOnFloor(h, new Vec3(3.5, 1, 3.5), 0f, 0f);
		var vanillaJoin = net.minecraft.network.chat.Component.translatable("multiplayer.player.joined", p.getDisplayName());
		var allow = net.fabricmc.fabric.api.message.v1.ServerMessageEvents.ALLOW_GAME_MESSAGE.invoker();
		h.assertFalse(allow.allowGameMessage(server, vanillaJoin, false), "vanilla join message replaced");
		h.assertTrue(allow.allowGameMessage(server, net.minecraft.network.chat.Component.literal("hello"), false), "other messages untouched");

		String text = dev.townhall.display.JoinMessages.format("&a+ &f{player} &7ist AzubiCraft beigetreten.", net.minecraft.network.chat.Component.literal("Max")).getString().replaceAll("§.", "");
		h.assertTrue(text.equals("+ Max ist AzubiCraft beigetreten."), "template: " + text);

		var cfg = TownhallMod.CONFIG.get();
		String before = cfg.joinMessages.leave;
		try {
			console(server, "joinmessage leave &c{player} ist weg");
			h.assertTrue(cfg.joinMessages.leave.equals("&c{player} ist weg"), "leave text set by command");
			console(server, "joinmessage leave -");
			h.assertTrue(cfg.joinMessages.leave.isEmpty(), "'-' = no message");
		} finally {
			cfg.joinMessages.leave = before;
		}

		String footer = dev.townhall.display.TabList.lines(cfg.tabList.footer, p).getString().replaceAll("§.", "").replace("\n", " / ");
		h.assertTrue(footer.contains("Online: " + server.getPlayerList().getPlayerCount() + "/" + server.getPlayerList().getMaxPlayers()), "footer placeholders: " + footer);
		String header = dev.townhall.display.TabList.lines(cfg.tabList.header, p).getString().replaceAll("§.", "").replace("\n", " / ");
		h.assertTrue(header.contains("AzubiCraft") && header.contains(p.getGameProfile().name()), "header: " + header);
		var oldHeader = cfg.tabList.header;
		try {
			console(server, "tablist header &6Zeile 1|&7Zeile 2");
			h.assertTrue(cfg.tabList.header.equals(List.of("&6Zeile 1", "&7Zeile 2")), "'|' splits lines");
		} finally {
			cfg.tabList.header = oldHeader;
		}
		h.succeed();
	}

	@GameTest
	public void fireExplosionsLeavesPerWorld(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		net.minecraft.server.level.ServerLevel end = server.getLevel(Level.END);
		BlockPos base = new BlockPos(200, 70, 200);
		BlockPos fire = base.above(), leaves = base.offset(4, 1, 0), tnt = base.offset(-6, 1, 0);
		var random = end.getRandom();
		Runnable build = () -> {
			for (int x = -9; x <= 6; x++) for (int z = -3; z <= 3; z++) end.setBlock(base.offset(x, 0, z), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
			end.setBlock(fire, net.minecraft.world.level.block.Blocks.FIRE.defaultBlockState(), 2 | 16);
			end.setBlock(leaves, net.minecraft.world.level.block.Blocks.OAK_LEAVES.defaultBlockState()
					.setValue(net.minecraft.world.level.block.LeavesBlock.DISTANCE, 7).setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, false), 2 | 16);
		};
		List<String> rules = List.of("fire", "explosions", "leafDecay", "mobs");
		rules.forEach(r -> console(server, "townhall worldrule minecraft:the_end " + r + " false"));
		try {
			h.assertFalse(DimensionSettings.of(Level.END).mobs(), "mobs rule off");
			build.run();
			end.getBlockState(fire).tick(end, fire, random);
			h.assertTrue(end.getBlockState(fire).isAir(), "fire goes out right away");
			end.getBlockState(leaves).randomTick(end, leaves, random);
			h.assertTrue(end.getBlockState(leaves).is(net.minecraft.world.level.block.Blocks.OAK_LEAVES), "leaves don't decay");
			end.explode(null, tnt.getX() + 0.5, tnt.getY(), tnt.getZ() + 0.5, 3f, Level.ExplosionInteraction.TNT);
			h.assertTrue(end.getBlockState(tnt.below()).is(net.minecraft.world.level.block.Blocks.STONE), "explosions don't break blocks");
		} finally {
			rules.forEach(r -> console(server, "townhall worldrule minecraft:the_end " + r + " default"));
		}
		// Positive controls: the same things with the rules back to normal.
		build.run();
		end.getBlockState(leaves).randomTick(end, leaves, random);
		h.assertFalse(end.getBlockState(leaves).is(net.minecraft.world.level.block.Blocks.OAK_LEAVES), "leaves decay again");
		end.explode(null, tnt.getX() + 0.5, tnt.getY(), tnt.getZ() + 0.5, 3f, Level.ExplosionInteraction.TNT);
		h.assertFalse(end.getBlockState(tnt.below()).is(net.minecraft.world.level.block.Blocks.STONE), "explosions break blocks again");
		h.succeed();
	}

	// ------------------------------------------------------------ 1.12.2: keys and build protection

	private static final net.minecraft.world.level.block.state.BlockState OAK_DOOR = Blocks.OAK_DOOR.defaultBlockState();

	private static void placeDoor(net.minecraft.server.level.ServerLevel level, BlockPos lower) {
		level.setBlock(lower, OAK_DOOR, 3);
		level.setBlock(lower.above(), OAK_DOOR.setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), 3);
	}

	private static net.minecraft.world.InteractionResult useAt(ServerPlayer p, net.minecraft.server.level.ServerLevel level, BlockPos abs) {
		var hit = new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(abs), net.minecraft.core.Direction.UP, abs, false);
		return net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.invoker().interact(p, level, net.minecraft.world.InteractionHand.MAIN_HAND, hit);
	}

	/** Full vanilla right-click (Fabric event, block use, then item use), like a real client packet. */
	private static net.minecraft.world.InteractionResult rightClick(ServerPlayer p, net.minecraft.server.level.ServerLevel level, BlockPos abs) {
		var hit = new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(abs), net.minecraft.core.Direction.UP, abs, false);
		return p.gameMode.useItemOn(p, level, p.getMainHandItem(), net.minecraft.world.InteractionHand.MAIN_HAND, hit);
	}

	private static boolean locked(net.minecraft.server.level.ServerLevel level, BlockPos lower) {
		return dev.townhall.key.DoorLocks.get(level.getServer()).get(level.dimension(), lower).isPresent();
	}

	private static void lock(net.minecraft.server.level.ServerLevel level, BlockPos lower, net.minecraft.world.item.ItemStack key) {
		dev.townhall.key.DoorLocks.get(level.getServer()).put(level.dimension(), lower,
				new dev.townhall.key.DoorLocks.Lock(dev.townhall.key.Keys.keyId(key).orElseThrow().toString(), "Test", UUID.randomUUID().toString(), "Tester"));
	}

	@GameTest
	public void lockingNeedsBuildRights(GameTestHelper h) { // B2
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer player = playerOnFloor(h, new Vec3(2.5, 1, 2.5), 0f, 0f);
		net.minecraft.server.level.ServerLevel end = server.getLevel(Level.END);
		BlockPos base = new BlockPos(300, 70, 300), door = base.above();
		dev.townhall.key.DoorLocks.get(server).remove(Level.END, door); // the test world persists; a failed earlier run may have left a lock
		end.setBlock(base, Blocks.STONE.defaultBlockState(), 3);
		placeDoor(end, door);
		player.teleportTo(end, 301.5, 71, 300.5, java.util.Set.of(), 0f, 0f, true);
		player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, dev.townhall.key.Keys.newKey("Fremd"));
		console(server, "townhall worldrule minecraft:the_end build false");
		try {
			h.assertTrue(useAt(player, end, door) == net.minecraft.world.InteractionResult.PASS, "public door just opens");
			h.assertFalse(locked(end, door), "no build rights: the key doesn't lock the door");
		} finally {
			console(server, "townhall worldrule minecraft:the_end build default");
		}
		try {
			h.assertTrue(useAt(player, end, door).consumesAction(), "with build rights the key links the door");
			h.assertTrue(locked(end, door), "door locked where the player may build");
		} finally {
			dev.townhall.key.DoorLocks.get(server).remove(Level.END, door);
		}
		h.succeed();
	}

	@GameTest
	public void lockedDoorKeepsItsSupport(GameTestHelper h) { // B3 a + hold
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer owner = playerOnFloor(h, new Vec3(3.5, 1, 3.5), 0f, 0f);
		ServerPlayer stranger = h.makeMockServerPlayerInLevel();
		var level = h.getLevel();
		BlockPos lockedDoor = h.absolutePos(new BlockPos(1, 1, 1)), openDoor = h.absolutePos(new BlockPos(5, 1, 1));
		placeDoor(level, lockedDoor);
		placeDoor(level, openDoor);
		var key = dev.townhall.key.Keys.newKey("Stütze");
		owner.getInventory().add(key.copy());
		lock(level, lockedDoor, key);
		try {
			var breakEvent = net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.invoker();
			BlockPos support = lockedDoor.below();
			h.assertFalse(breakEvent.beforeBlockBreak(level, stranger, support, level.getBlockState(support), null), "no key: can't break the block under a locked door");
			h.assertTrue(breakEvent.beforeBlockBreak(level, owner, support, level.getBlockState(support), null), "key owner may break it");
			h.assertTrue(breakEvent.beforeBlockBreak(level, stranger, openDoor.below(), level.getBlockState(openDoor.below()), null), "block under an unlocked door: normal");

			level.setBlock(support, Blocks.AIR.defaultBlockState(), 3);
			h.assertTrue(level.getBlockState(lockedDoor).is(Blocks.OAK_DOOR) && level.getBlockState(lockedDoor.above()).is(Blocks.OAK_DOOR), "locked door stays without support");
			h.assertTrue(locked(level, lockedDoor), "and stays locked");
			level.setBlock(openDoor.below(), Blocks.AIR.defaultBlockState(), 3);
			h.assertFalse(level.getBlockState(openDoor).is(Blocks.OAK_DOOR), "unlocked door pops without support (vanilla)");

			// Pistons: a locked door blocks the piston, an unlocked one is destroyed as usual.
			var dir = net.minecraft.core.Direction.EAST;
			h.assertFalse(new net.minecraft.world.level.block.piston.PistonStructureResolver(level, lockedDoor.west(), dir, true).resolve(), "piston stays retracted");
			placeDoor(level, openDoor);
			level.setBlock(openDoor.below(), Blocks.STONE.defaultBlockState(), 3);
			placeDoor(level, openDoor);
			var resolver = new net.minecraft.world.level.block.piston.PistonStructureResolver(level, openDoor.west(), dir, true);
			h.assertTrue(resolver.resolve() && resolver.getToDestroy().contains(openDoor), "piston destroys an unlocked door (vanilla)");

			// Zombies on hard: BreakDoorGoal gives up on locked doors.
			var zombie = net.minecraft.world.entity.EntityTypes.ZOMBIE.create(level, net.minecraft.world.entity.EntitySpawnReason.TRIGGERED);
			var goal = new net.minecraft.world.entity.ai.goal.BreakDoorGoal(zombie, 240, d -> true);
			var doorPos = net.minecraft.world.entity.ai.goal.DoorInteractGoal.class.getDeclaredField("doorPos");
			var hasDoor = net.minecraft.world.entity.ai.goal.DoorInteractGoal.class.getDeclaredField("hasDoor");
			doorPos.setAccessible(true);
			hasDoor.setAccessible(true);
			hasDoor.setBoolean(goal, true);
			zombie.setPos(Vec3.atBottomCenterOf(lockedDoor.north()));
			doorPos.set(goal, lockedDoor);
			h.assertFalse(goal.canContinueToUse(), "zombie stops breaking a locked door");
			zombie.setPos(Vec3.atBottomCenterOf(openDoor.north()));
			doorPos.set(goal, openDoor);
			h.assertTrue(goal.canContinueToUse(), "zombie keeps breaking an unlocked door (vanilla)");
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		} finally {
			dev.townhall.key.DoorLocks.get(server).remove(level.dimension(), lockedDoor);
		}
		h.succeed();
	}

	@GameTest
	public void explosionsSkipLockedDoorsAndLocksFollowTheDoor(GameTestHelper h) { // B3 c + d
		MinecraftServer server = h.getLevel().getServer();
		net.minecraft.server.level.ServerLevel end = server.getLevel(Level.END);
		BlockPos base = new BlockPos(400, 70, 400), lockedDoor = base.above(), openDoor = base.offset(12, 1, 0), stale = base.offset(24, 1, 0);
		for (int x = -3; x <= 27; x++) for (int z = -3; z <= 3; z++) end.setBlock(base.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
		var key = dev.townhall.key.Keys.newKey("Bunker");
		for (BlockPos p : List.of(lockedDoor, openDoor, stale)) dev.townhall.key.DoorLocks.get(server).remove(Level.END, p); // leftovers of failed runs
		placeDoor(end, lockedDoor);
		placeDoor(end, openDoor);
		lock(end, lockedDoor, key);
		try {
			end.explode(null, lockedDoor.getX() + 0.5, lockedDoor.getY(), lockedDoor.getZ() - 0.5, 3f, Level.ExplosionInteraction.TNT);
			h.assertTrue(end.getBlockState(lockedDoor).is(Blocks.OAK_DOOR) && end.getBlockState(lockedDoor.above()).is(Blocks.OAK_DOOR), "explosion leaves a locked door standing");
			h.assertTrue(locked(end, lockedDoor), "still locked after the explosion");
			end.explode(null, openDoor.getX() + 0.5, openDoor.getY(), openDoor.getZ() - 0.5, 3f, Level.ExplosionInteraction.TNT);
			h.assertFalse(end.getBlockState(openDoor).is(Blocks.OAK_DOOR), "explosion destroys an unlocked door (vanilla)");

			// A locked door removed with block updates (e.g. /setblock ... destroy): the lock goes with it.
			end.setBlock(lockedDoor, Blocks.AIR.defaultBlockState(), 3);
			h.assertFalse(locked(end, lockedDoor), "removed door: lock deleted");

			// Removed without updates (flags like /setblock strict), then a new door on the same spot: no stale lock.
			placeDoor(end, stale);
			lock(end, stale, key);
			end.setBlock(stale.above(), Blocks.AIR.defaultBlockState(), 2 | 16);
			end.setBlock(stale, Blocks.AIR.defaultBlockState(), 2 | 16);
			h.assertTrue(locked(end, stale), "no update: the entry is still stored");
			placeDoor(end, stale);
			h.assertFalse(locked(end, stale), "a new door doesn't inherit the old lock");
			lock(end, stale, key);
			end.setBlock(stale, end.getBlockState(stale).setValue(net.minecraft.world.level.block.DoorBlock.OPEN, true), 3);
			h.assertTrue(locked(end, stale), "opening a door is not a new door: lock stays");
		} finally {
			dev.townhall.key.DoorLocks.get(server).remove(Level.END, lockedDoor);
			dev.townhall.key.DoorLocks.get(server).remove(Level.END, stale);
		}
		h.succeed();
	}

	@GameTest
	public void reloadEndsCreativeOfRemovedBuilders(GameTestHelper h) { // B7
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer builder = playerOnFloor(h, new Vec3(2.5, 1, 2.5), 0f, 0f);
		net.minecraft.server.level.ServerLevel nether = server.getLevel(Level.NETHER);
		builder.teleportTo(nether, 70.5, 80, 70.5, java.util.Set.of(), 0f, 0f, true);
		CommandSourceStack asBuilder = server.createCommandSourceStack().withEntity(builder).withLevel(nether)
				.withPermission(net.minecraft.server.permissions.PermissionSet.NO_PERMISSIONS);
		String id = builder.getUUID().toString();
		try {
			opAt(builder, "builder add minecraft:the_nether @p[distance=..0.5]");
			server.getCommands().performPrefixedCommand(asBuilder, "builder creative");
			h.assertTrue(builder.entityTags().contains(Protection.BUILDER_CREATIVE_TAG), "builder in creative");
			console(server, "townhall reload");
			h.assertTrue(builder.entityTags().contains(Protection.BUILDER_CREATIVE_TAG), "still a builder after reload: creative stays");

			// Someone removes the builder in config/townhall.json, then /townhall reload.
			TownhallConfig.DimensionRules rules = TownhallMod.CONFIG.get().dimensions.get("minecraft:the_nether");
			String name = rules.builders.remove(id);
			TownhallMod.CONFIG.save();
			rules.builders.put(id, name);
			DimensionSettings.rebuild(TownhallMod.CONFIG.get());
			console(server, "townhall reload");
			h.assertFalse(Protection.isBuilderHere(builder), "no builder after reload");
			h.assertFalse(builder.entityTags().contains(Protection.BUILDER_CREATIVE_TAG), "reload puts the removed builder back to survival");
		} finally {
			TownhallConfig.DimensionRules rules = TownhallMod.CONFIG.get().dimensions.get("minecraft:the_nether");
			if (rules != null && rules.builders != null) {
				rules.builders.remove(id);
				if (rules.builders.isEmpty()) rules.builders = null;
			}
			TownhallMod.CONFIG.save();
			DimensionSettings.rebuild(TownhallMod.CONFIG.get());
			builder.removeTag(Protection.BUILDER_CREATIVE_TAG);
		}
		h.succeed();
	}

	@GameTest
	public void buildFalseProtectsSignsPotsAndProjectiles(GameTestHelper h) throws ReflectiveOperationException { // B11
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer player = playerOnFloor(h, new Vec3(2.5, 1, 2.5), 0f, 0f);
		net.minecraft.server.level.ServerLevel end = server.getLevel(Level.END);
		BlockPos base = new BlockPos(500, 70, 500);
		BlockPos sign = base.offset(0, 1, 0), pot = base.offset(2, 1, 0), repeater = base.offset(4, 1, 0), note = base.offset(6, 1, 0),
				pumpkin = base.offset(8, 1, 0), chest = base.offset(10, 1, 0), decorated = base.offset(12, 1, 0);
		Runnable build = () -> {
			for (int x = -2; x <= 14; x++) for (int z = -2; z <= 2; z++) end.setBlock(base.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
			end.setBlock(sign, Blocks.OAK_SIGN.defaultBlockState(), 3);
			end.setBlock(pot, Blocks.POTTED_POPPY.defaultBlockState(), 3);
			end.setBlock(repeater, Blocks.REPEATER.defaultBlockState(), 3);
			end.setBlock(note, Blocks.NOTE_BLOCK.defaultBlockState(), 3);
			end.setBlock(pumpkin, Blocks.PUMPKIN.defaultBlockState(), 3);
			end.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
			end.setBlock(decorated, Blocks.DECORATED_POT.defaultBlockState(), 3);
		};
		build.run();
		player.teleportTo(end, 505.5, 71, 502.5, java.util.Set.of(), 0f, 0f, true);
		var frame = new net.minecraft.world.entity.decoration.ItemFrame(end, base.offset(14, 1, 0), net.minecraft.core.Direction.UP);
		frame.setItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND));
		end.addFreshEntity(frame);
		var onHit = net.minecraft.world.entity.projectile.Projectile.class.getDeclaredMethod("onHit", net.minecraft.world.phys.HitResult.class);
		onHit.setAccessible(true);
		java.util.function.Supplier<net.minecraft.world.entity.projectile.arrow.Arrow> arrow = () -> {
			var a = new net.minecraft.world.entity.projectile.arrow.Arrow(end, player, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ARROW), null);
			a.setDeltaMovement(0, -2, 0);
			return a;
		};
		var hitPot = new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(decorated), net.minecraft.core.Direction.UP, decorated, false);
		console(server, "townhall worldrule minecraft:the_end build false");
		try {
			player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
			for (BlockPos pos : List.of(sign, pot, repeater, note)) {
				h.assertTrue(useAt(player, end, pos) == net.minecraft.world.InteractionResult.FAIL, "empty hand can't change " + end.getBlockState(pos));
			}
			h.assertTrue(useAt(player, end, chest) == net.minecraft.world.InteractionResult.PASS, "chests stay usable");
			for (var item : List.of(net.minecraft.world.item.Items.DYE.red(), net.minecraft.world.item.Items.GLOW_INK_SAC, net.minecraft.world.item.Items.HONEYCOMB,
					net.minecraft.world.item.Items.SHEARS, net.minecraft.world.item.Items.BRUSH, net.minecraft.world.item.Items.ENDER_EYE, net.minecraft.world.item.Items.POTION)) {
				player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.item.ItemStack(item));
				h.assertTrue(useAt(player, end, pumpkin) == net.minecraft.world.InteractionResult.FAIL, item + " is denied on blocks");
			}
			player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SHEARS));
			rightClick(player, end, pumpkin);
			h.assertTrue(end.getBlockState(pumpkin).is(Blocks.PUMPKIN), "pumpkin not carved");

			onHit.invoke(arrow.get(), new net.minecraft.world.phys.EntityHitResult(frame));
			h.assertFalse(frame.isRemoved() || frame.getItem().isEmpty(), "arrow doesn't break the item frame");
			onHit.invoke(arrow.get(), hitPot);
			h.assertTrue(end.getBlockState(decorated).is(Blocks.DECORATED_POT), "arrow doesn't break the decorated pot");
		} finally {
			console(server, "townhall worldrule minecraft:the_end build default");
		}
		// Positive controls with the rule back to normal.
		player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
		h.assertTrue(useAt(player, end, sign) == net.minecraft.world.InteractionResult.PASS, "signs editable again");
		player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SHEARS));
		rightClick(player, end, pumpkin);
		h.assertTrue(end.getBlockState(pumpkin).is(Blocks.CARVED_PUMPKIN), "shears carve the pumpkin again");
		onHit.invoke(arrow.get(), new net.minecraft.world.phys.EntityHitResult(frame));
		h.assertTrue(frame.isRemoved() || frame.getItem().isEmpty(), "arrow hits the item frame again");
		onHit.invoke(arrow.get(), hitPot);
		h.assertFalse(end.getBlockState(decorated).is(Blocks.DECORATED_POT), "arrow breaks the decorated pot again");
		frame.discard();
		h.succeed();
	}

	@GameTest
	public void buildFalseKeepsBlocksUsableWithToolsInHand(GameTestHelper h) { // B12
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer player = playerOnFloor(h, new Vec3(2.5, 1, 2.5), 0f, 0f);
		net.minecraft.server.level.ServerLevel end = server.getLevel(Level.END);
		BlockPos base = new BlockPos(600, 70, 600), chest = base.above(), door = base.offset(2, 1, 0), log = base.offset(4, 1, 0);
		for (int x = -2; x <= 6; x++) for (int z = -2; z <= 2; z++) end.setBlock(base.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
		end.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
		placeDoor(end, door);
		end.setBlock(log, Blocks.OAK_LOG.defaultBlockState(), 3);
		player.teleportTo(end, 602.5, 71, 602.5, java.util.Set.of(), 0f, 0f, true);
		player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_AXE));
		console(server, "townhall worldrule minecraft:the_end build false");
		try {
			rightClick(player, end, chest);
			h.assertTrue(player.containerMenu instanceof net.minecraft.world.inventory.ChestMenu, "axe in hand: chest still opens");
			player.closeContainer();
			rightClick(player, end, door);
			h.assertTrue(end.getBlockState(door).getValue(net.minecraft.world.level.block.DoorBlock.OPEN), "axe in hand: door still opens");
			h.assertTrue(rightClick(player, end, log) == net.minecraft.world.InteractionResult.FAIL, "axe on a log is denied");
			h.assertTrue(end.getBlockState(log).is(Blocks.OAK_LOG), "log not stripped");
			player.setShiftKeyDown(true);
			h.assertTrue(useAt(player, end, chest) == net.minecraft.world.InteractionResult.FAIL, "sneaking skips the chest: the axe rule applies");
			player.setShiftKeyDown(false);
		} finally {
			console(server, "townhall worldrule minecraft:the_end build default");
			player.closeContainer();
		}
		rightClick(player, end, log);
		h.assertTrue(end.getBlockState(log).is(Blocks.STRIPPED_OAK_LOG), "axe strips logs again after default");
		h.succeed();
	}

	@GameTest
	public void keysHaveCooldownAndDontCraft(GameTestHelper h) { // B14
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer player = playerOnFloor(h, new Vec3(2.5, 1, 2.5), 0f, 0f);
		java.util.function.IntSupplier keys = () -> {
			int n = 0;
			for (int i = 0; i < player.getInventory().getContainerSize(); i++) if (dev.townhall.key.Keys.keyId(player.getInventory().getItem(i)).isPresent()) n += player.getInventory().getItem(i).getCount();
			return n;
		};
		run(player, "key new Eins");
		run(player, "key new Zwei");
		h.assertTrue(keys.getAsInt() == 1, "second /key new within 10 s is refused (had " + keys.getAsInt() + ")");
		h.assertTrue(dev.townhall.key.Keys.keyId(player.getMainHandItem()).isPresent(), "the new key is in the main hand");
		run(player, "key copy");
		run(player, "key copy");
		h.assertTrue(keys.getAsInt() == 2, "one copy, the second within 10 s is refused (had " + keys.getAsInt() + ")");
		opAt(player, "key new Drei");
		h.assertTrue(keys.getAsInt() == 3, "operators have no cooldown");

		var key = dev.townhall.key.Keys.newKey("Kiste");
		var chest = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.CHEST);
		var hook = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TRIPWIRE_HOOK);
		var e = net.minecraft.world.item.ItemStack.EMPTY;
		h.assertTrue(net.minecraft.world.level.block.CrafterBlock.getPotentialResults(h.getLevel(), net.minecraft.world.item.crafting.CraftingInput.of(2, 1, List.of(chest, key))).isEmpty(), "crafter: key + chest gives nothing");
		h.assertTrue(net.minecraft.world.level.block.CrafterBlock.getPotentialResults(h.getLevel(), net.minecraft.world.item.crafting.CraftingInput.of(2, 1, List.of(chest, hook))).isPresent(), "crafter: tripwire hook + chest = trapped chest");
		var menu = new net.minecraft.world.inventory.CraftingMenu(1, player.getInventory(), net.minecraft.world.inventory.ContainerLevelAccess.create(h.getLevel(), h.absolutePos(BlockPos.ZERO)));
		menu.getSlot(1).set(chest.copy());
		menu.getSlot(2).set(key.copy());
		h.assertTrue(menu.getSlot(0).getItem().isEmpty(), "crafting table: key + chest gives nothing");
		menu.getSlot(2).set(hook.copy());
		h.assertTrue(menu.getSlot(0).getItem().is(net.minecraft.world.item.Items.TRAPPED_CHEST), "crafting table: tripwire hook + chest = trapped chest");
		h.succeed();
	}
}
