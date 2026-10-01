package dev.townhall.test;

import dev.townhall.TownhallMod;
import dev.townhall.activity.Afk;
import dev.townhall.config.TownhallConfig;
import dev.townhall.dimension.DimensionSettings;
import dev.townhall.display.JoinMessages;
import dev.townhall.display.TabList;
import dev.townhall.test.mixin.ServerLevelInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.SleepStatus;
import net.minecraft.stats.Stats;
import net.minecraft.world.Difficulty;
import net.minecraft.world.attribute.BedRule;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.storage.loot.IntRangePredicate;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.predicates.TimeCheck;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 1.14.0: sleep percentage and the world-rule / AFK / display fixes (ids from the review: S1, C1-C11).
 * Tests that change overworld rules do it synchronously and put the previous value back in the same tick,
 * so the other overworld tests (fixed time/weather) running in parallel never see the change.
 */
public class WorldRulesGameTests {

	private static final String OVERWORLD = "minecraft:overworld";
	private static final com.google.gson.Gson GSON = new com.google.gson.Gson();

	private static ServerPlayer player(GameTestHelper h, Vec3 relative) {
		for (int x = 0; x < 7; x++) for (int z = 0; z < 7; z++) h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
		TownhallMod.CONFIG.get().onboarding.enabled = false;
		ServerPlayer p = h.makeMockServerPlayerInLevel();
		Vec3 abs = h.absoluteVec(relative);
		p.absSnapTo(abs.x, abs.y, abs.z, 0f, 0f);
		return p;
	}

	private static void console(MinecraftServer server, String command) {
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
	}

	private static void run(ServerPlayer player, String command) {
		player.level().getServer().getCommands().performPrefixedCommand(player.createCommandSourceStack(), command);
	}

	/** Changes rules of one world for the body only (config object + prebuilt map), then restores the old values. */
	private static void withRules(String dimension, Consumer<TownhallConfig.DimensionRules> change, Runnable body) {
		TownhallConfig config = TownhallMod.CONFIG.get();
		TownhallConfig.DimensionRules existing = config.dimensions.get(dimension);
		TownhallConfig.DimensionRules backup = existing == null ? null : GSON.fromJson(GSON.toJson(existing), TownhallConfig.DimensionRules.class);
		TownhallConfig.DimensionRules rules = config.dimensions.computeIfAbsent(dimension, k -> new TownhallConfig.DimensionRules());
		change.accept(rules);
		DimensionSettings.rebuild(config);
		try {
			body.run();
		} finally {
			if (backup == null) config.dimensions.remove(dimension);
			else config.dimensions.put(dimension, backup);
			DimensionSettings.rebuild(config);
		}
	}

	private static <T> void withGameRule(MinecraftServer server, net.minecraft.world.level.gamerules.GameRule<T> rule, T value, Runnable body) {
		T before = server.getGameRules().get(rule);
		server.getGameRules().set(rule, value, server);
		try {
			body.run();
		} finally {
			server.getGameRules().set(rule, before, server);
		}
	}

	/** Recomputes darkness now (the environment attributes are otherwise cached for the tick). */
	private static void refreshSky(ServerLevel level) {
		level.environmentAttributes().invalidateTickCache();
		level.updateSkyBrightness();
	}

	// ------------------------------------------------------------ S1

	@GameTest
	public void sleepPercentageCommand(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		int before = server.getGameRules().get(GameRules.PLAYERS_SLEEPING_PERCENTAGE);
		ServerPlayer awake = player(h, new Vec3(1.5, 1, 1.5));
		ServerPlayer sleeper = player(h, new Vec3(4.5, 1, 4.5));
		try {
			console(server, "townhall sleep 50");
			int rule = server.getGameRules().get(GameRules.PLAYERS_SLEEPING_PERCENTAGE);
			h.assertTrue(rule == 50, "game rule is 50 after /townhall sleep 50, was " + rule);
			sleeper.setSleepingPos(sleeper.blockPosition());
			SleepStatus status = new SleepStatus();
			status.update(List.of(awake, sleeper));
			h.assertTrue(status.areEnoughSleeping(rule), "1 of 2 sleeping is enough at 50%");
			console(server, "townhall sleep 100");
			rule = server.getGameRules().get(GameRules.PLAYERS_SLEEPING_PERCENTAGE);
			h.assertTrue(rule == 100, "game rule is 100");
			h.assertFalse(status.areEnoughSleeping(rule), "1 of 2 sleeping is not enough at 100%");
			h.assertTrue(status.sleepersNeeded(50) == 1 && new SleepStatus().sleepersNeeded(50) == 1, "at least one sleeper");
		} finally {
			sleeper.clearSleepingPos();
			server.getGameRules().set(GameRules.PLAYERS_SLEEPING_PERCENTAGE, before, server);
		}
		h.succeed();
	}

