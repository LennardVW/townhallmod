package dev.townhall.mixin;

import dev.townhall.shop.ShopService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.List;
import java.util.ArrayList;

@Mixin(ServerExplosion.class)
abstract class ShopExplosionMixin {
    @Shadow @Final private ServerLevel level;
    @ModifyVariable(method = "interactWithBlocks", at = @At("HEAD"), argsOnly = true)
    private List<BlockPos> townhall$shopSafe(List<BlockPos> positions) {
        if (positions.isEmpty()) return positions;
        List<BlockPos> kept = new ArrayList<>(positions.size());
        for (BlockPos pos : positions) if (!ShopService.protectedAt(level, pos)) kept.add(pos);
        // Vanilla shuffles this argument in place; the filtered list must remain mutable.
        return kept.size() == positions.size() ? positions : kept;
    }
}
