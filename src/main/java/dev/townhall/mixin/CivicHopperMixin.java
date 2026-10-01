package dev.townhall.mixin;

import dev.townhall.city.CivicSites;
import dev.townhall.city.PlotService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(HopperBlockEntity.class)
abstract class CivicHopperMixin {
	@Inject(method="suckInItems",at=@At("HEAD"),cancellable=true)
	private static void townhall$civicExtraction(Level level,Hopper hopper,CallbackInfoReturnable<Boolean> cir) {
		if (!(level instanceof ServerLevel server)) return;
		BlockPos target=BlockPos.containing(hopper.getLevelX(),hopper.getLevelY(),hopper.getLevelZ()), source=target.above();
		if (CivicSites.protectedAt(server,source) || PlotService.deniesContainerTransfer(server,source,target)) cir.setReturnValue(false);
	}
	@Inject(method="ejectItems",at=@At("HEAD"),cancellable=true)
	private static void townhall$civicEjection(Level level,BlockPos source,HopperBlockEntity hopper,CallbackInfoReturnable<Boolean> cir) {
		if (!(level instanceof ServerLevel server)) return;
		BlockPos target=source.relative(hopper.getBlockState().getValue(HopperBlock.FACING));
		if (CivicSites.protectedAt(server,target) || PlotService.deniesContainerTransfer(server,source,target)) cir.setReturnValue(false);
	}
	@Inject(method="addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/Container;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/core/Direction;)Lnet/minecraft/world/item/ItemStack;",at=@At("HEAD"),cancellable=true)
	private static void townhall$civicInsertion(Container from,Container to,ItemStack stack,Direction direction,CallbackInfoReturnable<ItemStack> cir) {
		if (to instanceof BlockEntity target && target.getLevel() instanceof ServerLevel level) {
			if (CivicSites.protectedAt(level,target.getBlockPos()) || (from instanceof BlockEntity source && PlotService.deniesContainerTransfer(level,source.getBlockPos(),target.getBlockPos()))) cir.setReturnValue(stack);
		}
	}
}
