package dev.townhall.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import dev.townhall.TownhallMod;
import dev.townhall.config.ConfigManager;
import dev.townhall.config.TownhallConfig;
import dev.townhall.dimension.DimensionSettings;
import dev.townhall.mixin.CommandSourceStackAccessor;
import dev.townhall.storage.PlayerState;
import dev.townhall.storage.ReturnLocation;
import dev.townhall.storage.ReturnPositionStorage;
import dev.townhall.teleport.ConfinementService;
import dev.townhall.teleport.TeleportService;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.gamerules.GameRules;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import static dev.townhall.command.Feedback.fail;
import static dev.townhall.command.Feedback.ok;

/**
 * One root command per configured location, e.g. /townhall and /gefaengnis:
 * <pre>
 * /&lt;cmd&gt;                    go there (operators only for adminOnly locations)
 * /&lt;cmd&gt; return             go back to where you were before
 * /&lt;cmd&gt; send &lt;player&gt; [minutes]  operator: send a player there, optionally for that much online time
 * /&lt;cmd&gt; return &lt;player&gt;    operator: bring a player back (also releases confined players)
 * /&lt;cmd&gt; setspawn           operator: set this location's spawn to your position
 * /&lt;cmd&gt; reload | status | debug &lt;player&gt; | clearreturn &lt;player&gt;   operator, same for every location
 * </pre>
 * Each node looks up its location by command name on every use, so /location changes (new, renamed, removed places)
 * work at once: new names are added with refreshCommands(), old names simply stop matching and disappear.
 */
public final class TownhallCommand {

	private static final Map<UUID, Long> LAST_USE = new HashMap<>();

	private TownhallCommand() {}

	/**
	 * Root requirement of every location command. Its class also marks the root nodes this mod built (nodes stay in Brigadier
	 * until restart, even when unused), so a name is only reused when the node there is ours.
	 * Visible for operators, for everyone at normal locations, and at adminOnly locations for players an operator sent there
	 * (so they can use return).
	 */
	private record MayUse(String command) implements Predicate<CommandSourceStack> {
		@Override
		public boolean test(CommandSourceStack src) {
			return idFor(command).map(id -> isOperator(src) || !location(id).adminOnly || staysAt(src, id)).orElse(false);
		}
	}

	/** Server stopped: the cooldown is in memory only and starts fresh with the next server. */
	public static void reset() {
		LAST_USE.clear();
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		for (TownhallConfig.Location location : TownhallMod.CONFIG.get().locations.values()) addRoot(dispatcher, location.command);
	}

	/** Adds root commands for locations that don't have one yet and sends everyone the new command list. */
	public static void refreshCommands(MinecraftServer server) {
		CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
		for (TownhallConfig.Location location : TownhallMod.CONFIG.get().locations.values()) addRoot(dispatcher, location.command);
		sendCommandsToAll(server);
	}

	/** Sends every online player their command tree again (after roots or requirements changed). */
	public static void sendCommandsToAll(MinecraftServer server) {
		server.getPlayerList().getPlayers().forEach(p -> server.getCommands().sendCommands(p));
	}

	/** Registers the root unless the name exists; a name owned by Minecraft or another mod is skipped (Brigadier would merge into it). */
	private static void addRoot(CommandDispatcher<CommandSourceStack> dispatcher, String command) {
		CommandNode<CommandSourceStack> existing = dispatcher.getRoot().getChild(command);
		if (existing == null) {
			dispatcher.register(build(command));
		} else if (!isOurs(existing)) {
			TownhallMod.LOGGER.error("/{} is already a command of Minecraft or another mod; the location using it gets no command. Pick another name.", command);
		}
	}

	private static boolean isOurs(CommandNode<CommandSourceStack> node) {
		return node.getRequirement() instanceof MayUse;
	}

	/** True if the name is already a command of Minecraft or another mod (ours may be reused). */
	public static boolean isForeignCommand(MinecraftServer server, String name) {
		CommandNode<CommandSourceStack> node = server.getCommands().getDispatcher().getRoot().getChild(name);
		return node != null && !isOurs(node);
	}

