package dev.townhall.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.townhall.TownhallMod;
import dev.townhall.storage.HomeStorage;
import dev.townhall.storage.HomeStorage.Home;
import dev.townhall.teleport.TeleportRules;
import dev.townhall.teleport.TeleportService;
import dev.townhall.teleport.Warmup;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** /sethome, /home, /delhome, /homes. */
public final class HomeCommands {

	public static final String DEFAULT_NAME = "home";
	private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N}_\\-]{1,16}");
	private static final SuggestionProvider<CommandSourceStack> NAMES = (ctx, b) -> {
		if (ctx.getSource().getEntity() instanceof ServerPlayer p) {
			SharedSuggestionProvider.suggest(HomeStorage.get(p.level().getServer()).homes(p.getUUID()).keySet(), b);
		}
		return b.buildFuture();
	};

	private HomeCommands() {}

	public static void register(CommandDispatcher<CommandSourceStack> d) {
		d.register(Commands.literal("sethome").executes(c -> setHome(c, DEFAULT_NAME))
				.then(Commands.argument("name", StringArgumentType.word()).suggests(NAMES).executes(c -> setHome(c, StringArgumentType.getString(c, "name")))));
		d.register(Commands.literal("home").executes(c -> home(c, null))
				.then(Commands.argument("name", StringArgumentType.word()).suggests(NAMES).executes(c -> home(c, StringArgumentType.getString(c, "name")))));
		d.register(Commands.literal("delhome").executes(c -> delHome(c, DEFAULT_NAME))
				.then(Commands.argument("name", StringArgumentType.word()).suggests(NAMES).executes(c -> delHome(c, StringArgumentType.getString(c, "name")))));
		d.register(Commands.literal("homes").executes(HomeCommands::list));
	}

	private static ServerPlayer player(CommandContext<CommandSourceStack> c) {
		if (c.getSource().getEntity() instanceof ServerPlayer p) return p;
		c.getSource().sendFailure(Component.literal("Dieser Befehl muss von einem Spieler ausgeführt werden."));
		return null;
	}

	private static int setHome(CommandContext<CommandSourceStack> c, String raw) {
		ServerPlayer p = player(c);
		if (p == null) return 0;
		var src = c.getSource();
		String name = raw.toLowerCase(Locale.ROOT);
		if (!NAME.matcher(name).matches()) return Feedback.fail(src, "Der Name darf nur Buchstaben, Zahlen, _ und - enthalten (höchstens 16 Zeichen).");
		Optional<String> blocked = TeleportRules.blocked(p);
		if (blocked.isPresent()) return Feedback.fail(src, blocked.get());
		HomeStorage storage = HomeStorage.get(p.level().getServer());
		Map<String, Home> mine = storage.homes(p.getUUID());
		int max = TownhallMod.CONFIG.get().homes.maxHomes;
		if (!mine.containsKey(name) && mine.size() >= max) {
			return Feedback.fail(src, max == 1
					? "Du hast schon ein Zuhause (\"" + mine.keySet().iterator().next() + "\"). Du kannst nur dieses überschreiben oder es mit /delhome löschen."
					: "Du hast schon " + max + " Zuhause, mehr gehen nicht. Überschreibe eins oder lösche eins mit /delhome.");
		}
		storage.set(p.getUUID(), name, Home.of(p));
		return Feedback.ok(src, mine.containsKey(name) ? "Zuhause \"" + name + "\" überschrieben." : "Zuhause \"" + name + "\" gesetzt. Mit /home kommst du hierher zurück.");
	}

	private static int delHome(CommandContext<CommandSourceStack> c, String raw) {
		ServerPlayer p = player(c);
		if (p == null) return 0;
		String name = raw.toLowerCase(Locale.ROOT);
		if (!HomeStorage.get(p.level().getServer()).remove(p.getUUID(), name)) return Feedback.fail(c.getSource(), "Du hast kein Zuhause namens \"" + name + "\".");
		return Feedback.ok(c.getSource(), "Zuhause \"" + name + "\" gelöscht.");
	}

	private static int list(CommandContext<CommandSourceStack> c) {
		ServerPlayer p = player(c);
		if (p == null) return 0;
		Map<String, Home> mine = HomeStorage.get(p.level().getServer()).homes(p.getUUID());
		if (mine.isEmpty()) return Feedback.fail(c.getSource(), "Du hast noch kein Zuhause. Setze eins mit /sethome.");
		var out = Component.literal("Deine Zuhause (" + mine.size() + "/" + TownhallMod.CONFIG.get().homes.maxHomes + "): ").withStyle(ChatFormatting.GOLD);
		boolean first = true;
		for (String n : mine.keySet()) {
			if (!first) out.append(Component.literal(", ").withStyle(ChatFormatting.GRAY));
			first = false;
			out.append(Component.literal(n).withStyle(s -> s.withColor(ChatFormatting.AQUA).withClickEvent(new ClickEvent.RunCommand("/home " + n))));
		}
		p.sendSystemMessage(out);
		return mine.size();
	}

	private static int home(CommandContext<CommandSourceStack> c, String raw) {
		ServerPlayer p = player(c);
		if (p == null) return 0;
		var src = c.getSource();
		boolean op = TeleportRules.isOp(p);
		if (!op) {
			Optional<String> blocked = TeleportRules.blocked(p);
			if (blocked.isPresent()) return Feedback.fail(src, blocked.get());
		}
		HomeStorage storage = HomeStorage.get(p.level().getServer());
		Map<String, Home> mine = storage.homes(p.getUUID());
		if (mine.isEmpty()) return Feedback.fail(src, "Du hast noch kein Zuhause. Setze eins mit /sethome.");
		String name;
		if (raw != null) name = raw.toLowerCase(Locale.ROOT);
		else if (mine.containsKey(DEFAULT_NAME) || mine.size() > 1) name = DEFAULT_NAME;
		else name = mine.keySet().iterator().next();
		Home h = mine.get(name);
		if (h == null) return Feedback.fail(src, "Du hast kein Zuhause namens \"" + name + "\". Deine Zuhause: " + String.join(", ", mine.keySet()));
		if (!op && TeleportRules.lockedWorld(TownhallMod.CONFIG.get(), h.dimension())) return Feedback.fail(src, "Dieses Zuhause liegt an einem Ort, zu dem man sich nicht teleportieren kann.");
		long wait = TeleportRules.cooldownLeft(p);
		if (wait > 0) return Feedback.fail(src, "Bitte warte noch " + wait + " Sekunden, bevor du dich erneut teleportierst.");
		if (!TeleportRules.begin(p, pl -> arrive(pl, name, h))) return Feedback.fail(src, "Du wartest schon auf einen Teleport.");
		if (TeleportRules.hasWarmup(p)) {
			int s = TownhallMod.CONFIG.get().teleport.warmupSeconds;
			Feedback.ok(src, "Teleport zu \"" + name + "\" in " + s + " Sekunden. Bleib stehen und nimm keinen Schaden.");
		}
		return 1;
	}

	private static void arrive(ServerPlayer p, String name, Home h) {
		boolean op = TeleportRules.isOp(p);
		if (!op) {
			Optional<String> blocked = TeleportRules.blocked(p);
			if (blocked.isPresent()) {
				TeleportRules.fail(p, "Teleport abgebrochen. " + blocked.get());
				return;
			}
			if (TeleportRules.lockedWorld(TownhallMod.CONFIG.get(), h.dimension())) {
				TeleportRules.fail(p, "Teleport abgebrochen, das Ziel ist gesperrt.");
				return;
			}
		}
		ServerLevel level = p.level().getServer().getLevel(h.dimension());
		if (level == null) {
			TeleportRules.fail(p, "Teleport abgebrochen, die Welt dieses Zuhauses gibt es nicht mehr.");
			return;
		}
		Optional<Vec3> spot = TeleportRules.safeSpot(p, level, new Vec3(h.x(), h.y(), h.z()));
		if (spot.isEmpty()) {
			TeleportRules.fail(p, "Teleport abgebrochen, an deinem Zuhause ist kein sicherer Platz mehr.");
			return;
		}
		if (!TeleportService.moveTo(p, level, spot.get(), h.yaw(), h.pitch())) {
			TeleportRules.fail(p, "Teleport fehlgeschlagen. Sag bitte einem Operator Bescheid.");
			return;
		}
		TeleportRules.stamp(p);
		p.sendSystemMessage(Component.literal("Willkommen in deinem Zuhause \"" + name + "\".").withStyle(ChatFormatting.GREEN));
	}
}
