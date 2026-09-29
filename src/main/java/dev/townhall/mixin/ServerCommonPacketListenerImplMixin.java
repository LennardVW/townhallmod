package dev.townhall.mixin;

import dev.townhall.dimension.DimensionSettings;
import dev.townhall.nick.NickPackets;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Every packet to a player passes here. Time packets: players in a world with a fixed "time" rule get the clocks
 * stopped at that time. Player-info, score and team packets: other players' nicknames replace their profile names
 * (NickPackets), so the name above the head shows the nickname.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
abstract class ServerCommonPacketListenerImplMixin {

	@ModifyVariable(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V", at = @At("HEAD"), argsOnly = true)
	private Packet<?> townhall$fixedTime(Packet<?> packet) {
		if (!((Object) this instanceof ServerGamePacketListenerImpl game)) return packet;
		if (packet instanceof ClientboundSetTimePacket time) return DimensionSettings.timePacketFor(game.player.level().dimension(), time);
		return NickPackets.rewrite(game.player, packet);
	}
}
