package dev.townhall.audit;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.townhall.TownhallMod;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/** Server thread only. O(1) append/eviction; Minecraft owns autosave/shutdown serialization. */
public final class AuditStorage extends SavedData {
	public static final int DEFAULT_LIMIT = 50_000;
	public static final int MIN_LIMIT = 100;
	public static final int MAX_LIMIT = 100_000;
	public static final int MAX_QUERY = 100;
	public static final int MAX_RADIUS = 64;
	public static final Codec<AuditStorage> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			AuditEntry.ID_CODEC.optionalFieldOf("next_id", 1L).forGetter(AuditStorage::nextId),
			AuditEntry.CODEC.listOf(0, MAX_LIMIT).optionalFieldOf("entries", List.of()).forGetter(s -> List.copyOf(s.entries))
	).apply(instance, AuditStorage::new));
	public static final SavedDataType<AuditStorage> TYPE = new SavedDataType<>(
			Identifier.fromNamespaceAndPath("townhall", "audit"), AuditStorage::new, CODEC, null);

	private final ArrayDeque<AuditEntry> entries = new ArrayDeque<>();
	private long nextId;

	public AuditStorage() { this(1, List.of()); }

	private AuditStorage(long nextId, List<AuditEntry> loaded) {
		this.nextId = nextId;
		long previous = 0;
		for (AuditEntry entry : loaded) {
			// Preserve insertion order and reject duplicate/backward ids in malformed saved data.
			if (entry.id() <= previous) continue;
			entries.addLast(entry);
			previous = entry.id();
			this.nextId = Math.max(this.nextId, entry.id() == Long.MAX_VALUE ? Long.MAX_VALUE : entry.id() + 1);
		}
	}

	public static AuditStorage get(MinecraftServer server) {
		AuditStorage storage = server.getDataStorage().computeIfAbsent(TYPE);
		storage.trim(configuredLimit());
		return storage;
	}

	public static int configuredLimit() {
		return Math.clamp(TownhallMod.CONFIG.get().city.auditMaxEntries, MIN_LIMIT, MAX_LIMIT);
	}

	public int size() { return entries.size(); }
	public long nextId() { return nextId; }

	/** Counter exhaustion stops recording instead of wrapping/reusing an id. */
	public Optional<AuditEntry> append(long timestamp, UUID actorId, String actorName, String action,
			Optional<Identifier> dimension, Optional<BlockPos> position, String before, String after, String detail, int limit) {
		if (nextId == Long.MAX_VALUE) return Optional.empty();
		AuditEntry entry = new AuditEntry(nextId++, timestamp, actorId, actorName, action, dimension, position, before, after, detail);
		entries.addLast(entry);
		trim(limit);
		setDirty();
		return Optional.of(entry);
	}

	public void trim(int limit) {
		int bounded = Math.clamp(limit, MIN_LIMIT, MAX_LIMIT);
		boolean changed = false;
		while (entries.size() > bounded) { entries.removeFirst(); changed = true; }
		if (changed) setDirty();
	}

	/** At most the capped ring is scanned; result allocation/output is capped independently. */
	public List<AuditEntry> newest(Predicate<AuditEntry> filter, int limit) {
		int bounded = Math.clamp(limit, 0, MAX_QUERY);
		if (bounded == 0) return List.of();
		List<AuditEntry> result = new ArrayList<>(bounded);
		var it = entries.descendingIterator();
		while (it.hasNext() && result.size() < bounded) {
			AuditEntry entry = it.next();
			if (filter.test(entry)) result.add(entry);
		}
		return List.copyOf(result);
	}

	public List<AuditEntry> at(Identifier dimension, BlockPos position, int limit) {
		return newest(e -> e.dimension().filter(dimension::equals).isPresent()
				&& e.position().filter(position::equals).isPresent(), limit);
	}

	public List<AuditEntry> near(Identifier dimension, BlockPos position, int radius, int limit) {
		int bounded = Math.clamp(radius, 0, MAX_RADIUS);
		double squared = (double) bounded * bounded;
		return newest(e -> e.dimension().filter(dimension::equals).isPresent()
				&& e.position().filter(p -> p.distSqr(position) <= squared).isPresent(), limit);
	}

	public List<AuditEntry> player(UUID actor, int limit) {
		return newest(e -> e.actorId().equals(actor), limit);
	}
}
