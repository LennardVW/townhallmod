package dev.townhall.display;

import dev.townhall.TownhallMod;
import dev.townhall.activity.Playtime;
import dev.townhall.config.TownhallConfig;
import dev.townhall.nick.Nicknames;
import dev.townhall.util.Text;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * Header and footer of the tab list, from config.tabList (lines with &-colors). Placeholders:
 * {player} (nickname or name), {online}, {max}, {ping} (ms), {playtime}, {world}. Refreshed every 2 seconds.
 */
public final class TabList {

	private TabList() {}

	public static void update(MinecraftServer server) {
		TownhallConfig.TabList cfg = TownhallMod.CONFIG.get().tabList;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!cfg.enabled) {
				player.connection.send(new ClientboundTabListPacket(Component.empty(), Component.empty()));
				continue;
			}
			player.connection.send(new ClientboundTabListPacket(lines(cfg.header, player), lines(cfg.footer, player)));
		}
	}

	public static Component lines(List<String> lines, ServerPlayer player) {
		return Text.of(String.join("\n", lines.stream().map(l -> fill(l, player)).toList()));
	}

	static String fill(String line, ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		String name = Nicknames.get(server).entries().containsKey(player.getUUID())
				? Nicknames.get(server).entries().get(player.getUUID()).nick() + "&r" : player.getGameProfile().name();
		return line.replace("{player}", name)
				.replace("{online}", String.valueOf(server.getPlayerList().getPlayerCount()))
				.replace("{max}", String.valueOf(server.getPlayerList().getMaxPlayers()))
				.replace("{ping}", String.valueOf(player.connection.latency()))
				.replace("{playtime}", Playtime.get(server).of(player.getUUID()).map(e -> Playtime.format(e.millis())).orElse("0m"))
				.replace("{world}", player.level().dimension().identifier().getPath());
	}
}