	/** Problems of a new config that only the running server can see; /townhall reload rejects the file for them. */
	public static List<String> reloadProblems(MinecraftServer server, TownhallConfig next) {
		List<String> errors = new ArrayList<>();
		TownhallConfig current = TownhallMod.CONFIG.get();
		ReturnPositionStorage storage = ReturnPositionStorage.get(server);
		Map<String, Long> held = new java.util.TreeMap<>();
		for (UUID id : storage.activePlayers()) {
			storage.state(id).location().filter(loc -> !next.locations.containsKey(loc)).ifPresent(loc -> held.merge(loc, 1L, Long::sum));
		}
		held.forEach((loc, count) -> errors.add("locations." + loc + " fehlt, aber dort sind noch " + count + " Spieler eingesperrt. Lass sie zuerst mit /"
				+ current.location(loc).map(l -> l.command).orElse(loc) + " return <player> frei oder behalte den Ort."));
		next.locations.forEach((id, loc) -> {
			if (isForeignCommand(server, loc.command)) errors.add("locations." + id + ".command: /" + loc.command + " ist schon ein Befehl von Minecraft oder einer anderen Mod");
		});
		return errors;
	}

	/** The location that currently uses this command name. */
	public static Optional<String> idFor(String command) {
		return TownhallMod.CONFIG.get().locations.entrySet().stream()
				.filter(e -> command.equals(e.getValue().command)).map(Map.Entry::getKey).findFirst();
	}

	@FunctionalInterface
	private interface ForLocation {
		int run(String id) throws CommandSyntaxException;
	}

	private static int at(CommandContext<CommandSourceStack> ctx, String command, ForLocation action) throws CommandSyntaxException {
		Optional<String> id = idFor(command);
		return id.isPresent() ? action.run(id.get()) : fail(ctx.getSource(), "Diesen Ort gibt es nicht mehr.");
	}

	public static LiteralArgumentBuilder<CommandSourceStack> build(String command) {
		Predicate<CommandSourceStack> op = TownhallCommand::isOperator;
		return Commands.literal(command)
				.executes(ctx -> at(ctx, command, id -> enterSelf(ctx, id)))
				.then(Commands.literal("return")
						.executes(TownhallCommand::returnSelf)
						.then(Commands.argument("player", EntityArgument.player()).requires(op).executes(TownhallCommand::returnOther)))
				.then(Commands.literal("send").requires(op)
						.then(Commands.argument("player", EntityArgument.player()).executes(ctx -> at(ctx, command, id -> sendOther(ctx, id, Optional.empty())))
								.then(Commands.argument("minutes", IntegerArgumentType.integer(1, 525_600))
										.executes(ctx -> at(ctx, command, id -> sendOther(ctx, id, Optional.of(IntegerArgumentType.getInteger(ctx, "minutes"))))))))
				.then(Commands.literal("setspawn").requires(op).executes(ctx -> at(ctx, command, id -> setSpawn(ctx, id))))
				.then(Commands.literal("reload").requires(op).executes(TownhallCommand::reload))
				.then(difficultyCommand().requires(op))
				.then(worldRuleCommand().requires(op))
				.then(Commands.literal("status").requires(op).executes(TownhallCommand::status))
				.then(Commands.literal("afktime").requires(op)
						.then(Commands.argument("minutes", IntegerArgumentType.integer(0, 1440)).executes(ActivityCommands::setAfkTime)))
				.then(Commands.literal("sleep").requires(op).executes(TownhallCommand::showSleep)
						.then(Commands.argument("percent", IntegerArgumentType.integer(0, 100)).executes(TownhallCommand::setSleep)))
				.then(Commands.literal("deathsintab").requires(op)
						.then(Commands.literal("on").executes(ctx -> setDeathsInTab(ctx, true)))
						.then(Commands.literal("off").executes(ctx -> setDeathsInTab(ctx, false))))
				.then(Commands.literal("end").requires(op).executes(TownhallCommand::showEnd)
						.then(Commands.literal("on").executes(ctx -> setEnd(ctx, true)))
						.then(Commands.literal("off").executes(ctx -> setEnd(ctx, false))))
				.then(Commands.literal("debug").requires(op)
						.then(Commands.argument("player", GameProfileArgument.gameProfile()).executes(TownhallCommand::debug)))
				.then(Commands.literal("clearreturn").requires(op)
						.then(Commands.argument("player", GameProfileArgument.gameProfile()).executes(TownhallCommand::clearReturn)))
				.requires(new MayUse(command));
	}

	// ------------------------------------------------------------ player commands

