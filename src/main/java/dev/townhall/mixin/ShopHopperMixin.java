package dev.townhall.mixin;

import dev.townhall.shop.ShopService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** HEAD guards also cover Fabric Transfer API fallback and hopper minecarts. Never consume an item on denied insertion. */
@Mixin(HopperBlockEntity.class)
abstract class ShopHopperMixin {
    @Inject(method = "suckInItems", at = @At("HEAD"), cancellable = true)
    private static void townhall$shopExtraction(Level level, Hopper hopper, CallbackInfoReturnable<Boolean> cir) {
        if (level instanceof ServerLevel server && ShopService.blocksAutomation(server,
            BlockPos.containing(hopper.getLevelX(), hopper.getLevelY() + 1, hopper.getLevelZ()))) cir.setReturnValue(false);
    }
    @Inject(method = "ejectItems", at = @At("HEAD"), cancellable = true)
    private static void townhall$shopInsertion(Level level, BlockPos pos, HopperBlockEntity hopper, CallbackInfoReturnable<Boolean> cir) {
        if (level instanceof ServerLevel server && ShopService.blocksAutomation(server,
            pos.relative(hopper.getBlockState().getValue(HopperBlock.FACING)))) cir.setReturnValue(false);
    }
    @Inject(method = "addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/Container;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/core/Direction;)Lnet/minecraft/world/item/ItemStack;",
        at = @At("HEAD"), cancellable = true)
    private static void townhall$shopDropper(Container source, Container target, ItemStack item, Direction side, CallbackInfoReturnable<ItemStack> cir) {
        if (target instanceof BlockEntity be && be.getLevel() instanceof ServerLevel server
            && ShopService.blocksAutomation(server, be.getBlockPos())) cir.setReturnValue(item);
    }
}
