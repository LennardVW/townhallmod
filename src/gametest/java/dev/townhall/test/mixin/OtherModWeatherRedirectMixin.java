package dev.townhall.test.mixin;

import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Stands in for mc-worlds, which @Redirects the same weather broadcast. Townhall 1.4.0 used a @Redirect there too
 * and the server crashed on start ("@Redirect conflict"). With both loaded, this test server must still boot.
 */
@Mixin(ServerLevel.class)
public abstract class OtherModWeatherRedirectMixin {

	@Redirect(method = "advanceWeatherCycle", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/PlayerList;broadcastAll(Lnet/minecraft/network/protocol/Packet;)V"))
	private void townhallTest$otherModBroadcast(PlayerList players, Packet<?> packet) {
		players.broadcastAll(packet);
	}
}
