package dev.townhall.storage;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Homes per player (name → position), kept in the world's saved data. */
public final class HomeStorage extends SavedData {

	public record Home(ResourceKey<Level> dimension, double x, double y, double z, float yaw, float pitch) {
		public static final Codec<Home> CODEC = RecordCodecBuilder.create(i -> i.group(
				Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(Home::dimension),
				Codec.DOUBLE.fieldOf("x").forGetter(Home::x),
				Codec.DOUBLE.fieldOf("y").forGetter(Home::y),
				Codec.DOUBLE.fieldOf("z").forGetter(Home::z),
				Codec.FLOAT.fieldOf("yaw").forGetter(Home::yaw),
				Codec.FLOAT.fieldOf("pitch").forGetter(Home::pitch)
		).apply(i, Home::new));

		public static Home of(ServerPlayer p) {
			return new Home(p.level().dimension(), p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot());
		}
	}

	public static final Codec<HomeStorage> CODEC = Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.unboundedMap(Codec.STRING, Home.CODEC))
			.xmap(HomeStorage::new, s -> s.homes);

	public static final SavedDataType<HomeStorage> TYPE = new SavedDataType<>(
			Identifier.fromNamespaceAndPath("townhall", "homes"), HomeStorage::new, CODEC, null);

	private final Map<UUID, Map<String, Home>> homes = new HashMap<>();

	public HomeStorage() {}

	private HomeStorage(Map<UUID, Map<String, Home>> source) {
		source.forEach((id, m) -> homes.put(id, new LinkedHashMap<>(m)));
	}

	public static HomeStorage get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	/** A copy, in the order the homes were set. */
	public Map<String, Home> homes(UUID player) {
		return new LinkedHashMap<>(homes.getOrDefault(player, Map.of()));
	}

	public Optional<Home> get(UUID player, String name) {
		return Optional.ofNullable(homes.getOrDefault(player, Map.of()).get(name));
	}

	public void set(UUID player, String name, Home home) {
		homes.computeIfAbsent(player, k -> new LinkedHashMap<>()).put(name, home);
		setDirty();
	}

	public boolean remove(UUID player, String name) {
		Map<String, Home> m = homes.get(player);
		if (m == null || m.remove(name) == null) return false;
		if (m.isEmpty()) homes.remove(player);
		setDirty();
		return true;
	}
}
