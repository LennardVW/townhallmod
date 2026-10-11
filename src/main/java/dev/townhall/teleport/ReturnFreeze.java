package dev.townhall.teleport;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Consumer;

/** Holds a player in place for a few seconds before /townhall return fires (anti-abuse); display and ticking live in {@link Warmup}. */
public final class ReturnFreeze {

	private ReturnFreeze() {}

	public static boolean isWaiting(ServerPlayer player) {
		return Warmup.isWaiting(player);
	}

	public static void start(ServerPlayer player, int seconds, Consumer<ServerPlayer> action) {
		Warmup.start(player, seconds, true, action, null);
	}

	public static void clear() {
		Warmup.clear();
	}

	public static void onServerTick(MinecraftServer server) {
		Warmup.onServerTick(server);
	}
}
