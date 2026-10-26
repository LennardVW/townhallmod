package dev.townhall.mixin;

import dev.townhall.display.SilentNames;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Name tag / command / loading a name: names from mobs.silentNames silence a creature, see {@link SilentNames}. */
@Mixin(Entity.class)
abstract class EntityCustomNameMixin {

	@Inject(method = "setCustomName", at = @At("TAIL"))
	private void townhall$silentNames(Component name, CallbackInfo ci) {
		SilentNames.onNameChanged((Entity) (Object) this, name);
	}
}
