package dev.townhall.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.townhall.nick.Nicknames;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Plain player names in entity arguments (/tp Name, /msg Name, /give Name ...): if no online player has that real name,
 * a nickname of an online player counts too. Real names always win (online, and also names the server knows from before).
 */
@Mixin(EntitySelector.class)
abstract class EntitySelectorMixin {

	@WrapOperation(method = {"findEntities", "findPlayers"}, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/server/players/PlayerList;getPlayerByName(Ljava/lang/String;)Lnet/minecraft/server/level/ServerPlayer;"))
	private ServerPlayer townhall$nickname(PlayerList players, String name, Operation<ServerPlayer> original) {
		ServerPlayer real = original.call(players, name);
		return real != null ? real : Nicknames.onlineByNickname(players.getServer(), name).orElse(null);
	}
}
