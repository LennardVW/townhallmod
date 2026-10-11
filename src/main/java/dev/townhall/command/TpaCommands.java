package dev.townhall.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.townhall.TownhallMod;
import dev.townhall.teleport.TeleportRules;
import dev.townhall.teleport.TeleportService;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** /tpa, /tpahere, /tpaccept, /tpdeny, /tpacancel, /tpatoggle. The logic is in static methods so tests can call it directly. */
public final class TpaCommands {

	public static final long REQUEST_MILLIS = 60_000;

	/** @param here true = /tpahere (the target comes to the requester) */
	public record Request(UUID from, UUID to, boolean here, long expiresAt) {}

	/** One open request per requester. */
	private static final Map<UUID, Request> REQUESTS = new HashMap<>();
	private static final Set<UUID> REFUSING = new HashSet<>();

	private TpaCommands() {}

	public static void reset() {
		REQUESTS.clear();
		REFUSING.clear();
	}

	public static void register(CommandDispatcher<CommandSourceStack> d) {
		d.register(Commands.literal("tpa").then(Commands.argument("player", GameProfileArgument.gameProfile()).executes(c -> send(c, false))));
		d.register(Commands.literal("tpahere").then(Commands.argument("player", GameProfileArgument.gameProfile()).executes(c -> send(c, true))));
		d.register(Commands.literal("tpaccept").executes(c -> answer(c, true, null))
				.then(Commands.argument("player", GameProfileArgument.gameProfile()).executes(c -> answer(c, true, GameProfileArgument.getGameProfiles(c, "player")))));
		d.register(Commands.literal("tpdeny").executes(c -> answer(c, false, null))
				.then(Commands.argument("player", GameProfileArgument.gameProfile()).executes(c -> answer(c, false, GameProfileArgument.getGameProfiles(c, "player")))));
		d.register(Commands.literal("tpacancel").executes(TpaCommands::cancelCmd));
		d.register(Commands.literal("tpatoggle").executes(TpaCommands::toggleCmd));
	}

	// ------------------------------------------------------------ commands

	private static ServerPlayer player(CommandContext<CommandSourceStack> c) {
		if (c.getSource().getEntity() instanceof ServerPlayer p) return p;
		c.getSource().sendFailure(Component.literal("Dieser Befehl muss von einem Spieler ausgeführt werden."));
		return null;
	}

	private static int send(CommandContext<CommandSourceStack> c, boolean here) throws CommandSyntaxException {
		ServerPlayer me = player(c);
		if (me == null) return 0;
		var profiles = GameProfileArgument.getGameProfiles(c, "player");
		if (profiles.size() != 1) return Feedback.fail(c.getSource(), "Genau einen Spieler angeben.");
		ServerPlayer target = me.level().getServer().getPlayerList().getPlayer(profiles.iterator().next().id());
		if (target == null) return Feedback.fail(c.getSource(), "Dieser Spieler ist nicht online.");
		String error = request(me, target, here);
		return error == null ? 1 : Feedback.fail(c.getSource(), error);
	}

	private static int answer(CommandContext<CommandSourceStack> c, boolean accept, java.util.Collection<net.minecraft.server.players.NameAndId> profiles) {
		ServerPlayer me = player(c);
		if (me == null) return 0;
		UUID from = null;
		if (profiles != null) {
			if (profiles.size() != 1) return Feedback.fail(c.getSource(), "Genau einen Spieler angeben.");
			from = profiles.iterator().next().id();
		}
		String error = accept ? accept(me, from) : deny(me, from);
		return error == null ? 1 : Feedback.fail(c.getSource(), error);
	}

	private static int cancelCmd(CommandContext<CommandSourceStack> c) {
		ServerPlayer me = player(c);
		if (me == null) return 0;
		String error = cancel(me);
		return error == null ? 1 : Feedback.fail(c.getSource(), error);
	}

	private static int toggleCmd(CommandContext<CommandSourceStack> c) {
		ServerPlayer me = player(c);
		if (me == null) return 0;
		boolean nowRefusing = toggle(me);
		return Feedback.ok(c.getSource(), nowRefusing ? "Du nimmst jetzt keine Teleportanfragen mehr an." : "Du nimmst wieder Teleportanfragen an.");
	}

	// ------------------------------------------------------------ logic (null = success, otherwise the error text)

