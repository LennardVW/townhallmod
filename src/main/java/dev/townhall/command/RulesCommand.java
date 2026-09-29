package dev.townhall.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.townhall.onboarding.Onboarding;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * /rules (or /regeln): show the rules. /rules accept (or /regeln akzeptieren): accept them.
 * Operators: /rules reset &lt;player&gt; makes a player read and accept them again.
 */
public final class RulesCommand {

	private RulesCommand() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		for (String root : Onboarding.COMMANDS) {
			dispatcher.register(Commands.literal(root)
					.executes(RulesCommand::show)
					.then(Commands.literal("accept").executes(RulesCommand::accept))
					.then(Commands.literal("akzeptieren").executes(RulesCommand::accept))
					.then(Commands.literal("reset").requires(TownhallCommand::isOperator)
							.then(Commands.argument("player", EntityArgument.player()).executes(RulesCommand::reset))));
		}
	}

	private static int show(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		Onboarding.showRules(ctx.getSource().getPlayerOrException());
		return 1;
	}

	private static int accept(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		if (!Onboarding.accept(player)) ctx.getSource().sendFailure(Component.literal("You already accepted the rules."));
		return 1;
	}

	private static int reset(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
		Onboarding.reset(target);
		ctx.getSource().sendSuccess(() -> Component.literal(target.getPlainTextName() + " has to accept the rules again."), true);
		return 1;
	}
}
