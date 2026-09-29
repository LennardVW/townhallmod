package dev.townhall.activity;

import dev.townhall.TownhallMod;
import dev.townhall.nick.Nicknames;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * AFK: a player who hasn't looked around, walked, chatted or used a command for config.afkMinutes is AFK.
 * AFK players get a gray [AFK] after their name in the tab list; everyone sees a short chat line when it changes.
 * /afk sets it by hand. State lives in memory only (online players).
 */
public final class Afk {

	private record Seen(Vec3 pos, float yaw, float pitch, long lastActive, boolean afk, long manualSince) {}

	private static final Map<UUID, Seen> SEEN = new HashMap<>();

	private Afk() {}

	public static boolean isAfk(UUID player) {
		Seen s = SEEN.get(player);
		return s != null && s.afk();
	}

	/** Tab list name: nickname or real name, plus [AFK]. Null = vanilla (real name, no nickname). */
	public static Component tabName(ServerPlayer player) {
		Component base = Nicknames.of(player.getUUID()).orElse(null);
		if (!isAfk(player.getUUID())) return base;
		return Component.empty().append(base == null ? Component.literal(player.getGameProfile().name()) : base)
				.append(Component.literal(" [AFK]").withStyle(ChatFormatting.GRAY));
	}

	/** Chat, commands: the player is clearly here. */
	public static void active(ServerPlayer player) {
		Seen s = SEEN.get(player.getUUID());
		long now = System.currentTimeMillis();
		if (s != null && s.afk() && now - s.manualSince() < 2000) return; // the /afk command itself
		SEEN.put(player.getUUID(), new Seen(player.position(), player.getYRot(), player.getXRot(), now, false, 0));
		if (s != null && s.afk()) changed(player, false);
	}

	/** /afk: AFK right now, until the player does something. */
	public static void setAfk(ServerPlayer player) {
		long now = System.currentTimeMillis();
		boolean was = isAfk(player.getUUID());
		SEEN.put(player.getUUID(), new Seen(player.position(), player.getYRot(), player.getXRot(), now, true, now));
		if (!was) changed(player, true);
	}

	public static void onLeave(ServerPlayer player) {
		SEEN.remove(player.getUUID());
	}

	/** Once per second: compare look direction and position with the last check. */
	public static void check(MinecraftServer server, long now) {
		long limit = TownhallMod.CONFIG.get().afkMinutes * 60_000L;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Seen s = SEEN.get(player.getUUID());
			if (s == null) {
				SEEN.put(player.getUUID(), new Seen(player.position(), player.getYRot(), player.getXRot(), now, false, 0));
				continue;
			}
			boolean looked = Math.abs(s.yaw() - player.getYRot()) > 0.5f || Math.abs(s.pitch() - player.getXRot()) > 0.5f;
			// Water, pistons or other players can move someone who is away; only count walking on their own.
			boolean walked = s.pos().distanceToSqr(player.position()) > 0.01 && !player.isInWater() && !player.isPassenger();
			if ((looked || walked) && now - s.manualSince() > 2000) {
				SEEN.put(player.getUUID(), new Seen(player.position(), player.getYRot(), player.getXRot(), now, false, 0));
				if (s.afk()) changed(player, false);
			} else if (!s.afk() && limit > 0 && now - s.lastActive() >= limit) {
				SEEN.put(player.getUUID(), new Seen(player.position(), player.getYRot(), player.getXRot(), s.lastActive(), true, 0));
				changed(player, true);
			} else {
				SEEN.put(player.getUUID(), new Seen(player.position(), player.getYRot(), player.getXRot(), s.lastActive(), s.afk(), s.manualSince()));
			}
		}
	}

	private static void changed(ServerPlayer player, boolean afk) {
		MinecraftServer server = player.level().getServer();
		server.getPlayerList().broadcastAll(new ClientboundPlayerInfoUpdatePacket(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME, player));
		server.getPlayerList().broadcastSystemMessage(Component.empty().withStyle(ChatFormatting.GRAY)
				.append(player.getDisplayName()).append(afk ? " is now AFK." : " is back."), false);
	}
}
