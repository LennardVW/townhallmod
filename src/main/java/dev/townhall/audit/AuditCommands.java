package dev.townhall.audit;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.townhall.TownhallMod;
import dev.townhall.city.CityAccess;
import dev.townhall.command.Feedback;
import dev.townhall.command.TownhallCommand;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Parent registers this root; queries only walk the bounded saved ring, never world/chunk areas. */
public final class AuditCommands {
	private static final int DEFAULT_QUERY = 10;
	private AuditCommands() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("audit").requires(CityAccess::isAdmin)
				.executes(ctx -> status(ctx.getSource()))
				.then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
				.then(Commands.literal("inspect").executes(ctx -> inspect(ctx.getSource())))
				.then(Commands.literal("near").then(Commands.argument("radius", IntegerArgumentType.integer(1, AuditStorage.MAX_RADIUS))
						.executes(ctx -> near(ctx, DEFAULT_QUERY))
						.then(Commands.argument("limit", IntegerArgumentType.integer(1, AuditStorage.MAX_QUERY))
								.executes(ctx -> near(ctx, IntegerArgumentType.getInteger(ctx, "limit"))))))
				.then(Commands.literal("player").then(Commands.argument("player", GameProfileArgument.gameProfile())
						.executes(ctx -> player(ctx, DEFAULT_QUERY))
						.then(Commands.argument("limit", IntegerArgumentType.integer(1, AuditStorage.MAX_QUERY))
								.executes(ctx -> player(ctx, IntegerArgumentType.getInteger(ctx, "limit"))))))
				.then(Commands.literal("enabled").then(Commands.argument("enabled", BoolArgumentType.bool())
						.executes(ctx -> enabled(ctx.getSource(), BoolArgumentType.getBool(ctx, "enabled")))))
				.then(Commands.literal("limit").then(Commands.argument("limit", IntegerArgumentType.integer(AuditStorage.MIN_LIMIT, AuditStorage.MAX_LIMIT))
						.executes(ctx -> limit(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "limit"))))));
	}

	private static boolean allowed(CommandSourceStack source) {
		if (CityAccess.isAdmin(source)) return true;
		Feedback.fail(source, "Nur Administratoren dürfen das Protokoll abfragen oder ändern.");
		return false;
	}

	public static int status(CommandSourceStack source) {
		if (!allowed(source)) return 0;
		AuditStorage storage = AuditStorage.get(source.getServer());
		return Feedback.ok(source, "Protokoll: " + (AuditLog.enabled() ? "aktiv" : "aus") + ", " + storage.size()
				+ "/" + AuditStorage.configuredLimit() + " Einträge; nächste ID " + storage.nextId()
				+ ". Erfasst synchrone Blockänderungen; asynchrones WorldEdit ist nicht sicher zuordenbar.");
	}

	public static int inspect(CommandSourceStack source) {
		if (!allowed(source)) return 0;
		ServerPlayer player = source.getPlayer();
		if (player == null) return Feedback.fail(source, "Dafür muss ein Spieler einen Block ansehen.");
		// Bound the ray to six blocks and reject unloaded chunks before vanilla clipping can load them.
		BlockPos start = BlockPos.containing(player.getEyePosition());
		BlockPos end = BlockPos.containing(player.getEyePosition().add(player.getViewVector(1).scale(6)));
		// This rectangle covers every crossed chunk, including arbitrarily short corner crossings (at most four).
		for (int x = Math.min(start.getX(), end.getX()) >> 4; x <= (Math.max(start.getX(), end.getX()) >> 4); x++) {
			for (int z = Math.min(start.getZ(), end.getZ()) >> 4; z <= (Math.max(start.getZ(), end.getZ()) >> 4); z++) {
				if (!player.level().getChunkSource().hasChunk(x, z)) return Feedback.fail(source, "Der Zielbereich ist nicht geladen.");
			}
		}
		HitResult hit = player.pick(6, 1, false);
		if (hit.getType() != HitResult.Type.BLOCK || !(hit instanceof BlockHitResult block)) {
			return Feedback.fail(source, "Sieh einen Block in höchstens 6 Blöcken Entfernung an.");
		}
		BlockPos position = block.getBlockPos();
		return show(source, "Block " + coordinates(position), AuditStorage.get(source.getServer())
				.at(player.level().dimension().identifier(), position, DEFAULT_QUERY));
	}

	private static int near(CommandContext<CommandSourceStack> ctx, int limit) {
		CommandSourceStack source = ctx.getSource();
		if (!allowed(source)) return 0;
		int radius = IntegerArgumentType.getInteger(ctx, "radius");
		return show(source, "Umgebung (" + radius + " Blöcke)", AuditStorage.get(source.getServer())
				.near(source.getLevel().dimension().identifier(), BlockPos.containing(source.getPosition()), radius, limit));
	}

	private static int player(CommandContext<CommandSourceStack> ctx, int limit) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		if (!allowed(source)) return 0;
		var profiles = GameProfileArgument.getGameProfiles(ctx, "player");
		if (profiles.size() != 1) return Feedback.fail(source, "Bitte genau einen Spieler auswählen.");
		var profile = profiles.iterator().next();
		return show(source, "Spieler " + profile.name(), AuditStorage.get(source.getServer()).player(profile.id(), limit));
	}

	private static int show(CommandSourceStack source, String title, List<AuditEntry> entries) {
		Feedback.ok(source, title + ": " + entries.size() + " letzte Änderungen.");
		for (AuditEntry entry : entries) {
			String location = entry.dimension().map(d -> " " + d).orElse("") + entry.position().map(p -> " " + coordinates(p)).orElse("");
			String states = entry.before().isEmpty() && entry.after().isEmpty() ? "" : " | " + entry.before() + " → " + entry.after();
			Feedback.ok(source, "#" + entry.id() + " " + Instant.ofEpochMilli(entry.timestamp()) + " " + entry.actorName()
					+ " [" + entry.actorId() + "] " + entry.action() + location + states
					+ (entry.detail().isEmpty() ? "" : " | " + entry.detail()));
		}
		return entries.size();
	}

	private static int enabled(CommandSourceStack source, boolean value) {
		if (!allowed(source)) return 0;
		var settings = TownhallMod.CONFIG.get().city;
		boolean previous = settings.auditEnabled;
		if (previous == value) return Feedback.ok(source, "Protokoll ist bereits " + (value ? "aktiv." : "aus."));
		settings.auditEnabled = value;
		if (!TownhallCommand.saveConfig(source)) { settings.auditEnabled = previous; return 0; }
		// The successful disabling action is the final entry; ordinary records remain disabled afterwards.
		var actor = source.getPlayer();
		AuditStorage.get(source.getServer()).append(System.currentTimeMillis(), actor == null ? AuditLog.SYSTEM_ACTOR : actor.getUUID(),
				actor == null ? source.getTextName() : actor.getGameProfile().name(), "audit.enabled",
				Optional.empty(), Optional.empty(), Boolean.toString(previous), Boolean.toString(value), "", AuditStorage.configuredLimit());
		return Feedback.okAdmin(source, "Protokoll " + (value ? "aktiviert." : "deaktiviert."));
	}

	private static int limit(CommandSourceStack source, int value) {
		if (!allowed(source)) return 0;
		var settings = TownhallMod.CONFIG.get().city;
		int previous = settings.auditMaxEntries;
		if (previous == value) return Feedback.ok(source, "Protokollgrenze ist bereits " + value + ".");
		settings.auditMaxEntries = value;
		if (!TownhallCommand.saveConfig(source)) { settings.auditMaxEntries = previous; return 0; }
		AuditStorage.get(source.getServer()).trim(value);
		AuditLog.record(source, "audit.limit", previous + " → " + value);
		return Feedback.okAdmin(source, "Protokollgrenze: " + value + "; die ältesten überzähligen Einträge wurden entfernt.");
	}

	private static String coordinates(BlockPos pos) { return pos.getX() + "," + pos.getY() + "," + pos.getZ(); }
}
