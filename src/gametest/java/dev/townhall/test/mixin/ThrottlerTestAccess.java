package dev.townhall.test.mixin;

import net.minecraft.util.TickThrottler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(TickThrottler.class)
public interface ThrottlerTestAccess {
	@Accessor("count") int townhall$count();
}
