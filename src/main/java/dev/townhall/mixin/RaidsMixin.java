package dev.townhall.mixin;

import dev.townhall.dimension.DimensionSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** World rule "mobs": false = Raid Omen starts no raid in that world (vanilla returns null the same way for spectators). */
@Mixin(Raids.class)
abstract class RaidsMixin {

	@Inject(method = "createOrExtendRaid", at = @At("HEAD"), cancellable = true)
	private void townhall$noRaids(ServerPlayer player, BlockPos pos, CallbackInfoReturnable<Raid> cir) {
		if (!DimensionSettings.of(player.level().dimension()).mobs()) cir.setReturnValue(null);
	}
}
