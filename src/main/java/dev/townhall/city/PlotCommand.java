package dev.townhall.city;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
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
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import java.util.*;

public final class PlotCommand {
	private record Selection(String dimension,BlockPos first,BlockPos second) {}
	private static final Map<UUID,Selection> SELECTIONS = new HashMap<>();
	private static final UUID CONSOLE = new UUID(0,0);
	private PlotCommand() {}
	public static void reset() { SELECTIONS.clear(); }
	public static void register(CommandDispatcher<CommandSourceStack> d) {
		var root = Commands.literal("plot").executes(c -> info(c.getSource(),null));
		root.then(Commands.literal("list").executes(c -> Feedback.ok(c.getSource(),"Grundstücksschutz: " + TownhallMod.CONFIG.get().city.claimsEnabled + ". Bereiche: " + String.join(", ",PlotStorage.get(c.getSource().getServer()).plots().keySet().stream().limit(50).toList()))));
		root.then(Commands.literal("info").executes(c -> info(c.getSource(),null)).then(idArg().executes(c -> info(c.getSource(),id(c)))));
		root.then(Commands.literal("enabled").requires(CityAccess::isAdmin).then(Commands.argument("enabled",BoolArgumentType.bool()).executes(c -> {
			if (!TownhallMod.CONFIG.canSave()) return Feedback.fail(c.getSource(),dev.townhall.config.ConfigManager.NOT_SAVED);
			TownhallMod.CONFIG.get().city.claimsEnabled = BoolArgumentType.getBool(c,"enabled");
			if (!TownhallCommand.saveConfig(c.getSource())) return 0;
			RoleService.refresh(c.getSource().getServer());
			return changed(c.getSource(),"plot.enabled","Grundstücksschutz: " + TownhallMod.CONFIG.get().city.claimsEnabled);
		})));
		for (int n=1;n<=2;n++) {
			final int number = n;
			root.then(Commands.literal("pos" + n).requires(CityAccess::isAdmin).executes(c -> select(c.getSource(),c.getSource().getPlayerOrException().blockPosition(),number))
					.then(Commands.argument("pos",BlockPosArgument.blockPos()).executes(c -> select(c.getSource(),BlockPosArgument.getBlockPos(c,"pos"),number))));
		}
		root.then(Commands.literal("create").requires(CityAccess::isAdmin).then(Commands.argument("id",StringArgumentType.word()).executes(c -> create(c,Optional.empty()))
				.then(Commands.argument("player",GameProfileArgument.gameProfile()).executes(c -> {
					var profiles = GameProfileArgument.getGameProfiles(c,"player");
					if (profiles.size()!=1) return Feedback.fail(c.getSource(),"Ein Grundstück hat genau einen Eigentümer.");
					return create(c,Optional.of(profiles.iterator().next().id().toString()));
				}))));
		root.then(Commands.literal("delete").requires(CityAccess::isAdmin).then(idArg().executes(c -> {
			if (!PlotStorage.get(c.getSource().getServer()).remove(id(c))) return Feedback.fail(c.getSource(),"Unbekanntes Grundstück.");
			return changed(c.getSource(),"plot.delete","Grundstück " + id(c) + " entfernt.");
		})));
		for(String verb:List.of("owner","trust","untrust")) root.then(Commands.literal(verb).requires(CityAccess::isAdmin).then(idArg()
				.then(Commands.argument("player",GameProfileArgument.gameProfile()).executes(c -> players(c,verb)))));
		root.then(Commands.literal("unowned").requires(CityAccess::isAdmin).then(idArg().executes(c -> update(c,p -> new Plot(p.dimension(),p.minX(),p.minZ(),p.maxX(),p.maxZ(),Optional.empty(),p.trusted(),p.roles(),p.publicUse()),"Eigentümer entfernt."))));
		for(String verb:List.of("role","unrole")) root.then(Commands.literal(verb).requires(CityAccess::isAdmin).then(idArg()
				.then(Commands.argument("role",StringArgumentType.word()).suggests((c,b) -> SharedSuggestionProvider.suggest(RoleStorage.get(c.getSource().getServer()).definitions().keySet(),b))
						.executes(c -> roles(c,verb.equals("role"))))));
		root.then(Commands.literal("publicuse").requires(CityAccess::isAdmin).then(idArg().then(Commands.argument("enabled",BoolArgumentType.bool()).executes(c ->
				update(c,p -> new Plot(p.dimension(),p.minX(),p.minZ(),p.maxX(),p.maxZ(),p.owner(),p.trusted(),p.roles(),BoolArgumentType.getBool(c,"enabled")),"Besucherzugang geändert.")))));
		d.register(root);
	}
	private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack,String> idArg() {
		return Commands.argument("id",StringArgumentType.word()).suggests((c,b)-> SharedSuggestionProvider.suggest(PlotStorage.get(c.getSource().getServer()).plots().keySet(),b));
	}
	private static String id(CommandContext<CommandSourceStack> c) { return StringArgumentType.getString(c,"id"); }
	private static int changed(CommandSourceStack s,String action,String detail) { AuditLog.record(s,action,detail); return Feedback.okAdmin(s,detail); }
	private static int select(CommandSourceStack s,BlockPos pos,int point) {
		UUID who = s.getPlayer()==null ? CONSOLE : s.getPlayer().getUUID(); String dimension = s.getLevel().dimension().identifier().toString();
		Selection old = SELECTIONS.get(who); if(old==null || !old.dimension().equals(dimension)) old = new Selection(dimension,null,null);
		SELECTIONS.put(who,new Selection(dimension,point==1?pos.immutable():old.first(),point==2?pos.immutable():old.second()));
		return Feedback.ok(s,"Eckpunkt " + point + ": " + pos.toShortString() + " in " + dimension + ". Der Bereich umfasst die gesamte Bauhöhe.");
	}
	private static int create(CommandContext<CommandSourceStack> c,Optional<String> owner) {
		var s = c.getSource(); UUID who = s.getPlayer()==null ? CONSOLE : s.getPlayer().getUUID(); Selection selected = SELECTIONS.get(who);
		if(selected==null || selected.first()==null || selected.second()==null || !selected.dimension().equals(s.getLevel().dimension().identifier().toString())) return Feedback.fail(s,"Zuerst /plot pos1 und /plot pos2 in dieser Welt setzen.");
		Plot p = new Plot(selected.dimension(),Math.min(selected.first().getX(),selected.second().getX()),Math.min(selected.first().getZ(),selected.second().getZ()),Math.max(selected.first().getX(),selected.second().getX()),Math.max(selected.first().getZ(),selected.second().getZ()),owner,List.of(),List.of(),true);
		var storage = PlotStorage.get(s.getServer()); var error = storage.createProblem(id(c),p);
		if(error.isPresent()) return Feedback.fail(s,error.get());
		storage.put(id(c),p); return changed(s,"plot.create","Grundstück " + id(c) + " angelegt. Schutz aktiv: " + TownhallMod.CONFIG.get().city.claimsEnabled);
	}
	private static int info(CommandSourceStack s,String id) {
		var storage = PlotStorage.get(s.getServer());
		var entry = id==null ? storage.at(s.getLevel().dimension().identifier().toString(),BlockPos.containing(s.getPosition())) : Optional.ofNullable(storage.plots().get(id)).map(p -> Map.entry(id,p));
		if(entry.isEmpty()) return Feedback.fail(s,"Hier ist kein Grundstück eingerichtet.");
		Plot p = entry.get().getValue();
		return Feedback.ok(s,entry.get().getKey() + ": " + p.dimension() + " (" + p.minX()+","+p.minZ()+") bis ("+p.maxX()+","+p.maxZ()+") | Eigentümer: " + p.owner().orElse("öffentliche Fläche") + " | Mitbauer: " + p.trusted() + " | Rollen: " + p.roles() + " | Besucherzugang: " + p.publicUse() + " | Schutz aktiv: " + TownhallMod.CONFIG.get().city.claimsEnabled);
	}
	private static int update(CommandContext<CommandSourceStack> c,java.util.function.UnaryOperator<Plot> change,String detail) {
		var storage = PlotStorage.get(c.getSource().getServer()); Plot old = storage.plots().get(id(c));
		if(old==null) return Feedback.fail(c.getSource(),"Unbekanntes Grundstück.");
		storage.put(id(c),change.apply(old)); return changed(c.getSource(),"plot.update",id(c) + ": " + detail);
	}
	private static int players(CommandContext<CommandSourceStack> c,String verb) throws CommandSyntaxException {
		var profiles = GameProfileArgument.getGameProfiles(c,"player");
		if(verb.equals("owner") && profiles.size()!=1) return Feedback.fail(c.getSource(),"Genau einen Eigentümer angeben.");
		return update(c,p -> {
			Set<String> trusted = new LinkedHashSet<>(p.trusted()); Optional<String> owner = p.owner();
			for(var player:profiles) { String id=player.id().toString(); if(verb.equals("owner")) owner=Optional.of(id); else if(verb.equals("trust")) trusted.add(id); else trusted.remove(id); }
			return new Plot(p.dimension(),p.minX(),p.minZ(),p.maxX(),p.maxZ(),owner,List.copyOf(trusted),p.roles(),p.publicUse());
		},verb + ": " + String.join(", ",profiles.stream().map(p -> p.name() + " (" + p.id()+")").toList()));
	}
	private static int roles(CommandContext<CommandSourceStack> c,boolean add) {
		String role = StringArgumentType.getString(c,"role");
		if(add && !RoleStorage.get(c.getSource().getServer()).definitions().containsKey(role)) return Feedback.fail(c.getSource(),"Unbekannte Stadtrolle.");
		return update(c,p -> {
			Set<String> roles = new LinkedHashSet<>(p.roles()); if(add)roles.add(role);else roles.remove(role);
			return new Plot(p.dimension(),p.minX(),p.minZ(),p.maxX(),p.maxZ(),p.owner(),p.trusted(),List.copyOf(roles),p.publicUse());
		},"Rollenfreigabe " + role + ": " + add);
	}
}