	private static int enterSelf(CommandContext<CommandSourceStack> ctx, String id) {
		ServerPlayer player = requirePlayer(ctx.getSource());
		if (player == null) return 0;
		TownhallConfig config = TownhallMod.CONFIG.get();
		TownhallConfig.Location location = location(id);
		boolean operator = isOperatorSelf(ctx.getSource());
		if (location.adminOnly && !operator) return fail(ctx.getSource(), "Nur Operatoren dürfen dorthin.");
		if (onCooldown(ctx.getSource(), player)) return 0;
		return switch (TeleportService.sendTo(player, id, config, operator, false, Optional.empty())) {
			case SENT -> ok(ctx.getSource(), location.arrivedMessage);
			case ALREADY_THERE -> fail(ctx.getSource(), location.alreadyHereMessage.formatted(location.command));
			case UNAVAILABLE -> fail(ctx.getSource(), config.messages.locationUnavailable.formatted(location.dimension));
			case CONFINED -> fail(ctx.getSource(), confinedMessage(config, player));
			case FAILED -> fail(ctx.getSource(), "Teleport fehlgeschlagen. Sag bitte einem Operator Bescheid.");
		};
	}

	private static int returnSelf(CommandContext<CommandSourceStack> ctx) {
		ServerPlayer player = requirePlayer(ctx.getSource());
		if (player == null || onCooldown(ctx.getSource(), player)) return 0;
		TownhallConfig config = TownhallMod.CONFIG.get();
		TeleportService.ReturnResult result = TeleportService.returnPlayer(player, config, isOperatorSelf(ctx.getSource()));
		if (result == TeleportService.ReturnResult.CONFINED) return fail(ctx.getSource(), confinedMessage(config, player));
		if (result == TeleportService.ReturnResult.NO_POSITION && isStuck(player, config)) {
			// Someone who reached a location another way must not get stuck there.
			return releaseToFallback(player, config)
					? ok(ctx.getSource(), config.messages.noReturnPosition + " Du wurdest stattdessen zum Spawn geschickt.")
					: fail(ctx.getSource(), "Teleport fehlgeschlagen. Sag bitte einem Operator Bescheid.");
		}
		return reportReturn(ctx.getSource(), config, result, null);
	}

	// ------------------------------------------------------------ operator commands

	private static int sendOther(CommandContext<CommandSourceStack> ctx, String id, Optional<Integer> minutes) throws CommandSyntaxException {
		ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
		TownhallConfig config = TownhallMod.CONFIG.get();
		TownhallConfig.Location location = location(id);
		String name = target.getPlainTextName();
		Optional<Long> duration = minutes.map(m -> m * 60_000L);
		return switch (TeleportService.sendTo(target, id, config, true, true, duration)) {
			case SENT -> {
				String forMinutes = minutes.map(m -> " für " + m + (m == 1 ? " Minute" : " Minuten")).orElse("");
				target.sendSystemMessage(Component.literal(location.arrivedMessage + (forMinutes.isEmpty() ? "" : " (" + forMinutes.strip() + " Online-Zeit)"))
						.withStyle(ChatFormatting.GREEN));
				boolean confined = ReturnPositionStorage.get(ctx.getSource().getServer()).state(target.getUUID()).confined();
				String release = confined ? " Der Spieler kommt nicht von allein raus; vorzeitig freilassen mit /" + location.command + " return " + name + "." : "";
				yield ok(ctx.getSource(), name + " geschickt. Ziel: " + config.displayName(id) + (forMinutes.isEmpty() ? "." : "," + forMinutes + " (Online-Zeit).") + release);
			}

			case ALREADY_THERE, CONFINED -> fail(ctx.getSource(), name + " ist schon dort.");
			case UNAVAILABLE -> fail(ctx.getSource(), config.messages.locationUnavailable.formatted(location.dimension));
			case FAILED -> fail(ctx.getSource(), "Teleport von " + name + " fehlgeschlagen, siehe Server-Log.");
		};
	}

	private static int returnOther(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
		TownhallConfig config = TownhallMod.CONFIG.get();
		TeleportService.ReturnResult result = TeleportService.returnPlayer(target, config, true);
		if (result == TeleportService.ReturnResult.NO_POSITION) {
			if (!isStuck(target, config)) return fail(ctx.getSource(), "Für " + target.getPlainTextName() + " ist keine Rückkehrposition gespeichert.");
			// In a location (e.g. jailed) without a saved spot: release and send to spawn instead of leaving them stuck.
			if (!releaseToFallback(target, config)) return fail(ctx.getSource(), "Teleport von " + target.getPlainTextName() + " fehlgeschlagen, siehe Server-Log.");
			target.sendSystemMessage(Component.literal(config.messages.returnedFallback).withStyle(ChatFormatting.GREEN));
			return ok(ctx.getSource(), "Für " + target.getPlainTextName() + " war keine Rückkehrposition gespeichert, daher wurde der Spieler freigelassen und zum Spawn geschickt.");
		}
		return reportReturn(ctx.getSource(), config, result, target);
	}

