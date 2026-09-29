package dev.townhall.command;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/**
 * Command replies; each returns the Brigadier result (1 = success, 0 = failure).
 * <ul>
 * <li>{@link #ok}: green, only to the sender (player actions, lookups).</li>
 * <li>{@link #okAdmin}: green, also to the other operators and the server log, like vanilla /gamerule (config changes).</li>
 * <li>{@link #fail}: red, only to the sender.</li>
 * </ul>
 */
public final class Feedback {

	private Feedback() {}

	public static int ok(CommandSourceStack source, String message) {
		source.sendSuccess(() -> Component.literal(message).withStyle(ChatFormatting.GREEN), false);
		return 1;
	}

	public static int okAdmin(CommandSourceStack source, String message) {
		return okAdmin(source, Component.literal(message).withStyle(ChatFormatting.GREEN));
	}

	public static int okAdmin(CommandSourceStack source, Component message) {
		source.sendSuccess(() -> message, true);
		return 1;
	}

	public static int fail(CommandSourceStack source, String message) {
		source.sendFailure(Component.literal(message));
		return 0;
	}
}
