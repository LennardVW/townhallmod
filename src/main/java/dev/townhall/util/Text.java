package dev.townhall.util;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerPlayer;

/** Config texts use &-color codes (&a, &l, ...); vanilla clients render them as legacy formatting. */
public final class Text {

	private Text() {}

	public static Component of(String configText) {
		return Component.literal(configText.replaceAll("&([0-9a-fk-orA-FK-OR])", "§$1"));
	}

	/** Big title + subtitle in the middle of the screen; empty strings are skipped. */
	public static void title(ServerPlayer player, String title, String subtitle) {
		if ((title == null || title.isBlank()) && (subtitle == null || subtitle.isBlank())) return;
		player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 60, 20));
		player.connection.send(new ClientboundSetSubtitleTextPacket(of(subtitle == null ? "" : subtitle)));
		player.connection.send(new ClientboundSetTitleTextPacket(of(title == null ? "" : title)));
	}
}
