package dev.townhall.activity;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Active play time per player (AFK time doesn't count), in data/townhall/playtime.dat.
 * The first time a player is seen, their vanilla play time statistic is taken over, so older time counts too.
 */
public final class Playtime extends SavedData {

	public record Entry(long millis, String name) {
		static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.LONG.fieldOf("millis").forGetter(Entry::millis),
				Codec.STRING.fieldOf("name").forGetter(Entry::name)).apply(i, Entry::new));
	}

	public static final Codec<Playtime> CODEC = Codec.unboundedMap(UUIDUtil.STRING_CODEC, Entry.CODEC).xmap(Playtime::new, p -> p.entries);

	public static final SavedDataType<Playtime> TYPE = new SavedDataType<>(
			Identifier.fromNamespaceAndPath("townhall", "playtime"), Playtime::new, CODEC, null);

	private final Map<UUID, Entry> entries;

	public Playtime() {
		this(Map.of());
	}

	private Playtime(Map<UUID, Entry> entries) {
		this.entries = new HashMap<>(entries);
	}

	public static Playtime get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	public Optional<Entry> of(UUID player) {
		return Optional.ofNullable(entries.get(player));
	}

	/** Adds time for every online player who isn't AFK. Called once per second. */
	public void tick(MinecraftServer server, long elapsedMillis) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Entry e = entries.get(player.getUUID());
			if (e == null) {
				// ticks → ms: 1 tick = 50 ms
				e = new Entry(player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)) * 50L, player.getGameProfile().name());
			}
			long add = Afk.isAfk(player.getUUID()) ? 0 : elapsedMillis;
			entries.put(player.getUUID(), new Entry(e.millis() + add, player.getGameProfile().name()));
		}
		if (elapsedMillis > 0 && !server.getPlayerList().getPlayers().isEmpty()) setDirty();
	}

	public List<Map.Entry<UUID, Entry>> top(int count) {
		return entries.entrySet().stream()
				.sorted(Comparator.comparingLong((Map.Entry<UUID, Entry> e) -> e.getValue().millis()).reversed())
				.limit(count).toList();
	}

	/** Test hook. */
	public void set(UUID player, String name, long millis) {
		entries.put(player, new Entry(millis, name));
		setDirty();
	}

	public static String format(long millis) {
		long minutes = millis / 60_000;
		long hours = minutes / 60;
		long days = hours / 24;
		if (days > 0) return days + "d " + (hours % 24) + "h " + (minutes % 60) + "m";
		if (hours > 0) return hours + "h " + (minutes % 60) + "m";
		return minutes + "m";
	}
}
