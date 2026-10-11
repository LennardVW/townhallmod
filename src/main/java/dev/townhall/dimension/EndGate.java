package dev.townhall.dimension;

import dev.townhall.TownhallMod;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Admin switch for the End: while closed, end portal blocks outside the End do nothing (the way back still works). */
public final class EndGate {

	private static final long MESSAGE_GAP_MS = 3000;
	private static final Map<UUID, Long> LAST_MESSAGE = new ConcurrentHashMap<>();

	private EndGate() {}

	/** True if an end portal block in this dimension must not send entities to the End. */
	public static boolean blocks(boolean endEnabled, boolean inEnd) {
		return !endEnabled && !inEnd;
	}

	public static boolean blocks(Level level) {
		return blocks(TownhallMod.CONFIG.get().endEnabled, level.dimension() == Level.END);
	}

	/** Actionbar hint, at most once per 3 seconds per player. */
	public static void notifyClosed(Entity entity) {
		if (!(entity instanceof ServerPlayer player)) return;
		long now = System.currentTimeMillis();
		Long last = LAST_MESSAGE.get(player.getUUID());
		if (last != null && now - last < MESSAGE_GAP_MS) return;
		LAST_MESSAGE.put(player.getUUID(), now);
		player.sendSystemMessage(Component.literal("Das End ist noch geschlossen."), true);
	}
}
