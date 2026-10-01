package dev.townhall.city;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import java.util.*;

/** Chunk-indexed SavedData: a block check only sees plots intersecting this chunk. */
public final class PlotStorage extends SavedData {
	public static final Codec<PlotStorage> CODEC = Codec.unboundedMap(Codec.STRING,Plot.CODEC).comapFlatMap(PlotStorage::decode,s -> s.plots);
	public static final SavedDataType<PlotStorage> TYPE = new SavedDataType<>(Identifier.fromNamespaceAndPath("townhall","plots"),PlotStorage::new,CODEC,null);
	private static com.mojang.serialization.DataResult<PlotStorage> decode(Map<String,Plot> plots) {
		if (plots.size() > 2000 || plots.values().stream().mapToLong(PlotStorage::chunkCount).sum() > 32_000)
			return com.mojang.serialization.DataResult.error(() -> "Plot storage capacity exceeded");
		PlotStorage result = new PlotStorage();
		for (var entry : plots.entrySet()) {
			var problem = result.createProblem(entry.getKey(), entry.getValue());
			if (problem.isPresent()) return com.mojang.serialization.DataResult.error(problem::get);
			result.plots.put(entry.getKey(), entry.getValue());
		}
		result.reindex();
		return com.mojang.serialization.DataResult.success(result);
	}
	private final Map<String,Plot> plots;
	private final Map<String,Map<Long,List<String>>> chunks = new HashMap<>();
	public PlotStorage() { this(Map.of()); }
	private PlotStorage(Map<String,Plot> plots) {
		this.plots = new LinkedHashMap<>(plots); reindex();
	}
	public static PlotStorage get(MinecraftServer server) { return server.getDataStorage().computeIfAbsent(TYPE); }
	public Map<String,Plot> plots() { return Collections.unmodifiableMap(plots); }
	private static long chunk(int x,int z) { return ((long)x << 32) ^ (z & 0xffffffffL); }
	public Optional<Map.Entry<String,Plot>> at(String dimension, BlockPos pos) {
		var map = chunks.get(dimension); if (map == null) return Optional.empty();
		for (String id : map.getOrDefault(chunk(pos.getX() >> 4,pos.getZ() >> 4),List.of())) {
			Plot p = plots.get(id); if (p.contains(pos)) return Optional.of(Map.entry(id,p));
		}
		return Optional.empty();
	}
	public Optional<String> createProblem(String id,Plot p) {
		if (!CityAccess.validId(id)) return Optional.of("Ungültige Grundstücks-ID.");
		if (plots.containsKey(id)) return Optional.of("Diese Grundstücks-ID existiert bereits.");
		if (plots.size() >= 2000) return Optional.of("Maximal 2000 Grundstücke.");
		long width = (long)p.maxX() - p.minX() + 1, depth = (long)p.maxZ() - p.minZ() + 1;
		if (width < 1 || depth < 1 || width > 256 || depth > 256) return Optional.of("Grundstücke sind höchstens 256 × 256 Blöcke groß.");
		if (Math.abs((long)p.minX()) > 30_000_000 || Math.abs((long)p.maxX()) > 30_000_000 || Math.abs((long)p.minZ()) > 30_000_000 || Math.abs((long)p.maxZ()) > 30_000_000) return Optional.of("Koordinaten außerhalb der Weltgrenze.");
		if (plots.values().stream().anyMatch(p::overlaps)) return Optional.of("Der Bereich überschneidet ein vorhandenes Grundstück.");
		long total = plots.values().stream().mapToLong(PlotStorage::chunkCount).sum() + chunkCount(p);
		return total > 32_000 ? Optional.of("Zu viele Grundstücks-Chunks (maximal 32000).") : Optional.empty();
	}
	private static long chunkCount(Plot p) { return ((long)(p.maxX() >> 4) - (p.minX() >> 4) + 1) * ((long)(p.maxZ() >> 4) - (p.minZ() >> 4) + 1); }
	public void put(String id,Plot p) { plots.put(id,p); reindex(); setDirty(); }
	public boolean remove(String id) { if (plots.remove(id) == null) return false; reindex(); setDirty(); return true; }
	private void reindex() {
		chunks.clear();
		plots.forEach((id,p) -> {
			// A malformed external file must not make a chunk-index loop unbounded.
			if ((long)p.maxX() - p.minX() > 255 || (long)p.maxZ() - p.minZ() > 255 || p.maxX() < p.minX() || p.maxZ() < p.minZ()) throw new IllegalArgumentException("Invalid plot bounds: " + id);
			var map = chunks.computeIfAbsent(p.dimension(), d -> new HashMap<>());
			for (int x = p.minX() >> 4; x <= p.maxX() >> 4; x++) for (int z = p.minZ() >> 4; z <= p.maxZ() >> 4; z++) map.computeIfAbsent(chunk(x,z),c -> new ArrayList<>()).add(id);
		});
	}
}
