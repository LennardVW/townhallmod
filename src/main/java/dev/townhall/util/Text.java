package dev.townhall.util;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerPlayer;

/** Config texts use &-color codes (&a, &l, ...); vanilla clients render them as legacy formatting. */
public final class Text {

	private Text() {}

	/** "&" + one of 0-9, a-f, k-o, r (any case) becomes "§" + that character; everything else stays as it is. */
	public static Component of(String configText) {
		int amp = configText.indexOf('&');
		if (amp < 0) return Component.literal(configText);
		char[] chars = configText.toCharArray();
		for (int i = amp; i < chars.length - 1; i++) {
			if (chars[i] == '&' && isCode(chars[i + 1])) {
				chars[i] = '§';
				i++; // the code character itself is never the start of the next match
			}
		}
		return Component.literal(new String(chars));
	}

	private static boolean isCode(char c) {
		return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
				|| (c >= 'k' && c <= 'o') || (c >= 'K' && c <= 'O') || c == 'r' || c == 'R';
	}

	/** Big title + subtitle in the middle of the screen; empty strings are skipped. */
	public static void title(ServerPlayer player, String title, String subtitle) {
		if ((title == null || title.isBlank()) && (subtitle == null || subtitle.isBlank())) return;
		player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 60, 20));
		player.connection.send(new ClientboundSetSubtitleTextPacket(of(subtitle == null ? "" : subtitle)));
		player.connection.send(new ClientboundSetTitleTextPacket(of(title == null ? "" : title)));
	}
}
