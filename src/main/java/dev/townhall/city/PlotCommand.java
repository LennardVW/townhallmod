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
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;
import java.util.*;

public final class PlotCommand {
	private record Selection(String dimension,BlockPos first,BlockPos second) {}
	private static final Map<UUID,Selection> SELECTIONS = new HashMap<>();
	private static final UUID CONSOLE = new UUID(0,0);
	private PlotCommand() {}
	private record Invite(String plotId,String from,long expires) {}
	private record Pending(String plotId,long expires) {}
	private static final Map<UUID,List<Invite>> INVITES = new HashMap<>();
	private static final Map<UUID,Pending> UNCLAIMS = new HashMap<>();
	private static final long INVITE_MILLIS = 5 * 60_000L, CONFIRM_MILLIS = 30_000L;
	public static void reset() { SELECTIONS.clear(); INVITES.clear(); UNCLAIMS.clear(); }
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
		root.then(Commands.literal("owner").requires(CityAccess::isAdmin).then(idArg()
				.then(Commands.argument("player",GameProfileArgument.gameProfile()).executes(c -> players(c,"owner",Level.ADMIN)))));
		for(String verb:List.of("trust","untrust","manager","unmanager")) {
			Level level = verb.endsWith("manager") ? Level.OWNER : Level.MANAGER;
			root.then(withPlayer(Commands.literal(verb),c -> players(c,verb,level)));
		}
		root.then(withPlayer(Commands.literal("transfer"),c -> players(c,"transfer",Level.OWNER)));
		root.then(Commands.literal("coowner")
				.then(withPlayer(Commands.literal("add"),PlotCommand::coOwnerAdd))
				.then(withPlayer(Commands.literal("remove"),PlotCommand::coOwnerRemove)));
		root.then(Commands.literal("claim").executes(c -> claim(c)).then(Commands.argument("id",StringArgumentType.word()).executes(c -> claim(c))));
		root.then(Commands.literal("expand").executes(c -> expand(c.getSource())));
		root.then(Commands.literal("accept").executes(c -> respond(c,true)).then(Commands.argument("id",StringArgumentType.word()).executes(c -> respond(c,true))));
		root.then(Commands.literal("deny").executes(c -> respond(c,false)).then(Commands.argument("id",StringArgumentType.word()).executes(c -> respond(c,false))));
		root.then(Commands.literal("unclaim").executes(c -> unclaim(c,false)).then(Commands.literal("confirm").executes(c -> unclaim(c,true))));
		root.then(Commands.literal("bonus").requires(CityAccess::isAdmin).then(Commands.argument("player",GameProfileArgument.gameProfile()).executes(c -> bonus(c,-1))
				.then(Commands.argument("chunks",IntegerArgumentType.integer(0,1024)).executes(c -> bonus(c,IntegerArgumentType.getInteger(c,"chunks"))))));
		for(String h:List.of("help","hilfe")) root.then(Commands.literal(h).executes(c -> help(c.getSource())));
		root.then(Commands.literal("members").executes(c -> members(c.getSource(),null)).then(idArg().executes(c -> members(c.getSource(),id(c)))));
		root.then(Commands.literal("unowned").requires(CityAccess::isAdmin).then(idArg().executes(c -> update(c,Level.ADMIN,p -> p.withOwner(Optional.empty()),"Eigentümer entfernt."))));
		for(String verb:List.of("role","unrole")) root.then(Commands.literal(verb).requires(CityAccess::isAdmin).then(idArg()
				.then(Commands.argument("role",StringArgumentType.word()).suggests((c,b) -> SharedSuggestionProvider.suggest(RoleStorage.get(c.getSource().getServer()).definitions().keySet(),b))
						.executes(c -> roles(c,verb.equals("role"))))));
		root.then(Commands.literal("publicuse")
				.then(Commands.argument("enabled",BoolArgumentType.bool()).executes(c -> publicUse(c)))
				.then(idArg().then(Commands.argument("enabled",BoolArgumentType.bool()).executes(c -> publicUse(c)))));
		d.register(root);
	}
	private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack,String> idArg() {
		return Commands.argument("id",StringArgumentType.word()).suggests((c,b)-> SharedSuggestionProvider.suggest(PlotStorage.get(c.getSource().getServer()).plots().keySet(),b));
	}
	private enum Level { ADMIN, MAIN, OWNER, MANAGER }
	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> withPlayer(com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> node,com.mojang.brigadier.Command<CommandSourceStack> run) {
		return node.then(Commands.argument("player",GameProfileArgument.gameProfile()).executes(run))
				.then(idArg().then(Commands.argument("player",GameProfileArgument.gameProfile()).executes(run)));
	}
	private static String id(CommandContext<CommandSourceStack> c) { return StringArgumentType.getString(c,"id"); }
	private static String optionalId(CommandContext<CommandSourceStack> c) {
		try { return id(c); } catch (IllegalArgumentException e) { return null; }
	}
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
		return Feedback.ok(s,entry.get().getKey() + ": " + p.dimension() + " (" + p.minX()+","+p.minZ()+") bis ("+p.maxX()+","+p.maxZ()+") | Eigentümer: " + p.owner().orElse("öffentliche Fläche") + " | Miteigentümer: " + p.coOwners() + " | Manager: " + p.managers() + " | Mitbauer: " + p.trusted() + " | Rollen: " + p.roles() + " | Chunks: " + p.chunkCount() + (p.owner().isPresent() ? " von " + storage.quota(p,TownhallMod.CONFIG.get().city.claimChunksPerPlayer) + " erlaubt" : "") + " | Besucherzugang: " + p.publicUse() + " | Schutz aktiv: " + TownhallMod.CONFIG.get().city.claimsEnabled);
	}
	private static boolean mayManage(CommandSourceStack s,Plot p,Level level) {
		if(CityAccess.isAdmin(s)) return true;
		if(level==Level.ADMIN || s.getPlayer()==null) return false;
		UUID who = s.getPlayer().getUUID();
		return level==Level.MAIN ? p.isMain(who) : p.isOwner(who) || (level==Level.MANAGER && p.isManager(who));
	}
	private static Optional<Map.Entry<String,Plot>> resolve(CommandSourceStack s,String id) {
		var storage = PlotStorage.get(s.getServer());
		if(id!=null) return Optional.ofNullable(storage.plots().get(id)).map(p -> Map.entry(id,p));
		var here = storage.at(s.getLevel().dimension().identifier().toString(),BlockPos.containing(s.getPosition()));
		// nicht auf einem Grundstück: das eigene nehmen (Eigentümer/Miteigentümer), damit /plot coowner add Anna auch daneben klappt
		if(here.isPresent() || s.getPlayer()==null) return here;
		// Quelle kann von der Spielerposition abweichen (Multiworld-Mods): dann die echte Position des Spielers prüfen
		ServerPlayer player = s.getPlayer();
		here = storage.at(player.level().dimension().identifier().toString(),player.blockPosition());
		return here.isPresent() ? here : storage.ownedBy(player.getUUID());
	}
	private static int update(CommandContext<CommandSourceStack> c,Level level,java.util.function.UnaryOperator<Plot> change,String detail) {
		var s = c.getSource(); var entry = resolve(s,optionalId(c));
		if(entry.isEmpty()) return Feedback.fail(s,optionalId(c)==null ? "Du stehst auf keinem Grundstück und besitzt keins. Stell dich auf das Grundstück oder gib die Kennung an." : "Unbekanntes Grundstück.");
		if(!mayManage(s,entry.get().getValue(),level)) return Feedback.fail(s,level==Level.ADMIN ? "Das dürfen nur Admins." : level==Level.MAIN ? "Das darf nur der Haupt-Eigentümer dieses Grundstücks." : level==Level.OWNER ? "Das darf nur ein Eigentümer dieses Grundstücks." : "Du darfst dieses Grundstück nicht verwalten.");
		PlotStorage.get(s.getServer()).put(entry.get().getKey(),change.apply(entry.get().getValue()));
		return changed(s,"plot.update",entry.get().getKey() + ": " + detail);
	}
	private static int publicUse(CommandContext<CommandSourceStack> c) {
		boolean on = BoolArgumentType.getBool(c,"enabled");
		return update(c,Level.MANAGER,p -> p.withPublicUse(on),"Besucherzugang: " + on);
	}
	private static String nameOf(MinecraftServer server,String uuid) {
		try { return server.services().nameToIdCache().get(UUID.fromString(uuid)).map(NameAndId::name).orElse(uuid); } catch (IllegalArgumentException e) { return uuid; }
	}
	private static int members(CommandSourceStack s,String id) {
		var entry = resolve(s,id);
		if(entry.isEmpty()) return Feedback.fail(s,id==null ? "Hier ist kein Grundstück eingerichtet." : "Unbekanntes Grundstück.");
		Plot p = entry.get().getValue(); var server = s.getServer();
		java.util.function.Function<List<String>,String> names = l -> l.isEmpty() ? "niemand" : String.join(", ",l.stream().map(u -> nameOf(server,u)).toList());
		return Feedback.ok(s,entry.get().getKey() + " | Eigentümer: " + p.owner().map(u -> nameOf(server,u)).orElse("öffentliche Fläche") + " | Miteigentümer: " + names.apply(p.coOwners()) + " | Manager: " + names.apply(p.managers()) + " | Mitbauer: " + names.apply(p.trusted()));
	}
	private static int players(CommandContext<CommandSourceStack> c,String verb,Level level) throws CommandSyntaxException {
		var profiles = GameProfileArgument.getGameProfiles(c,"player");
		if((verb.equals("owner") || verb.equals("transfer")) && profiles.size()!=1) return Feedback.fail(c.getSource(),"Genau einen Eigentümer angeben.");
		if(verb.equals("transfer") && !CityAccess.isAdmin(c.getSource())) {
			var entry = resolve(c.getSource(),optionalId(c));
			if(entry.isPresent() && mayManage(c.getSource(),entry.get().getValue(),level)) {
				var problem = transferProblem(c.getSource().getServer(),entry.get().getKey(),entry.get().getValue(),profiles.iterator().next().id());
				if(problem.isPresent()) return Feedback.fail(c.getSource(),problem.get());
			}
		}
		return update(c,level,p -> {
			Set<String> trusted = new LinkedHashSet<>(p.trusted()), managers = new LinkedHashSet<>(p.managers()); Optional<String> owner = p.owner();
			for(var player:profiles) {
				String id=player.id().toString();
				switch(verb) {
					case "owner","transfer" -> owner=Optional.of(id);
					case "trust" -> trusted.add(id);
					case "untrust" -> trusted.remove(id);
					case "manager" -> managers.add(id);
					default -> managers.remove(id);
				}
			}
			List<String> co = new ArrayList<>(p.coOwners()); if(verb.equals("owner") || verb.equals("transfer")) co.remove(owner.orElse(""));
			return p.withOwner(owner).withTrusted(List.copyOf(trusted)).withManagers(List.copyOf(managers)).withCoOwners(co);
		},verb + ": " + String.join(", ",profiles.stream().map(p -> p.name() + " (" + p.id()+")").toList()));
	}
	private static int roles(CommandContext<CommandSourceStack> c,boolean add) {
		String role = StringArgumentType.getString(c,"role");
		if(add && !RoleStorage.get(c.getSource().getServer()).definitions().containsKey(role)) return Feedback.fail(c.getSource(),"Unbekannte Stadtrolle.");
		return update(c,Level.ADMIN,p -> {
			Set<String> roles = new LinkedHashSet<>(p.roles()); if(add)roles.add(role);else roles.remove(role);
			return p.withRoles(List.copyOf(roles));
		},"Rollenfreigabe " + role + ": " + add);
	}
	private static Optional<String> transferProblem(MinecraftServer server,String id,Plot p,UUID target) {
		var storage = PlotStorage.get(server);
		var other = storage.ownedBy(target);
		if(other.isPresent() && !other.get().getKey().equals(id)) return Optional.of("Dieser Spieler besitzt schon ein Grundstück (" + other.get().getKey() + ").");
		List<String> co = new ArrayList<>(p.coOwners()); co.remove(target.toString());
		Plot np = p.withOwner(Optional.of(target.toString())).withCoOwners(co);
		int quota = storage.quota(np,TownhallMod.CONFIG.get().city.claimChunksPerPlayer);
		if(np.chunkCount() > quota) return Optional.of("Das Grundstück hat " + np.chunkCount() + " Chunks, dem neuen Eigentümer stehen nur " + quota + " zu. Ein Admin kann das Kontingent mit /plot bonus erhöhen.");
		return Optional.empty();
	}
	private static int claim(CommandContext<CommandSourceStack> c) {
		var s = c.getSource(); ServerPlayer player = s.getPlayer();
		if(player==null) return Feedback.fail(s,"Nur Spieler können ein Grundstück beanspruchen.");
		if(!TownhallMod.CONFIG.get().city.claimsEnabled) return Feedback.fail(s,"Der Grundstücksschutz ist ausgeschaltet.");
		var storage = PlotStorage.get(s.getServer()); var mine = storage.ownedBy(player.getUUID());
		if(mine.isPresent()) return Feedback.fail(s,mine.get().getValue().isMain(player.getUUID()) ? "Du besitzt schon das Grundstück " + mine.get().getKey() + "." : "Du bist schon Miteigentümer von " + mine.get().getKey() + ".");
		if(TownhallMod.CONFIG.get().city.claimChunksPerPlayer + storage.bonus(player.getUUID()) < 1) return Feedback.fail(s,"Dein Kontingent beträgt 0 Chunks.");
		int cx = player.blockPosition().getX() >> 4, cz = player.blockPosition().getZ() >> 4;
		String id = optionalId(c);
		if(id==null) {
			String base = "plot_" + player.getGameProfile().name().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]","");
			base = base.substring(0,Math.min(base.length(),28)); id = base;
			for(int n=2;storage.plots().containsKey(id);n++) id = base + "_" + n;
		}
		Plot p = new Plot(s.getLevel().dimension().identifier().toString(),cx*16,cz*16,cx*16+15,cz*16+15,Optional.of(player.getUUID().toString()),List.of(),List.of(),true);
		var error = storage.createProblem(id,p); if(error.isPresent()) return Feedback.fail(s,error.get());
		storage.put(id,p);
		return changed(s,"plot.claim","Grundstück " + id + " beansprucht: Chunk " + cx + "," + cz + ". Mit /plot expand wächst es in Nachbarchunks, soweit das Kontingent reicht.");
	}
	private static int expand(CommandSourceStack s) {
		ServerPlayer player = s.getPlayer(); if(player==null) return Feedback.fail(s,"Nur Spieler können ein Grundstück erweitern.");
		var storage = PlotStorage.get(s.getServer()); var mine = storage.ownedBy(player.getUUID());
		if(mine.isEmpty()) return Feedback.fail(s,"Du besitzt kein Grundstück. Mit /plot claim beanspruchst du den Chunk, in dem du stehst.");
		String id = mine.get().getKey(); Plot p = mine.get().getValue(); BlockPos at = player.blockPosition();
		if(!p.dimension().equals(s.getLevel().dimension().identifier().toString())) return Feedback.fail(s,"Dein Grundstück liegt in einer anderen Welt.");
		if(p.contains(at)) return Feedback.fail(s,"Du stehst schon in deinem Grundstück. Stell dich in den Chunk, den du dazunehmen willst.");
		int cx = at.getX() >> 4, cz = at.getZ() >> 4;
		Plot np = p.withBounds(Math.min(p.minX(),cx*16),Math.min(p.minZ(),cz*16),Math.max(p.maxX(),cx*16+15),Math.max(p.maxZ(),cz*16+15));
		int quota = storage.quota(p,TownhallMod.CONFIG.get().city.claimChunksPerPlayer);
		if(np.chunkCount() > quota) return Feedback.fail(s,"Das Grundstück würde " + np.chunkCount() + " Chunks umfassen, erlaubt sind " + quota + ". Mehr Fläche gibt es mit einem Miteigentümer (/plot coowner add) oder per Admin-Freischaltung.");
		var error = storage.resizeProblem(id,np); if(error.isPresent()) return Feedback.fail(s,error.get());
		storage.put(id,np);
		return changed(s,"plot.expand",id + ": erweitert auf " + np.chunkCount() + " von " + quota + " Chunks.");
	}
	private static Component button(String label,String command,ChatFormatting color) {
		return Component.literal(label).withStyle(st -> st.withColor(color).withClickEvent(new ClickEvent.RunCommand(command)));
	}
	private static int coOwnerAdd(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		var s = c.getSource(); var profiles = GameProfileArgument.getGameProfiles(c,"player");
		if(profiles.size()!=1) return Feedback.fail(s,"Genau einen Spieler angeben.");
		var entry = resolve(s,optionalId(c));
		if(entry.isEmpty()) return Feedback.fail(s,optionalId(c)==null ? "Du stehst auf keinem Grundstück und besitzt keins. Stell dich auf das Grundstück oder gib die Kennung an." : "Unbekanntes Grundstück.");
		String id = entry.get().getKey(); Plot p = entry.get().getValue();
		if(!mayManage(s,p,Level.MAIN)) return Feedback.fail(s,"Das darf nur der Haupt-Eigentümer dieses Grundstücks.");
		if(p.owner().isEmpty()) return Feedback.fail(s,"Eine öffentliche Fläche hat keinen Eigentümer, dem man jemanden zuordnen könnte.");
		var target = profiles.iterator().next(); UUID t = target.id();
		if(p.isOwner(t)) return Feedback.fail(s,target.name() + " ist schon Eigentümer dieses Grundstücks.");
		var other = PlotStorage.get(s.getServer()).ownedBy(t);
		if(other.isPresent()) return Feedback.fail(s,target.name() + " besitzt schon ein Grundstück (" + other.get().getKey() + ").");
		ServerPlayer tp = s.getServer().getPlayerList().getPlayer(t);
		if(tp==null) return Feedback.fail(s,target.name() + " ist nicht online. Die Einladung geht nur an Spieler, die gerade auf dem Server sind.");
		String from = s.getPlayer()==null ? "Ein Admin" : s.getPlayer().getGameProfile().name();
		List<Invite> list = INVITES.computeIfAbsent(t,k -> new ArrayList<>()); list.removeIf(i -> i.plotId().equals(id));
		list.add(new Invite(id,from,System.currentTimeMillis()+INVITE_MILLIS));
		tp.sendSystemMessage(Component.literal(from + " lädt dich als Miteigentümer des Grundstücks " + id + " ein. ").withStyle(ChatFormatting.YELLOW)
				.append(button("[Annehmen]","/plot accept " + id,ChatFormatting.GREEN)).append(Component.literal(" ")).append(button("[Ablehnen]","/plot deny " + id,ChatFormatting.RED)));
		AuditLog.record(s,"plot.coowner.invite",id + ": " + target.name() + " (" + t + ")");
		return Feedback.ok(s,"Einladung an " + target.name() + " geschickt. Sie gilt 5 Minuten und muss angenommen werden.");
	}
	private static int coOwnerRemove(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		var profiles = GameProfileArgument.getGameProfiles(c,"player");
		if(profiles.size()!=1) return Feedback.fail(c.getSource(),"Genau einen Spieler angeben.");
		var target = profiles.iterator().next(); String t = target.id().toString();
		var s = c.getSource(); var entry = resolve(s,optionalId(c));
		if(entry.isPresent() && mayManage(s,entry.get().getValue(),Level.MAIN)) {
			Plot p = entry.get().getValue();
			if(!p.coOwners().contains(t)) return Feedback.fail(s,target.name() + " ist kein Miteigentümer dieses Grundstücks.");
			List<String> co = new ArrayList<>(p.coOwners()); co.remove(t); Plot np = p.withCoOwners(co);
			int quota = PlotStorage.get(s.getServer()).quota(np,TownhallMod.CONFIG.get().city.claimChunksPerPlayer);
			if(!CityAccess.isAdmin(s) && np.chunkCount() > quota) return Feedback.fail(s,"Ohne " + target.name() + " dürfte das Grundstück nur " + quota + " Chunks haben, es hat aber " + np.chunkCount() + ". Entfernen ist erst möglich, wenn das Kontingent wieder reicht.");
		}
		return update(c,Level.MAIN,p -> { List<String> co = new ArrayList<>(p.coOwners()); co.remove(t); return p.withCoOwners(co); },"coowner remove: " + target.name() + " (" + t + ")");
	}
	private static int respond(CommandContext<CommandSourceStack> c,boolean accept) {
		var s = c.getSource(); ServerPlayer player = s.getPlayer();
		if(player==null) return Feedback.fail(s,"Nur Spieler können Einladungen beantworten.");
		UUID who = player.getUUID(); List<Invite> list = INVITES.getOrDefault(who,new ArrayList<>());
		long now = System.currentTimeMillis(); list.removeIf(i -> i.expires() < now);
		String id = optionalId(c);
		List<Invite> match = list.stream().filter(i -> id==null || i.plotId().equals(id)).toList();
		if(match.isEmpty()) return Feedback.fail(s,"Keine offene Einladung" + (id==null ? "." : " für " + id + "."));
		if(match.size()>1) return Feedback.fail(s,"Mehrere Einladungen offen: " + String.join(", ",match.stream().map(Invite::plotId).toList()) + ". Gib die Kennung an.");
		Invite inv = match.get(0); list.remove(inv);
		if(!accept) return Feedback.ok(s,"Einladung zu " + inv.plotId() + " abgelehnt.");
		var storage = PlotStorage.get(s.getServer()); Plot p = storage.plots().get(inv.plotId());
		if(p==null || p.owner().isEmpty()) return Feedback.fail(s,"Dieses Grundstück gibt es nicht mehr.");
		var mine = storage.ownedBy(who);
		if(mine.isPresent()) return Feedback.fail(s,"Du besitzt schon ein Grundstück (" + mine.get().getKey() + ") und kannst nicht zusätzlich Miteigentümer werden.");
		List<String> co = new ArrayList<>(p.coOwners()); co.add(who.toString());
		storage.put(inv.plotId(),p.withCoOwners(co)); INVITES.remove(who);
		return changed(s,"plot.coowner",inv.plotId() + ": Miteigentümer " + player.getGameProfile().name() + " (" + who + ")");
	}
	private static int unclaim(CommandContext<CommandSourceStack> c,boolean confirm) {
		var s = c.getSource(); UUID who = s.getPlayer()==null ? CONSOLE : s.getPlayer().getUUID(); long now = System.currentTimeMillis();
		if(!confirm) {
			var entry = resolve(s,null);
			if(entry.isEmpty() && s.getPlayer()!=null) entry = PlotStorage.get(s.getServer()).ownedBy(who);
			if(entry.isEmpty()) return Feedback.fail(s,"Hier ist kein Grundstück eingerichtet.");
			if(!mayManage(s,entry.get().getValue(),Level.MAIN)) return Feedback.fail(s,"Das darf nur der Haupt-Eigentümer dieses Grundstücks.");
			UNCLAIMS.put(who,new Pending(entry.get().getKey(),now+CONFIRM_MILLIS));
			String pid = entry.get().getKey();
			s.sendSuccess(() -> Component.literal("Grundstück " + pid + " aufgeben? Die Bauwerke bleiben stehen, der Schutz endet. ").withStyle(ChatFormatting.YELLOW).append(button("[Bestätigen]","/plot unclaim confirm",ChatFormatting.RED)),false);
			return 1;
		}
		Pending pending = UNCLAIMS.remove(who);
		if(pending==null || pending.expires() < now) return Feedback.fail(s,"Keine Bestätigung offen. Zuerst /plot unclaim eingeben.");
		var storage = PlotStorage.get(s.getServer()); Plot p = storage.plots().get(pending.plotId());
		if(p==null) return Feedback.fail(s,"Unbekanntes Grundstück.");
		if(!mayManage(s,p,Level.MAIN)) return Feedback.fail(s,"Das darf nur der Haupt-Eigentümer dieses Grundstücks.");
		storage.remove(pending.plotId());
		return changed(s,"plot.unclaim","Grundstück " + pending.plotId() + " aufgegeben.");
	}
	private static int bonus(CommandContext<CommandSourceStack> c,int chunks) throws CommandSyntaxException {
		var s = c.getSource(); var profiles = GameProfileArgument.getGameProfiles(c,"player");
		if(profiles.size()!=1) return Feedback.fail(s,"Genau einen Spieler angeben.");
		var target = profiles.iterator().next(); var storage = PlotStorage.get(s.getServer());
		if(chunks<0) return Feedback.ok(s,"Zusatzkontingent von " + target.name() + ": " + storage.bonus(target.id()) + " Chunks (plus " + TownhallMod.CONFIG.get().city.claimChunksPerPlayer + " Grundkontingent).");
		storage.setBonus(target.id(),chunks);
		return changed(s,"plot.bonus","Zusatzkontingent von " + target.name() + " (" + target.id() + "): " + chunks + " Chunks.");
	}
	private static void helpLine(java.util.List<Component> out,String command,String purpose) {
		String base = command.split(" [<\\[]")[0];
		out.add(Component.literal("/plot " + command).withStyle(st -> st.withColor(ChatFormatting.AQUA).withClickEvent(new ClickEvent.SuggestCommand("/plot " + base + " ")))
				.append(Component.literal(" – " + purpose).withStyle(ChatFormatting.GRAY)));
	}
	/** Command lines shown to the executor; admin commands only for admins. */
	public static List<Component> helpLines(boolean admin) {
		List<Component> l = new ArrayList<>();
		l.add(Component.literal("Grundstücke – <id> ist optional: ohne sie gilt das Grundstück, auf dem du stehst.").withStyle(ChatFormatting.GOLD));
		helpLine(l,"claim [id]","den Chunk, in dem du stehst, als dein Grundstück einrichten (eins pro Spieler)");
		helpLine(l,"expand","Grundstück um den Chunk erweitern, in dem du stehst (solange das Kontingent reicht)");
		helpLine(l,"coowner add <spieler>","Miteigentümer einladen (zählt als zweiter Chunk, nur Haupt-Eigentümer)");
		helpLine(l,"coowner remove <spieler>","Miteigentümer entfernen");
		helpLine(l,"accept [id]","Einladung als Miteigentümer annehmen");
		helpLine(l,"deny [id]","Einladung ablehnen");
		helpLine(l,"trust [id] <spieler>","Mitbauer hinzufügen");
		helpLine(l,"untrust [id] <spieler>","Mitbauer entfernen");
		helpLine(l,"manager [id] <spieler>","Manager ernennen, darf Mitbauer und Besucherzugang verwalten");
		helpLine(l,"unmanager [id] <spieler>","Manager entfernen");
		helpLine(l,"publicuse [id] <true|false>","Besucher dürfen Türen und Knöpfe benutzen");
		helpLine(l,"transfer [id] <spieler>","Grundstück übergeben");
		helpLine(l,"unclaim","Grundstück aufgeben, mit Bestätigung");
		helpLine(l,"members [id]","Eigentümer, Manager und Mitbauer anzeigen");
		helpLine(l,"info [id]","Größe, Kontingent und Rechte anzeigen");
		helpLine(l,"list","alle Grundstücke auflisten");
		if(admin) {
			l.add(Component.literal("Nur für Admins:").withStyle(ChatFormatting.GOLD));
			helpLine(l,"enabled <true|false>","Grundstücksschutz ein- oder ausschalten");
			helpLine(l,"pos1 / pos2","Eckpunkte wählen");
			helpLine(l,"create <id> [spieler]","Bereich anlegen");
			helpLine(l,"delete <id>","Bereich entfernen");
			helpLine(l,"owner <id> <spieler>","Eigentümer setzen");
			helpLine(l,"unowned <id>","Eigentümer entfernen");
			helpLine(l,"role <id> <rolle>","Stadtrolle zum Bauen zulassen");
			helpLine(l,"unrole <id> <rolle>","Rollenfreigabe entfernen");
			helpLine(l,"bonus <spieler> [chunks]","Zusatzkontingent setzen oder zeigen");
		}
		return l;
	}
	private static int help(CommandSourceStack s) {
		for(Component line : helpLines(CityAccess.isAdmin(s))) s.sendSuccess(() -> line,false);
		return 1;
	}
}
