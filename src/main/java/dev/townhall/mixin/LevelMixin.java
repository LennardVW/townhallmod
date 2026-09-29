package dev.townhall.mixin;

import dev.townhall.dimension.DimensionSettings;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla has one difficulty for all worlds (LevelAccessor.getDifficulty() reads the shared level data).
 * This adds a Level.getDifficulty() override so a dimension can have its own difficulty from the config.
 * Mobs, damage scaling, hunger and peaceful regeneration all ask the level, so they follow it.
 * Also pins getDefaultClockTime() for worlds with a fixed "time" rule.
 */
@Mixin(Level.class)
abstract class LevelMixin {

	public Difficulty getDifficulty() {
		Level self = (Level) (Object) this;
		if (self instanceof ServerLevel) {
			Difficulty override = DimensionSettings.difficulty(self.dimension());
			if (override != null) return override;
		}
		return self.getLevelData().getDifficulty();
	}

	/** Time of day for this world's own logic (villagers, loot time checks); pinned when the world has a fixed "time" rule. */
	@Inject(method = "getDefaultClockTime", at = @At("RETURN"), cancellable = true)
	private void townhall$fixedTime(CallbackInfoReturnable<Long> cir) {
		Level self = (Level) (Object) this;
		if (!(self instanceof ServerLevel)) return;
		Long fixed = DimensionSettings.fixedTime(self.dimension());
		if (fixed != null) cir.setReturnValue(DimensionSettings.pin(cir.getReturnValue(), fixed));
	}
}
