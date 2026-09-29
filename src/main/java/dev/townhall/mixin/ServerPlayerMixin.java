package dev.townhall.mixin;

import dev.townhall.activity.Afk;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Tab list: nickname and [AFK] (vanilla returns null = the real name). Sent on join and after /nick or AFK changes. */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerMixin {

	@Inject(method = "getTabListDisplayName", at = @At("HEAD"), cancellable = true)
	private void townhall$nickname(CallbackInfoReturnable<Component> cir) {
		Component name = Afk.tabName((ServerPlayer) (Object) this);
		if (name != null) cir.setReturnValue(name);
	}
}
