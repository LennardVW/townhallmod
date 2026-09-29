package dev.townhall.mixin;

import dev.townhall.nick.NickRefreshable;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Lets NickPackets despawn a player for one viewer and spawn them again, so the new name above the head shows. */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
abstract class TrackedEntityMixin implements NickRefreshable {

	@Shadow
	public abstract void removePlayer(ServerPlayer player);

	@Shadow
	public abstract void updatePlayer(ServerPlayer player);

	@Override
	public void townhall$respawnFor(ServerPlayer viewer, Runnable whileHidden) {
		removePlayer(viewer);
		whileHidden.run();
		updatePlayer(viewer);
	}
}
