package dev.townhall.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.townhall.TownhallMod;
import dev.townhall.nick.Nicknames;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Operator-only nicknames, shown in chat and the tab list:
 * <pre>
 * /nick set &lt;player&gt; &lt;nickname&gt;   &-color codes work, e.g. &6Bürgermeister; also for offline players
 * /nick reset &lt;player&gt;
 * /nick list
 * </pre>
 */
public final class NickCommand {

	static final int MAX_LENGTH = 32;

	private NickCommand() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("nick").requires(TownhallCommand::isOperator)
				.then(Commands.literal("set").then(Commands.argument("player", GameProfileArgument.gameProfile())
						.then(Commands.argument("nickname", StringArgumentType.greedyString()).executes(NickCommand::set))))
				.then(Commands.literal("reset").then(Commands.argument("player", GameProfileArgument.gameProfile()).executes(NickCommand::reset)))
				.then(Commands.literal("list").executes(NickCommand::list)));
	}

	private static int set(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		Collection<NameAndId> players = GameProfileArgument.getGameProfiles(ctx, "player");
		if (players.size() != 1) return fail(ctx, "Pick exactly one player.");
		NameAndId player = players.iterator().next();
		String nick = StringArgumentType.getString(ctx, "nickname").strip();
		String problem = problem(ctx.getSource().getServer(), player.id(), nick);
		if (problem != null) return fail(ctx, problem);
		Nicknames.get(ctx.getSource().getServer()).set(ctx.getSource().getServer(), player.id(), player.name(), nick);
		TownhallMod.LOGGER.info("{} set the nickname of {} to {}", ctx.getSource().getTextName(), player.name(), nick);
		ctx.getSource().sendSuccess(() -> Component.literal(player.name() + " is now shown as ").withStyle(ChatFormatting.GREEN)
				.append(Nicknames.of(player.id()).orElseThrow()), true);
		return 1;
	}

	/** Why this nickname can't be used, or null. Stops empty, too long and look-alike names of other players. */
	static String problem(MinecraftServer server, UUID owner, String nick) {
		String plain = Nicknames.plain(nick);
		if (plain.isEmpty()) return "The nickname is empty.";
		if (plain.length() > MAX_LENGTH) return "Nicknames can have at most " + MAX_LENGTH + " characters.";
		for (var online : server.getPlayerList().getPlayers()) {
			if (!online.getUUID().equals(owner) && Nicknames.plain(online.getGameProfile().name()).equals(plain)) {
				return "That is the name of another player.";
			}
		}
		for (Map.Entry<UUID, Nicknames.Entry> e : Nicknames.get(server).entries().entrySet()) {
			if (e.getKey().equals(owner)) continue;
			if (Nicknames.plain(e.getValue().nick()).equals(plain) || Nicknames.plain(e.getValue().name()).equals(plain)) {
				return "Another player already uses that name.";
			}
		}
		return null;
	}

	private static int reset(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		int done = 0;
		for (NameAndId player : GameProfileArgument.getGameProfiles(ctx, "player")) {
			if (Nicknames.get(ctx.getSource().getServer()).reset(ctx.getSource().getServer(), player.id())) done++;
		}
		if (done == 0) return fail(ctx, "No nickname set.");
		TownhallMod.LOGGER.info("{} reset {} nickname(s)", ctx.getSource().getTextName(), done);
		ctx.getSource().sendSuccess(() -> Component.literal("Nickname removed, the real name is shown again.").withStyle(ChatFormatting.GREEN), true);
		return done;
	}

	private static int list(CommandContext<CommandSourceStack> ctx) {
		var entries = Nicknames.get(ctx.getSource().getServer()).entries();
		if (entries.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Component.literal("No nicknames set.").withStyle(ChatFormatting.GOLD), false);
			return 0;
		}
		var text = Component.literal("Nicknames:").withStyle(ChatFormatting.GOLD);
		entries.forEach((uuid, e) -> text.append(Component.literal("\n " + e.name() + " → ").withStyle(ChatFormatting.GRAY))
				.append(Nicknames.of(uuid).orElse(Component.literal(e.nick()))));
		ctx.getSource().sendSuccess(() -> text, false);
		return entries.size();
	}

	private static int fail(CommandContext<CommandSourceStack> ctx, String message) {
		ctx.getSource().sendFailure(Component.literal(message));
		return 0;
	}
}
