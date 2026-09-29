package dev.townhall.mixin;

import com.mojang.brigadier.ParseResults;
import dev.townhall.TownhallMod;
import dev.townhall.command.TownhallCommand;
import dev.townhall.storage.PlayerState;
import dev.townhall.storage.ReturnPositionStorage;
import dev.townhall.onboarding.Onboarding;
import dev.townhall.teleport.ConfinementService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Locale;

/**
 * Confined players may only run the commands in confinement.allowedCommands.
 * This also stops teleport commands from other mods (/home, /spawn, /tpa, ...), which this mod can't know about.
 * Every player command (chat and signed) goes through performCommand.
 */
@Mixin(Commands.class)
abstract class CommandsMixin {

	@Inject(method = "performCommand", at = @At("HEAD"), cancellable = true)
	private void townhall$limitConfinedPlayers(ParseResults<CommandSourceStack> parse, String command, CallbackInfo ci) {
		CommandSourceStack source = parse.getContext().getSource();
		if (!(source.getEntity() instanceof ServerPlayer player) || TownhallCommand.isOperator(source)
				|| TownhallMod.isOperator(player.permissions())) return;
		String root = command.startsWith("/") ? command.substring(1) : command;
		int space = root.indexOf(' ');
		root = (space < 0 ? root : root.substring(0, space)).toLowerCase(Locale.ROOT);

		// Rules not accepted yet: only the rules commands work.
		if (Onboarding.isRestricted(player)) {
			if (Onboarding.COMMANDS.contains(root)) return;
			Onboarding.remind(player);
			ci.cancel();
			return;
		}
		PlayerState state = ReturnPositionStorage.get(source.getServer()).state(player.getUUID());
		if (!state.confined()) return;
		var config = TownhallMod.CONFIG.get();
		for (String allowed : config.confinement.allowedCommands) {
			if (allowed.equalsIgnoreCase(root)) return;
		}
		source.sendFailure(Component.literal(config.messages.commandBlocked.formatted(root) + " " + ConfinementService.confinedMessage(config, state)));
		ci.cancel();
	}
}
