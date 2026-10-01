package dev.townhall.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.townhall.protection.Protection;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * "build": false also holds against projectiles from players who can't build there: arrows, tridents, snowballs etc.
 * fly through item frames, paintings, armor stands, boats and minecarts, and don't trigger block effects
 * (decorated pots, chorus flowers, pointed dripstone, TNT, campfires, targets). Projectiles without a player owner
 * (dispensers, skeletons) are unchanged.
 */
@Mixin(Projectile.class)
abstract class ProjectileMixin {

	@WrapOperation(method = "onHit", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/Projectile;onHitEntity(Lnet/minecraft/world/phys/EntityHitResult;)V"))
	private void townhall$protectWorldObjects(Projectile projectile, EntityHitResult hit, Operation<Void> original) {
		if (Protection.projectileMayHit(projectile, hit.getEntity())) original.call(projectile, hit);
	}

	@WrapOperation(method = "onHitBlock", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;onProjectileHit(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/phys/BlockHitResult;Lnet/minecraft/world/entity/projectile/Projectile;)V"))
	private void townhall$protectBlocks(BlockState state, Level level, BlockState same, BlockHitResult hit, Projectile projectile, Operation<Void> original) {
		if (Protection.projectileMayChangeBlocks(projectile, hit.getBlockPos())) original.call(state, level, same, hit, projectile);
	}
}
