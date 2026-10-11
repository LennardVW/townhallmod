package dev.townhall.city;

import dev.townhall.TownhallMod;
import dev.townhall.nick.Nicknames;
import dev.townhall.util.Text;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

/** Screen title when a player enters or leaves somebody else's plot. Logic in {@link #update}, sending is thin on top. */
public final class PlotEnterTracker {
	private PlotEnterTracker() {}
	public enum Kind { NONE, ENTER, LEAVE }
	/** @param id plot id (entered or left), plot may be null on LEAVE if the plot was removed meanwhile */
	public record Result(Kind kind, String id, Plot plot) {
		static final Result NONE = new Result(Kind.NONE,null,null);
	}
	private static final int INTERVAL = 10;
	private static final Map<UUID,String> FOREIGN = new HashMap<>();   // last foreign plot id per player
	private static final Map<UUID,BlockPos> LAST_POS = new HashMap<>();

	public static void register() {
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(PlotEnterTracker::onTick);
	}

	static void onTick(MinecraftServer server) {
		if (server.getTickCount() % INTERVAL != 0) return;
		if (!TownhallMod.CONFIG.get().city.plotEnterTitles || !TownhallMod.CONFIG.get().city.claimsEnabled) { reset(); return; }
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			BlockPos pos = player.blockPosition();
			if (pos.equals(LAST_POS.put(player.getUUID(),pos))) continue;
			send(player,update(player));
		}
	}

	/** Compares the plot at the player's position with the last foreign plot and returns what changed. */
	public static Result update(ServerPlayer player) {
		UUID id = player.getUUID();
		if (!TownhallMod.CONFIG.get().city.claimsEnabled) { FOREIGN.remove(id); return Result.NONE; }
		var at = PlotService.at(player.level(),player.blockPosition());
		String now = null; Plot plot = null;
		if (at.isPresent()) {
			Plot p = at.get().getValue();
			boolean own = p.isOwner(id) || p.isManager(id) || p.trusted().contains(id.toString());
			if (!own) { now = at.get().getKey(); plot = p; }
		}
		String before = now == null ? FOREIGN.remove(id) : FOREIGN.put(id,now);
		if (Objects.equals(before,now)) return Result.NONE;
		if (now != null) return new Result(Kind.ENTER,now,plot);
		Plot old = PlotStorage.get(player.level().getServer()).plots().get(before);
		return new Result(Kind.LEAVE,before,old);
	}

	public static String ownerNames(MinecraftServer server, Plot plot) {
		List<String> uuids = new ArrayList<>();
		plot.owner().ifPresent(uuids::add);
		uuids.addAll(plot.coOwners());
		return String.join(", ",uuids.stream().map(u -> name(server,u)).toList());
	}

	private static String name(MinecraftServer server, String uuid) {
		try {
			UUID u = UUID.fromString(uuid);
			ServerPlayer online = server.getPlayerList().getPlayer(u);
			if (online != null) return online.getDisplayName().getString();
			var nick = Nicknames.of(u);
			if (nick.isPresent()) return nick.get().getString();
			return server.services().nameToIdCache().get(u).map(net.minecraft.server.players.NameAndId::name).orElse(uuid);
		} catch (IllegalArgumentException e) { return uuid; }
	}

	/** {title, subtitle} for a result, or null for NONE. */
	public static String[] texts(MinecraftServer server, Result r) {
		if (r.kind() == Kind.NONE) return null;
		String owners = r.plot() == null || r.plot().owner().isEmpty() ? null : ownerNames(server,r.plot());
		if (r.kind() == Kind.ENTER) return owners == null ? new String[]{r.id(),"Öffentliche Fläche"} : new String[]{"Grundstück von " + owners,r.id()};
		return new String[]{"","Du verlässt " + (owners == null ? r.id() : "das Grundstück von " + owners)};
	}

	private static void send(ServerPlayer player, Result r) {
		String[] t = texts(player.level().getServer(),r);
		if (t == null) return;
		player.connection.send(new ClientboundSetTitlesAnimationPacket(5,40,10));
		player.connection.send(new ClientboundSetSubtitleTextPacket(Text.of(t[1])));
		player.connection.send(new ClientboundSetTitleTextPacket(Text.of(t[0])));
	}

	public static void onLeave(ServerPlayer player) { FOREIGN.remove(player.getUUID()); LAST_POS.remove(player.getUUID()); }
	public static void reset() { FOREIGN.clear(); LAST_POS.clear(); }
}
