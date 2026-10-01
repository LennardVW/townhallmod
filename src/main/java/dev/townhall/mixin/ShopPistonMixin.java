package dev.townhall.mixin;

import dev.townhall.shop.ShopService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PistonBaseBlock.class)
abstract class ShopPistonMixin {
    @Inject(method = "isPushable", at = @At("HEAD"), cancellable = true)
    private static void townhall$shopImmovable(BlockState state, Level level, BlockPos pos, Direction direction,
        boolean destroy, Direction pistonDirection, CallbackInfoReturnable<Boolean> cir) {
        if (level instanceof ServerLevel server && ShopService.protectedAt(server, pos)) cir.setReturnValue(false);
    }
}