	/** Flips the player's request block; returns true if requests are now blocked. */
	public static boolean toggle(ServerPlayer p) {
		if (REFUSING.remove(p.getUUID())) return false;
		REFUSING.add(p.getUUID());
		return true;
	}

	public static Optional<Request> open(UUID from) {
		Request r = REQUESTS.get(from);
		if (r != null && r.expiresAt() <= TeleportRules.CLOCK.getAsLong()) {
			REQUESTS.remove(from);
			return Optional.empty();
		}
		return Optional.ofNullable(r);
	}

	public static String request(ServerPlayer from, ServerPlayer to, boolean here) {
		if (from == to || from.getUUID().equals(to.getUUID())) return "Du kannst dir selbst keine Anfrage schicken.";
		Optional<String> blocked = TeleportRules.blocked(from);
		if (blocked.isPresent() && !TeleportRules.isOp(from)) return blocked.get();
		if (REFUSING.contains(to.getUUID()) && !TeleportRules.isOp(from)) return to.getPlainTextName() + " nimmt keine Teleportanfragen an.";
		String bad = unavailable(to);
		if (bad != null) return to.getPlainTextName() + " " + bad;
		long wait = TeleportRules.cooldownLeft(from);
		if (wait > 0) return "Bitte warte noch " + wait + " Sekunden, bevor du dich erneut teleportierst.";
		REQUESTS.put(from.getUUID(), new Request(from.getUUID(), to.getUUID(), here, TeleportRules.CLOCK.getAsLong() + REQUEST_MILLIS));
		from.sendSystemMessage(Component.literal("Anfrage an " + to.getPlainTextName() + " gesendet (" + REQUEST_MILLIS / 1000 + " Sekunden gültig). Mit /tpacancel ziehst du sie zurück.").withStyle(ChatFormatting.GREEN));
		String who = from.getPlainTextName();
		to.sendSystemMessage(Component.literal(who + (here ? " möchte, dass du dich zu ihm teleportierst. " : " möchte sich zu dir teleportieren. ")).withStyle(ChatFormatting.GOLD)
				.append(button("[Annehmen]", "/tpaccept " + who, ChatFormatting.GREEN)).append(Component.literal(" "))
				.append(button("[Ablehnen]", "/tpdeny " + who, ChatFormatting.RED)));
		return null;
	}

	public static String accept(ServerPlayer me, UUID fromId) {
		Request r = pick(me, fromId);
		if (r == null) return fromId == null ? "Du hast keine offene Teleportanfrage." : "Von diesem Spieler hast du keine offene Anfrage.";
		MinecraftServerHolder h = new MinecraftServerHolder(me);
		ServerPlayer from = h.player(r.from());
		if (from == null) {
			REQUESTS.remove(r.from());
			return "Der Spieler ist nicht mehr online.";
		}
		ServerPlayer mover = r.here() ? me : from;
		ServerPlayer dest = r.here() ? from : me;
		if (!TeleportRules.isOp(mover)) {
			Optional<String> blocked = TeleportRules.blocked(mover);
			if (blocked.isPresent()) return mover == me ? blocked.get() : from.getPlainTextName() + " kann sich gerade nicht teleportieren.";
		}
		String bad = unavailable(dest);
		if (bad != null) return dest.getPlainTextName() + " " + bad;
		long wait = TeleportRules.cooldownLeft(mover);
		if (wait > 0) return (mover == me ? "Bitte warte noch " + wait + " Sekunden, bevor du dich erneut teleportierst." : from.getPlainTextName() + " muss noch " + wait + " Sekunden warten.");
		UUID destId = dest.getUUID();
		if (!TeleportRules.begin(mover, p -> arrive(p, destId))) return mover.getPlainTextName() + " wartet schon auf einen Teleport.";
		REQUESTS.remove(r.from());
		me.sendSystemMessage(Component.literal("Anfrage von " + from.getPlainTextName() + " angenommen.").withStyle(ChatFormatting.GREEN));
		from.sendSystemMessage(Component.literal(me.getPlainTextName() + " hat deine Anfrage angenommen.").withStyle(ChatFormatting.GREEN));
		if (TeleportRules.hasWarmup(mover)) {
			mover.sendSystemMessage(Component.literal("Teleport in " + TownhallMod.CONFIG.get().teleport.warmupSeconds + " Sekunden. Bleib stehen und nimm keinen Schaden.").withStyle(ChatFormatting.YELLOW));
		}
		return null;
	}