	private static int setSpawn(CommandContext<CommandSourceStack> ctx, String id) {
		ServerPlayer player = requirePlayer(ctx.getSource());
		if (player == null) return 0;
		TownhallConfig config = TownhallMod.CONFIG.get();
		TownhallConfig.Location location = location(id);
		if (!player.level().dimension().equals(location.dimensionKey())) {
			return fail(ctx.getSource(), "Stell dich in " + location.dimension + ", um den Spawn zu setzen (" + config.displayName(id) + ").");
		}
		location.spawn = TownhallConfig.Spot.of(player);
		TownhallMod.LOGGER.info("{} set the spawn of {} to {}", player.getPlainTextName(), id, formatSpot(location.spawn));
		if (!saveConfig(ctx.getSource())) return 0;
		return ok(ctx.getSource(), "Spawn gesetzt (" + config.displayName(id) + "): " + formatSpot(location.spawn) + ".");
	}

	/** difficulty &lt;dimension&gt; [peaceful|easy|normal|hard|default] */
	private static LiteralArgumentBuilder<CommandSourceStack> difficultyCommand() {
		var dimension = Commands.argument("dimension", DimensionArgument.dimension()).executes(TownhallCommand::showDifficulty);
		for (String value : List.of("peaceful", "easy", "normal", "hard", "default")) {
			dimension.then(Commands.literal(value).executes(ctx -> setDifficulty(ctx, value)));
		}
		return Commands.literal("difficulty").then(dimension);
	}

	/**
	 * worldrule &lt;dimension&gt; [pvp|build|hunger|fallDamage|mobs|fire|explosions|leafDecay] [true|false|default]
	 * worldrule &lt;dimension&gt; time [day|noon|night|midnight|&lt;ticks&gt;|default]
	 * worldrule &lt;dimension&gt; weather [clear|rain|thunder|default]
	 */
	private static LiteralArgumentBuilder<CommandSourceStack> worldRuleCommand() {
		var dimension = Commands.argument("dimension", DimensionArgument.dimension()).executes(TownhallCommand::showWorldRules);
		for (String rule : TownhallConfig.WORLD_RULES) {
			var ruleNode = Commands.literal(rule);
			for (String value : List.of("true", "false", "default")) {
				ruleNode.then(Commands.literal(value).executes(ctx -> setWorldRule(ctx, rule, value)));
			}
			dimension.then(ruleNode);
		}
		var time = Commands.literal("time");
		for (String value : List.of("day", "noon", "night", "midnight", "default")) {
			time.then(Commands.literal(value).executes(ctx -> setWorldRule(ctx, "time", value)));
		}
		time.then(Commands.argument("ticks", IntegerArgumentType.integer(0, 23999))
				.executes(ctx -> setWorldRule(ctx, "time", String.valueOf(IntegerArgumentType.getInteger(ctx, "ticks")))));
		dimension.then(time);
		var weather = Commands.literal("weather");
		for (String value : List.of("clear", "rain", "thunder", "default")) {
			weather.then(Commands.literal(value).executes(ctx -> setWorldRule(ctx, "weather", value)));
		}
		dimension.then(weather);
		return Commands.literal("worldrule").then(dimension);
	}

	private static int setEnd(CommandContext<CommandSourceStack> ctx, boolean on) {
		TownhallMod.CONFIG.get().endEnabled = on;
		if (!saveConfig(ctx.getSource())) return 0;
		return showEnd(ctx);
	}

	private static int showEnd(CommandContext<CommandSourceStack> ctx) {
		return ok(ctx.getSource(), TownhallMod.CONFIG.get().endEnabled
				? "Das End ist offen: Endportale funktionieren."
				: "Das End ist geschlossen: Endportale tun nichts. Operatoren können weiter per /tp hin, der Rückweg funktioniert.");
	}

