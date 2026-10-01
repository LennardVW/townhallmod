package dev.townhall.test.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.TickThrottler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerGamePacketListenerImpl.class)
public interface PasteNetworkTestAccess {
	@Invoker("performUnsignedChatCommand") void townhall$performUnsigned(String command);
	@Invoker("detectCommandRateSpam") void townhall$commandSpam();
	@Invoker("detectChatRateSpam") void townhall$chatSpam();
	@Accessor("commandSpamThrottler") TickThrottler townhall$commandThrottler();
	@Accessor("chatSpamThrottler") TickThrottler townhall$chatThrottler();
}
