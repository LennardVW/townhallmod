package dev.townhall.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.commands.synchronization.SuggestionProviders;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Tab completion of player names: vanilla clients complete entity and profile arguments themselves from their tab list,
 * where nicknamed players only have an invisible profile name (NickPackets). Marking these arguments "ask_server" in the
 * command tree makes the client ask the server instead, which suggests real names and nicknames
 * (CommandSourceStackMixin). Only arguments without own suggestions are changed.
 */
@Mixin(targets = "net.minecraft.commands.Commands$1")
abstract class CommandNodeInspectorMixin {

	@ModifyReturnValue(method = "suggestionId", at = @At("RETURN"))
	private Identifier townhall$askServerForNames(Identifier id, ArgumentCommandNode<CommandSourceStack, ?> node) {
		if (id != null) return id;
		return node.getType() instanceof EntityArgument || node.getType() instanceof GameProfileArgument
				? SuggestionProviders.getName(SuggestionProviders.ASK_SERVER) : null;
	}
}
