package dev.townhall.key;

import com.mojang.serialization.Codec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Key ids made invalid with /key delete all. Copies of such a key stay in inventories but open and lock nothing;
 * a lock with a revoked id counts as not locked. Stored in data/townhall/revoked_keys.dat.
 */
public final class RevokedKeys extends SavedData {

	public static final Codec<RevokedKeys> CODEC = Codec.STRING.listOf().xmap(RevokedKeys::new, r -> List.copyOf(r.ids));

	public static final SavedDataType<RevokedKeys> TYPE = new SavedDataType<>(
			Identifier.fromNamespaceAndPath("townhall", "revoked_keys"), RevokedKeys::new, CODEC, null);

	private final Set<String> ids;

	public RevokedKeys() {
		this(List.of());
	}

	private RevokedKeys(List<String> ids) {
		this.ids = new HashSet<>(ids);
	}

	public static RevokedKeys get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	public boolean isRevoked(UUID id) {
		return !ids.isEmpty() && ids.contains(id.toString());
	}

	public boolean isRevoked(String id) {
		return !ids.isEmpty() && ids.contains(id);
	}

	public void revoke(UUID id) {
		if (ids.add(id.toString())) setDirty();
	}

	public int size() {
		return ids.size();
	}
}
