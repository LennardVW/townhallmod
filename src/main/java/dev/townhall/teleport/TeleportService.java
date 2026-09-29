package dev.townhall.teleport;

import dev.townhall.TownhallMod;
import dev.townhall.config.TownhallConfig;
import dev.townhall.config.TownhallConfig.Location;
import dev.townhall.storage.PlayerState;
import dev.townhall.storage.ReturnLocation;
import dev.townhall.storage.ReturnPositionStorage;
import dev.townhall.util.Text;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.Set;

/** All teleports. Called from commands and player events, so always on the server thread. */
public final class TeleportService {

	public enum EnterResult { SENT, ALREADY_THERE, UNAVAILABLE, CONFINED, FAILED }

	public enum ReturnResult { EXACT, NEARBY, FALLBACK, NO_POSITION, CONFINED, FAILED }

	private TeleportService() {}

	/**
	 * Sends the player to a location.
	 * The return position is only saved when they come from outside all location worlds, so moving
	 * Townhall → prison → Townhall keeps the original spot in the overworld.
	 *
	 * @param byOperator     the command came from an operator (send, or an op using the command); ignores confinement
	 * @param sentByOther    an operator sent someone else
	 * @param durationMillis timed stay: sent back automatically after this much online time
	 */
	public static EnterResult sendTo(ServerPlayer player, String locationId, TownhallConfig config, boolean byOperator, boolean sentByOther,
			Optional<Long> durationMillis) {
		Location location = config.location(locationId).orElseThrow();
		MinecraftServer server = player.level().getServer();
		ReturnPositionStorage storage = ReturnPositionStorage.get(server);
		PlayerState state = storage.state(player.getUUID());

		if (state.confined() && !byOperator) return EnterResult.CONFINED;
		boolean inLocationWorld = config.isLocationDimension(player.level().dimension());
		if (inLocationWorld && player.level().dimension().equals(location.dimensionKey())
				&& state.location().map(locationId::equals).orElse(true) && !sentByOther) {
			return EnterResult.ALREADY_THERE;
		}

		ServerLevel target = server.getLevel(location.dimensionKey());
		if (target == null) return EnterResult.UNAVAILABLE;

		ReturnLocation before = ReturnLocation.of(player);
		if (!teleportToSpawn(player, location)) return EnterResult.FAILED;

		PlayerState next = inLocationWorld ? state : state.withReturnPosition(before);
		// Operators are never locked in (also when they send themselves); everyone else is, if the location isn't escapable.
		boolean confined = !location.isEscapable() && !TownhallMod.isOperator(player.permissions());
		storage.set(player.getUUID(), next.withStay(locationId, confined, durationMillis));
		Text.title(player, location.title, location.subtitle);
		if (config.debugLogging) TownhallMod.LOGGER.info("{} was sent to {} from {}", player.getPlainTextName(), locationId, before);
		return EnterResult.SENT;
	}

	/** Brings the player back to the stored position, a safe spot next to it, or the fallback. */
	public static ReturnResult returnPlayer(ServerPlayer player, TownhallConfig config, boolean byOperator) {
		MinecraftServer server = player.level().getServer();
		ReturnPositionStorage storage = ReturnPositionStorage.get(server);
		PlayerState state = storage.state(player.getUUID());
		if (state.confined() && !byOperator) return ReturnResult.CONFINED;
		if (state.returnPosition().isEmpty()) return ReturnResult.NO_POSITION;
		ReturnLocation loc = state.returnPosition().get();

		ServerLevel level = server.getLevel(loc.dimension());
		ReturnResult result;
		boolean ok;
		if (level == null) {
			TownhallMod.LOGGER.warn("Stored dimension {} of {} no longer exists, using fallback", loc.dimension().identifier(), player.getPlainTextName());
			result = ReturnResult.FALLBACK;
			ok = sendToFallback(player, config);
		} else {
			Vec3 original = new Vec3(loc.x(), loc.y(), loc.z());
			Optional<Vec3> target = config.safeTeleport.enabled
					? SafeLocationFinder.find(level, player, original, config.safeTeleport.horizontalRadius, config.safeTeleport.verticalRadius)
					: Optional.of(original);
			if (target.isPresent()) {
				result = target.get().equals(original) ? ReturnResult.EXACT : ReturnResult.NEARBY;
				SafeLocationFinder.loadChunks(level, target.get(), 0);
				ok = teleport(player, level, target.get(), loc.yaw(), loc.pitch());
			} else {
				result = ReturnResult.FALLBACK;
				ok = sendToFallback(player, config);
			}
		}
		if (!ok) {
			TownhallMod.LOGGER.error("Teleport of {} back to {} failed", player.getPlainTextName(), loc);
			return ReturnResult.FAILED;
		}
		storage.set(player.getUUID(), config.returnSettings.clearAfterSuccessfulReturn ? PlayerState.EMPTY : state.left());
		return result;
	}

	/** Puts the player at a location's spawn without touching their stored state. */
	public static boolean teleportToSpawn(ServerPlayer player, Location location) {
		ServerLevel level = player.level().getServer().getLevel(location.dimensionKey());
		if (level == null) return false;
		TownhallConfig.Spot spawn = location.spawn;
		Vec3 pos = new Vec3(spawn.x, spawn.y, spawn.z);
		SafeLocationFinder.loadChunks(level, pos, 0);
		return teleport(player, level, pos, spawn.yaw, spawn.pitch);
	}

	/** World spawn (or the fixed fallback position from the config). */
	public static boolean sendToFallback(ServerPlayer player, TownhallConfig config) {
		MinecraftServer server = player.level().getServer();
		TownhallConfig.Fallback fb = config.fallback;
		ServerLevel level;
		Vec3 pos;
		float yaw;
		float pitch;
		if (fb.useWorldSpawn) {
			LevelData.RespawnData spawn = server.getRespawnData();
			level = server.getLevel(spawn.dimension());
			pos = Vec3.atBottomCenterOf(spawn.pos());
			yaw = spawn.yaw();
			pitch = spawn.pitch();
		} else {
			level = server.getLevel(config.fallbackDimension());
			pos = new Vec3(fb.position.x, fb.position.y, fb.position.z);
			yaw = fb.position.yaw;
			pitch = fb.position.pitch;
		}
		if (level == null) level = server.overworld();

		Optional<Vec3> safe = config.safeTeleport.enabled
				? SafeLocationFinder.find(level, player, pos, config.safeTeleport.horizontalRadius, config.safeTeleport.verticalRadius)
				: Optional.of(pos);
		if (safe.isEmpty()) {
			BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos.containing(pos));
			safe = Optional.of(Vec3.atBottomCenterOf(top));
		}
		SafeLocationFinder.loadChunks(level, safe.get(), 0);
		return teleport(player, level, safe.get(), yaw, pitch);
	}

	private static boolean teleport(ServerPlayer player, ServerLevel level, Vec3 pos, float yaw, float pitch) {
		if (player.isPassenger()) player.stopRiding();
		boolean ok = player.teleportTo(level, pos.x, pos.y, pos.z, Set.of(), yaw, pitch, true);
		if (ok) {
			player.setDeltaMovement(Vec3.ZERO);
			player.resetFallDistance();
		}
		return ok;
	}
}
