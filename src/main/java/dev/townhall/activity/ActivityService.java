package dev.townhall.activity;

import dev.townhall.display.TabList;
import dev.townhall.teleport.ConfinementService;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.server.MinecraftServer;

/**
 * Once per second: AFK check and play time. Chat marks players active here, commands in CommandActivityMixin.
 * Runs 10 ticks after ConfinementService's per-second pass, so the two never land on the same tick.
 */
public final class ActivityService {

	private static final int INTERVAL_TICKS = 20;
	/** Half a second after ConfinementService (which runs when its own counter reaches 20). */
	private static final int OFFSET_TICKS = 10;
	private static final long MAX_STEP_MILLIS = 5_000;

	private static int ticks = OFFSET_TICKS;
	/** System.nanoTime of the last pass (monotonic); 0 = no pass yet. */
	private static long lastRunNanos;
	private static int seconds;

	private ActivityService() {}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(ActivityService::onTick);
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> Afk.active(sender));
		// A restarted server (singleplayer, tests) starts both per-second schedules fresh, still 10 ticks apart.
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			reset();
			ConfinementService.reset();
		});
	}

	static void reset() {
		ticks = OFFSET_TICKS;
		lastRunNanos = 0;
		seconds = 0;
	}

	private static void onTick(MinecraftServer server) {
		if (++ticks < INTERVAL_TICKS) return;
		ticks = 0;
		long nanos = System.nanoTime();
		long elapsed = lastRunNanos == 0 ? 0 : Math.clamp((nanos - lastRunNanos) / 1_000_000, 0, MAX_STEP_MILLIS);
		lastRunNanos = nanos;
		// AFK compares wall-clock timestamps it stores itself, so it keeps System.currentTimeMillis.
		Afk.check(server, System.currentTimeMillis());
		Playtime.get(server).tick(server, elapsed);
		if (++seconds % 2 == 0) TabList.update(server);
	}
}
