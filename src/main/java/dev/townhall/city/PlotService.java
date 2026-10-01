package dev.townhall.city;

import dev.townhall.TownhallMod;
import dev.townhall.protection.Protection;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import java.util.Optional;

public final class PlotService {
	private PlotService() {}
	public static Optional<java.util.Map.Entry<String,Plot>> at(ServerLevel level,BlockPos pos) {
		if (!TownhallMod.CONFIG.get().city.claimsEnabled) return Optional.empty();
		return PlotStorage.get(level.getServer()).at(level.dimension().identifier().toString(),pos);
	}
	public static boolean canBuild(ServerPlayer player,ServerLevel level,BlockPos pos) {
		if (TownhallMod.isOperator(player.permissions())) return true;
		var p = at(level,pos);
		return p.map(e -> e.getValue().admits(player.getUUID(),RoleService.roles(player)))
				.orElseGet(() -> Protection.mayBuildIn(player,level.dimension()));
	}
	public static boolean canUse(ServerPlayer player,ServerLevel level,BlockPos pos) {
		if (!canUseSingle(player,level,pos)) return false;
		var state = level.getBlockState(pos);
		if (state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock && state.getValue(net.minecraft.world.level.block.ChestBlock.TYPE) != net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
			return canUseSingle(player,level,net.minecraft.world.level.block.ChestBlock.getConnectedBlockPos(pos,state));
		}
		return true;
	}

	private static boolean canUseSingle(ServerPlayer player,ServerLevel level,BlockPos pos) {
		if (TownhallMod.isOperator(player.permissions())) return true;
		var entry = at(level,pos); if (entry.isEmpty()) return true;
		Plot p = entry.get().getValue();
		if (p.admits(player.getUUID(),RoleService.roles(player))) return true;
		// Visitors may use doors/buttons/workstations when publicUse=true, but never open a storage container.
		return p.publicUse() && !(level.getBlockEntity(pos) instanceof Container);
	}
	public static boolean protects(ServerLevel level,BlockPos pos) { return at(level,pos).isPresent(); }
	public static boolean pistonCrossesBoundary(ServerLevel level,BlockPos source,BlockPos destination) {
		var to = at(level,destination); if (to.isEmpty()) return false;
		return at(level,source).map(e -> !e.getKey().equals(to.get().getKey())).orElse(true);
	}
	public static boolean deniesContainerTransfer(ServerLevel level,BlockPos a,BlockPos b) {
		return pistonCrossesBoundary(level,a,b) || pistonCrossesBoundary(level,b,a);
	}
}
