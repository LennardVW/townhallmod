package dev.townhall.city;

import dev.townhall.election.ElectionService;
import dev.townhall.shop.ShopService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Managed urns and shop markers survive until explicitly removed through their administration commands. */
public final class CivicSites {
	private CivicSites() {}
	public static boolean protectedAt(ServerLevel level,BlockPos pos) {
		return ElectionService.protectedAt(level,pos) || ShopService.protectedAt(level,pos);
	}
	public static boolean canUse(ServerPlayer player,ServerLevel level,BlockPos pos) {
		return (!ElectionService.protectedAt(level,pos) || ElectionService.canUse(player,level,pos))
				&& (!ShopService.protectedAt(level,pos) || ShopService.canUse(player,level,pos));
	}
}
