package dev.townhall.activity;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.server.MinecraftServer;

/** Once per second: AFK check and play time. Chat marks players active here, commands in CommandActivityMixin. */
public final class ActivityService {

	private static int ticks;
	private static long lastRun;
	private static int seconds;

	private ActivityService() {}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(ActivityService::onTick);
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> Afk.active(sender));
	}

	private static void onTick(MinecraftServer server) {
		if (++ticks < 20) return;
		ticks = 0;
		long now = System.currentTimeMillis();
		long elapsed = lastRun == 0 ? 0 : Math.min(now - lastRun, 5000);
		lastRun = now;
		Afk.check(server, now);
		Playtime.get(server).tick(server, elapsed);
		if (++seconds % 2 == 0) dev.townhall.display.TabList.update(server);
	}
}