	private static int setDeathsInTab(CommandContext<CommandSourceStack> ctx, boolean on) {
		TownhallMod.CONFIG.get().deathsInTab = on;
		dev.townhall.display.DeathsInTab.apply(ctx.getSource().getServer());
		if (!saveConfig(ctx.getSource())) return 0;
		return ok(ctx.getSource(), on ? "Tode werden in der Tab-Liste angezeigt." : "Tode werden nicht mehr in der Tab-Liste angezeigt.");
	}

	/**
	 * sleep [0-100]: vanilla game rule players_sleeping_percentage (saved with the world, not in our config).
	 * Vanilla counts per world: sleepers in a world against the players in that same world.
	 */
	private static int setSleep(CommandContext<CommandSourceStack> ctx) {
		int percent = IntegerArgumentType.getInteger(ctx, "percent");
		var server = ctx.getSource().getServer();
		server.getGameRules().set(GameRules.PLAYERS_SLEEPING_PERCENTAGE, percent, server); // like /gamerule: notifies and updates
		TownhallMod.LOGGER.info("Sleeping percentage set to {}", percent);
		return showSleep(ctx);
	}

	private static int showSleep(CommandContext<CommandSourceStack> ctx) {
		var server = ctx.getSource().getServer();
		int percent = server.getGameRules().get(GameRules.PLAYERS_SLEEPING_PERCENTAGE);
		return ok(ctx.getSource(), "Schlafen: " + percent + " % der Spieler in einer Welt müssen schlafen, um die Nacht zu überspringen. " + sleepExample(server, percent));
	}

	private static String sleepExample(MinecraftServer server, int percent) {
		String others = "Spieler in anderen Welten (z. B. im Rathaus) zählen nicht.";
		if (percent > 100) return "Niemand kann die Nacht überspringen. " + others;
		long players = server.overworld().players().stream().filter(p -> !p.isSpectator()).count();
		if (players == 0) return "Gerade ist niemand in der Oberwelt. " + others;
		long needed = Math.max(1, (long) Math.ceil(players * percent / 100.0)); // like vanilla SleepStatus.sleepersNeeded
		return "Oberwelt jetzt: " + needed + " von " + players + " müssen schlafen. " + others;
	}

	private static int showWorldRules(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerLevel level = DimensionArgument.getDimension(ctx, "dimension");
		DimensionSettings.Rules r = DimensionSettings.of(level.dimension());
		ctx.getSource().sendSuccess(() -> Component.empty()
				.append(line("Welt", level.dimension().identifier().toString()))
				.append(line("difficulty", level.getDifficulty().getSerializedName()))
				.append(line("pvp", String.valueOf(r.pvp())))
				.append(line("build", r.build() + (r.build() ? "" : " (Operatoren dürfen trotzdem)")))
				.append(line("builders", BuilderCommand.names(level.dimension().identifier().toString())))
				.append(line("hunger", String.valueOf(r.hunger())))
				.append(line("fallDamage", String.valueOf(r.fallDamage())))
				.append(line("mobs", String.valueOf(r.mobs())))
				.append(line("fire", String.valueOf(r.fire())))
				.append(line("explosions", String.valueOf(r.explosions())))
				.append(line("leafDecay", String.valueOf(r.leafDecay())))
				.append(line("time", r.time() == null ? "normal" : "immer " + r.time()))
				.append(line("weather", r.weather() == null ? "normal" : "immer " + r.weather())), false);
		return 1;
	}

	private static int setWorldRule(CommandContext<CommandSourceStack> ctx, String rule, String value) throws CommandSyntaxException {
		ServerLevel level = DimensionArgument.getDimension(ctx, "dimension");
		String id = level.dimension().identifier().toString();
		TownhallConfig config = TownhallMod.CONFIG.get();
		TownhallConfig.DimensionRules rules = config.dimensions.computeIfAbsent(id, k -> new TownhallConfig.DimensionRules());
		boolean reset = value.equals("default");
		Boolean v = reset ? null : Boolean.valueOf(value);
		switch (rule) {
			case "pvp" -> rules.pvp = v;
			case "build" -> rules.build = v;
			case "hunger" -> rules.hunger = v;
			case "fallDamage" -> rules.fallDamage = v;
			case "mobs" -> rules.mobs = v;
			case "fire" -> rules.fire = v;
			case "explosions" -> rules.explosions = v;
			case "leafDecay" -> rules.leafDecay = v;
			case "time" -> rules.time = reset ? null : value;
			case "weather" -> rules.weather = reset ? null : value;
			default -> throw new IllegalArgumentException(rule);
		}
		DimensionSettings.rebuild(config);
		if (rule.equals("time")) DimensionSettings.syncAll(ctx.getSource().getServer());
		TownhallMod.LOGGER.info("World rule {} of {} set to {}", rule, id, value);
		if (!saveConfig(ctx.getSource())) return 0;
		String now = !reset ? value
				: rule.equals("time") || rule.equals("weather") ? "Standard (normal)" : "Standard (an)";
		return ok(ctx.getSource(), rule + " in " + id + " ist jetzt " + now + ".");
	}

