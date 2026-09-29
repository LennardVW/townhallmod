package dev.townhall.mixin;

import dev.townhall.nick.Nicknames;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.server.players.NameAndId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;
import java.util.Collections;

/**
 * Plain names in profile arguments (/playtime Name, /nick reset Name, /builder add ... Name, /op Name): a nickname
 * (also of an offline player) resolves to its owner, unless some player the server knows really has that name.
 * Checked before the name cache, because in offline mode the cache invents a profile for every unknown name.
 */
@Mixin(GameProfileArgument.class)
abstract class GameProfileArgumentMixin {

	@Inject(method = "lambda$parse$0", at = @At("HEAD"), cancellable = true)
	private static void townhall$nickname(String name, CommandSourceStack source, CallbackInfoReturnable<Collection<NameAndId>> cir) {
		Nicknames.profileByNickname(source.getServer(), name).ifPresent(profile -> cir.setReturnValue(Collections.singleton(profile)));
	}
}
