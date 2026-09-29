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

import static dev.townhall.command.Feedback.fail;
import static dev.townhall.command.Feedback.okAdmin;

/**
 * Operator-only nicknames, shown in chat and the tab list:
 * <pre>
 * /nick set &lt;player&gt; &lt;nickname&gt;   &-color codes work, e.g. &6Bürgermeister; also for offline players
 * /nick reset &lt;player&gt;
 * /nick list
 * </pre>
 */
public final class NickCommand {

	public static final int MAX_LENGTH = 32;

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
		if (players.size() != 1) return fail(ctx.getSource(), "Pick exactly one player.");
		NameAndId player = players.iterator().next();
		String nick = StringArgumentType.getString(ctx, "nickname").strip();
		String problem = problem(ctx.getSource().getServer(), player, nick);
		if (problem != null) return fail(ctx.getSource(), problem);
		Nicknames.get(ctx.getSource().getServer()).set(ctx.getSource().getServer(), player.id(), player.name(), nick);
		TownhallMod.LOGGER.info("{} set the nickname of {} to {}", ctx.getSource().getTextName(), player.name(), nick);
		return okAdmin(ctx.getSource(), Component.literal(player.name() + " is now shown as ").withStyle(ChatFormatting.GREEN)
				.append(Nicknames.of(player.id()).orElseThrow()));
	}

	/**
	 * Why this nickname can't be used, or null. Stops empty and too long names, and every name that would make a typed
	 * player name ambiguous: another player's nickname, or the real name of any player the server knows (online, name
	 * cache, operators, whitelist, play time and nickname records). Color codes, case and outer spaces don't count.
	 * The owner may use their own real name.
	 */
	static String problem(MinecraftServer server, NameAndId owner, String nick) {
		String plain = Nicknames.plain(nick);
		if (plain.isEmpty()) return "The nickname is empty.";
		if (plain.length() > MAX_LENGTH) return "Nicknames can have at most " + MAX_LENGTH + " characters.";
		for (Map.Entry<UUID, Nicknames.Entry> e : Nicknames.get(server).entries().entrySet()) {
			if (!e.getKey().equals(owner.id()) && Nicknames.plain(e.getValue().nick()).equals(plain)) {
				return "Another player already has that nickname.";
			}
		}
		if (!Nicknames.plain(owner.name()).equals(plain) && Nicknames.isKnownRealName(server, plain)) {
			return "That is the name of another player.";
		}
		return null;
	}

	private static int reset(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		int done = 0;
		for (NameAndId player : GameProfileArgument.getGameProfiles(ctx, "player")) {
			if (Nicknames.get(ctx.getSource().getServer()).reset(ctx.getSource().getServer(), player.id())) done++;
		}
		if (done == 0) return fail(ctx.getSource(), "No nickname set.");
		TownhallMod.LOGGER.info("{} reset {} nickname(s)", ctx.getSource().getTextName(), done);
		okAdmin(ctx.getSource(), "Nickname removed, the real name is shown again.");
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
}
