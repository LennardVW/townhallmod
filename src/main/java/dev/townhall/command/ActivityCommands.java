package dev.townhall.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.townhall.TownhallMod;
import dev.townhall.activity.Afk;
import dev.townhall.activity.Playtime;
import dev.townhall.nick.Nicknames;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.players.NameAndId;

/**
 * <pre>
 * /afk                        mark yourself AFK
 * /playtime [player]          active play time (also offline players; nicknames work as the name)
 * /playtime top               top 10
 * Names are shown as nicknames (with colors); hovering shows the real player.
 * /townhall afktime &lt;minutes&gt; operator: minutes without activity until AFK (0 = only /afk)   (in TownhallCommand)
 * </pre>
 */
public final class ActivityCommands {

	private ActivityCommands() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("afk").executes(ctx -> {
			Afk.setAfk(ctx.getSource().getPlayerOrException());
			return 1;
		}));
		dispatcher.register(Commands.literal("playtime")
				.executes(ctx -> show(ctx, ctx.getSource().getPlayerOrException().nameAndId()))
				.then(Commands.literal("top").executes(ActivityCommands::top))
				.then(Commands.argument("player", GameProfileArgument.gameProfile()).executes(ctx -> {
					int shown = 0;
					for (NameAndId p : GameProfileArgument.getGameProfiles(ctx, "player")) shown += show(ctx, p);
					return shown;
				})));
	}

	private static int show(CommandContext<CommandSourceStack> ctx, NameAndId player) {
		var entry = Playtime.get(ctx.getSource().getServer()).of(player.id());
		if (entry.isEmpty()) {
			ctx.getSource().sendFailure(Component.empty().append(Nicknames.displayName(ctx.getSource().getServer(), player.id(), player.name()))
					.append(" hat hier noch nicht gespielt."));
			return 0;
		}
		Component name = Nicknames.displayName(ctx.getSource().getServer(), player.id(), player.name());
		ctx.getSource().sendSuccess(() -> Component.empty().withStyle(ChatFormatting.GOLD).append(name).append(": ")
				.append(Component.literal(Playtime.format(entry.get().millis())).withStyle(ChatFormatting.WHITE))
				.append(Component.literal(" gespielt").withStyle(ChatFormatting.GRAY)), false);
		return 1;
	}

	private static int top(CommandContext<CommandSourceStack> ctx) {
		var top = Playtime.get(ctx.getSource().getServer()).top(10);
		MutableComponent text = Component.literal("Meiste Spielzeit").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
		int place = 1;
		for (var e : top) {
			ChatFormatting color = switch (place) {
				case 1 -> ChatFormatting.YELLOW;
				case 2 -> ChatFormatting.WHITE;
				case 3 -> ChatFormatting.GOLD;
				default -> ChatFormatting.GRAY;
			};
			text.append(Component.literal("\n" + place + ". ").withStyle(color))
					.append(Component.empty().withStyle(ChatFormatting.WHITE).append(Nicknames.displayName(ctx.getSource().getServer(), e.getKey(), e.getValue().name())))
					.append(Component.literal("  " + Playtime.format(e.getValue().millis())).withStyle(ChatFormatting.GRAY));
			place++;
		}
		if (top.isEmpty()) text.append(Component.literal("\nNoch niemand.").withStyle(ChatFormatting.GRAY));
		ctx.getSource().sendSuccess(() -> text, false);
		return top.size();
	}

	static int setAfkTime(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		int minutes = IntegerArgumentType.getInteger(ctx, "minutes");
		TownhallMod.CONFIG.get().afkMinutes = minutes;
		if (!TownhallCommand.saveConfig(ctx.getSource())) return 0;
		ctx.getSource().sendSuccess(() -> Component.literal(minutes == 0 ? "Automatisches AFK ist aus (nur /afk)." : "Spieler gelten nach " + minutes + " Minuten ohne Aktivität als AFK.")
				.withStyle(ChatFormatting.GREEN), true);
		return 1;
	}
}
