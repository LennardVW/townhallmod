package dev.townhall.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.townhall.TownhallMod;
import dev.townhall.config.TownhallConfig;
import dev.townhall.dimension.DimensionSettings;
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

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

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
	/** Root command names this mod registered (nodes stay in Brigadier until restart, even when unused). */
	private static final java.util.Set<String> REGISTERED = new java.util.HashSet<>();

	private TownhallCommand() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		for (TownhallConfig.Location location : TownhallMod.CONFIG.get().locations.values()) {
			dispatcher.register(build(location.command));
			REGISTERED.add(location.command);
		}
	}

	/** Adds root commands for locations that don't have one yet and sends everyone the new command list. */
	public static void refreshCommands(MinecraftServer server) {
		CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
		for (TownhallConfig.Location location : TownhallMod.CONFIG.get().locations.values()) {
			if (dispatcher.getRoot().getChild(location.command) == null) {
				dispatcher.register(build(location.command));
				REGISTERED.add(location.command);
			}
		}
		server.getPlayerList().getPlayers().forEach(p -> server.getCommands().sendCommands(p));
	}

	/** True if the name is already a command of Minecraft or another mod (ours may be reused). */
	public static boolean isForeignCommand(MinecraftServer server, String name) {
		return server.getCommands().getDispatcher().getRoot().getChild(name) != null && !REGISTERED.contains(name);
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
		return id.isPresent() ? action.run(id.get()) : fail(ctx.getSource(), "This place doesn't exist anymore.");
	}

	public static LiteralArgumentBuilder<CommandSourceStack> build(String command) {
		Predicate<CommandSourceStack> op = TownhallCommand::isOperator;
		Predicate<CommandSourceStack> mayUse = src -> idFor(command).map(id -> isOperator(src) || !location(id).adminOnly).orElse(false);
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
				.then(Commands.literal("deathsintab").requires(op)
						.then(Commands.literal("on").executes(ctx -> setDeathsInTab(ctx, true)))
						.then(Commands.literal("off").executes(ctx -> setDeathsInTab(ctx, false))))
				.then(Commands.literal("debug").requires(op)
						.then(Commands.argument("player", GameProfileArgument.gameProfile()).executes(TownhallCommand::debug)))
				.then(Commands.literal("clearreturn").requires(op)
						.then(Commands.argument("player", GameProfileArgument.gameProfile()).executes(TownhallCommand::clearReturn)))
				.requires(mayUse);
	}

	// ------------------------------------------------------------ player commands

	private static int enterSelf(CommandContext<CommandSourceStack> ctx, String id) {
		ServerPlayer player = requirePlayer(ctx.getSource());
		if (player == null) return 0;
		TownhallConfig config = TownhallMod.CONFIG.get();
		boolean operator = isOperator(ctx.getSource());
		if (config.location(id).orElseThrow().adminOnly && !operator) return fail(ctx.getSource(), "Only operators can go there.");
		if (onCooldown(ctx.getSource(), player)) return 0;
		TownhallConfig.Location location = location(id);
		return switch (TeleportService.sendTo(player, id, config, operator, false, Optional.empty())) {
			case SENT -> ok(ctx.getSource(), location.arrivedMessage);
			case ALREADY_THERE -> fail(ctx.getSource(), location.alreadyHereMessage.formatted(location.command));
			case UNAVAILABLE -> fail(ctx.getSource(), config.messages.locationUnavailable.formatted(location.dimension));
			case CONFINED -> fail(ctx.getSource(), confinedMessage(config, player));
			case FAILED -> fail(ctx.getSource(), "Teleport failed. Please tell an operator.");
		};
	}

	private static int returnSelf(CommandContext<CommandSourceStack> ctx) {
		ServerPlayer player = requirePlayer(ctx.getSource());
		if (player == null || onCooldown(ctx.getSource(), player)) return 0;
		TownhallConfig config = TownhallMod.CONFIG.get();
		TeleportService.ReturnResult result = TeleportService.returnPlayer(player, config, isOperator(ctx.getSource()));
		if (result == TeleportService.ReturnResult.CONFINED) return fail(ctx.getSource(), confinedMessage(config, player));
		if (result == TeleportService.ReturnResult.NO_POSITION && isStuck(player, config)) {
			// Someone who reached a location another way must not get stuck there.
			return releaseToFallback(player, config)
					? ok(ctx.getSource(), config.messages.noReturnPosition + " Sent you to spawn instead.")
					: fail(ctx.getSource(), "Teleport failed. Please tell an operator.");
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
				String forMinutes = minutes.map(m -> " for " + m + (m == 1 ? " minute" : " minutes")).orElse("");
				target.sendSystemMessage(Component.literal(location.arrivedMessage + (forMinutes.isEmpty() ? "" : " (" + forMinutes.strip() + " of online time)"))
						.withStyle(ChatFormatting.GREEN));
				boolean confined = ReturnPositionStorage.get(ctx.getSource().getServer()).state(target.getUUID()).confined();
				String release = confined ? " They can't leave on their own; release early with /" + location.command + " return " + name + "." : "";
				yield ok(ctx.getSource(), "Sent " + name + " to " + config.displayName(id) + (forMinutes.isEmpty() ? "." : forMinutes + " (online time).") + release);
			}

			case ALREADY_THERE, CONFINED -> fail(ctx.getSource(), name + " is already there.");
			case UNAVAILABLE -> fail(ctx.getSource(), config.messages.locationUnavailable.formatted(location.dimension));
			case FAILED -> fail(ctx.getSource(), "Teleporting " + name + " failed, see the server log.");
		};
	}

	private static int returnOther(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
		TownhallConfig config = TownhallMod.CONFIG.get();
		TeleportService.ReturnResult result = TeleportService.returnPlayer(target, config, true);
		if (result == TeleportService.ReturnResult.NO_POSITION) {
			if (!isStuck(target, config)) return fail(ctx.getSource(), "No return position stored for " + target.getPlainTextName() + ".");
			// In a location (e.g. jailed) without a saved spot: release and send to spawn instead of leaving them stuck.
			if (!releaseToFallback(target, config)) return fail(ctx.getSource(), "Teleporting " + target.getPlainTextName() + " failed, see the server log.");
			target.sendSystemMessage(Component.literal(config.messages.returnedFallback).withStyle(ChatFormatting.GREEN));
			return ok(ctx.getSource(), "No return position was stored for " + target.getPlainTextName() + ", so they were released and sent to spawn.");
		}
		return reportReturn(ctx.getSource(), config, result, target);
	}

	private static int setSpawn(CommandContext<CommandSourceStack> ctx, String id) {
		ServerPlayer player = requirePlayer(ctx.getSource());
		if (player == null) return 0;
		TownhallConfig config = TownhallMod.CONFIG.get();
		TownhallConfig.Location location = location(id);
		if (!player.level().dimension().equals(location.dimensionKey())) {
			return fail(ctx.getSource(), "Stand in " + location.dimension + " to set the spawn of " + config.displayName(id) + ".");
		}
		location.spawn = new TownhallConfig.Spot(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
		TownhallMod.CONFIG.save();
		TownhallMod.LOGGER.info("{} set the spawn of {} to {}", player.getPlainTextName(), id, formatSpot(location.spawn));
		return ok(ctx.getSource(), "Spawn of " + config.displayName(id) + " set to " + formatSpot(location.spawn) + ".");
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

	private static int setDeathsInTab(CommandContext<CommandSourceStack> ctx, boolean on) {
		TownhallMod.CONFIG.get().deathsInTab = on;
		TownhallMod.CONFIG.save();
		dev.townhall.display.DeathsInTab.apply(ctx.getSource().getServer());
		return ok(ctx.getSource(), on ? "Deaths are shown in the tab list." : "Deaths are no longer shown in the tab list.");
	}

	private static int showWorldRules(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerLevel level = DimensionArgument.getDimension(ctx, "dimension");
		DimensionSettings.Rules r = DimensionSettings.of(level.dimension());
		ctx.getSource().sendSuccess(() -> Component.empty()
				.append(line("World", level.dimension().identifier().toString()))
				.append(line("difficulty", level.getDifficulty().getSerializedName()))
				.append(line("pvp", String.valueOf(r.pvp())))
				.append(line("build", r.build() + (r.build() ? "" : " (operators still can)")))
				.append(line("builders", BuilderCommand.names(level.dimension().identifier().toString())))
				.append(line("hunger", String.valueOf(r.hunger())))
				.append(line("fallDamage", String.valueOf(r.fallDamage())))
				.append(line("mobs", String.valueOf(r.mobs())))
				.append(line("fire", String.valueOf(r.fire())))
				.append(line("explosions", String.valueOf(r.explosions())))
				.append(line("leafDecay", String.valueOf(r.leafDecay())))
				.append(line("time", r.time() == null ? "normal" : "always " + r.time()))
				.append(line("weather", r.weather() == null ? "normal" : "always " + r.weather())), false);
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
		TownhallMod.CONFIG.save();
		DimensionSettings.rebuild(config);
		if (rule.equals("time")) DimensionSettings.syncAll(ctx.getSource().getServer());
		TownhallMod.LOGGER.info("World rule {} of {} set to {}", rule, id, value);
		String now = !reset ? value
				: rule.equals("time") || rule.equals("weather") ? "default (normal)" : "default (on)";
		return ok(ctx.getSource(), rule + " in " + id + " is now " + now + ".");
	}

	private static int showDifficulty(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerLevel level = DimensionArgument.getDimension(ctx, "dimension");
		boolean overridden = DimensionSettings.difficulty(level.dimension()) != null;
		String id = level.dimension().identifier().toString();
		return ok(ctx.getSource(), "Difficulty of " + id + ": " + level.getDifficulty().getSerializedName()
				+ (overridden ? " (set for this world)" : " (server default)"));
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
		TownhallMod.CONFIG.save();
		DimensionSettings.rebuild(config);
		DimensionSettings.syncAll(ctx.getSource().getServer());
		TownhallMod.LOGGER.info("Difficulty of {} set to {}", id, value);
		return ok(ctx.getSource(), "Difficulty of " + id + " is now " + level.getDifficulty().getSerializedName()
				+ (value.equals("default") ? " (server default)" : "") + ".");
	}

	private static int reload(CommandContext<CommandSourceStack> ctx) {
		List<String> errors = TownhallMod.CONFIG.reload();
		if (!errors.isEmpty()) {
			ctx.getSource().sendFailure(Component.literal("Config not reloaded, the previous config stays active:\n - " + String.join("\n - ", errors)));
			return 0;
		}
		TownhallConfig config = TownhallMod.CONFIG.get();
		var server = ctx.getSource().getServer();
		DimensionSettings.rebuild(config);
		DimensionSettings.syncAll(server);
		List<String> missing = config.locations.values().stream()
				.filter(l -> server.getLevel(l.dimensionKey()) == null).map(l -> l.dimension).distinct().toList();
		StringBuilder msg = new StringBuilder("Townhall config reloaded.");
		if (!missing.isEmpty()) msg.append(" Not loaded: ").append(String.join(", ", missing)).append(". Loaded: ").append(TownhallMod.loadedDimensions(server)).append('.');
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
			text.append(line("/" + loc.command, loc.dimension + (available ? "" : " (NOT LOADED)") + " at " + formatSpot(loc.spawn)
					+ (loc.adminOnly ? ", admin only" : "") + (loc.isEscapable() ? "" : ", not escapable (radius " + loc.confineRadius + ")")));
		});
		text.append(line("World rules", config.dimensions.isEmpty() ? "none" : String.join(", ", config.dimensions.keySet())))
				.append(line("Stored return positions", String.valueOf(storage.returnPositionCount())))
				.append(line("Confined / timed players", activeList(server, storage)))
				.append(line("Config", TownhallMod.CONFIG.loadedFromFile() ? "loaded from " + TownhallMod.CONFIG.path().getFileName() : "built-in defaults (file invalid)"))
				.append(line("Persistent storage", "loaded"))
				.append(line("Loaded dimensions", TownhallMod.loadedDimensions(server)));
		ctx.getSource().sendSuccess(() -> text, false);
		return 1;
	}

	private static int debug(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		var server = ctx.getSource().getServer();
		for (NameAndId profile : GameProfileArgument.getGameProfiles(ctx, "player")) {
			ServerPlayer online = server.getPlayerList().getPlayer(profile.id());
			PlayerState state = ReturnPositionStorage.get(server).state(profile.id());
			MutableComponent text = Component.empty()
					.append(line("Player", profile.name() + " (" + profile.id() + ")"))
					.append(line("Current Dimension", online != null ? online.level().dimension().identifier().toString() : "offline"))
					.append(line("Last Location", state.location().orElse("none") + (state.confined() ? " (confined)" : "")))
					.append(line("Time Left", state.remainingMillis().map(ConfinementService::formatDuration).orElse("-")));
			if (state.returnPosition().isEmpty()) {
				text.append(line("Stored Return Position", "none"));
			} else {
				ReturnLocation loc = state.returnPosition().get();
				text.append(line("Stored Return Position", loc.dimension().identifier().toString()))
						.append(line("X", fmt(loc.x()))).append(line("Y", fmt(loc.y()))).append(line("Z", fmt(loc.z())))
						.append(line("Yaw", fmt(loc.yaw()))).append(line("Pitch", fmt(loc.pitch())))
						.append(line("Stored At", Instant.ofEpochMilli(loc.timestamp()).toString()));
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
				ok(ctx.getSource(), "Cleared the stored position and confinement of " + profile.name() + ".");
			} else {
				fail(ctx.getSource(), "Nothing stored for " + profile.name() + ".");
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
		return true;
	}

	private static String confinedMessage(TownhallConfig config, ServerPlayer player) {
		return ConfinementService.confinedMessage(config, ReturnPositionStorage.get(player.level().getServer()).state(player.getUUID()));
	}

	private static String activeList(net.minecraft.server.MinecraftServer server, ReturnPositionStorage storage) {
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
		if (message == null) return fail(source, "Teleport failed, see the server log.");
		if (result == TeleportService.ReturnResult.NO_POSITION) return fail(source, message);
		if (other == null) return ok(source, message);
		other.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.GREEN));
		return ok(source, "Returned " + other.getPlainTextName() + " (" + result.name().toLowerCase(Locale.ROOT) + ").");
	}

	public static boolean isOperator(CommandSourceStack source) {
		return TownhallMod.isOperator(source.permissions());
	}

	private static ServerPlayer requirePlayer(CommandSourceStack source) {
		if (source.getEntity() instanceof ServerPlayer player) return player;
		source.sendFailure(Component.literal("This command must be executed by a player."));
		return null;
	}

	/** Players (not operators, by default) must wait between uses. Returns true and tells the player when blocked. */
	private static boolean onCooldown(CommandSourceStack source, ServerPlayer player) {
		TownhallConfig config = TownhallMod.CONFIG.get();
		if (config.commands.cooldownSeconds <= 0 || (config.commands.operatorsBypassCooldown && isOperator(source))) return false;
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

	private static int ok(CommandSourceStack source, String message) {
		source.sendSuccess(() -> Component.literal(message).withStyle(ChatFormatting.GREEN), false);
		return 1;
	}

	private static int fail(CommandSourceStack source, String message) {
		source.sendFailure(Component.literal(message));
		return 0;
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
