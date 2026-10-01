package dev.townhall.mixin;

import dev.townhall.dimension.DimensionSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.List;

/**
 * World rule "explosions": false = explosions break no blocks and light no fire (damage to entities stays).
 * Explosions that only trigger blocks (wind charges pressing buttons, opening doors, ringing bells) keep working.
 * Only @ModifyVariable on the parameters, so this chains with other mods (ChestLock) and other townhall mixins.
 */
@Mixin(ServerExplosion.class)
abstract class ServerExplosionMixin {

	@Shadow
	@Final
	private ServerLevel level;

	@Shadow
	@Final
	private Explosion.BlockInteraction blockInteraction;

	@ModifyVariable(method = "interactWithBlocks", at = @At("HEAD"), argsOnly = true)
	private List<BlockPos> townhall$noBlockDamage(List<BlockPos> blocks) {
		boolean destroys = blockInteraction == Explosion.BlockInteraction.DESTROY || blockInteraction == Explosion.BlockInteraction.DESTROY_WITH_DECAY;
		return destroys && !DimensionSettings.of(level.dimension()).explosions() ? List.of() : blocks;
	}

	@ModifyVariable(method = "createFire", at = @At("HEAD"), argsOnly = true)
	private List<BlockPos> townhall$noFire(List<BlockPos> blocks) {
		return DimensionSettings.of(level.dimension()).explosions() ? blocks : List.of();
	}
}
