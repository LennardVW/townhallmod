package dev.townhall.storage;

import com.mojang.serialization.Codec;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Player states keyed by UUID, stored in the server's saved-data folder.
 * Minecraft writes it with the world (autosave and shutdown), so there is no file I/O on the tick path.
 */
public final class ReturnPositionStorage extends SavedData {

	public static final Codec<ReturnPositionStorage> CODEC = Codec.unboundedMap(UUIDUtil.STRING_CODEC, PlayerState.CODEC)
			.xmap(ReturnPositionStorage::new, s -> s.states);

	public static final SavedDataType<ReturnPositionStorage> TYPE = new SavedDataType<>(
			Identifier.fromNamespaceAndPath("townhall", "players"), ReturnPositionStorage::new, CODEC, null);

	private final Map<UUID, PlayerState> states;
	/** Index of the players whose state is active (confined or timed), kept in step with {@link #states}. */
	private final Set<UUID> active = new HashSet<>();

	public ReturnPositionStorage() {
		this(Map.of());
	}

	private ReturnPositionStorage(Map<UUID, PlayerState> states) {
		this.states = new HashMap<>(states);
		states.forEach((id, state) -> {
			if (state.isActive()) active.add(id);
		});
	}

	public static ReturnPositionStorage get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	public PlayerState state(UUID player) {
		return states.getOrDefault(player, PlayerState.EMPTY);
	}

	public Optional<ReturnLocation> get(UUID player) {
		return state(player).returnPosition();
	}

	public void set(UUID player, PlayerState state) {
		if (state.isEmpty()) states.remove(player);
		else states.put(player, state);
		if (state.isActive()) active.add(player);
		else active.remove(player);
		setDirty();
	}

	/** Forgets everything about the player (return position, location, confinement). */
	public boolean remove(UUID player) {
		boolean removed = states.remove(player) != null;
		active.remove(player);
		if (removed) setDirty();
		return removed;
	}

	public long returnPositionCount() {
		return states.values().stream().filter(s -> s.returnPosition().isPresent()).count();
	}

	public long confinedCount() {
		return states.values().stream().filter(PlayerState::confined).count();
	}

	/**
	 * The location became escapable: everyone confined there is free to leave. Return position, location and a running
	 * timer stay, so return works and a timed stay still ends on its own. Returns how many were freed.
	 */
	public int releaseConfinedAt(String locationId) {
		int released = 0;
		for (Map.Entry<UUID, PlayerState> e : states.entrySet()) {
			PlayerState s = e.getValue();
			if (s.confined() && s.location().map(locationId::equals).orElse(false)) {
				PlayerState freed = new PlayerState(s.returnPosition(), s.location(), false, s.remainingMillis());
				e.setValue(freed);
				if (!freed.isActive()) active.remove(e.getKey());
				released++;
			}
		}
		if (released > 0) setDirty();
		return released;
	}

	/**
	 * Players that are confined or have a timer. A copy, so callers may change states while iterating. Read from an
	 * index, so the once-per-second check costs nothing for the (many) players who only have a return position.
	 */
	public List<UUID> activePlayers() {
		return active.isEmpty() ? List.of() : List.copyOf(active);
	}
}