	public static String deny(ServerPlayer me, UUID fromId) {
		Request r = pick(me, fromId);
		if (r == null) return fromId == null ? "Du hast keine offene Teleportanfrage." : "Von diesem Spieler hast du keine offene Anfrage.";
		REQUESTS.remove(r.from());
		me.sendSystemMessage(Component.literal("Anfrage abgelehnt.").withStyle(ChatFormatting.YELLOW));
		ServerPlayer from = new MinecraftServerHolder(me).player(r.from());
		if (from != null) from.sendSystemMessage(Component.literal(me.getPlainTextName() + " hat deine Teleportanfrage abgelehnt.").withStyle(ChatFormatting.RED));
		return null;
	}

	public static String cancel(ServerPlayer me) {
		if (open(me.getUUID()).isEmpty()) return "Du hast keine offene Teleportanfrage.";
		REQUESTS.remove(me.getUUID());
		me.sendSystemMessage(Component.literal("Anfrage zurückgezogen.").withStyle(ChatFormatting.YELLOW));
		return null;
	}

	// ------------------------------------------------------------ helpers

	private static Request pick(ServerPlayer me, UUID fromId) {
		if (fromId != null) {
			Optional<Request> r = open(fromId);
			return r.isPresent() && r.get().to().equals(me.getUUID()) ? r.get() : null;
		}
		List<Request> mine = new ArrayList<>();
		for (UUID from : List.copyOf(REQUESTS.keySet())) open(from).filter(r -> r.to().equals(me.getUUID())).ifPresent(mine::add);
		return mine.stream().min(java.util.Comparator.comparingLong(Request::expiresAt)).orElse(null);
	}

	/** Why nobody may be teleported to this player right now, or null. */
	private static String unavailable(ServerPlayer dest) {
		if (dev.townhall.onboarding.Onboarding.isRestricted(dest)) return "hat die Serverregeln noch nicht akzeptiert.";
		var state = dev.townhall.storage.ReturnPositionStorage.get(dest.level().getServer()).state(dest.getUUID());
		if (state.confined() || TeleportRules.lockedWorld(TownhallMod.CONFIG.get(), dest.level().dimension())) return "ist an einem Ort, zu dem man sich nicht teleportieren kann.";
		return null;
	}

	private static void arrive(ServerPlayer mover, UUID destId) {
		ServerPlayer dest = mover.level().getServer().getPlayerList().getPlayer(destId);
		if (dest == null || !dest.isAlive()) {
			TeleportRules.fail(mover, "Teleport abgebrochen, das Ziel ist nicht mehr online.");
			return;
		}
		if (!TeleportRules.isOp(mover)) {
			Optional<String> blocked = TeleportRules.blocked(mover);
			if (blocked.isPresent()) {
				TeleportRules.fail(mover, "Teleport abgebrochen. " + blocked.get());
				return;
			}
		}
		String bad = unavailable(dest);
		if (bad != null) {
			TeleportRules.fail(mover, "Teleport abgebrochen, " + dest.getPlainTextName() + " " + bad);
			return;
		}
		ServerLevel level = (ServerLevel) dest.level();
		Optional<Vec3> spot = TeleportRules.safeSpot(mover, level, dest.position());
		if (spot.isEmpty()) {
			TeleportRules.fail(mover, "Teleport abgebrochen, am Ziel ist kein sicherer Platz.");
			return;
		}
		if (!TeleportService.moveTo(mover, level, spot.get(), mover.getYRot(), mover.getXRot())) {
			TeleportRules.fail(mover, "Teleport fehlgeschlagen. Sag bitte einem Operator Bescheid.");
			return;
		}
		TeleportRules.stamp(mover);
		mover.sendSystemMessage(Component.literal("Du wurdest zu " + dest.getPlainTextName() + " teleportiert.").withStyle(ChatFormatting.GREEN));
	}

	private static Component button(String label, String command, ChatFormatting color) {
		return Component.literal(label).withStyle(st -> st.withColor(color).withClickEvent(new ClickEvent.RunCommand(command)));
	}

	/** Tiny helper so the logic above reads the same for both sides. */
	private record MinecraftServerHolder(ServerPlayer any) {
		ServerPlayer player(UUID id) {
			return any.level().getServer().getPlayerList().getPlayer(id);
		}
	}
}
