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
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.Optional;

/**
 * Checks whether a player can stand at a position, and searches a small box around it if not.
 * Only touches the handful of chunks inside the search box; never scans beyond the configured radius.
 * The search walks a prebuilt nearest-first offset table (no candidate list, no sorting per search).
 */
public final class SafeLocationFinder {

	private SafeLocationFinder() {}

	/**
	 * Room for the player's body, no lava/fire/magma/cactus in or under it, and ground (or water) to stand on.
	 * All parts are plain checks without side effects, so the order only changes the cost: the ground check comes
	 * before the body check because in open air (the slowest search) it fails first and saves a second collision query.
	 */
	public static boolean isSafe(ServerLevel level, ServerPlayer player, Vec3 pos) {
		return isSafe(level, player, pos, null);
	}

	/** {@code air} (may be null) only answers "no block can collide here" faster; the result is the same without it. */
	private static boolean isSafe(ServerLevel level, ServerPlayer player, Vec3 pos, AirMap air) {
		if (!level.isInWorldBounds(BlockPos.containing(pos))) return false;
		AABB body = player.getDimensions(Pose.STANDING).makeBoundingBox(pos);
		if (!level.getWorldBorder().isWithinBounds(body)) return false;
		if (!level.getFluidState(BlockPos.containing(pos)).is(FluidTags.WATER)) {
			AABB ground = new AABB(body.minX, body.minY - 1.0, body.minZ, body.maxX, body.minY, body.maxZ);
			// noCollision = no block, no entity and no world border collision; blocks can be skipped where all are air
			boolean noGround = air != null && air.allAir(ground)
					? level.noEntityCollision(player, ground) && level.noBorderCollision(player, ground)
					: level.noCollision(player, ground);
			if (noGround) return false;
		}
		if (!level.noCollision(player, body)) return false;

		for (BlockPos p : BlockPos.betweenClosed(
				Mth.floor(body.minX), Mth.floor(body.minY) - 1, Mth.floor(body.minZ),
				Mth.floor(body.maxX - 1.0E-6), Mth.floor(body.maxY - 1.0E-6), Mth.floor(body.maxZ - 1.0E-6))) {
			BlockState state = level.getBlockState(p);
			if (state.is(BlockTags.DANGEROUS_FOR_TELEPORTATION) || state.getFluidState().is(FluidTags.LAVA)) return false;
		}
		return true;
	}

