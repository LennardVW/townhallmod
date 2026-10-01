package dev.townhall.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.townhall.dimension.DimensionSettings;
import dev.townhall.nick.Nicknames;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Worlds with "hunger": false: running, jumping, fighting and healing don't use up food.
 * Nicknames: getDisplayName (chat, death and join messages) uses the nickname instead of the real name;
 * team colors and the hover/click with the real player stay as in vanilla.
 */
@Mixin(Player.class)
abstract class PlayerMixin {

	@ModifyExpressionValue(method = "getDisplayName", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;getName()Lnet/minecraft/network/chat/Component;"))
	private Component townhall$nickname(Component name) {
		return (Object) this instanceof ServerPlayer player ? dev.townhall.city.RoleService.decorate(player, Nicknames.of(player.getUUID()).orElse(name), false) : name;
	}

	@Inject(method = "causeFoodExhaustion", at = @At("HEAD"), cancellable = true)
	private void townhall$noHunger(float amount, CallbackInfo ci) {
		if ((Object) this instanceof ServerPlayer player && !DimensionSettings.of(player.level().dimension()).hunger()) ci.cancel();
	}
}
