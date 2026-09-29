package dev.townhall.display;

import dev.townhall.TownhallMod;
import dev.townhall.config.TownhallConfig;
import dev.townhall.util.Text;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;

import java.util.Set;

/**
 * Own join/leave messages instead of vanilla's yellow "joined the game". Texts from config.joinMessages with
 * &-colors; {player} is the player's display name (nickname if set). A player can also get a personal join message.
 */
public final class JoinMessages {

	private static final Set<String> VANILLA_KEYS = Set.of("multiplayer.player.joined", "multiplayer.player.joined.renamed", "multiplayer.player.left");

	private JoinMessages() {}

	public static void register() {
		ServerMessageEvents.ALLOW_GAME_MESSAGE.register((server, message, overlay) ->
				!(TownhallMod.CONFIG.get().joinMessages.enabled && isVanillaJoinOrLeave(message)));
	}

	static boolean isVanillaJoinOrLeave(Component message) {
		return message.getContents() instanceof TranslatableContents t && VANILLA_KEYS.contains(t.getKey());
	}

	/** Call on join, before the leave-game statistic changes (first join = never left before). */
	public static void onJoin(ServerPlayer player) {
		TownhallConfig.JoinMessages cfg = TownhallMod.CONFIG.get().joinMessages;
		if (!cfg.enabled) return;
		boolean first = player.getStats().getValue(Stats.CUSTOM.get(Stats.LEAVE_GAME)) == 0;
		String personal = cfg.players.get(player.getUUID().toString());
		String template = first ? cfg.firstJoin : personal != null ? personal : cfg.join;
		broadcast(player, template);
	}

	public static void onLeave(ServerPlayer player) {
		TownhallConfig.JoinMessages cfg = TownhallMod.CONFIG.get().joinMessages;
		if (cfg.enabled) broadcast(player, cfg.leave);
	}

	private static void broadcast(ServerPlayer player, String template) {
		if (template == null || template.isBlank()) return;
		player.level().getServer().getPlayerList().broadcastSystemMessage(format(template, player.getDisplayName()), false);
	}

	/** Template text with {player} replaced by the (colored, hoverable) name. */
	public static MutableComponent format(String template, Component name) {
		MutableComponent out = Component.empty();
		String[] parts = template.split("\\{player}", -1);
		for (int i = 0; i < parts.length; i++) {
			if (i > 0) out.append(name);
			if (!parts[i].isEmpty()) out.append(Text.of(parts[i]));
		}
		return out;
	}
}
