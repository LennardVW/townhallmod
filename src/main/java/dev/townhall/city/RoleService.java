package dev.townhall.city;

import dev.townhall.TownhallMod;
import dev.townhall.util.Text;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Explicit town capabilities; there is deliberately no wildcard or vanilla command permission grant. */
public final class RoleService {
	public static final List<String> PERMISSIONS = List.of("city.announce", "police.jail", "police.release", "shop.manage", "election.count");
	private RoleService() {}
	public static boolean hasPermission(ServerPlayer player, String permission) {
		return TownhallMod.isOperator(player.permissions()) || RoleStorage.get(player.level().getServer()).has(player.getUUID(), permission);
	}
	public static boolean hasPermission(CommandSourceStack source, String permission) {
		return source.getPlayer() == null ? CityAccess.isAdmin(source) : hasPermission(source.getPlayer(), permission);
	}
	public static Set<String> roles(ServerPlayer player) { return RoleStorage.get(player.level().getServer()).memberships(player.getUUID()); }
	public static Component decorate(ServerPlayer player, Component name, boolean tab) {
		CitySettings settings = TownhallMod.CONFIG.get().city;
		if (!(tab ? settings.rolesInTab : settings.rolesInChat)) return name;
		RoleStorage storage = RoleStorage.get(player.level().getServer());
		return storage.memberships(player.getUUID()).stream()
				.filter(storage.definitions()::containsKey)
				.sorted(Comparator.<String>comparingInt(id -> storage.definitions().get(id).priority()).reversed().thenComparing(id -> id))
				.map(storage.definitions()::get).filter(r -> !r.prefix().isEmpty()).findFirst()
				.<Component>map(r -> Component.empty().append(Text.of(r.prefix())).append(name)).orElse(name);
	}
	public static void refresh(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			server.getCommands().sendCommands(p);
			server.getPlayerList().broadcastAll(new ClientboundPlayerInfoUpdatePacket(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME, p));
		}
	}
}
