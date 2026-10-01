package dev.townhall.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.townhall.protection.Protection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ChestBlock.class)
abstract class CivicChestMixin {
	@ModifyReturnValue(method="getStateForPlacement",at=@At("RETURN"))
	private BlockState townhall$plotChest(BlockState state,BlockPlaceContext context) {
		if (state == null || state.getValue(ChestBlock.TYPE)==ChestType.SINGLE || !(context.getPlayer() instanceof ServerPlayer player)) return state;
		return Protection.mayBuildAt(player,player.level(),ChestBlock.getConnectedBlockPos(context.getClickedPos(),state))
				? state : state.setValue(ChestBlock.TYPE,ChestType.SINGLE);
	}
}
