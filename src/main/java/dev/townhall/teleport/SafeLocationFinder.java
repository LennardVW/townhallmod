package dev.townhall.teleport;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Checks whether a player can stand at a position, and searches a small box around it if not.
 * Only touches the handful of chunks inside the search box; never scans beyond the configured radius.
 */
public final class SafeLocationFinder {

	private SafeLocationFinder() {}

	/** Room for the player's body, no lava/fire/magma/cactus in or under it, and ground (or water) to stand on. */
	public static boolean isSafe(ServerLevel level, ServerPlayer player, Vec3 pos) {
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

	/** The original position if safe, otherwise the closest safe block position within the radius. */
	public static Optional<Vec3> find(ServerLevel level, ServerPlayer player, Vec3 original, int horizontalRadius, int verticalRadius) {
		loadChunks(level, original, horizontalRadius);
		if (isSafe(level, player, original)) return Optional.of(original);

		BlockPos base = BlockPos.containing(original);
		List<BlockPos> candidates = new ArrayList<>();
		for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
			for (int dz = -horizontalRadius; dz <= horizontalRadius; dz++) {
				for (int dy = -verticalRadius; dy <= verticalRadius; dy++) {
					candidates.add(base.offset(dx, dy, dz));
				}
			}
		}
		candidates.sort(Comparator.comparingDouble(p -> p.distSqr(base)));
		for (BlockPos p : candidates) {
			Vec3 candidate = Vec3.atBottomCenterOf(p);
			if (isSafe(level, player, candidate)) return Optional.of(candidate);
		}
		return Optional.empty();
	}

	/** Loads (or generates) the chunks the search can touch, through the normal chunk system. */
	public static void loadChunks(ServerLevel level, Vec3 center, int radius) {
		int minX = SectionPos.blockToSectionCoord(Mth.floor(center.x) - radius);
		int maxX = SectionPos.blockToSectionCoord(Mth.floor(center.x) + radius);
		int minZ = SectionPos.blockToSectionCoord(Mth.floor(center.z) - radius);
		int maxZ = SectionPos.blockToSectionCoord(Mth.floor(center.z) + radius);
		for (int cx = minX; cx <= maxX; cx++) {
			for (int cz = minZ; cz <= maxZ; cz++) {
				level.getChunk(cx, cz);
			}
		}
	}
}
