package dev.townhall.mixin;

import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** Used only on fresh copies (NickPackets), never on a packet that is shared between players. */
@Mixin(ClientboundPlayerInfoUpdatePacket.class)
public interface PlayerInfoUpdatePacketAccessor {
	@Mutable
	@Accessor("entries")
	void townhall$setEntries(List<ClientboundPlayerInfoUpdatePacket.Entry> entries);
}
