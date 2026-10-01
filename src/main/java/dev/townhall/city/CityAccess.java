package dev.townhall.city;

import dev.townhall.TownhallMod;
import dev.townhall.command.TownhallCommand;
import net.minecraft.commands.CommandSourceStack;

/** A sign's elevated command source must never turn a normal player into an administrator. */
public final class CityAccess {
	private CityAccess() {}
	public static boolean isAdmin(CommandSourceStack source) {
		return source.getPlayer() == null ? TownhallCommand.isOperator(source)
				: TownhallMod.isOperator(source.getPlayer().permissions());
	}
	public static boolean validId(String id) {
		return id != null && id.matches("[a-z][a-z0-9_-]{0,31}");
	}
}
