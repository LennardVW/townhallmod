package dev.townhall.mixin;

import dev.townhall.dimension.DimensionSettings;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Vanilla has one difficulty for all worlds (LevelAccessor.getDifficulty() reads the shared level data).
 * This adds a Level.getDifficulty() override so a dimension can have its own difficulty from the config.
 * Mobs, damage scaling, hunger and peaceful regeneration all ask the level, so they follow it.
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
}
