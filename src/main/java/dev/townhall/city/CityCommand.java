package dev.townhall.city;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.townhall.audit.AuditLog;
import dev.townhall.command.Feedback;
import dev.townhall.util.Text;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

public final class CityCommand {
	private CityCommand() {}
	public static void register(CommandDispatcher<CommandSourceStack> d) {
		d.register(Commands.literal("stadt").executes(c -> Feedback.ok(c.getSource(),
				"AzubiCraft: /townhall zum Rathaus, /rules für Regeln, /role list (Admin), /wahl list, /shop list, /plot info. Survival und persönlicher Handel sind für alle offen."))
				.then(Commands.literal("announce").requires(s -> RoleService.hasPermission(s,"city.announce"))
						.then(Commands.argument("text",StringArgumentType.greedyString()).executes(c -> {
							String message = StringArgumentType.getString(c,"text").strip();
							if (message.isEmpty() || message.length() > 256) return Feedback.fail(c.getSource(),"Ankündigung: 1-256 Zeichen.");
							c.getSource().getServer().getPlayerList().broadcastSystemMessage(Text.of("&6[AzubiCraft] &r" + message),false);
							AuditLog.record(c.getSource(),"city.announce","Stadtankündigung veröffentlicht.");
							return 1;
						}))));
	}
}
