package dev.townhall.teleport;

import dev.townhall.TownhallMod;
import dev.townhall.config.TownhallConfig;
import dev.townhall.config.TownhallConfig.Location;
import dev.townhall.onboarding.Onboarding;
import dev.townhall.storage.PlayerState;
import dev.townhall.storage.ReturnPositionStorage;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Keeps confined players inside their location and counts down timed stays.
 * Runs once per second and only looks at players who are confined or timed, never at everyone.
 */
public final class ConfinementService {

	private static final int INTERVAL_TICKS = 20;
	/** A lag spike or a paused server must not eat a big chunk of someone's sentence at once. */
	private static final long MAX_STEP_MILLIS = 5_000;

	private static int ticks;
	/** System.nanoTime of the last pass (monotonic: a changed system clock can't add or eat time); 0 = no pass yet. */
	private static long lastRunNanos;

	private ConfinementService() {}

	/** Fresh schedule for a (re)started server; called by ActivityService on SERVER_STARTED. */
	public static void reset() {
		ticks = 0;
		lastRunNanos = 0;
	}

	public static void onServerTick(MinecraftServer server) {
		if (++ticks < INTERVAL_TICKS) return;
		ticks = 0;
		long now = System.nanoTime();
		long elapsed = lastRunNanos == 0 ? 0 : Math.clamp((now - lastRunNanos) / 1_000_000, 0, MAX_STEP_MILLIS);
		lastRunNanos = now;
		check(server, elapsed);
		Onboarding.check(server);
	}

	/** One check pass; {@code elapsedMillis} of online time is subtracted from every online timed player. */
	public static void check(MinecraftServer server, long elapsedMillis) {
		TownhallConfig config = TownhallMod.CONFIG.get();
		ReturnPositionStorage storage = ReturnPositionStorage.get(server);
		for (UUID id : storage.activePlayers()) {
			ServerPlayer player = server.getPlayerList().getPlayer(id);
			if (player == null || !player.isAlive()) continue; // offline: the clock stops
			PlayerState state = storage.state(id);
			Optional<Location> location = state.location().flatMap(config::location);

			if (state.remainingMillis().isPresent()) {
				long left = state.remainingMillis().get() - elapsedMillis;
				if (left <= 0) {
					release(player, config);
					continue;
				}
				storage.set(id, state.withRemaining(left));
				String name = state.location().map(config::displayName).orElse("");
				player.sendOverlayMessage(Component.literal(config.messages.timeLeft.formatted(capitalize(name), formatDuration(left))).withStyle(ChatFormatting.YELLOW));
			}
			if (state.confined() && location.isPresent() && !TownhallMod.isOperator(player.permissions()) && !isInside(player, location.get())) {
				if (TeleportService.teleportToSpawn(player, location.get())) {
					player.sendSystemMessage(Component.literal(config.messages.pulledBack.formatted(config.displayName(state.location().get()))).withStyle(ChatFormatting.RED));
				}
			}
		}
	}

	/** Puts a confined player back at their location's spawn, e.g. right after they respawned somewhere else. */
	public static void keepConfined(ServerPlayer player, TownhallConfig config) {
		PlayerState state = ReturnPositionStorage.get(player.level().getServer()).state(player.getUUID());
		if (!state.confined() || TownhallMod.isOperator(player.permissions())) return;
		state.location().flatMap(config::location).ifPresent(location -> TeleportService.teleportToSpawn(player, location));
	}

	/** Time is up: back to the stored position (or the fallback), confinement and timer cleared. */
	public static void release(ServerPlayer player, TownhallConfig config) {
		ReturnPositionStorage storage = ReturnPositionStorage.get(player.level().getServer());
		TeleportService.ReturnResult result = TeleportService.returnPlayer(player, config, true);
		if (result == TeleportService.ReturnResult.NO_POSITION) {
			TeleportService.sendToFallback(player, config);
			storage.set(player.getUUID(), PlayerState.EMPTY);
			TeleportService.resendCommands(player);
		} else if (result == TeleportService.ReturnResult.FAILED) {
			TownhallMod.LOGGER.error("Could not release {}, will retry", player.getPlainTextName());
			return;
		}
		player.sendSystemMessage(Component.literal(config.messages.released).withStyle(ChatFormatting.GREEN));
		TownhallMod.LOGGER.info("{} was released after their timed stay", player.getPlainTextName());
	}

	public static boolean isInside(ServerPlayer player, Location location) {
		if (!player.level().dimension().equals(location.dimensionKey())) return false;
		if (location.confineRadius <= 0) return true;
		double dx = player.getX() - location.spawn.x;
		double dz = player.getZ() - location.spawn.z;
		return dx * dx + dz * dz <= (double) location.confineRadius * location.confineRadius;
	}

	/** Message for a confined player trying to leave, with the time left if the stay is timed. */
	public static String confinedMessage(TownhallConfig config, PlayerState state) {
		String where = state.location().map(config::displayName).orElse("this place");
		return state.remainingMillis()
				.map(ms -> config.messages.confinedTimed.formatted(where, formatDuration(ms)))
				.orElseGet(() -> config.messages.confined.formatted(where));
	}

	/** 75_000 → "1:15", 3_725_000 → "1:02:05". */
	public static String formatDuration(long millis) {
		long total = (millis + 999) / 1000;
		long h = total / 3600, m = total % 3600 / 60, s = total % 60;
		return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) : String.format(Locale.ROOT, "%d:%02d", m, s);
	}

	private static String capitalize(String s) {
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}
}
