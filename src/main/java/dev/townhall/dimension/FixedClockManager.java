package dev.townhall.dimension;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.clock.ClockInstance;
import net.minecraft.world.clock.ClockManager;
import net.minecraft.world.clock.WorldClock;

/**
 * The clocks a server world reads its sky light, mob spawning and other time-based attributes from.
 * Worlds with a fixed "time" rule see every clock stopped at that time; all others see the real clocks.
 * The rule is read on every call, so changing it takes effect on the next tick without rebuilding anything.
 */
public record FixedClockManager(ServerLevel level, ClockManager delegate) implements ClockManager {

	@Override
	public ClockInstance getInstance(Holder<WorldClock> clock) {
		ClockInstance real = delegate.getInstance(clock);
		Long fixed = DimensionSettings.fixedTime(level.dimension());
		return fixed == null ? real : new Stopped(DimensionSettings.pin(real.totalTicks(), fixed));
	}

	private record Stopped(long totalTicks) implements ClockInstance {
		@Override public float partialTick() { return 0f; }
		@Override public float rate() { return 0f; }
		@Override public boolean isPaused() { return true; }
	}
}
