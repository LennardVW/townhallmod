package dev.townhall.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.townhall.dimension.DimensionSettings;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.clock.ClockTimeMarker;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.SkeletonHorse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


/**
 * Fixed weather per world. advanceWeatherCycle moves each world's rain/thunder level towards the shared weather
 * (second isRaining/isThundering read). For worlds with a "weather" rule that read returns the fixed value instead,
 * so rain fades in or out normally and the shared weather timers keep running untouched.
 * Also: the "mobs" rule for custom spawners and skeleton horse traps, and sleeping in worlds with fixed time/weather.
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
	@Inject(method = "tickCustomSpawners", at = @At("HEAD"), cancellable = true)
	private void townhall$noCustomSpawners(boolean spawnEnemies, CallbackInfo ci) {
		if (!DimensionSettings.of(((ServerLevel) (Object) this).dimension()).mobs()) ci.cancel();
	}

	/** World rule "mobs": false = lightning spawns no skeleton horse trap (the lightning itself stays). */
	@WrapOperation(method = "tickThunder", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
	private boolean townhall$noHorseTrap(ServerLevel level, Entity entity, Operation<Boolean> original) {
		if (entity instanceof SkeletonHorse && !DimensionSettings.of(level.dimension()).mobs()) return false;
		return original.call(level, entity);
	}

	/**
	 * Enough players slept: vanilla moves the shared clock to the morning. Not from a world with fixed time or fixed
	 * thunder (see DimensionSettings.maySleep); beds there refuse sleeping anyway, this also covers other mods' sleepers.
	 */
	@WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/clock/ServerClockManager;moveToTimeMarker(Lnet/minecraft/core/Holder;Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/world/clock/ServerClockManager$MoveResult;"))
	private ServerClockManager.MoveResult townhall$noSharedTimeSkip(ServerClockManager clocks, Holder<WorldClock> clock, ResourceKey<ClockTimeMarker> marker, Operation<ServerClockManager.MoveResult> original) {
		if (!DimensionSettings.maySleep(((ServerLevel) (Object) this).dimension())) return null; // result is discarded
		return original.call(clocks, clock, marker);
	}

	/** After sleeping vanilla ends the shared rain; a world with its own weather must not do that for everyone. */
	@WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;resetWeatherCycle()V"))
	private void townhall$noSharedWeatherReset(ServerLevel level, Operation<Void> original) {
		if (DimensionSettings.hasFixedWeather(level.dimension())) return;
		original.call(level);
	}

	/**
	 * Start/stop-rain packets go to every player in vanilla; keep them away from worlds with their own weather.
	 * WrapOperation instead of @Redirect: multiworld mods (mc-worlds) redirect the same call, and two redirects crash.
	 * A world with fixed weather sends only to its own players. Otherwise the original (or the other mod's) broadcast
	 * runs, and players in other worlds get their own world's value re-sent right after it where it differs.
	 */
	@WrapOperation(method = "advanceWeatherCycle", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/PlayerList;broadcastAll(Lnet/minecraft/network/protocol/Packet;)V"))
	private void townhall$broadcastWeather(PlayerList players, Packet<?> packet, Operation<Void> original) {
		ServerLevel self = (ServerLevel) (Object) this;
		if (DimensionSettings.hasFixedWeather(self.dimension())) {
			DimensionSettings.sendToWorld(players, packet, self.dimension());
			return;
		}
		original.call(players, packet);
		DimensionSettings.resendOwnWeather(players, packet, self.dimension());
	}
}
