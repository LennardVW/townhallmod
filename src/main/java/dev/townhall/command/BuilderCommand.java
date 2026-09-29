package dev.townhall.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.townhall.TownhallMod;
import dev.townhall.config.TownhallConfig;
import dev.townhall.dimension.DimensionSettings;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.GameType;
import dev.townhall.protection.Protection;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.townhall.command.Feedback.fail;
import static dev.townhall.command.Feedback.ok;
import static dev.townhall.command.Feedback.okAdmin;

/**
 * Builders: players who may build in a world whose "build" rule is false (normally only operators can).
 * <pre>
 * /builder add &lt;dimension&gt; &lt;player&gt;      also works for offline players the server knows
 * /builder remove &lt;dimension&gt; &lt;player&gt;
 * /builder list [dimension]
 * /builder creative | survival          builders themselves, only in a world where they are builders
 * </pre>
 * add/remove/list are operator-only; add also works for players who are offline or never joined (name lookup).
 * Stored by UUID in config dimensions.&lt;id&gt;.builders; the name is only for display.
 */
public final class BuilderCommand {

	private static final SuggestionProvider<CommandSourceStack> BUILDER_NAMES = (ctx, builder) -> {
		String dim = DimensionArgument.getDimension(ctx, "dimension").dimension().identifier().toString();
		TownhallConfig.DimensionRules rules = TownhallMod.CONFIG.get().dimensions.get(dim);
		return SharedSuggestionProvider.suggest(rules == null || rules.builders == null ? List.of() : rules.builders.values(), builder);
	};

	private BuilderCommand() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("builder")
				.requires(src -> TownhallCommand.isOperator(src) || (src.getPlayer() != null && DimensionSettings.isBuilderAnywhere(src.getPlayer().getUUID())))
				.then(Commands.literal("creative").executes(ctx -> mode(ctx, GameType.CREATIVE)))
				.then(Commands.literal("survival").executes(ctx -> mode(ctx, GameType.SURVIVAL)))
				.then(Commands.literal("add").requires(TownhallCommand::isOperator).then(Commands.argument("dimension", DimensionArgument.dimension())
						.then(Commands.argument("player", GameProfileArgument.gameProfile()).executes(BuilderCommand::add))))
				.then(Commands.literal("remove").requires(TownhallCommand::isOperator).then(Commands.argument("dimension", DimensionArgument.dimension())
						.then(Commands.argument("player", StringArgumentType.word()).suggests(BUILDER_NAMES).executes(BuilderCommand::remove))))
				.then(Commands.literal("list").requires(TownhallCommand::isOperator).executes(ctx -> list(ctx, null))
						.then(Commands.argument("dimension", DimensionArgument.dimension())
								.executes(ctx -> list(ctx, dimension(ctx))))));
	}

	private static String dimension(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		return DimensionArgument.getDimension(ctx, "dimension").dimension().identifier().toString();
	}

	private static int add(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		String dim = dimension(ctx);
		Collection<NameAndId> players = GameProfileArgument.getGameProfiles(ctx, "player");
		TownhallConfig config = TownhallMod.CONFIG.get();
		TownhallConfig.DimensionRules rules = config.dimensions.computeIfAbsent(dim, k -> new TownhallConfig.DimensionRules());
		if (rules.builders == null) rules.builders = new LinkedHashMap<>();
		for (NameAndId player : players) rules.builders.put(player.id().toString(), player.name());
		boolean saved = save(ctx, config);
		TownhallCommand.sendCommandsToAll(ctx.getSource().getServer()); // /builder shows up (or disappears) right away
		String names = String.join(", ", players.stream().map(NameAndId::name).toList());
		TownhallMod.LOGGER.info("{} made {} builder(s) in {}", ctx.getSource().getTextName(), names, dim);
		if (!saved) return 0;
		boolean buildOff = !DimensionSettings.of(DimensionArgument.getDimension(ctx, "dimension").dimension()).build();
		return okAdmin(ctx.getSource(), names + " can now build in " + dim + "."
				+ (buildOff ? "" : " Note: building is open for everyone there; turn it off with /townhall worldrule " + dim + " build false."));
	}

	private static int remove(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		String dim = dimension(ctx);
		String name = StringArgumentType.getString(ctx, "player");
		TownhallConfig config = TownhallMod.CONFIG.get();
		TownhallConfig.DimensionRules rules = config.dimensions.get(dim);
		if (rules == null || rules.builders == null || !rules.builders.values().removeIf(n -> n.equalsIgnoreCase(name))) {
			return fail(ctx.getSource(), name + " is not a builder in " + dim + ".");
		}
		if (rules.builders.isEmpty()) rules.builders = null;
		boolean saved = save(ctx, config);
		ctx.getSource().getServer().getPlayerList().getPlayers().forEach(Protection::enforceBuilderMode);
		TownhallCommand.sendCommandsToAll(ctx.getSource().getServer()); // /builder shows up (or disappears) right away
		TownhallMod.LOGGER.info("{} removed builder {} in {}", ctx.getSource().getTextName(), name, dim);
		if (!saved) return 0;
		return okAdmin(ctx.getSource(), name + " can no longer build in " + dim + ".");
	}

	private static int mode(CommandContext<CommandSourceStack> ctx, GameType mode) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		if (mode == GameType.CREATIVE && !Protection.isBuilderHere(player) && !TownhallCommand.isOperator(ctx.getSource())) {
			return fail(ctx.getSource(), "You are not a builder in this world.");
		}
		player.setGameMode(mode);
		if (mode == GameType.CREATIVE) player.addTag(Protection.BUILDER_CREATIVE_TAG);
		else player.removeTag(Protection.BUILDER_CREATIVE_TAG);
		TownhallMod.LOGGER.info("{} switched to {} as builder in {}", player.getPlainTextName(), mode.getName(), player.level().dimension().identifier());
		return ok(ctx.getSource(), "Game mode: " + mode.getName());
	}

	private static int list(CommandContext<CommandSourceStack> ctx, String onlyDim) {
		StringBuilder out = new StringBuilder();
		for (Map.Entry<String, TownhallConfig.DimensionRules> e : TownhallMod.CONFIG.get().dimensions.entrySet()) {
			if (onlyDim != null && !onlyDim.equals(e.getKey())) continue;
			if (e.getValue() == null || e.getValue().builders == null || e.getValue().builders.isEmpty()) continue;
			out.append("\n ").append(e.getKey()).append(": ").append(String.join(", ", e.getValue().builders.values()));
		}
		String text = out.isEmpty() ? "No builders" + (onlyDim == null ? "." : " in " + onlyDim + ".") : "Builders:" + out;
		ctx.getSource().sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.GOLD), false);
		return 1;
	}

	/** Builder names of a world, for status output. */
	public static String names(String dim) {
		TownhallConfig.DimensionRules rules = TownhallMod.CONFIG.get().dimensions.get(dim);
		return rules == null || rules.builders == null || rules.builders.isEmpty() ? "none" : String.join(", ", rules.builders.values());
	}

	/** Applies the change live; returns false (and tells the admin) if it could not be saved. */
	private static boolean save(CommandContext<CommandSourceStack> ctx, TownhallConfig config) {
		DimensionSettings.rebuild(config);
		return TownhallCommand.saveConfig(ctx.getSource());
	}

}
