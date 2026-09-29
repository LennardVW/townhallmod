package dev.townhall.mixin;

import dev.townhall.dimension.DimensionSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.List;

/** World rule "explosions": false = no blocks are destroyed (damage to entities stays). Chains with other mods (ChestLock). */
@Mixin(ServerExplosion.class)
abstract class ServerExplosionMixin {

	@Shadow
	@Final
	private ServerLevel level;

	@ModifyVariable(method = "interactWithBlocks", at = @At("HEAD"), argsOnly = true)
	private List<BlockPos> townhall$noBlockDamage(List<BlockPos> blocks) {
		return DimensionSettings.of(level.dimension()).explosions() ? blocks : List.of();
	}
}