	// ------------------------------------------------------------ C1

	@GameTest
	public void noSleepingInFixedTimeWorlds(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerLevel overworld = server.overworld();
		h.assertTrue(h.getLevel() == overworld, "test runs in the overworld");
		ServerPlayer p = player(h, new Vec3(3.5, 1, 2.5));
		BlockPos bedRel = new BlockPos(3, 1, 3);
		h.setBlock(bedRel, Blocks.BED.red().defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH));
		BlockPos bed = h.absolutePos(bedRel);
		BlockState state = overworld.getBlockState(bed);
		AbstractBedBlock block = (AbstractBedBlock) state.getBlock();

		var clock = overworld.dimensionType().defaultClock().orElseThrow();
		long realTime = server.clockManager().getInstance(clock).totalTicks();
		try {
			// Fixed midnight while the shared clock says noon.
			server.clockManager().setTotalTicks(clock, DimensionSettings.pin(realTime, 6000));
			withRules(OVERWORLD, r -> r.time = "midnight", () -> {
				refreshSky(overworld);
				h.assertTrue(overworld.isDarkOutside(), "fixed midnight makes the world dark (why beds worked)");
				var result = p.startSleepInBed(block, state, BedRule.CAN_SLEEP_WHEN_DARK, bed);
				boolean slept = p.isSleeping();
				if (slept) p.stopSleepInBed(true, true);
				h.assertFalse(slept, "no sleeping in a fixed-time world (it would skip everyone's time)");
				h.assertTrue(BedRule.CAN_SLEEP_WHEN_DARK.asProblem().equals(result.left().orElse(null)), "vanilla 'only at night' answer: " + result);
				h.assertTrue(p.getRespawnConfig() != null && p.getRespawnConfig().respawnData().pos().equals(bed), "bed still sets the spawn point");
			});
			float rain = overworld.getRainLevel(1f), thunder = overworld.getThunderLevel(1f);
			withRules(OVERWORLD, r -> r.weather = "thunder", () -> {
				overworld.setRainLevel(1f); // the world's own storm, fully faded in
				overworld.setThunderLevel(1f);
				refreshSky(overworld);
				boolean dark = overworld.isDarkOutside();
				p.startSleepInBed(block, state, BedRule.CAN_SLEEP_WHEN_DARK, bed);
				boolean slept = p.isSleeping();
				if (slept) p.stopSleepInBed(true, true);
				overworld.setRainLevel(rain);
				overworld.setThunderLevel(thunder);
				h.assertTrue(dark, "the world's own thunder makes it dark at noon");
				h.assertFalse(slept, "no sleeping in a fixed-thunder world");
			});
			// Positive control: normal world at real midnight sleeps like vanilla.
			withRules(OVERWORLD, r -> { r.time = null; r.weather = null; }, () -> {
				server.clockManager().setTotalTicks(clock, DimensionSettings.pin(realTime, 18000));
				refreshSky(overworld);
				var result = p.startSleepInBed(block, state, BedRule.CAN_SLEEP_WHEN_DARK, bed);
				boolean slept = p.isSleeping();
				if (slept) p.stopSleepInBed(true, true);
				h.assertTrue(slept, "normal world at night: sleeping works (" + result + ")");
			});
		} finally {
			server.clockManager().setTotalTicks(clock, realTime);
			refreshSky(overworld);
		}
		h.succeed();
	}

	// ------------------------------------------------------------ C2

	@GameTest
	public void rainFromOneWorldDoesNotLeakIntoOthers(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerLevel overworld = server.overworld(), nether = server.getLevel(Level.NETHER);
		ServerPlayer here = player(h, new Vec3(1.5, 1, 1.5));
		ServerPlayer away = player(h, new Vec3(4.5, 1, 4.5));
		away.teleportTo(nether, 0.5, 120, 0.5, java.util.Set.of(), 0f, 0f, true);
		h.assertTrue(away.level() == nether, "second player is in the nether");
		var weather = overworld.getWeatherData();
		boolean wasRaining = weather.isRaining(), wasThundering = weather.isThundering();
		float rain = overworld.getRainLevel(1f), thunder = overworld.getThunderLevel(1f);
		PacketLog.watch(here.getUUID());
		PacketLog.watch(away.getUUID());
		try {
			// The overworld just lost its weather rule and is fading out: the rain stops at 0.2 and vanilla tells everyone.
			withRules(OVERWORLD, r -> r.weather = null, () -> {
				weather.setRaining(false);
				weather.setThundering(false);
				overworld.setRainLevel(0.205f);
				overworld.setThunderLevel(0f);
				((ServerLevelInvoker) overworld).townhallTest$advanceWeatherCycle();
			});
			float overworldRain = overworld.getRainLevel(1f);
			List<Float> hereRain = rainLevels(here), awayRain = rainLevels(away);
			h.assertTrue(awayRain.contains(overworldRain), "the nether player got the overworld's broadcast (scenario): " + awayRain);
			h.assertTrue(awayRain.getLast() == nether.getRainLevel(1f), "last rain level for the nether player is the nether's own: " + awayRain);
			h.assertTrue(!hereRain.isEmpty() && hereRain.getLast() == overworldRain, "overworld player keeps the overworld value: " + hereRain);
			var awayEvents = PacketLog.of(away.getUUID(), ClientboundGameEventPacket.class).stream().map(ClientboundGameEventPacket::getEvent).toList();
			h.assertTrue(awayEvents.contains(ClientboundGameEventPacket.STOP_RAINING), "stop-rain arrived (matches the nether)");
		} finally {
			PacketLog.stop(here.getUUID());
			PacketLog.stop(away.getUUID());
			weather.setRaining(wasRaining);
			weather.setThundering(wasThundering);
			overworld.setRainLevel(rain);
			overworld.setThunderLevel(thunder);
		}
		h.succeed();
	}

	private static List<Float> rainLevels(ServerPlayer p) {
		return PacketLog.of(p.getUUID(), ClientboundGameEventPacket.class).stream()
				.filter(e -> e.getEvent() == ClientboundGameEventPacket.RAIN_LEVEL_CHANGE).map(ClientboundGameEventPacket::getParam).toList();
	}

	// ------------------------------------------------------------ C3

	/** Counts what the code asks from the random source; nextFloat 0.99 = the chunk generation spawn loop never runs. */
	private static final class CountingRandom extends LegacyRandomSource {
		int calls;

		CountingRandom() {
			super(42L);
		}

		@Override
		public float nextFloat() {
			calls++;
			return 0.99f;
		}

		@Override
		public int nextInt(int bound) {
			calls++;
			return 0;
		}
	}

	@GameTest
	public void mobsRuleCoversAllSpawnPaths(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerLevel level = h.getLevel();
		ServerPlayer p = player(h, new Vec3(1.5, 1, 1.5));
		Difficulty difficulty = server.getWorldData().getDifficulty();
		if (difficulty == Difficulty.PEACEFUL) server.setDifficulty(Difficulty.NORMAL, true);
		try {
			withGameRule(server, GameRules.SPAWN_MOBS, true, () -> withGameRule(server, GameRules.SPAWN_MONSTERS, true, () -> {
				// Chunk generation: cancelled before it even rolls the dice.
				BlockPos center = h.absolutePos(new BlockPos(3, 1, 3));
				ChunkPos chunk = ChunkPos.containing(center);
				CountingRandom off = new CountingRandom(), on = new CountingRandom();
				withRules(OVERWORLD, r -> r.mobs = false, () -> NaturalSpawner.spawnMobsForChunkGeneration(level, center, chunk, off));
				NaturalSpawner.spawnMobsForChunkGeneration(level, center, chunk, on);
				h.assertTrue(off.calls == 0, "chunk generation spawns nothing with mobs:false (random used " + off.calls + "x)");
				h.assertTrue(on.calls > 0, "positive control: chunk generation spawning runs with the rule default");

				// Nether portal: zombified piglins.
				BlockPos portalRel = new BlockPos(5, 1, 5);
				h.setBlock(portalRel, Blocks.NETHER_PORTAL);
				BlockPos portal = h.absolutePos(portalRel);
				AABB box = new AABB(portal).inflate(3);
				withRules(OVERWORLD, r -> r.mobs = false, () -> level.getBlockState(portal).randomTick(level, portal, new CountingRandom()));
				int blocked = level.getEntities(EntityTypes.ZOMBIFIED_PIGLIN, box, e -> true).size();
				level.getBlockState(portal).randomTick(level, portal, new CountingRandom());
				var spawned = level.getEntities(EntityTypes.ZOMBIFIED_PIGLIN, box, e -> true);
				int allowed = spawned.size();
				spawned.forEach(e -> e.discard());
				h.setBlock(portalRel, Blocks.AIR);
				h.assertTrue(blocked == 0, "portal spawns no piglin with mobs:false");
				h.assertTrue(allowed > 0, "positive control: portal spawns a piglin with the rule default");

				// Trial spawner.
				BlockPos trialRel = new BlockPos(1, 1, 5);
				h.setBlock(trialRel, Blocks.TRIAL_SPAWNER);
				TrialSpawnerBlockEntity trial = h.getBlockEntity(trialRel, TrialSpawnerBlockEntity.class);
				trial.getTrialSpawner().overridePeacefulAndMobSpawnRule();
				boolean[] off2 = new boolean[1];
				Optional<?>[] mob = new Optional<?>[1];
				withRules(OVERWORLD, r -> r.mobs = false, () -> {
					off2[0] = trial.getTrialSpawner().canSpawnInLevel(level);
					mob[0] = trial.getTrialSpawner().spawnMob(level, h.absolutePos(trialRel));
				});
				boolean on2 = trial.getTrialSpawner().canSpawnInLevel(level);
				h.setBlock(trialRel, Blocks.AIR);
				h.assertFalse(off2[0], "trial spawner can't spawn with mobs:false");
				h.assertTrue(mob[0].isEmpty(), "trial spawner spawnMob does nothing with mobs:false");
				h.assertTrue(on2, "positive control: trial spawner can spawn with the rule default");

			}));
		} finally {
			if (difficulty == Difficulty.PEACEFUL) server.setDifficulty(Difficulty.PEACEFUL, true);
		}
		// Raids need a village: a claimed meeting point (bell). Its POI is registered on a later tick.
		BlockPos bell = h.absolutePos(new BlockPos(3, 1, 1));
		h.setBlock(new BlockPos(3, 1, 1), Blocks.BELL);
		h.runAfterDelay(3, () -> {
			if (difficulty == Difficulty.PEACEFUL) server.setDifficulty(Difficulty.NORMAL, true);
			level.getPoiManager().take(t -> t.is(net.minecraft.world.entity.ai.village.poi.PoiTypes.MEETING), (t, pos) -> true, bell, 1);
			try {
				h.assertTrue(level.isVillage(p.blockPosition()), "precondition: the bell makes a village");
				Raid[] blocked = new Raid[1];
				withRules(OVERWORLD, r -> r.mobs = false, () -> blocked[0] = level.getRaids().createOrExtendRaid(p, p.blockPosition()));
				h.assertTrue(blocked[0] == null, "no raid starts with mobs:false");
				Raid raid = level.getRaids().createOrExtendRaid(p, p.blockPosition());
				h.assertTrue(raid != null, "positive control: a raid starts with the rule default");
				try {
					raid.tick(level);
					h.assertFalse(raid.isStopped(), "positive control: the raid keeps running with the rule default");
					withRules(OVERWORLD, r -> r.mobs = false, () -> raid.tick(level));
					h.assertTrue(raid.isStopped(), "a running raid stops with mobs:false");
				} finally {
					raid.stop();
				}
			} finally {
				if (difficulty == Difficulty.PEACEFUL) server.setDifficulty(Difficulty.PEACEFUL, true);
				level.getPoiManager().release(bell);
				h.setBlock(new BlockPos(3, 1, 1), Blocks.AIR);
			}
			h.succeed();
		});
	}

	// ------------------------------------------------------------ C4 + C5

	@GameTest
	public void explosionsRuleStopsFireButNotTriggers(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerLevel end = server.getLevel(Level.END);
		BlockPos base = new BlockPos(300, 70, 300);
		Runnable floor = () -> {
			for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++) {
				end.setBlock(base.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
				for (int y = 1; y <= 5; y++) end.setBlock(base.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
			}
		};
		AABB area = new AABB(base).inflate(6);
		java.util.function.IntSupplier fires = () -> (int) BlockPos.betweenClosedStream(area).filter(pos -> end.getBlockState(pos).is(Blocks.FIRE)).count();
		BlockPos button = base.above();
		BlockState buttonState = Blocks.STONE_BUTTON.defaultBlockState().setValue(ButtonBlock.FACE, AttachFace.FLOOR);
		Vec3 blast = Vec3.atCenterOf(base.above());

		int[] fireOff = new int[1];
		boolean[] pressed = new boolean[1], floorKept = new boolean[1];
		withRules("minecraft:the_end", r -> r.explosions = false, () -> {
			floor.run();
			end.explode(null, blast.x, blast.y, blast.z, 3f, true, Level.ExplosionInteraction.TNT);
			fireOff[0] = fires.getAsInt();
			floorKept[0] = end.getBlockState(base).is(Blocks.STONE);
			floor.run();
			end.setBlock(button, buttonState, 3);
			end.explode(null, blast.x + 1, blast.y, blast.z, 1.2f, false, Level.ExplosionInteraction.TRIGGER);
			pressed[0] = end.getBlockState(button).is(Blocks.STONE_BUTTON) && end.getBlockState(button).getValue(ButtonBlock.POWERED);
		});
		// Positive control: the same fire explosion in a normal world.
		floor.run();
		end.explode(null, blast.x, blast.y, blast.z, 3f, true, Level.ExplosionInteraction.TNT);
		int fireOn = fires.getAsInt();
		boolean floorBroken = !end.getBlockState(base).is(Blocks.STONE);
		floor.run();

		h.assertTrue(fireOff[0] == 0, "explosions:false lights no fire, found " + fireOff[0]);
		h.assertTrue(floorKept[0], "explosions:false breaks no blocks");
		h.assertTrue(pressed[0], "explosions:false still lets a wind-charge-type explosion press a button");
		h.assertTrue(fireOn > 0 && floorBroken, "positive control: normal world burns (" + fireOn + " fires) and breaks blocks");
		h.succeed();
	}

	// ------------------------------------------------------------ C6 + C7

	@GameTest
	public void pushingIsNotActivityButCommandsAre(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer p = player(h, new Vec3(3.5, 1, 3.5));
		int before = TownhallMod.CONFIG.get().afkMinutes;
		long now = System.currentTimeMillis();
		try {
			TownhallMod.CONFIG.get().afkMinutes = 1;
			Afk.check(server, now);
			// Pushed a block away (piston, water, other player): no keys held, no looking around.
			p.setLastClientInput(Input.EMPTY);
			p.absSnapTo(p.getX() + 1, p.getY(), p.getZ(), p.getYRot(), p.getXRot());
			Afk.check(server, now + 61_000);
			h.assertTrue(Afk.isAfk(p.getUUID()), "being pushed doesn't keep a player active");

			// Walking on purpose: movement key held.
			p.setLastClientInput(new Input(true, false, false, false, false, false, false));
			p.absSnapTo(p.getX() - 1, p.getY(), p.getZ(), p.getYRot(), p.getXRot());
			Afk.check(server, now + 62_000);
			h.assertFalse(Afk.isAfk(p.getUUID()), "walking with a key held ends AFK");
			p.setLastClientInput(Input.EMPTY);

			// Looking around.
			Afk.check(server, now + 130_000);
			h.assertTrue(Afk.isAfk(p.getUUID()), "AFK again after a minute");
			p.setYRot(p.getYRot() + 45f);
			Afk.check(server, now + 131_000);
			h.assertFalse(Afk.isAfk(p.getUUID()), "looking around ends AFK");

			// Commands (C7): any command ends AFK, /afk sets it.
			Afk.check(server, now + 200_000);
			h.assertTrue(Afk.isAfk(p.getUUID()), "AFK before the command");
			run(p, "playtime");
			h.assertFalse(Afk.isAfk(p.getUUID()), "running /playtime ends AFK");
			run(p, "afk");
			h.assertTrue(Afk.isAfk(p.getUUID()), "/afk makes the player AFK");
			h.assertTrue(Afk.isAfkCommand("/afk") && Afk.isAfkCommand("AFK") && !Afk.isAfkCommand("afktime 5"), "only /afk itself is skipped");
		} finally {
			TownhallMod.CONFIG.get().afkMinutes = before;
			Afk.onLeave(p);
		}
		h.succeed();
	}

	// ------------------------------------------------------------ C8

	@GameTest
	public void timeCheckSeesFixedTime(GameTestHelper h) {
		ServerLevel overworld = h.getLevel().getServer().overworld();
		var clock = overworld.dimensionType().defaultClock().orElseThrow();
		TimeCheck midnight = new TimeCheck(clock, Optional.of(24000L), IntRangePredicate.exact(18000));
		LootContext context = new LootContext.Builder(new LootParams.Builder(overworld).create(LootContextParamSets.EMPTY)).create(Optional.empty());
		boolean[] fixed = new boolean[1];
		boolean[] normal = new boolean[1];
		withRules(OVERWORLD, r -> r.time = "midnight", () -> fixed[0] = midnight.test(context));
		withRules(OVERWORLD, r -> r.time = null, () -> normal[0] = midnight.test(context));
		long real = Math.floorMod(overworld.getServer().clockManager().getInstance(clock).totalTicks(), 24000L);
		h.assertTrue(fixed[0], "time_check sees the fixed midnight");
		h.assertTrue(normal[0] == (real == 18000L), "positive control: without the rule time_check follows the real clock");
		h.succeed();
	}

	// ------------------------------------------------------------ C10

	@GameTest
	public void personalJoinMessageDashMeansNone(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer p = player(h, new Vec3(2.5, 1, 4.5));
		var cfg = TownhallMod.CONFIG.get().joinMessages;
		boolean enabled = cfg.enabled;
		String key = p.getUUID().toString();
		p.getStats().setValue(p, Stats.CUSTOM.get(Stats.LEAVE_GAME), 1); // not the first join
		PacketLog.watch(p.getUUID());
		try {
			cfg.enabled = true;
			var source = server.createCommandSourceStack().withEntity(p).withLevel(p.level()).withPosition(p.position());
			server.getCommands().performPrefixedCommand(source, "joinmessage set @p[distance=..0.5] -");
			h.assertTrue("".equals(cfg.players.get(key)), "'-' is stored as no message: " + cfg.players.get(key));
			JoinMessages.onJoin(p);
			h.assertFalse(chat(p).stream().anyMatch(t -> t.equals("-")), "nobody sees a '-' join message");

			server.getCommands().performPrefixedCommand(source, "joinmessage set @p[distance=..0.5] &aHallo {player} QX7");
			JoinMessages.onJoin(p);
			h.assertTrue(chat(p).stream().anyMatch(t -> t.contains("QX7")), "positive control: a real personal message is broadcast");
		} finally {
			PacketLog.stop(p.getUUID());
			cfg.players.remove(key);
			cfg.enabled = enabled;
		}
		h.succeed();
	}

	private static List<String> chat(ServerPlayer p) {
		return PacketLog.of(p.getUUID(), ClientboundSystemChatPacket.class).stream().map(c -> c.content().getString().replaceAll("§.", "")).toList();
	}

	// ------------------------------------------------------------ C11

	@GameTest
	public void tabListOffClearsOnlyOnce(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer p = player(h, new Vec3(5.5, 1, 2.5));
		var cfg = TownhallMod.CONFIG.get().tabList;
		boolean enabled = cfg.enabled;
		PacketLog.watch(p.getUUID());
		try {
			cfg.enabled = true;
			TabList.update(server);
			h.assertTrue(tabs(p).size() == 1 && !tabs(p).getFirst().header().getString().isEmpty(), "on: header sent");
			console(server, "tablist off");
			TabList.update(server);
			TabList.update(server);
			List<ClientboundTabListPacket> off = tabs(p).subList(1, tabs(p).size());
			h.assertTrue(off.size() == 1 && off.getFirst().header().getString().isEmpty() && off.getFirst().footer().getString().isEmpty(),
					"off: cleared exactly once, then nothing (got " + off.size() + ")");
			console(server, "tablist on");
			List<ClientboundTabListPacket> all = tabs(p);
			h.assertTrue(all.size() == 3 && !all.getLast().header().getString().isEmpty(), "on again: header sent");
		} finally {
			PacketLog.stop(p.getUUID());
			cfg.enabled = enabled;
			TabList.update(server);
		}
		h.succeed();
	}

	private static List<ClientboundTabListPacket> tabs(ServerPlayer p) {
		return PacketLog.of(p.getUUID(), ClientboundTabListPacket.class);
	}
}