	private static int showDifficulty(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerLevel level = DimensionArgument.getDimension(ctx, "dimension");
		boolean overridden = DimensionSettings.difficulty(level.dimension()) != null;
		String id = level.dimension().identifier().toString();
		return ok(ctx.getSource(), "Schwierigkeit von " + id + ": " + level.getDifficulty().getSerializedName()
				+ (overridden ? " (für diese Welt gesetzt)" : " (Server-Standard)"));
	}

	private static int setDifficulty(CommandContext<CommandSourceStack> ctx, String value) throws CommandSyntaxException {
		ServerLevel level = DimensionArgument.getDimension(ctx, "dimension");
		String id = level.dimension().identifier().toString();
		TownhallConfig config = TownhallMod.CONFIG.get();
		if (value.equals("default")) {
			TownhallConfig.DimensionRules rules = config.dimensions.get(id);
			if (rules != null) rules.difficulty = null;
		} else {
			config.dimensions.computeIfAbsent(id, k -> new TownhallConfig.DimensionRules()).difficulty = value;
		}
		DimensionSettings.rebuild(config);
		DimensionSettings.syncAll(ctx.getSource().getServer());
		TownhallMod.LOGGER.info("Difficulty of {} set to {}", id, value);
		if (!saveConfig(ctx.getSource())) return 0;
		return ok(ctx.getSource(), "Schwierigkeit von " + id + " ist jetzt " + level.getDifficulty().getSerializedName()
				+ (value.equals("default") ? " (Server-Standard)" : "") + ".");
	}

	private static int reload(CommandContext<CommandSourceStack> ctx) {
		var server = ctx.getSource().getServer();
		List<String> errors = TownhallMod.CONFIG.reload(next -> reloadProblems(server, next));
		if (!errors.isEmpty()) {
			ctx.getSource().sendFailure(Component.literal("Konfiguration nicht neu geladen, die bisherige bleibt aktiv (Änderungen per Befehl werden erst gespeichert, wenn ein Reload klappt):\n - "
					+ String.join("\n - ", errors)));
			return 0;
		}
		TownhallConfig config = TownhallMod.CONFIG.get();
		// Locations that became escapable in the file free their prisoners, like /location set <id> escapable true.
		config.locations.forEach((id, loc) -> {
			if (loc.isEscapable()) ReturnPositionStorage.get(server).releaseConfinedAt(id);
		});
		DimensionSettings.rebuild(config);
		DimensionSettings.syncAll(server);
		List<String> missing = config.locations.values().stream()
				.filter(l -> server.getLevel(l.dimensionKey()) == null).map(l -> l.dimension).distinct().toList();
		StringBuilder msg = new StringBuilder("Rathaus-Konfiguration neu geladen.");
		if (!missing.isEmpty()) msg.append(" Nicht geladen: ").append(String.join(", ", missing)).append(". Geladen: ").append(TownhallMod.loadedDimensions(server)).append('.');
		refreshCommands(server);
		dev.townhall.display.DeathsInTab.apply(server);
		// Builders removed in the file lose creative and WorldEdit right away, not only on their next world change.
		server.getPlayerList().getPlayers().forEach(dev.townhall.protection.Protection::enforceBuilderMode);
		ctx.getSource().sendSuccess(() -> Component.literal(msg.toString()).withStyle(missing.isEmpty() ? ChatFormatting.GREEN : ChatFormatting.YELLOW), true);
		return 1;
	}

