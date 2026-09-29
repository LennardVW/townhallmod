package dev.townhall.onboarding;

import com.mojang.serialization.Codec;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Which rules version each player accepted, keyed by UUID. Saved with the world in data/townhall/onboarding.dat. */
public final class OnboardingStorage extends SavedData {

	public static final Codec<OnboardingStorage> CODEC = Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.INT)
			.xmap(OnboardingStorage::new, s -> s.accepted);

	public static final SavedDataType<OnboardingStorage> TYPE = new SavedDataType<>(
			Identifier.fromNamespaceAndPath("townhall", "onboarding"), OnboardingStorage::new, CODEC, null);

	private final Map<UUID, Integer> accepted;

	public OnboardingStorage() {
		this(Map.of());
	}

	private OnboardingStorage(Map<UUID, Integer> accepted) {
		this.accepted = new HashMap<>(accepted);
	}

	public static OnboardingStorage get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	public int acceptedVersion(UUID player) {
		return accepted.getOrDefault(player, 0);
	}

	public void accept(UUID player, int version) {
		accepted.put(player, version);
		setDirty();
	}

	public void reset(UUID player) {
		if (accepted.remove(player) != null) setDirty();
	}
}
