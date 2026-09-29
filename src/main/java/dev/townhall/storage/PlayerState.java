package dev.townhall.storage;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Optional;

/**
 * What the mod remembers about one player.
 *
 * @param returnPosition  where they stood before entering the first location (kept while moving between locations)
 * @param location        id of the location they were last sent to
 * @param confined        may not leave on their own (non-escapable location); commands are limited and they are pulled back
 * @param remainingMillis time left before they are sent back automatically; only counts down while they are online
 */
public record PlayerState(Optional<ReturnLocation> returnPosition, Optional<String> location, boolean confined,
		Optional<Long> remainingMillis) {

	public static final PlayerState EMPTY = new PlayerState(Optional.empty(), Optional.empty(), false, Optional.empty());

	public static final Codec<PlayerState> CODEC = RecordCodecBuilder.create(i -> i.group(
			ReturnLocation.CODEC.optionalFieldOf("return_position").forGetter(PlayerState::returnPosition),
			Codec.STRING.optionalFieldOf("location").forGetter(PlayerState::location),
			Codec.BOOL.optionalFieldOf("confined", false).forGetter(PlayerState::confined),
			Codec.LONG.optionalFieldOf("remaining_ms").forGetter(PlayerState::remainingMillis)
	).apply(i, PlayerState::new));

	public boolean isEmpty() {
		return returnPosition.isEmpty() && location.isEmpty() && !confined && remainingMillis.isEmpty();
	}

	/** Needs the once-per-second check (pull back and/or count down). */
	public boolean isActive() {
		return confined || remainingMillis.isPresent();
	}

	public PlayerState withStay(String locationId, boolean confined, Optional<Long> remainingMillis) {
		return new PlayerState(returnPosition, Optional.of(locationId), confined, remainingMillis);
	}

	public PlayerState withReturnPosition(ReturnLocation position) {
		return new PlayerState(Optional.of(position), location, confined, remainingMillis);
	}

	public PlayerState withRemaining(long millis) {
		return new PlayerState(returnPosition, location, confined, Optional.of(millis));
	}

	/** Left the location: keep only the return position (used when it isn't cleared after returning). */
	public PlayerState left() {
		return new PlayerState(returnPosition, Optional.empty(), false, Optional.empty());
	}
}
