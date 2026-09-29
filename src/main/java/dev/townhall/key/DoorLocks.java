package dev.townhall.key;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Which door is linked to which key. Doors have no block entity, so locks are stored per world position of the
 * lower door half, in data/townhall/door_locks.dat. Stale entries (door gone) are removed when they are looked up.
 */
public final class DoorLocks extends SavedData {

	/** keyId = id stored on the key item, owner = who linked the door. */
	public record Lock(String keyId, String keyName, String owner, String ownerName) {
		static final Codec<Lock> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.STRING.fieldOf("key").forGetter(Lock::keyId),
				Codec.STRING.fieldOf("keyName").forGetter(Lock::keyName),
				Codec.STRING.fieldOf("owner").forGetter(Lock::owner),
				Codec.STRING.fieldOf("ownerName").forGetter(Lock::ownerName)).apply(i, Lock::new));

		public UUID key() {
			return UUID.fromString(keyId);
		}
	}

	public static final Codec<DoorLocks> CODEC = Codec.unboundedMap(Codec.STRING, Lock.CODEC).xmap(DoorLocks::new, d -> d.locks);

	public static final SavedDataType<DoorLocks> TYPE = new SavedDataType<>(
			Identifier.fromNamespaceAndPath("townhall", "door_locks"), DoorLocks::new, CODEC, null);

	private final Map<String, Lock> locks;

	public DoorLocks() {
		this(Map.of());
	}

	private DoorLocks(Map<String, Lock> locks) {
		this.locks = new HashMap<>(locks);
	}

	public static DoorLocks get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	static String key(ResourceKey<Level> dimension, BlockPos lowerHalf) {
		return dimension.identifier() + "|" + lowerHalf.getX() + "|" + lowerHalf.getY() + "|" + lowerHalf.getZ();
	}

	public Optional<Lock> get(ResourceKey<Level> dimension, BlockPos lowerHalf) {
		return Optional.ofNullable(locks.get(key(dimension, lowerHalf)));
	}

	public void put(ResourceKey<Level> dimension, BlockPos lowerHalf, Lock lock) {
		locks.put(key(dimension, lowerHalf), lock);
		setDirty();
	}

	public boolean remove(ResourceKey<Level> dimension, BlockPos lowerHalf) {
		boolean removed = locks.remove(key(dimension, lowerHalf)) != null;
		if (removed) setDirty();
		return removed;
	}

	public int size() {
		return locks.size();
	}
}
