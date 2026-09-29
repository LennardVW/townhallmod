package dev.townhall.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.townhall.TownhallMod;
import dev.townhall.config.TownhallConfig;
import dev.townhall.storage.ReturnPositionStorage;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

import static dev.townhall.command.Feedback.fail;
import static dev.townhall.command.Feedback.okAdmin;

/**
 * Operator-only management of locations, so nobody has to edit townhall.json:
 * <pre>
 * /location list
 * /location info &lt;id&gt;
 * /location create &lt;id&gt; [prison]        at your position; "prison" = adminOnly + not escapable
 * /location delete &lt;id&gt;
 * /location setspawn &lt;id&gt;              spawn and world = where you stand
 * /location set &lt;id&gt; command|name|adminOnly|escapable|radius|dimension|message|alreadyHereMessage|title|subtitle &lt;value&gt;
 * </pre>
 * Every change is saved to the config and works at once (new command names are registered live).
 */
public final class LocationCommand {

	private static final Pattern ID = Pattern.compile("[a-z0-9_\\-]+");

	private static final SuggestionProvider<CommandSourceStack> IDS = (ctx, builder) ->
			SharedSuggestionProvider.suggest(TownhallMod.CONFIG.get().locations.keySet(), builder);

	private LocationCommand() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("location").requires(TownhallCommand::isOperator)
				.then(Commands.literal("list").executes(LocationCommand::list))
				.then(Commands.literal("info").then(id().executes(ctx -> info(ctx, id(ctx)))))
				.then(Commands.literal("create").then(Commands.argument("id", StringArgumentType.word())
						.executes(ctx -> create(ctx, false))
						.then(Commands.literal("prison").executes(ctx -> create(ctx, true)))))
				.then(Commands.literal("delete").then(id().executes(LocationCommand::delete)))
				.then(Commands.literal("setspawn").then(id().executes(LocationCommand::setSpawn)))
				.then(setCommand()));
	}

	private static RequiredArgumentBuilder<CommandSourceStack, String> id() {
		return Commands.argument("id", StringArgumentType.word()).suggests(IDS);
	}

	private static String id(CommandContext<CommandSourceStack> ctx) {
		return StringArgumentType.getString(ctx, "id");
	}

	private static LiteralArgumentBuilder<CommandSourceStack> setCommand() {
		var id = id();
		id.then(Commands.literal("command").then(Commands.argument("value", StringArgumentType.string()).executes(LocationCommand::setCommandName)));
		id.then(Commands.literal("adminOnly").then(Commands.argument("value", BoolArgumentType.bool())
				.executes(ctx -> change(ctx, "adminOnly", (l, v) -> l.adminOnly = v, BoolArgumentType.getBool(ctx, "value")))));
		id.then(Commands.literal("escapable").then(Commands.argument("value", BoolArgumentType.bool()).executes(LocationCommand::setEscapable)));
		id.then(Commands.literal("radius").then(Commands.argument("value", IntegerArgumentType.integer(0, 100_000))
				.executes(ctx -> change(ctx, "confineRadius", (l, v) -> l.confineRadius = v, IntegerArgumentType.getInteger(ctx, "value")))));
		id.then(Commands.literal("dimension").then(Commands.argument("value", DimensionArgument.dimension()).executes(ctx -> {
			ResourceKey<Level> dimension = DimensionArgument.getDimension(ctx, "value").dimension();
			String problem = sharedWorldProblem(ctx.getSource().getServer(), dimension);
			if (problem != null) return fail(ctx.getSource(), problem);
			return change(ctx, "dimension", (l, v) -> l.dimension = v, dimension.identifier().toString());
		})));
		text(id, "name", (l, v) -> l.displayName = v);
		text(id, "message", (l, v) -> l.arrivedMessage = v);
		text(id, "alreadyHereMessage", (l, v) -> l.alreadyHereMessage = v);
		text(id, "title", (l, v) -> l.title = v);
		text(id, "subtitle", (l, v) -> l.subtitle = v);
		return Commands.literal("set").then(id);
	}

	/** Free text setting; "-" clears it. alreadyHereMessage is filled in with the command name (%s), so a lone % is rejected. */
	private static void text(RequiredArgumentBuilder<CommandSourceStack, String> id, String setting, BiConsumer<TownhallConfig.Location, String> setter) {
		id.then(Commands.literal(setting).then(Commands.argument("value", StringArgumentType.greedyString()).executes(ctx -> {
			String value = StringArgumentType.getString(ctx, "value");
			if (setting.equals("alreadyHereMessage")) {
				String problem = TownhallConfig.formatProblem(value, "townhall");
				if (problem != null) return fail(ctx.getSource(), "alreadyHereMessage: " + problem + ". %s is replaced by the command name.");
			}
			return change(ctx, setting, setter, value.equals("-") ? "" : value);
		})));
	}

	/** escapable true also frees everyone already confined there (their return position and timer stay). */
	private static int setEscapable(CommandContext<CommandSourceStack> ctx) {
		boolean value = BoolArgumentType.getBool(ctx, "value");
		int result = change(ctx, "escapable", (l, v) -> l.escapable = v, value);
		// The change is live even if saving failed, so free the prisoners whenever the location exists.
		if (!value || !TownhallMod.CONFIG.get().locations.containsKey(id(ctx))) return result;
		int released = ReturnPositionStorage.get(ctx.getSource().getServer()).releaseConfinedAt(id(ctx));
		if (released > 0) okAdmin(ctx.getSource(), released + " confined player(s) there may leave now (return).");
		return result;
	}

	/**
	 * The overworld and the fallback world are shared by everyone: a location there makes /&lt;cmd&gt; say "already there"
	 * and never stores a return position for anyone in that world. Returns why, or null if the world is fine.
	 */
	private static String sharedWorldProblem(MinecraftServer server, ResourceKey<Level> dimension) {
		TownhallConfig config = TownhallMod.CONFIG.get();
		boolean shared = dimension.equals(Level.OVERWORLD) || dimension.equals(config.fallbackDimension())
				|| (config.fallback.useWorldSpawn && dimension.equals(server.getRespawnData().dimension()));
		return shared ? dimension.identifier() + " is the overworld/fallback world that everyone shares. A location there would break /<cmd> and return"
				+ " for everyone in it; use a world of its own." : null;
	}

	private static <T> int change(CommandContext<CommandSourceStack> ctx, String setting, BiConsumer<TownhallConfig.Location, T> setter, T value) {
		String id = id(ctx);
		TownhallConfig.Location location = TownhallMod.CONFIG.get().locations.get(id);
		if (location == null) return unknown(ctx, id);
		setter.accept(location, value);
		TownhallMod.LOGGER.info("{} set {} of location {} to {}", ctx.getSource().getTextName(), setting, id, value);
		if (!saveAndRefresh(ctx)) return 0;
		return okAdmin(ctx.getSource(), setting + " of " + id + " is now " + (value instanceof String s && s.isEmpty() ? "empty" : value) + ".");
	}

	private static int setCommandName(CommandContext<CommandSourceStack> ctx) {
		String id = id(ctx);
		String name = StringArgumentType.getString(ctx, "value");
		TownhallConfig config = TownhallMod.CONFIG.get();
		TownhallConfig.Location location = config.locations.get(id);
		if (location == null) return unknown(ctx, id);
		String problem = commandProblem(ctx, name, id);
		if (problem != null) return fail(ctx.getSource(), problem);
		String old = location.command;
		location.command = name;
		TownhallMod.LOGGER.info("{} renamed the command of location {} from /{} to /{}", ctx.getSource().getTextName(), id, old, name);
		if (!saveAndRefresh(ctx)) return 0;
		return okAdmin(ctx.getSource(), id + " now uses /" + name + " (/" + old + " is gone).");
	}

	private static String commandProblem(CommandContext<CommandSourceStack> ctx, String name, String exceptId) {
		if (!TownhallConfig.isValidCommandName(name)) return "Command names may only use letters, digits and _ . : -";
		if (TownhallMod.CONFIG.get().commandTaken(name, exceptId)) return "/" + name + " is already used by another location.";
		if (TownhallCommand.isForeignCommand(ctx.getSource().getServer(), name)) return "/" + name + " is already a command of Minecraft or another mod.";
		return null;
	}

	private static int create(CommandContext<CommandSourceStack> ctx, boolean prison) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		String id = id(ctx).toLowerCase(Locale.ROOT);
		TownhallConfig config = TownhallMod.CONFIG.get();
		if (!ID.matcher(id).matches()) return fail(ctx.getSource(), "Ids may only use a-z, 0-9, _ and -.");
		if (config.locations.containsKey(id)) return fail(ctx.getSource(), "Location " + id + " already exists.");
		String problem = commandProblem(ctx, id, null);
		if (problem != null) return fail(ctx.getSource(), problem + " Pick another id, or rename its command later with /location set <id> command <name>.");
		String shared = sharedWorldProblem(ctx.getSource().getServer(), player.level().dimension());
		if (shared != null) return fail(ctx.getSource(), shared);

		TownhallConfig.Location location = new TownhallConfig.Location();
		location.command = id;
		location.displayName = id;
		location.dimension = player.level().dimension().identifier().toString();
		location.spawn = TownhallConfig.Spot.of(player);
		location.adminOnly = prison;
		location.escapable = !prison;
		location.title = (prison ? "&c" : "&6") + id;
		location.arrivedMessage = prison ? "You were sent to " + id + "." : "Welcome to " + id + "!";
		location.alreadyHereMessage = "You are already here. Use /%s return to go back.";
		config.locations.put(id, location);
		TownhallMod.LOGGER.info("{} created location {} ({}) in {}", ctx.getSource().getTextName(), id, prison ? "prison" : "normal", location.dimension);
		if (!saveAndRefresh(ctx)) return 0;
		return okAdmin(ctx.getSource(), "Created /" + id + (prison ? " as a prison (admins only, no escape)" : "") + ". Spawn = your position.");
	}

	private static int delete(CommandContext<CommandSourceStack> ctx) {
		String id = id(ctx);
		TownhallConfig config = TownhallMod.CONFIG.get();
		if (!config.locations.containsKey(id)) return unknown(ctx, id);
		if (config.locations.size() == 1) return fail(ctx.getSource(), "This is the last location; at least one must stay.");
		ReturnPositionStorage storage = ReturnPositionStorage.get(ctx.getSource().getServer());
		long inside = storage.activePlayers().stream()
				.filter(uuid -> storage.state(uuid).location().map(id::equals).orElse(false)).count();
		if (inside > 0) return fail(ctx.getSource(), inside + " player(s) are still held there. Release them first with /" + config.locations.get(id).command + " return <player>.");
		config.locations.remove(id);
		TownhallMod.LOGGER.info("{} deleted location {}", ctx.getSource().getTextName(), id);
		if (!saveAndRefresh(ctx)) return 0;
		return okAdmin(ctx.getSource(), "Deleted " + id + ".");
	}

	private static int setSpawn(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		String id = id(ctx);
		TownhallConfig.Location location = TownhallMod.CONFIG.get().locations.get(id);
		if (location == null) return unknown(ctx, id);
		String shared = sharedWorldProblem(ctx.getSource().getServer(), player.level().dimension());
		if (shared != null) return fail(ctx.getSource(), shared);
		location.dimension = player.level().dimension().identifier().toString();
		location.spawn = TownhallConfig.Spot.of(player);
		TownhallMod.LOGGER.info("{} set the spawn of {} to {} in {}", ctx.getSource().getTextName(), id, spot(location.spawn), location.dimension);
		if (!saveAndRefresh(ctx)) return 0;
		return okAdmin(ctx.getSource(), "Spawn of " + id + " set to " + spot(location.spawn) + " in " + location.dimension + ".");
	}

	private static int list(CommandContext<CommandSourceStack> ctx) {
		MutableComponent text = Component.literal("Locations:").withStyle(ChatFormatting.GOLD);
		TownhallMod.CONFIG.get().locations.forEach((id, l) -> text.append(Component.literal("\n " + id).withStyle(ChatFormatting.WHITE))
				.append(Component.literal("  /" + l.command + "  " + l.dimension
						+ (l.adminOnly ? "  admins only" : "") + (l.isEscapable() ? "" : "  no escape")).withStyle(ChatFormatting.GRAY)));
		ctx.getSource().sendSuccess(() -> text, false);
		return 1;
	}

	private static int info(CommandContext<CommandSourceStack> ctx, String id) {
		TownhallConfig.Location l = TownhallMod.CONFIG.get().locations.get(id);
		if (l == null) return unknown(ctx, id);
		ServerLevel level = ctx.getSource().getServer().getLevel(l.dimensionKey());
		MutableComponent text = Component.literal("Location " + id).withStyle(ChatFormatting.GOLD);
		for (List<String> row : List.of(
				List.of("command", "/" + l.command),
				List.of("name", l.displayName == null ? id : l.displayName),
				List.of("dimension", l.dimension + (level == null ? " (NOT LOADED)" : "")),
				List.of("spawn", spot(l.spawn)),
				List.of("adminOnly", String.valueOf(l.adminOnly)),
				List.of("escapable", String.valueOf(l.isEscapable())),
				List.of("radius", String.valueOf(l.confineRadius)),
				List.of("message", l.arrivedMessage),
				List.of("alreadyHereMessage", l.alreadyHereMessage),
				List.of("title", l.title), List.of("subtitle", l.subtitle))) {
			text.append(Component.literal("\n " + row.get(0) + ": ").withStyle(ChatFormatting.GRAY))
					.append(Component.literal(row.get(1) == null ? "" : row.get(1)).withStyle(ChatFormatting.WHITE));
		}
		ctx.getSource().sendSuccess(() -> text, false);
		return 1;
	}

	/** The change is live either way; returns false (and tells the admin) if it could not be saved. */
	private static boolean saveAndRefresh(CommandContext<CommandSourceStack> ctx) {
		TownhallCommand.refreshCommands(ctx.getSource().getServer());
		return TownhallCommand.saveConfig(ctx.getSource());
	}

	private static String spot(TownhallConfig.Spot s) {
		return String.format(Locale.ROOT, "%.1f %.1f %.1f", s.x, s.y, s.z);
	}

	private static int unknown(CommandContext<CommandSourceStack> ctx, String id) {
		return fail(ctx.getSource(), "No location " + id + ". See /location list.");
	}

}
