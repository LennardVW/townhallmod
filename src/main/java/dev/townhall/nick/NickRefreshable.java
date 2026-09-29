package dev.townhall.nick;

import net.minecraft.server.level.ServerPlayer;

/** Implemented by ChunkMap.TrackedEntity (TrackedEntityMixin). */
public interface NickRefreshable {
	void townhall$respawnFor(ServerPlayer viewer, Runnable whileHidden);
}
