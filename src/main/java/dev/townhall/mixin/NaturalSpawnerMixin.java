package dev.townhall.mixin;

import dev.townhall.dimension.DimensionSettings;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** World rule "mobs": false = no natural spawning in that world. */
@Mixin(NaturalSpawner.class)
abstract class NaturalSpawnerMixin {

	@Inject(method = "spawnForChunk", at = @At("HEAD"), cancellable = true)
	private static void townhall$noMobs(ServerLevel level, LevelChunk chunk, NaturalSpawner.SpawnState state, List<MobCategory> categories, CallbackInfo ci) {
		if (!DimensionSettings.of(level.dimension()).mobs()) ci.cancel();
	}
}
