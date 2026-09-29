package dev.townhall.onboarding;

import dev.townhall.TownhallMod;
import dev.townhall.config.TownhallConfig;
import dev.townhall.util.Text;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * First join: private welcome text, tutorial and rules with an accept button.
 * Until a player accepts the current rules version, {@link #isRestricted} is true and the protection hooks block them.
 */
public final class Onboarding {

	/** Where a pending player joined; they are kept there. Only online, pending players are in here. */
	private record Pending(ServerLevel level, Vec3 anchor, long lastReminder) {}

	private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();
	public static final Set<String> COMMANDS = Set.of("rules", "regeln");

	private Onboarding() {}

	public static boolean needsToAccept(ServerPlayer player) {
		TownhallConfig.Onboarding cfg = TownhallMod.CONFIG.get().onboarding;
		return cfg.enabled && OnboardingStorage.get(player.level().getServer()).acceptedVersion(player.getUUID()) < cfg.rulesVersion;
	}

	/** Blocked from playing: hasn't accepted yet, restrictions are on, and not an operator. Cheap: a map lookup. */
	public static boolean isRestricted(ServerPlayer player) {
		return PENDING.containsKey(player.getUUID()) && TownhallMod.CONFIG.get().onboarding.restrictUntilAccepted
				&& !TownhallMod.isOperator(player.permissions());
	}

	public static void onJoin(ServerPlayer player) {
		if (!needsToAccept(player)) return;
		PENDING.put(player.getUUID(), new Pending(player.level(), player.position(), System.currentTimeMillis()));
		showAll(player);
	}

	public static void onDisconnect(ServerPlayer player) {
		PENDING.remove(player.getUUID());
	}

	/** Welcome, tutorial and rules, only to this player. */
	public static void showAll(ServerPlayer player) {
		TownhallConfig.Onboarding cfg = TownhallMod.CONFIG.get().onboarding;
		String name = player.getPlainTextName();
		player.sendSystemMessage(Component.empty());
		cfg.welcome.forEach(line -> player.sendSystemMessage(Text.of(line.replace("%player%", name))));
		player.sendSystemMessage(Component.empty());
		cfg.tutorial.forEach(line -> player.sendSystemMessage(Text.of(line.replace("%player%", name))));
		showRules(player);
	}

	public static void showRules(ServerPlayer player) {
		TownhallConfig.Onboarding cfg = TownhallMod.CONFIG.get().onboarding;
		player.sendSystemMessage(Component.empty());
		cfg.rules.forEach(line -> player.sendSystemMessage(Text.of(line)));
		if (needsToAccept(player)) sendAcceptButton(player);
	}

	private static void sendAcceptButton(ServerPlayer player) {
		TownhallConfig.Onboarding cfg = TownhallMod.CONFIG.get().onboarding;
		MutableComponent button = Text.of(cfg.acceptButton).copy().withStyle(style -> style
				.withClickEvent(new ClickEvent.RunCommand("/rules accept"))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("/rules accept").withStyle(ChatFormatting.GRAY))));
		player.sendSystemMessage(Component.empty());
		player.sendSystemMessage(button);
		player.sendSystemMessage(Text.of(cfg.acceptHint));
	}

	/** Returns false if there was nothing to accept. */
	public static boolean accept(ServerPlayer player) {
		TownhallConfig.Onboarding cfg = TownhallMod.CONFIG.get().onboarding;
		if (!needsToAccept(player)) return false;
		OnboardingStorage.get(player.level().getServer()).accept(player.getUUID(), cfg.rulesVersion);
		PENDING.remove(player.getUUID());
		player.sendSystemMessage(Text.of(cfg.accepted));
		Text.title(player, cfg.acceptedTitle, "");
		TownhallMod.LOGGER.info("{} accepted the rules (version {})", player.getPlainTextName(), cfg.rulesVersion);
		return true;
	}

	/** Operators: make a player see and accept the rules again. */
	public static void reset(ServerPlayer player) {
		OnboardingStorage.get(player.level().getServer()).reset(player.getUUID());
		onJoin(player);
	}

	public static void remind(ServerPlayer player) {
		player.sendOverlayMessage(Text.of(TownhallMod.CONFIG.get().onboarding.reminder));
	}

	/** Once per second: keep pending players where they joined and remind them now and then. */
	public static void check(MinecraftServer server) {
		TownhallConfig.Onboarding cfg = TownhallMod.CONFIG.get().onboarding;
		long now = System.currentTimeMillis();
		for (Map.Entry<UUID, Pending> e : PENDING.entrySet()) {
			ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
			if (player == null) {
				PENDING.remove(e.getKey());
				continue;
			}
			if (!isRestricted(player)) continue;
			Pending p = e.getValue();
			if (player.level() != p.level() || player.position().distanceToSqr(p.anchor()) > 9) {
				player.teleportTo(p.level(), p.anchor().x, p.anchor().y, p.anchor().z, Set.of(), player.getYRot(), player.getXRot(), true);
			}
			if (now - p.lastReminder() >= cfg.reminderSeconds * 1000L) {
				PENDING.put(e.getKey(), new Pending(p.level(), p.anchor(), now));
				sendAcceptButton(player);
			}
			remind(player);
		}
	}
}
