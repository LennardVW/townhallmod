package dev.townhall.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.townhall.dimension.DimensionSettings;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;


/**
 * Fixed weather per world. advanceWeatherCycle moves each world's rain/thunder level towards the shared weather
 * (second isRaining/isThundering read). For worlds with a "weather" rule that read returns the fixed value instead,
 * so rain fades in or out normally and the shared weather timers keep running untouched.
 */
@Mixin(ServerLevel.class)
abstract class ServerLevelMixin {

	@ModifyExpressionValue(method = "advanceWeatherCycle", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/saveddata/WeatherData;isRaining()Z", ordinal = 1))
	private boolean townhall$raining(boolean vanilla) {
		return DimensionSettings.raining(((ServerLevel) (Object) this).dimension(), vanilla);
	}

	@ModifyExpressionValue(method = "advanceWeatherCycle", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/saveddata/WeatherData;isThundering()Z", ordinal = 1))
	private boolean townhall$thundering(boolean vanilla) {
		return DimensionSettings.thundering(((ServerLevel) (Object) this).dimension(), vanilla);
	}

	/** World rule "mobs": false = no patrols, phantoms, wandering traders, cats or siege zombies. */
	@org.spongepowered.asm.mixin.injection.Inject(method = "tickCustomSpawners", at = @At("HEAD"), cancellable = true)
	private void townhall$noCustomSpawners(boolean spawnEnemies, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		if (!DimensionSettings.of(((ServerLevel) (Object) this).dimension()).mobs()) ci.cancel();
	}

	/**
	 * Start/stop-rain packets go to every player in vanilla; keep them away from worlds with their own weather.
	 * WrapOperation instead of @Redirect: multiworld mods (mc-worlds) redirect the same call, and two redirects crash.
	 * A world with fixed weather sends only to its own players. Otherwise the original (or the other mod's) broadcast
	 * runs, and players in fixed-weather worlds get their own world's weather re-sent right after it.
	 */
	@WrapOperation(method = "advanceWeatherCycle", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/PlayerList;broadcastAll(Lnet/minecraft/network/protocol/Packet;)V"))
	private void townhall$broadcastWeather(PlayerList players, Packet<?> packet, Operation<Void> original) {
		ServerLevel self = (ServerLevel) (Object) this;
		if (DimensionSettings.hasFixedWeather(self.dimension())) {
			DimensionSettings.sendToWorld(players, packet, self.dimension());
			return;
		}
		original.call(players, packet);
		DimensionSettings.resendFixedWeather(players, self.dimension());
	}
}
