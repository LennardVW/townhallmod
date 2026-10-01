package dev.townhall.mixin;

import dev.townhall.dimension.DimensionSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** World rule "mobs": false = no natural spawning in that world, also not the animals placed when new chunks generate. */
@Mixin(NaturalSpawner.class)
abstract class NaturalSpawnerMixin {

	@Inject(method = "spawnForChunk", at = @At("HEAD"), cancellable = true)
	private static void townhall$noMobs(ServerLevel level, LevelChunk chunk, NaturalSpawner.SpawnState state, List<MobCategory> categories, CallbackInfo ci) {
		if (!DimensionSettings.of(level.dimension()).mobs()) ci.cancel();
	}

	@Inject(method = "spawnMobsForChunkGeneration", at = @At("HEAD"), cancellable = true)
	private static void townhall$noChunkGenerationMobs(ServerLevelAccessor level, BlockPos biomePos, ChunkPos chunk, RandomSource random, CallbackInfo ci) {
		if (!DimensionSettings.of(level.getLevel().dimension()).mobs()) ci.cancel();
	}
}
