package dev.townhall.test.mixin;

import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets a test run one weather step of a world right now. */
@Mixin(ServerLevel.class)
public interface ServerLevelInvoker {

	@Invoker("advanceWeatherCycle")
	void townhallTest$advanceWeatherCycle();
}
