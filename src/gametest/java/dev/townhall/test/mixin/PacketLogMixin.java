package dev.townhall.test.mixin;

import dev.townhall.test.PacketLog;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import io.netty.channel.ChannelFutureListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records packets sent to players that a test watches (PacketLog.watch), so tests can assert on what a client got. */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class PacketLogMixin {

	@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V", at = @At("HEAD"))
	private void townhallTest$record(Packet<?> packet, ChannelFutureListener listener, CallbackInfo ci) {
		if ((Object) this instanceof ServerGamePacketListenerImpl game) PacketLog.record(game.player.getUUID(), packet);
	}
}
