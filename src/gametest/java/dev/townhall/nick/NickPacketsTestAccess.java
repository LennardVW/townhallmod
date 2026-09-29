package dev.townhall.nick;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;

import java.util.UUID;

/** Test access to package-private NickPackets helpers. */
public final class NickPacketsTestAccess {
	private NickPacketsTestAccess() {}

	public static ClientboundSetPlayerTeamPacket addTeam(UUID player, Component nick) {
		return NickPackets.addTeam(player, nick);
	}
}
