package dev.townhall.city;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.townhall.TownhallMod;
import dev.townhall.audit.AuditLog;
import dev.townhall.command.Feedback;
import dev.townhall.command.TownhallCommand;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.GameProfileArgument;
import java.util.*;

public final class RoleCommand {
	private RoleCommand() {}
	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		var root = Commands.literal("role").requires(CityAccess::isAdmin);
		root.executes(c -> list(c.getSource()));
		root.then(Commands.literal("list").executes(c -> list(c.getSource())));
		root.then(Commands.literal("create").then(Commands.argument("id", StringArgumentType.word())
				.then(Commands.argument("text", StringArgumentType.greedyString()).executes(RoleCommand::create))));
		for (String verb : List.of("info", "delete")) root.then(Commands.literal(verb).then(roleArg().executes(c -> simple(c, verb))));
		for (String verb : List.of("grant", "revoke")) root.then(Commands.literal(verb).then(roleArg()
				.then(Commands.argument("player", GameProfileArgument.gameProfile()).executes(c -> membership(c, verb.equals("grant"))))));
		for (String setting : List.of("name", "prefix")) root.then(Commands.literal(setting).then(roleArg()
				.then(Commands.argument("text", StringArgumentType.greedyString()).executes(c -> text(c, setting)))));
		root.then(Commands.literal("priority").then(roleArg().then(Commands.argument("value", IntegerArgumentType.integer(0, 1000)).executes(RoleCommand::priority))));
		root.then(Commands.literal("permission").then(roleArg()
				.then(Commands.argument("permission", StringArgumentType.word()).suggests((c,b) -> SharedSuggestionProvider.suggest(RoleService.PERMISSIONS,b))
						.then(Commands.argument("enabled", BoolArgumentType.bool()).executes(RoleCommand::permission)))));
		root.then(Commands.literal("display").then(Commands.argument("where", StringArgumentType.word())
				.suggests((c,b) -> SharedSuggestionProvider.suggest(List.of("chat", "tab"),b))
				.then(Commands.argument("enabled", BoolArgumentType.bool()).executes(RoleCommand::display))));
		var node = dispatcher.register(root);
		dispatcher.register(Commands.literal("rolle").requires(CityAccess::isAdmin).redirect(node));
	}
	private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack,String> roleArg() {
		return Commands.argument("id", StringArgumentType.word()).suggests((c,b) -> SharedSuggestionProvider.suggest(RoleStorage.get(c.getSource().getServer()).definitions().keySet(), b));
	}
	private static String id(CommandContext<CommandSourceStack> c) { return StringArgumentType.getString(c,"id"); }
	private static RoleStorage storage(CommandContext<CommandSourceStack> c) { return RoleStorage.get(c.getSource().getServer()); }
	private static int changed(CommandSourceStack source, String action, String detail) {
		RoleService.refresh(source.getServer()); AuditLog.record(source, action, detail); return Feedback.okAdmin(source,detail);
	}
	private static int list(CommandSourceStack s) {
		return Feedback.ok(s,"Stadtrollen: " + String.join(", ",RoleStorage.get(s.getServer()).definitions().keySet()));
	}
	private static int create(CommandContext<CommandSourceStack> c) {
		String id = id(c), title = StringArgumentType.getString(c,"text").strip();
		RoleStorage s = storage(c);
		if (!CityAccess.validId(id)) return Feedback.fail(c.getSource(),"Rollen-ID: kleine Buchstaben, Zahlen, - oder _, höchstens 32 Zeichen.");
		if (s.definitions().containsKey(id)) return Feedback.fail(c.getSource(),"Diese Rolle existiert bereits.");
		if (s.definitions().size() >= 100 || title.isEmpty() || title.length() > 64) return Feedback.fail(c.getSource(),"Maximal 100 Rollen; Name: 1-64 Zeichen.");
		s.put(id,new RoleDefinition(title,"[" + title + "] ",List.of(),0));
		return changed(c.getSource(),"role.create","Rolle " + id + " erstellt: " + title);
	}
	private static int simple(CommandContext<CommandSourceStack> c, String verb) {
		RoleStorage s = storage(c); RoleDefinition role = s.definitions().get(id(c));
		if (role == null) return Feedback.fail(c.getSource(),"Unbekannte Rolle.");
		if (verb.equals("delete")) {
			s.remove(id(c)); return changed(c.getSource(),"role.delete","Rolle " + id(c) + " und ihre Mitgliedschaften entfernt.");
		}
		String members = String.join(", ",s.members().values().stream().filter(m -> m.roles().contains(id(c))).map(RoleStorage.Member::name).sorted().toList());
		return Feedback.ok(c.getSource(),role.title() + " (" + id(c) + ") | Rechte: " + role.permissions() + " | Mitglieder: " + members + " | Priorität: " + role.priority());
	}
	private static int membership(CommandContext<CommandSourceStack> c, boolean grant) throws CommandSyntaxException {
		RoleStorage s = storage(c);
		if (!s.definitions().containsKey(id(c))) return Feedback.fail(c.getSource(),"Unbekannte Rolle.");
		var players = GameProfileArgument.getGameProfiles(c,"player");
		for (var p : players) { if (grant) s.grant(p.id(),p.name(),id(c)); else s.revoke(p.id(),id(c)); }
		return changed(c.getSource(),grant ? "role.grant" : "role.revoke", "Rolle " + id(c) + (grant ? " vergeben an " : " entzogen für ") + String.join(", ",players.stream().map(p -> p.name() + " (" + p.id() + ")").toList()));
	}
	private static int text(CommandContext<CommandSourceStack> c, String setting) {
		RoleDefinition d = storage(c).definitions().get(id(c));
		if (d == null) return Feedback.fail(c.getSource(),"Unbekannte Rolle.");
		String text = StringArgumentType.getString(c,"text"); if (text.equals("-")) text = "";
		if (text.length() > 96 || (setting.equals("name") && text.isBlank())) return Feedback.fail(c.getSource(),"Text zu lang oder leer (Name muss vorhanden sein).");
		storage(c).put(id(c),new RoleDefinition(setting.equals("name") ? text : d.title(),setting.equals("prefix") ? text : d.prefix(),d.permissions(),d.priority()));
		return changed(c.getSource(),"role." + setting, "Rolle " + id(c) + ": " + setting + " geändert.");
	}
	private static int priority(CommandContext<CommandSourceStack> c) {
		RoleDefinition d = storage(c).definitions().get(id(c)); if (d == null) return Feedback.fail(c.getSource(),"Unbekannte Rolle.");
		storage(c).put(id(c),new RoleDefinition(d.title(),d.prefix(),d.permissions(),IntegerArgumentType.getInteger(c,"value")));
		return changed(c.getSource(),"role.priority","Anzeigepriorität für " + id(c) + " geändert.");
	}
	private static int permission(CommandContext<CommandSourceStack> c) {
		RoleDefinition d = storage(c).definitions().get(id(c)); if (d == null) return Feedback.fail(c.getSource(),"Unbekannte Rolle.");
		String p = StringArgumentType.getString(c,"permission");
		if (!RoleService.PERMISSIONS.contains(p)) return Feedback.fail(c.getSource(),"Unbekanntes Stadtrecht. Erlaubt: " + RoleService.PERMISSIONS);
		Set<String> permissions = new LinkedHashSet<>(d.permissions());
		if (BoolArgumentType.getBool(c,"enabled")) permissions.add(p); else permissions.remove(p);
		storage(c).put(id(c),new RoleDefinition(d.title(),d.prefix(),List.copyOf(permissions),d.priority()));
		return changed(c.getSource(),"role.permission", "Stadtrecht " + p + " für " + id(c) + " geändert.");
	}
	private static int display(CommandContext<CommandSourceStack> c) {
		if (!TownhallMod.CONFIG.canSave()) return Feedback.fail(c.getSource(),dev.townhall.config.ConfigManager.NOT_SAVED);
		boolean on = BoolArgumentType.getBool(c,"enabled"); String where = StringArgumentType.getString(c,"where");
		if (where.equals("chat")) TownhallMod.CONFIG.get().city.rolesInChat = on;
		else if (where.equals("tab")) TownhallMod.CONFIG.get().city.rolesInTab = on;
		else return Feedback.fail(c.getSource(),"Anzeige: chat oder tab.");
		if (!TownhallCommand.saveConfig(c.getSource())) return 0;
		return changed(c.getSource(),"role.display","Rollenanzeige in " + where + ": " + on);
	}
}
