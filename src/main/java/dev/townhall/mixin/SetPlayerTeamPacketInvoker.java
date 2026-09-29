package dev.townhall.mixin;

import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Collection;
import java.util.Optional;

@Mixin(ClientboundSetPlayerTeamPacket.class)
public interface SetPlayerTeamPacketInvoker {
	@Invoker("<init>")
	static ClientboundSetPlayerTeamPacket townhall$create(String name, int method, Optional<ClientboundSetPlayerTeamPacket.Parameters> parameters, Collection<String> players) {
		throw new AssertionError();
	}

	@org.spongepowered.asm.mixin.gen.Accessor("method")
	int townhall$method();
}