	private static int status(CommandContext<CommandSourceStack> ctx) {
		TownhallConfig config = TownhallMod.CONFIG.get();
		var server = ctx.getSource().getServer();
		ReturnPositionStorage storage = ReturnPositionStorage.get(server);
		MutableComponent text = Component.empty();
		config.locations.forEach((id, loc) -> {
			boolean available = server.getLevel(loc.dimensionKey()) != null;
			text.append(line("/" + loc.command, loc.dimension + (available ? "" : " (NICHT GELADEN)") + " bei " + formatSpot(loc.spawn)
					+ (loc.adminOnly ? ", nur Admins" : "") + (loc.isEscapable() ? "" : ", kein Ausbruch (Radius " + loc.confineRadius + ")")));
		});
		text.append(line("Weltregeln", config.dimensions.isEmpty() ? "keine" : String.join(", ", config.dimensions.keySet())))
				.append(line("Gespeicherte Rückkehrpositionen", String.valueOf(storage.returnPositionCount())))
				.append(line("Eingesperrt / befristet", activeList(server, storage)))
				.append(line("Config", (TownhallMod.CONFIG.loadedFromFile() ? "geladen aus " + TownhallMod.CONFIG.path().getFileName() : "eingebaute Standardwerte (Datei ungültig)")
						+ (TownhallMod.CONFIG.canSave() ? "" : ", Datei fehlerhaft: Änderungen werden NICHT gespeichert, bis /townhall reload klappt")))
				.append(line("Geladene Dimensionen", TownhallMod.loadedDimensions(server)));
		ctx.getSource().sendSuccess(() -> text, false);
		return 1;
	}

	private static int debug(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		var server = ctx.getSource().getServer();
		for (NameAndId profile : GameProfileArgument.getGameProfiles(ctx, "player")) {
			ServerPlayer online = server.getPlayerList().getPlayer(profile.id());
			PlayerState state = ReturnPositionStorage.get(server).state(profile.id());
			MutableComponent text = Component.empty()
					.append(line("Spieler", profile.name() + " (" + profile.id() + ")"))
					.append(line("Aktuelle Dimension", online != null ? online.level().dimension().identifier().toString() : "offline"))
					.append(line("Letzter Ort", state.location().orElse("keiner") + (state.confined() ? " (eingesperrt)" : "")))
					.append(line("Restzeit", state.remainingMillis().map(ConfinementService::formatDuration).orElse("-")));
			if (state.returnPosition().isEmpty()) {
				text.append(line("Gespeicherte Rückkehrposition", "keine"));
			} else {
				ReturnLocation loc = state.returnPosition().get();
				text.append(line("Gespeicherte Rückkehrposition", loc.dimension().identifier().toString()))
						.append(line("X", fmt(loc.x()))).append(line("Y", fmt(loc.y()))).append(line("Z", fmt(loc.z())))
						.append(line("Yaw", fmt(loc.yaw()))).append(line("Pitch", fmt(loc.pitch())))
						.append(line("Gespeichert am", Instant.ofEpochMilli(loc.timestamp()).toString()
								+ loc.playerName().map(name -> " (als " + name + ")").orElse("")));
			}
			ctx.getSource().sendSuccess(() -> text, false);
		}
		return 1;
	}

	private static int clearReturn(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ReturnPositionStorage storage = ReturnPositionStorage.get(ctx.getSource().getServer());
		int cleared = 0;
		for (NameAndId profile : GameProfileArgument.getGameProfiles(ctx, "player")) {
			if (storage.remove(profile.id())) {
				cleared++;
				ServerPlayer online = ctx.getSource().getServer().getPlayerList().getPlayer(profile.id());
				if (online != null) TeleportService.resendCommands(online);
				ok(ctx.getSource(), "Gespeicherte Position und Einsperrung von " + profile.name() + " gelöscht.");
			} else {
				fail(ctx.getSource(), "Für " + profile.name() + " ist nichts gespeichert.");
			}
		}
		return cleared;
	}

	// ------------------------------------------------------------ helpers

	private static TownhallConfig.Location location(String id) {
		return TownhallMod.CONFIG.get().location(id).orElseThrow();
	}

	/** In a location world, or still marked as being in a location. */
	private static boolean isStuck(ServerPlayer player, TownhallConfig config) {
		return config.isLocationDimension(player.level().dimension())
				|| !ReturnPositionStorage.get(player.level().getServer()).state(player.getUUID()).isEmpty();
	}

	private static boolean releaseToFallback(ServerPlayer player, TownhallConfig config) {
		if (!TeleportService.sendToFallback(player, config)) return false;
		ReturnPositionStorage.get(player.level().getServer()).set(player.getUUID(), PlayerState.EMPTY);
		TeleportService.resendCommands(player);
		return true;
	}

	/** The player's stored state names this location (an operator sent them there). */
	private static boolean staysAt(CommandSourceStack src, String id) {
		return src.getEntity() instanceof ServerPlayer player
				&& ReturnPositionStorage.get(src.getServer()).state(player.getUUID()).location().map(id::equals).orElse(false);
	}

