package dev.townhall.nick;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;

/** Test access to package-private NickPackets helpers. */
public final class NickPacketsTestAccess {
	private NickPacketsTestAccess() {}

	public static ClientboundSetPlayerTeamPacket addTeam(UUID player, Component nick) {
		return NickPackets.addTeam(player, nick);
	}

	public static ClientboundSetPlayerTeamPacket removeTeam(UUID player) {
		return NickPackets.removeTeam(player);
	}

	public static List<ClientboundSetScorePacket> scorePackets(ServerPlayer target) {
		return NickPackets.scorePackets(target);
	}
}
