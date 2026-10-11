package dev.townhall.mixin;

import dev.townhall.protection.BuilderSchematics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Command packets reach these methods on the server thread, after vanilla packet/chat validation. */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class BuilderPasteNetworkMixin {
	@Shadow public ServerPlayer player;
	@Unique private int townhall$pasteTick = Integer.MIN_VALUE;
	@Unique private int townhall$pasteCount;
	@Unique private boolean townhall$pasteExemption;

	@Unique
	private boolean townhall$preparePaste(String command) {
		townhall$pasteExemption = false;
		if (!player.gameMode.isCreative() || !BuilderSchematics.mayUse(player.createCommandSourceStack())) return true;
		int space = command.indexOf(' ');
		String root = space < 0 ? command : command.substring(0, space);
		if (!root.equals("setblock") && !root.equals("fill")) return true;
		int tick = player.level().getServer().getTickCount();
		if (townhall$pasteTick != tick) { townhall$pasteTick = tick; townhall$pasteCount = 0; }
		if (++townhall$pasteCount > BuilderSchematics.MAX_COMMANDS_PER_TICK) {
			player.sendOverlayMessage(Component.literal("Paste zu schnell. Setze commandLimitPerTick in Litematica auf höchstens " + BuilderSchematics.MAX_COMMANDS_PER_TICK + "."));
			return false;
		}
		townhall$pasteExemption = true;
		return true;
	}

	@Inject(method = "performUnsignedChatCommand", at = @At("HEAD"), cancellable = true)
	private void townhall$prepareUnsigned(String command, CallbackInfo ci) {
		if (!townhall$preparePaste(command)) ci.cancel();
	}

	@Inject(method = "performSignedChatCommand", at = @At("HEAD"), cancellable = true)
	private void townhall$prepareSigned(ServerboundChatCommandSignedPacket packet, LastSeenMessages lastSeen, CallbackInfo ci) {
		if (!townhall$preparePaste(packet.command())) ci.cancel();
	}

	@Inject(method = "detectCommandRateSpam", at = @At("HEAD"), cancellable = true)
	private void townhall$accountPaste(CallbackInfo ci) {
		if (townhall$pasteExemption) { townhall$pasteExemption = false; ci.cancel(); }
	}
}
