package dev.townhall.teleport;

import net.minecraft.ChatFormatting;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Holds a player in place for a few seconds before /townhall return fires (anti-abuse). Tick based, state per UUID. */
public final class ReturnFreeze {
	private record Wait(ServerPlayer player, ServerLevel level, Vec3 anchor, long endTick, Consumer<ServerPlayer> action) {}

	private static final Map<UUID, Wait> WAITING = new HashMap<>();

	private ReturnFreeze() {}

	public static boolean isWaiting(ServerPlayer player) {
		return WAITING.containsKey(player.getUUID());
	}

	public static void start(ServerPlayer player, int seconds, Consumer<ServerPlayer> action) {
		long end = player.level().getServer().getTickCount() + seconds * 20L;
		WAITING.put(player.getUUID(), new Wait(player, (ServerLevel) player.level(), player.position(), end, action));
		countdown(player, seconds);
	}

	public static void clear() {
		WAITING.clear();
	}

	public static void onServerTick(MinecraftServer server) {
		if (WAITING.isEmpty()) return;
		long now = server.getTickCount();
		Iterator<Map.Entry<UUID, Wait>> it = WAITING.entrySet().iterator();
		java.util.List<Wait> due = new java.util.ArrayList<>();
		while (it.hasNext()) {
			Wait w = it.next().getValue();
			ServerPlayer p = w.player();
			if (p.isRemoved() || !p.isAlive() || p.level() != w.level()) {
				it.remove();
				continue;
			}
			if (now >= w.endTick()) {
				it.remove();
				due.add(w);
				continue;
			}
			if (p.position().distanceToSqr(w.anchor()) > 0.01) {
				p.teleportTo(w.level(), w.anchor().x, w.anchor().y, w.anchor().z, java.util.Set.of(), p.getYRot(), p.getXRot(), true);
			}
			long left = w.endTick() - now;
			if (left % 20 == 0) countdown(p, (int) (left / 20));
		}
		for (Wait w : due) w.action().accept(w.player());
	}

	private static void countdown(ServerPlayer player, int seconds) {
		player.sendOverlayMessage(net.minecraft.network.chat.Component.literal("Rückkehr in " + seconds + " …").withStyle(ChatFormatting.YELLOW));
	}
}