	private static String confinedMessage(TownhallConfig config, ServerPlayer player) {
		return ConfinementService.confinedMessage(config, ReturnPositionStorage.get(player.level().getServer()).state(player.getUUID()));
	}

	private static String activeList(MinecraftServer server, ReturnPositionStorage storage) {
		List<String> entries = storage.activePlayers().stream().map(id -> {
			PlayerState st = storage.state(id);
			String name = server.services().nameToIdCache().get(id).map(NameAndId::name).orElse(id.toString());
			return name + " in " + st.location().orElse("?") + st.remainingMillis().map(ms -> " (" + ConfinementService.formatDuration(ms) + " left)").orElse("");
		}).toList();
		return entries.isEmpty() ? "none" : String.join(", ", entries);
	}

	private static int reportReturn(CommandSourceStack source, TownhallConfig config, TeleportService.ReturnResult result, ServerPlayer other) {
		String message = switch (result) {
			case EXACT -> config.messages.returned;
			case NEARBY -> config.messages.returnedNearby;
			case FALLBACK -> config.messages.returnedFallback;
			case NO_POSITION -> config.messages.noReturnPosition;
			case CONFINED, FAILED -> null;
		};
		if (message == null) return fail(source, "Teleport fehlgeschlagen, siehe Server-Log.");
		if (result == TeleportService.ReturnResult.NO_POSITION) return fail(source, message);
		if (other == null) return ok(source, message);
		other.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.GREEN));
		return ok(source, "Zurückgeschickt: " + other.getPlainTextName() + " (" + result.name().toLowerCase(Locale.ROOT) + ").");
	}

	public static boolean isOperator(CommandSourceStack source) {
		return TownhallMod.isOperator(source.permissions());
	}

	/**
	 * Operator check for what a player does for themselves: command whitelist, going somewhere, return, cooldown, adminOnly.
	 * Sign click commands (and command blocks using execute as) run with GAMEMASTER source permissions, so for a player the
	 * player's own permissions decide. Only the server console acting through a player keeps its own rights.
	 */
	public static boolean isOperatorSelf(CommandSourceStack source) {
		if (source.getEntity() instanceof ServerPlayer player && ((CommandSourceStackAccessor) source).townhall$source() != source.getServer()) {
			return TownhallMod.isOperator(player.permissions());
		}
		return isOperator(source);
	}

	/** Saves the config; if that fails, tells the admin (the change is live but not written) and returns false. */
	public static boolean saveConfig(CommandSourceStack source) {
		if (TownhallMod.CONFIG.save()) {
            dev.townhall.audit.AuditLog.record(source, "config.save", "Serverkonfiguration gespeichert");
            return true;
        }
		source.sendFailure(Component.literal(ConfigManager.NOT_SAVED));
		return false;
	}

	private static ServerPlayer requirePlayer(CommandSourceStack source) {
		if (source.getEntity() instanceof ServerPlayer player) return player;
		source.sendFailure(Component.literal("Dieser Befehl muss von einem Spieler ausgeführt werden."));
		return null;
	}

	/** Players (not operators, by default) must wait between uses. Returns true and tells the player when blocked. */
	private static boolean onCooldown(CommandSourceStack source, ServerPlayer player) {
		TownhallConfig config = TownhallMod.CONFIG.get();
		if (config.commands.cooldownSeconds <= 0 || (config.commands.operatorsBypassCooldown && isOperatorSelf(source))) return false;
		long now = System.currentTimeMillis();
		Long last = LAST_USE.get(player.getUUID());
		long waitMs = last == null ? 0 : last + config.commands.cooldownSeconds * 1000L - now;
		if (waitMs > 0) {
			fail(source, config.messages.cooldown.formatted((waitMs + 999) / 1000));
			return true;
		}
		LAST_USE.put(player.getUUID(), now);
		return false;
	}

	private static Component line(String key, String value) {
		return Component.literal(key + ": ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(value + "\n").withStyle(ChatFormatting.WHITE));
	}

	private static String formatSpot(TownhallConfig.Spot s) {
		return fmt(s.x) + " " + fmt(s.y) + " " + fmt(s.z) + " (yaw " + fmt(s.yaw) + ", pitch " + fmt(s.pitch) + ")";
	}

	private static String fmt(double v) {
		return String.format(Locale.ROOT, "%.2f", v);
	}
}
