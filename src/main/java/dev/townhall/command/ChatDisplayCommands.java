package dev.townhall.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.townhall.TownhallMod;
import dev.townhall.config.TownhallConfig;
import dev.townhall.display.JoinMessages;
import dev.townhall.display.TabList;
import dev.townhall.util.Text;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.players.NameAndId;

import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Operator-only, all saved to the config at once:
 * <pre>
 * /joinmessage join|leave|firstjoin &lt;text&gt;   server-wide texts, {player} = name; "-" = no message
 * /joinmessage set &lt;player&gt; &lt;text&gt;         personal join message
 * /joinmessage reset &lt;player&gt;
 * /joinmessage on|off                       off = vanilla messages
 * /tablist header|footer &lt;text&gt;             "|" starts a new line; "-" = empty
 * /tablist on|off
 * </pre>
 */
public final class ChatDisplayCommands {

	private ChatDisplayCommands() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("joinmessage").requires(TownhallCommand::isOperator)
				.then(text("join", (c, v) -> c.joinMessages.join = v))
				.then(text("leave", (c, v) -> c.joinMessages.leave = v))
				.then(text("firstjoin", (c, v) -> c.joinMessages.firstJoin = v))
				.then(Commands.literal("set").then(Commands.argument("player", GameProfileArgument.gameProfile())
						.then(Commands.argument("text", StringArgumentType.greedyString()).executes(ChatDisplayCommands::setPersonal))))
				.then(Commands.literal("reset").then(Commands.argument("player", GameProfileArgument.gameProfile()).executes(ChatDisplayCommands::resetPersonal)))
				.then(Commands.literal("on").executes(ctx -> toggleJoin(ctx, true)))
				.then(Commands.literal("off").executes(ctx -> toggleJoin(ctx, false))));
		dispatcher.register(Commands.literal("tablist").requires(TownhallCommand::isOperator)
				.then(lines("header", (c, v) -> c.tabList.header = v))
				.then(lines("footer", (c, v) -> c.tabList.footer = v))
				.then(Commands.literal("on").executes(ctx -> toggleTab(ctx, true)))
				.then(Commands.literal("off").executes(ctx -> toggleTab(ctx, false))));
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> text(String name, BiConsumer<TownhallConfig, String> setter) {
		return Commands.literal(name).then(Commands.argument("text", StringArgumentType.greedyString()).executes(ctx -> {
			String value = StringArgumentType.getString(ctx, "text");
			setter.accept(TownhallMod.CONFIG.get(), value.equals("-") ? "" : value);
			TownhallMod.CONFIG.save();
			return ok(ctx, Component.literal(name + " message: ").withStyle(ChatFormatting.GREEN)
					.append(value.equals("-") ? Component.literal("none") : JoinMessages.format(value, ctx.getSource().getDisplayName())));
		}));
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> lines(String name, BiConsumer<TownhallConfig, List<String>> setter) {
		return Commands.literal(name).then(Commands.argument("text", StringArgumentType.greedyString()).executes(ctx -> {
			String value = StringArgumentType.getString(ctx, "text");
			List<String> lines = value.equals("-") ? List.of() : Arrays.asList(value.split("\\|", -1));
			setter.accept(TownhallMod.CONFIG.get(), lines);
			TownhallMod.CONFIG.save();
			TabList.update(ctx.getSource().getServer());
			return ok(ctx, Component.literal("Tab list " + name + " set (" + lines.size() + " lines).").withStyle(ChatFormatting.GREEN));
		}));
	}

	private static int setPersonal(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		String text = StringArgumentType.getString(ctx, "text");
		for (NameAndId p : GameProfileArgument.getGameProfiles(ctx, "player")) {
			TownhallMod.CONFIG.get().joinMessages.players.put(p.id().toString(), text);
		}
		TownhallMod.CONFIG.save();
		return ok(ctx, Component.literal("Personal join message: ").withStyle(ChatFormatting.GREEN).append(Text.of(text)));
	}

	private static int resetPersonal(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		int removed = 0;
		for (NameAndId p : GameProfileArgument.getGameProfiles(ctx, "player")) {
			if (TownhallMod.CONFIG.get().joinMessages.players.remove(p.id().toString()) != null) removed++;
		}
		TownhallMod.CONFIG.save();
		return ok(ctx, Component.literal(removed == 0 ? "No personal join message set." : "Personal join message removed.").withStyle(ChatFormatting.GREEN));
	}

	private static int toggleJoin(CommandContext<CommandSourceStack> ctx, boolean on) {
		TownhallMod.CONFIG.get().joinMessages.enabled = on;
		TownhallMod.CONFIG.save();
		return ok(ctx, Component.literal(on ? "Own join messages are on." : "Vanilla join messages are back.").withStyle(ChatFormatting.GREEN));
	}

	private static int toggleTab(CommandContext<CommandSourceStack> ctx, boolean on) {
		TownhallMod.CONFIG.get().tabList.enabled = on;
		TownhallMod.CONFIG.save();
		TabList.update(ctx.getSource().getServer());
		return ok(ctx, Component.literal(on ? "Tab list header/footer on." : "Tab list header/footer off.").withStyle(ChatFormatting.GREEN));
	}

	private static int ok(CommandContext<CommandSourceStack> ctx, Component message) {
		ctx.getSource().sendSuccess(() -> message, true);
		return 1;
	}
}
