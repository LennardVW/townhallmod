package dev.townhall.dimension;

import dev.townhall.config.TownhallConfig;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.game.ClientboundChangeDifficultyPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.Difficulty;
import net.minecraft.world.clock.ClockNetworkState;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Per-world rules from the config's "dimensions" section, as a fast lookup.
 * Level.getDifficulty() and the damage/hunger hooks run very often, so they read this prebuilt map, never the config.
 */
public final class DimensionSettings {

	/**
	 * Resolved rules of one world; null difficulty = server default, booleans default to vanilla (true).
	 * time = fixed ticks of the day (null = runs normally), weather = clear/rain/thunder (null = normal),
	 * builders = players who may build even when build is false.
	 */
	public record Rules(Difficulty difficulty, boolean pvp, boolean build, boolean hunger, boolean fallDamage, Long time, String weather, Set<UUID> builders,
			boolean mobs, boolean fire, boolean explosions, boolean leafDecay) {
		static final Rules VANILLA = new Rules(null, true, true, true, true, null, null, Set.of(), true, true, true, true);
	}

	private static volatile Map<ResourceKey<Level>, Rules> rules = Map.of();

	private DimensionSettings() {}

	/** Call after every config load or change. */
	public static void rebuild(TownhallConfig config) {
		Map<ResourceKey<Level>, Rules> map = new HashMap<>();
		config.dimensions.forEach((id, r) -> {
			if (r == null) return;
			map.put(ResourceKey.create(Registries.DIMENSION, Identifier.parse(id)), new Rules(
					r.difficulty == null ? null : Difficulty.byName(r.difficulty),
					r.pvp == null || r.pvp,
					r.build == null || r.build,
					r.hunger == null || r.hunger,
					r.fallDamage == null || r.fallDamage,
					TownhallConfig.parseTime(r.time),
					r.weather,
					r.builders == null ? Set.of() : r.builders.keySet().stream().map(UUID::fromString).collect(java.util.stream.Collectors.toUnmodifiableSet()),
					r.mobs == null || r.mobs,
					r.fire == null || r.fire,
					r.explosions == null || r.explosions,
					r.leafDecay == null || r.leafDecay));
		});
		rules = Map.copyOf(map);
	}

	/** True if the player is a builder in at least one world. */
	public static boolean isBuilderAnywhere(UUID player) {
		return rules.values().stream().anyMatch(r -> r.builders().contains(player));
	}

	public static Rules of(ResourceKey<Level> dimension) {
		return rules.getOrDefault(dimension, Rules.VANILLA);
	}

	/** The overridden difficulty of a dimension, or null to use the server's normal difficulty. */
	public static Difficulty difficulty(ResourceKey<Level> dimension) {
		return of(dimension).difficulty();
	}

	/**
	 * Shows the player the difficulty and time of the world they're in. The client only knows one global difficulty
	 * and one set of clocks, so this is resent whenever the player changes worlds or a rule changes.
	 * The time packet passes ServerCommonPacketListenerImplMixin, which pins it for fixed-time worlds.
	 */
	public static void syncClient(ServerPlayer player) {
		Level level = player.level();
		player.connection.send(new ClientboundChangeDifficultyPacket(level.getDifficulty(), level.getLevelData().isDifficultyLocked()));
		player.connection.send(player.level().getServer().clockManager().createFullSyncPacket());
	}

	public static void syncAll(MinecraftServer server) {
		server.getPlayerList().getPlayers().forEach(DimensionSettings::syncClient);
	}

	// ---- time ----

	/** Fixed ticks of the day for this world, or null if time runs normally. */
	public static Long fixedTime(ResourceKey<Level> dimension) {
		return of(dimension).time();
	}

	/**
	 * Moves a clock value to the fixed time of the same day. Keeping the day count means day-based things
	 * (villager restocks, moon phase) still move on once per real day.
	 */
	public static long pin(long totalTicks, long timeOfDay) {
		return totalTicks - Math.floorMod(totalTicks, 24000L) + timeOfDay;
	}

	/** The time packet a player in this world should get: every clock stopped at the fixed time, or the packet unchanged. */
	public static ClientboundSetTimePacket timePacketFor(ResourceKey<Level> dimension, ClientboundSetTimePacket packet) {
		Long fixed = fixedTime(dimension);
		if (fixed == null) return packet;
		Map<Holder<WorldClock>, ClockNetworkState> clocks = new HashMap<>();
		packet.clockUpdates().forEach((clock, state) -> clocks.put(clock, new ClockNetworkState(pin(state.totalTicks(), fixed), 0f, 0f)));
		return new ClientboundSetTimePacket(packet.gameTime(), clocks);
	}

	// ---- weather ----

	/** Weather state for this world: the fixed value if set, else what the shared weather says. */
	public static boolean raining(ResourceKey<Level> dimension, boolean vanilla) {
		String weather = of(dimension).weather();
		return weather == null ? vanilla : !weather.equals("clear");
	}

	public static boolean thundering(ResourceKey<Level> dimension, boolean vanilla) {
		String weather = of(dimension).weather();
		return weather == null ? vanilla : weather.equals("thunder");
	}

	/**
	 * Vanilla sends start/stop-rain packets from one world to every player on the server.
	 * A player only gets them if neither the sending world nor the player's world has fixed weather, or both are the same world.
	 */
	public static boolean receivesWeatherFrom(ResourceKey<Level> playerWorld, ResourceKey<Level> source) {
		if (playerWorld.equals(source)) return true;
		return of(source).weather() == null && of(playerWorld).weather() == null;
	}

	public static boolean hasFixedWeather(ResourceKey<Level> dimension) {
		return of(dimension).weather() != null;
	}

	public static void sendToWorld(PlayerList players, Packet<?> packet, ResourceKey<Level> world) {
		for (ServerPlayer player : players.getPlayers()) {
			if (player.level().dimension().equals(world)) player.connection.send(packet);
		}
	}

	/**
	 * After another world's weather packet went to everyone: players in a fixed-weather world get their own world's
	 * state again (same tick, so nothing flickers). Keeps working when another mod replaced the broadcast.
	 */
	public static void resendFixedWeather(PlayerList players, ResourceKey<Level> source) {
		for (ServerPlayer player : players.getPlayers()) {
			if (receivesWeatherFrom(player.level().dimension(), source)) continue;
			Level level = player.level();
			player.connection.send(new ClientboundGameEventPacket(level.isRaining() ? ClientboundGameEventPacket.START_RAINING : ClientboundGameEventPacket.STOP_RAINING, 0f));
			player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, level.getRainLevel(1f)));
			player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, level.getThunderLevel(1f)));
		}
	}

	/** Entering a world: show its difficulty and time, fill up food where hunger is off. */
	public static void onEnter(ServerPlayer player) {
		syncClient(player);
		if (!of(player.level().dimension()).hunger()) {
			player.getFoodData().setFoodLevel(20);
			player.getFoodData().setSaturation(5f);
		}
	}
}
