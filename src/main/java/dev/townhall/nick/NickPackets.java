package dev.townhall.nick;

import com.mojang.authlib.GameProfile;
import dev.townhall.mixin.ChunkMapAccessor;
import dev.townhall.mixin.PlayerInfoUpdatePacketAccessor;
import dev.townhall.mixin.SetPlayerTeamPacketInvoker;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

/**
 * The name above a player's head is: team prefix + game profile name + team suffix, as the client knows them.
 * Profile names are limited to 16 characters, so for every other viewer the profile name becomes an invisible,
 * unique token (only color codes) and a client-only team for that player carries the full nickname as prefix
 * (up to 32 characters, with colors). The client looks up scores (death count in the tab list) and team members by
 * profile name, so score and team packets are renamed to the token too. The player's own client keeps the real profile.
 */
public final class NickPackets {

	private static final String HEX = "0123456789abcdef";

	private NickPackets() {}

	/**
	 * Invisible profile name for a nicknamed player: "§r" plus six color codes from the UUID (14 characters,
	 * renders as nothing). Stable for a player, and unique enough that two players never share it in practice.
	 */
	public static String token(UUID player) {
		String hex = Long.toHexString(player.getMostSignificantBits() ^ player.getLeastSignificantBits());
		StringBuilder token = new StringBuilder("§r");
		for (int i = 0; i < 6; i++) token.append('§').append(HEX.charAt(Character.digit(hex.charAt(hex.length() - 1 - i), 16)));
		return token.toString();
	}

	static String teamName(UUID player) {
		return "th_nick_" + player.toString().substring(0, 8);
	}

	/** Client-only team that puts the full nickname in front of the invisible profile name. */
	static ClientboundSetPlayerTeamPacket addTeam(UUID player, Component nick) {
		PlayerTeam team = new PlayerTeam(new Scoreboard(), teamName(player));
		team.setPlayerPrefix(nick);
		team.getPlayers().add(token(player));
		return ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, true);
	}

	static ClientboundSetPlayerTeamPacket removeTeam(UUID player) {
		return ClientboundSetPlayerTeamPacket.createRemovePacket(new PlayerTeam(new Scoreboard(), teamName(player)));
	}

	/** A player joined: they learn the nickname teams of everyone online, everyone else learns theirs. */
	public static void onJoin(ServerPlayer joined) {
		var players = joined.level().getServer().getPlayerList().getPlayers();
		for (ServerPlayer other : players) {
			if (other == joined) continue;
			Nicknames.of(other.getUUID()).ifPresent(nick -> joined.connection.send(addTeam(other.getUUID(), nick)));
			Nicknames.of(joined.getUUID()).ifPresent(nick -> other.connection.send(addTeam(joined.getUUID(), nick)));
		}
	}

	public static void onLeave(ServerPlayer left) {
		if (Nicknames.of(left.getUUID()).isEmpty()) return;
		for (ServerPlayer other : left.level().getServer().getPlayerList().getPlayers()) {
			if (other != left) other.connection.send(removeTeam(left.getUUID()));
		}
	}

	/** Returns the packet as this viewer should get it. */
	public static Packet<?> rewrite(ServerPlayer viewer, Packet<?> packet) {
		Map<String, String> renames = Nicknames.headNamesByRealName();
		if (renames.isEmpty()) return packet;
		String own = viewer.getGameProfile().name();
		return switch (packet) {
			case ClientboundPlayerInfoUpdatePacket info when info.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER) -> {
				List<ClientboundPlayerInfoUpdatePacket.Entry> entries = new ArrayList<>();
				boolean changed = false;
				for (var e : info.entries()) {
					String head = e.profile() == null || e.profileId().equals(viewer.getUUID()) || Nicknames.of(e.profileId()).isEmpty()
							? null : token(e.profileId());
					if (head == null) {
						entries.add(e);
						continue;
					}
					changed = true;
					entries.add(new ClientboundPlayerInfoUpdatePacket.Entry(e.profileId(), new GameProfile(e.profile().id(), head, e.profile().properties()),
							e.listed(), e.latency(), e.gameMode(), e.displayName(), e.showHat(), e.listOrder(), e.chatSession()));
				}
				if (!changed) yield packet;
				ClientboundPlayerInfoUpdatePacket copy = new ClientboundPlayerInfoUpdatePacket(info.actions(), List.of());
				((PlayerInfoUpdatePacketAccessor) copy).townhall$setEntries(List.copyOf(entries));
				yield copy;
			}
			case ClientboundSetScorePacket score when !score.owner().equals(own) && renames.containsKey(score.owner()) ->
					new ClientboundSetScorePacket(renames.get(score.owner()), score.objectiveName(), score.score(), score.display(), score.numberFormat());
			case ClientboundResetScorePacket reset when reset.owner() != null && !reset.owner().equals(own) && renames.containsKey(reset.owner()) ->
					new ClientboundResetScorePacket(renames.get(reset.owner()), reset.objectiveName());
			case ClientboundSetPlayerTeamPacket team when team.getPlayers().stream().anyMatch(n -> !n.equals(own) && renames.containsKey(n)) ->
					SetPlayerTeamPacketInvoker.townhall$create(team.getName(), ((SetPlayerTeamPacketInvoker) team).townhall$method(), team.getParameters(),
							team.getPlayers().stream().map(n -> n.equals(own) ? n : renames.getOrDefault(n, n)).toList());
			default -> packet;
		};
	}

	/**
	 * After a nickname change: every other viewer drops the player (entity and tab entry) and gets them again, now with
	 * the new profile name, so the name above the head changes right away. Their scores are sent again under the new name.
	 */
	public static void refresh(ServerPlayer target) {
		var server = target.level().getServer();
		Object tracked = ((ChunkMapAccessor) target.level().getChunkSource().chunkMap).townhall$entityMap().get(target.getId());
		for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
			if (viewer == target) {
				viewer.connection.send(new ClientboundPlayerInfoUpdatePacket(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME, target));
				continue;
			}
			Runnable reAdd = () -> {
				viewer.connection.send(new ClientboundPlayerInfoRemovePacket(List.of(target.getUUID())));
				viewer.connection.send(removeTeam(target.getUUID()));
				Nicknames.of(target.getUUID()).ifPresent(nick -> viewer.connection.send(addTeam(target.getUUID(), nick)));
				viewer.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(target)));
				var scoreboard = server.getScoreboard();
				for (var entry : scoreboard.listPlayerScores(target).object2IntEntrySet()) {
					var objective = entry.getKey();
					if (scoreboard.getObjectiveDisplaySlotCount(objective) == 0) continue;
					var score = scoreboard.getPlayerScoreInfo(target, objective);
					// Goes through the send hook like every packet, so it arrives under the new name.
					viewer.connection.send(new ClientboundSetScorePacket(target.getGameProfile().name(), objective.getName(), entry.getIntValue(),
							java.util.Optional.empty(), score == null ? java.util.Optional.empty() : java.util.Optional.ofNullable(score.numberFormat())));
				}
			};
			if (tracked instanceof NickRefreshable refreshable) refreshable.townhall$respawnFor(viewer, reAdd);
			else reAdd.run();
		}
	}
}
