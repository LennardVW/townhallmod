package dev.townhall.nick;

import com.mojang.authlib.GameProfile;
import dev.townhall.mixin.ChunkMapAccessor;
import dev.townhall.mixin.PlayerInfoUpdatePacketAccessor;
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
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Score;
import net.minecraft.world.scores.Scoreboard;

/**
 * The name above a player's head is: team prefix + game profile name + team suffix, as the client knows them.
 * Profile names are limited to 16 characters, so for every other viewer the profile name becomes an invisible,
 * unique token (only color codes) and a client-only team for that player carries the full nickname as prefix
 * (up to 32 characters, with colors). The client looks up scores (death count in the tab list) by profile name, so score
 * packets of online nicknamed players are renamed to the token too. The player's own client keeps the real profile.
 * <p>
 * Vanilla team packets ({@code /team}) are NOT renamed: on the client the token only ever sits in its th_nick team and the
 * real name sits in the real team, exactly as the server has it. A client scoreboard throws (and disconnects) when it is
 * told to remove a name from a team the name isn't in, so the token must never be in two teams.
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
		Nicknames.onLeave(left);
		if (Nicknames.of(left.getUUID()).isEmpty()) return;
		for (ServerPlayer other : left.level().getServer().getPlayerList().getPlayers()) {
			if (other != left) other.connection.send(removeTeam(left.getUUID()));
		}
	}

	/** Returns the packet as this viewer should get it. */
	public static Packet<?> rewrite(ServerPlayer viewer, Packet<?> packet) {
		if (!Nicknames.any()) return packet;
		Map<String, String> renames = Nicknames.headNamesByRealName();
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
			default -> packet;
		};
	}

	/**
	 * After a nickname change: every other viewer drops the player (tab entry, and the entity for viewers in the same world)
	 * and gets them again, now with the new profile name, so the name above the head changes right away. Their scores are
	 * sent again under the new name. Viewers in other worlds only get info, team and score packets: re-tracking the entity
	 * for them would spawn a ghost of the player in their world.
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
				// Goes through the send hook like every packet, so it arrives under the new name.
				scorePackets(target).forEach(viewer.connection::send);
			};
			if (tracked instanceof NickRefreshable refreshable && viewer.level() == target.level()) refreshable.townhall$respawnFor(viewer, reAdd);
			else reAdd.run();
		}
	}

	/** The target's scores in displayed objectives, as the server would send them (display text and number format kept). */
	static List<ClientboundSetScorePacket> scorePackets(ServerPlayer target) {
		var scoreboard = target.level().getServer().getScoreboard();
		List<ClientboundSetScorePacket> packets = new ArrayList<>();
		for (var entry : scoreboard.listPlayerScores(target).object2IntEntrySet()) {
			var objective = entry.getKey();
			if (scoreboard.getObjectiveDisplaySlotCount(objective) == 0) continue;
			var info = scoreboard.getPlayerScoreInfo(target, objective);
			Optional<Component> display = info instanceof Score score ? Optional.ofNullable(score.display()) : Optional.empty();
			packets.add(new ClientboundSetScorePacket(target.getScoreboardName(), objective.getName(), entry.getIntValue(),
					display, info == null ? Optional.empty() : Optional.ofNullable(info.numberFormat())));
		}
		return packets;
	}
}
