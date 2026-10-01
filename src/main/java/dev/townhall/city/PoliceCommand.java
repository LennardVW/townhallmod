package dev.townhall.city;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.townhall.TownhallMod;
import dev.townhall.audit.AuditLog;
import dev.townhall.command.Feedback;
import dev.townhall.command.TownhallCommand;
import dev.townhall.onboarding.Onboarding;
import dev.townhall.storage.ReturnPositionStorage;
import dev.townhall.teleport.TeleportService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import java.util.Optional;

/** Police only get the configured prison, time limit and explicit release right. */
public final class PoliceCommand {
	private PoliceCommand() {}
	public static void register(CommandDispatcher<CommandSourceStack> d) {
		var root = Commands.literal("polizei").executes(c -> Feedback.ok(c.getSource(),"Polizei: /polizei jail <Spieler> <Minuten> <Grund>, /polizei release <Spieler>"));
		root.then(Commands.literal("jail").requires(s -> RoleService.hasPermission(s,"police.jail"))
				.then(Commands.argument("player",EntityArgument.player()).then(Commands.argument("minutes",IntegerArgumentType.integer(1,1440))
						.then(Commands.argument("reason",StringArgumentType.greedyString()).executes(PoliceCommand::jail)))));
		root.then(Commands.literal("release").requires(s -> RoleService.hasPermission(s,"police.release"))
				.then(Commands.argument("player",EntityArgument.player()).executes(PoliceCommand::release)));
		root.then(Commands.literal("location").requires(CityAccess::isAdmin).then(Commands.argument("id",StringArgumentType.word()).executes(c -> {
			String id = StringArgumentType.getString(c,"id"); var config = TownhallMod.CONFIG.get();
			if (!TownhallMod.CONFIG.canSave()) return Feedback.fail(c.getSource(),dev.townhall.config.ConfigManager.NOT_SAVED);
			if (!config.locations.containsKey(id) || config.locations.get(id).isEscapable()) return Feedback.fail(c.getSource(),"Der Ort muss existieren und ein nicht ausbrechbares Gefängnis sein.");
			config.city.policeLocation = id;
			if (!TownhallCommand.saveConfig(c.getSource())) return 0;
			AuditLog.record(c.getSource(),"police.location",id); return Feedback.okAdmin(c.getSource(),"Polizeigefängnis: " + id);
		})));
		root.then(Commands.literal("maxminutes").requires(CityAccess::isAdmin).then(Commands.argument("minutes",IntegerArgumentType.integer(1,1440)).executes(c -> {
			if (!TownhallMod.CONFIG.canSave()) return Feedback.fail(c.getSource(),dev.townhall.config.ConfigManager.NOT_SAVED);
			TownhallMod.CONFIG.get().city.policeMaxMinutes = IntegerArgumentType.getInteger(c,"minutes");
			if (!TownhallCommand.saveConfig(c.getSource())) return 0;
			AuditLog.record(c.getSource(),"police.limit","Maximale Minuten: " + TownhallMod.CONFIG.get().city.policeMaxMinutes);
			return Feedback.okAdmin(c.getSource(),"Maximale Polizeistrafe: " + TownhallMod.CONFIG.get().city.policeMaxMinutes + " Online-Minuten.");
		})));
		d.register(root);
	}
	private static boolean restricted(CommandSourceStack s) {
		return s.getPlayer() != null && (Onboarding.isRestricted(s.getPlayer()) || ReturnPositionStorage.get(s.getServer()).state(s.getPlayer().getUUID()).confined()) && !CityAccess.isAdmin(s);
	}
	private static int jail(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		var source = c.getSource(); var config = TownhallMod.CONFIG.get(); var target = EntityArgument.getPlayer(c,"player");
		if (restricted(source)) return Feedback.fail(source,"Du kannst diese Aktion gerade nicht ausführen.");
		int minutes = IntegerArgumentType.getInteger(c,"minutes"); String reason = StringArgumentType.getString(c,"reason").strip();
		if (minutes > config.city.policeMaxMinutes || reason.isEmpty() || reason.length() > 256) return Feedback.fail(source,"Maximal " + config.city.policeMaxMinutes + " Minuten; Grund: 1-256 Zeichen.");
		var location = config.locations.get(config.city.policeLocation);
		if (location == null || location.isEscapable()) return Feedback.fail(source,"Ein Admin muss zuerst ein nicht ausbrechbares Polizeigefängnis festlegen.");
		if (TownhallMod.isOperator(target.permissions())) return Feedback.fail(source,"Admins werden nicht eingesperrt.");
		if (ReturnPositionStorage.get(source.getServer()).state(target.getUUID()).confined()) return Feedback.fail(source,"Dieser Spieler ist bereits eingesperrt. Die laufende Strafe bleibt erhalten.");
		var result = TeleportService.sendTo(target,config.city.policeLocation,config,true,true,Optional.of(minutes * 60_000L));
		if (result != TeleportService.EnterResult.SENT) return Feedback.fail(source,"Einweisung fehlgeschlagen: " + result + ".");
		AuditLog.record(source,"police.jail",target.getGameProfile().name() + " (" + target.getUUID() + "): " + minutes + " Minuten, " + reason);
		target.sendSystemMessage(net.minecraft.network.chat.Component.literal("Gefängnis: " + minutes + " Online-Minuten. Grund: " + reason));
		return Feedback.ok(source,"Spieler für " + minutes + " Online-Minuten eingewiesen.");
	}
	private static int release(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		var s = c.getSource(); var target = EntityArgument.getPlayer(c,"player"); var config = TownhallMod.CONFIG.get();
		if (restricted(s)) return Feedback.fail(s,"Du kannst diese Aktion gerade nicht ausführen.");
		var state = ReturnPositionStorage.get(s.getServer()).state(target.getUUID());
		if (!state.confined() || !state.location().orElse("").equals(config.city.policeLocation)) return Feedback.fail(s,"Der Spieler ist nicht im festgelegten Polizeigefängnis.");
		var result = TeleportService.returnPlayer(target,config,true);
		if (result == TeleportService.ReturnResult.NO_POSITION) {
			if (!TeleportService.sendToFallback(target,config)) return Feedback.fail(s,"Notfall-Teleport fehlgeschlagen; Strafe bleibt erhalten.");
			ReturnPositionStorage.get(s.getServer()).remove(target.getUUID()); TeleportService.resendCommands(target);
		} else if (result == TeleportService.ReturnResult.FAILED) return Feedback.fail(s,"Rückkehr fehlgeschlagen; Strafe bleibt erhalten.");
		AuditLog.record(s,"police.release",target.getGameProfile().name() + " (" + target.getUUID() + ")");
		return Feedback.ok(s,"Spieler freigelassen.");
	}
}
