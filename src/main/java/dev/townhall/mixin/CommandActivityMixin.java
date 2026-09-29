package dev.townhall.mixin;

import com.mojang.brigadier.ParseResults;
import dev.townhall.activity.Afk;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Any command a player runs (chat, signed, sign click) shows they are here and ends AFK. /afk itself doesn't. */
@Mixin(Commands.class)
abstract class CommandActivityMixin {

	@Inject(method = "performCommand", at = @At("HEAD"))
	private void townhall$markActive(ParseResults<CommandSourceStack> parse, String command, CallbackInfo ci) {
		if (parse.getContext().getSource().getEntity() instanceof ServerPlayer player && !Afk.isAfkCommand(command)) Afk.active(player);
	}
}
