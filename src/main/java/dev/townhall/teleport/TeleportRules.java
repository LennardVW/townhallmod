package dev.townhall.teleport;

import dev.townhall.TownhallMod;
import dev.townhall.config.TownhallConfig;
import dev.townhall.onboarding.Onboarding;
import dev.townhall.storage.PlayerState;
import dev.townhall.storage.ReturnPositionStorage;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Shared rules of /home and /tpa: who may teleport, cooldown between teleports and the warmup. */
public final class TeleportRules {

	/** Millisecond clock; tests replace it to let requests expire and cooldowns pass. */
	public static LongSupplier CLOCK = System::currentTimeMillis;

	private static final Map<UUID, Long> LAST = new HashMap<>();

	private TeleportRules() {}

	public static void reset() {
		LAST.clear();
		CLOCK = System::currentTimeMillis;
	}

	public static boolean isOp(ServerPlayer p) {
		return TownhallMod.isOperator(p.permissions());
	}

	/** True for worlds of a location the player can't simply leave (prison): no homes there, no teleports in or out. */
	public static boolean lockedWorld(TownhallConfig config, ResourceKey<Level> dimension) {
		return config.locations.values().stream().anyMatch(l -> !l.isEscapable() && l.dimensionKey().equals(dimension));
	}

	/** Why this player may not use home/tpa right now (rules not accepted, confined, in a locked world); empty = fine. */
	public static Optional<String> blocked(ServerPlayer p) {
		TownhallConfig config = TownhallMod.CONFIG.get();
		if (Onboarding.isRestricted(p)) return Optional.of("Bitte zuerst die Serverregeln akzeptieren.");
		PlayerState state = ReturnPositionStorage.get(p.level().getServer()).state(p.getUUID());
		if (state.confined()) return Optional.of(dev.townhall.teleport.ConfinementService.confinedMessage(config, state));
		if (lockedWorld(config, p.level().dimension())) return Optional.of("Von hier aus kannst du dich nicht teleportieren.");
		return Optional.empty();
	}

	/** Seconds the player still has to wait since their last teleport; 0 for operators or no cooldown. */
	public static long cooldownLeft(ServerPlayer p) {
		int cd = TownhallMod.CONFIG.get().teleport.cooldownSeconds;
		Long last = LAST.get(p.getUUID());
		if (cd <= 0 || last == null || isOp(p)) return 0;
		long ms = last + cd * 1000L - CLOCK.getAsLong();
		return ms <= 0 ? 0 : (ms + 999) / 1000;
	}

	public static void stamp(ServerPlayer p) {
		LAST.put(p.getUUID(), CLOCK.getAsLong());
	}

	/** Nearby safe spot for the player at the wanted position (or the position itself when safe teleport is off). */
	public static Optional<Vec3> safeSpot(ServerPlayer p, ServerLevel level, Vec3 wanted) {
		TownhallConfig.SafeTeleport st = TownhallMod.CONFIG.get().safeTeleport;
		return st.enabled ? SafeLocationFinder.find(level, p, wanted, st.horizontalRadius, st.verticalRadius) : Optional.of(wanted);
	}

	/**
	 * Runs the action now (operators, warmup 0) or after the standing-still countdown. Returns false if the player
	 * already waits for a teleport (nothing started).
	 */
	public static boolean begin(ServerPlayer p, Consumer<ServerPlayer> action) {
		if (Warmup.isWaiting(p)) return false;
		int secs = TownhallMod.CONFIG.get().teleport.warmupSeconds;
		if (secs <= 0) {
			action.accept(p);
			return true;
		}
		Warmup.start(p, secs, false, action, (pl, why) -> pl.sendSystemMessage(Component.literal(switch (why) {
			case MOVED -> "Teleport abgebrochen, du hast dich bewegt.";
			case DAMAGE -> "Teleport abgebrochen, du hast Schaden genommen.";
			case WORLD -> "Teleport abgebrochen, du hast die Welt gewechselt.";
		}).withStyle(ChatFormatting.RED)));
		return true;
	}

	public static boolean hasWarmup(ServerPlayer p) {
		return TownhallMod.CONFIG.get().teleport.warmupSeconds > 0;
	}

	public static void fail(ServerPlayer p, String message) {
		p.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.RED));
	}
}