	/** The original position if safe, otherwise the closest safe block position within the radius. */
	public static Optional<Vec3> find(ServerLevel level, ServerPlayer player, Vec3 original, int horizontalRadius, int verticalRadius) {
		loadChunks(level, original, horizontalRadius);
		if (isSafe(level, player, original)) return Optional.of(original);

		BlockPos base = BlockPos.containing(original);
		AirMap air = new AirMap(level, base, horizontalRadius, verticalRadius);
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int packed : offsets(horizontalRadius, verticalRadius)) {
			p.set(base.getX() + unpackX(packed), base.getY() + unpackY(packed), base.getZ() + unpackZ(packed));
			Vec3 candidate = Vec3.atBottomCenterOf(p);
			if (isSafe(level, player, candidate, air)) return Optional.of(candidate);
		}
		return Optional.empty();
	}

	/** Search order of the last used radius; the config rarely changes, so it is built once instead of per search. */
	private static Offsets cached;

	private record Offsets(int horizontal, int vertical, int[] packed) {}

	/**
	 * Every offset of the box, nearest first (squared distance to the base block); equal distances keep the order
	 * dx, then dz, then dy ascending. Packed as three signed bytes (radius is at most 16). Public for the GameTests.
	 */
	public static int[] offsets(int horizontalRadius, int verticalRadius) {
		Offsets c = cached;
		if (c != null && c.horizontal() == horizontalRadius && c.vertical() == verticalRadius) return c.packed();
		int size = (2 * horizontalRadius + 1) * (2 * horizontalRadius + 1) * (2 * verticalRadius + 1);
		long[] keyed = new long[size];
		int i = 0;
		for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
			for (int dz = -horizontalRadius; dz <= horizontalRadius; dz++) {
				for (int dy = -verticalRadius; dy <= verticalRadius; dy++) {
					// distance in the high bits, the running index below it: sorting the longs is a stable sort by distance
					keyed[i] = ((long) (dx * dx + dy * dy + dz * dz) << 32) | i;
					i++;
				}
			}
		}
		Arrays.sort(keyed);
		int[] packed = new int[size];
		int span = 2 * verticalRadius + 1, row = (2 * horizontalRadius + 1) * span;
		for (int k = 0; k < size; k++) {
			int index = (int) keyed[k];
			int dx = index / row - horizontalRadius, dz = index % row / span - horizontalRadius, dy = index % span - verticalRadius;
			packed[k] = (dx & 0xFF) << 16 | (dy & 0xFF) << 8 | (dz & 0xFF);
		}
		cached = new Offsets(horizontalRadius, verticalRadius, packed);
		return packed;
	}

	public static int unpackX(int packed) {
		return (byte) (packed >> 16);
	}

	public static int unpackY(int packed) {
		return (byte) (packed >> 8);
	}

	public static int unpackZ(int packed) {
		return (byte) packed;
	}

	/**
	 * Which blocks around the search box are air, one bit per block, read once per search from loaded chunks only.
	 * Air never collides, so a collision box whose whole block range (the range vanilla's BlockCollisions walks:
	 * one block of margin on every side) is air can skip the block part of the collision check. Blocks in chunks that
	 * aren't loaded count as "not known to be air", so those boxes take the normal check.
	 */
	static final class AirMap {
		private final int minX, minY, minZ, sizeX, sizeY, sizeZ;
		/** Per column (x, z): bit y - minY set = not air (or unknown). */
		private final long[] solid;

		AirMap(ServerLevel level, BlockPos base, int horizontalRadius, int verticalRadius) {
			// candidates reach ±radius; their collision boxes reach 3 blocks down (ground) and 3 up (body), plus the margin
			minX = base.getX() - horizontalRadius - 1;
			minZ = base.getZ() - horizontalRadius - 1;
			minY = base.getY() - verticalRadius - 3;
			sizeX = 2 * horizontalRadius + 3;
			sizeZ = 2 * horizontalRadius + 3;
			sizeY = Math.min(64, 2 * verticalRadius + 7);
			solid = new long[sizeX * sizeZ];
			BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
			LevelChunk chunk = null;
			for (int x = 0; x < sizeX; x++) {
				for (int z = 0; z < sizeZ; z++) {
					int bx = minX + x, bz = minZ + z;
					if (chunk == null || chunk.getPos().x() != SectionPos.blockToSectionCoord(bx) || chunk.getPos().z() != SectionPos.blockToSectionCoord(bz)) {
						chunk = level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(bx), SectionPos.blockToSectionCoord(bz));
					}
					long mask = 0;
					if (chunk == null) {
						mask = -1L;
					} else {
						for (int y = 0; y < sizeY; y++) {
							if (!chunk.getBlockState(p.set(bx, minY + y, bz)).isAir()) mask |= 1L << y;
						}
					}
					solid[x * sizeZ + z] = mask;
				}
			}
		}

		/** True if every block BlockCollisions would look at for this box is known to be air. */
		boolean allAir(AABB box) {
			int x0 = Mth.floor(box.minX - 1.0E-7) - 1 - minX, x1 = Mth.floor(box.maxX + 1.0E-7) + 1 - minX;
			int y0 = Mth.floor(box.minY - 1.0E-7) - 1 - minY, y1 = Mth.floor(box.maxY + 1.0E-7) + 1 - minY;
			int z0 = Mth.floor(box.minZ - 1.0E-7) - 1 - minZ, z1 = Mth.floor(box.maxZ + 1.0E-7) + 1 - minZ;
			if (x0 < 0 || z0 < 0 || y0 < 0 || x1 >= sizeX || z1 >= sizeZ || y1 >= sizeY) return false;
			long bits = (y1 - y0 == 63 ? -1L : ((1L << (y1 - y0 + 1)) - 1)) << y0;
			for (int x = x0; x <= x1; x++) {
				for (int z = z0; z <= z1; z++) {
					if ((solid[x * sizeZ + z] & bits) != 0) return false;
				}
			}
			return true;
		}
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
