package dev.townhall.test.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Set;

/** Test access to ChunkMap.TrackedEntity (package-private): who currently sees the entity. */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public interface TrackedEntityTestAccessor {
	@Accessor("seenBy")
	Set<ServerPlayerConnection> townhall$seenBy();

	@Invoker("removePlayer")
	void townhall$removePlayer(ServerPlayer player);
}
