package dev.townhall.city;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** A whole-height rectangle; there are no hidden height gaps below cellars or above roofs. */
public record Plot(String dimension, int minX, int minZ, int maxX, int maxZ, Optional<String> owner,
		List<String> trusted, List<String> roles, boolean publicUse, List<String> managers, List<String> coOwners) {
	public static final Codec<Plot> CODEC = RecordCodecBuilder.<Plot>create(i -> i.group(
			Codec.STRING.fieldOf("dimension").forGetter(Plot::dimension),
			Codec.INT.fieldOf("minX").forGetter(Plot::minX), Codec.INT.fieldOf("minZ").forGetter(Plot::minZ),
			Codec.INT.fieldOf("maxX").forGetter(Plot::maxX), Codec.INT.fieldOf("maxZ").forGetter(Plot::maxZ),
			Codec.STRING.optionalFieldOf("owner").forGetter(Plot::owner),
			Codec.STRING.listOf().optionalFieldOf("trusted",List.of()).forGetter(Plot::trusted),
			Codec.STRING.listOf().optionalFieldOf("roles",List.of()).forGetter(Plot::roles),
			Codec.BOOL.optionalFieldOf("publicUse",true).forGetter(Plot::publicUse),
			Codec.STRING.listOf().optionalFieldOf("managers",List.of()).forGetter(Plot::managers),
			Codec.STRING.listOf().optionalFieldOf("coOwners",List.of()).forGetter(Plot::coOwners)
	).apply(i,Plot::new)).validate(Plot::validate);
	private static com.mojang.serialization.DataResult<Plot> validate(Plot p) {
		if (net.minecraft.resources.Identifier.tryParse(p.dimension()) == null
				|| (long)p.maxX() - p.minX() < 0 || (long)p.maxX() - p.minX() > 255
				|| (long)p.maxZ() - p.minZ() < 0 || (long)p.maxZ() - p.minZ() > 255
				|| Math.abs((long)p.minX()) > 30_000_000 || Math.abs((long)p.maxX()) > 30_000_000
				|| Math.abs((long)p.minZ()) > 30_000_000 || Math.abs((long)p.maxZ()) > 30_000_000)
			return com.mojang.serialization.DataResult.error(() -> "Invalid plot dimension or bounds");
		try {
			if (p.owner().isPresent()) UUID.fromString(p.owner().get());
			for (String id : p.trusted()) UUID.fromString(id);
			for (String id : p.managers()) UUID.fromString(id);
			for (String id : p.coOwners()) UUID.fromString(id);
		} catch (IllegalArgumentException ex) {
			return com.mojang.serialization.DataResult.error(() -> "Invalid plot owner/trusted UUID");
		}
		return com.mojang.serialization.DataResult.success(p);
	}
	public Plot { trusted = List.copyOf(trusted); roles = List.copyOf(roles); managers = List.copyOf(managers); coOwners = List.copyOf(coOwners); }
	public Plot(String dimension, int minX, int minZ, int maxX, int maxZ, Optional<String> owner,
			List<String> trusted, List<String> roles, boolean publicUse) {
		this(dimension,minX,minZ,maxX,maxZ,owner,trusted,roles,publicUse,List.of(),List.of());
	}
	public Plot withOwner(Optional<String> v) { return new Plot(dimension,minX,minZ,maxX,maxZ,v,trusted,roles,publicUse,managers,coOwners); }
	public Plot withTrusted(List<String> v) { return new Plot(dimension,minX,minZ,maxX,maxZ,owner,v,roles,publicUse,managers,coOwners); }
	public Plot withRoles(List<String> v) { return new Plot(dimension,minX,minZ,maxX,maxZ,owner,trusted,v,publicUse,managers,coOwners); }
	public Plot withManagers(List<String> v) { return new Plot(dimension,minX,minZ,maxX,maxZ,owner,trusted,roles,publicUse,v,coOwners); }
	public Plot withCoOwners(List<String> v) { return new Plot(dimension,minX,minZ,maxX,maxZ,owner,trusted,roles,publicUse,managers,v); }
	public Plot withBounds(int x0,int z0,int x1,int z1) { return new Plot(dimension,x0,z0,x1,z1,owner,trusted,roles,publicUse,managers,coOwners); }
	public Plot withPublicUse(boolean v) { return new Plot(dimension,minX,minZ,maxX,maxZ,owner,trusted,roles,v,managers,coOwners); }
	public boolean isMain(UUID id) { return owner.filter(id.toString()::equals).isPresent(); }
	/** Main owner or co-owner: both hold the full owner rights. */
	public boolean isOwner(UUID id) { return isMain(id) || coOwners.contains(id.toString()); }
	public long chunkCount() { return ((long)(maxX >> 4) - (minX >> 4) + 1) * ((long)(maxZ >> 4) - (minZ >> 4) + 1); }
	public boolean isManager(UUID id) { return managers.contains(id.toString()); }
	public boolean contains(BlockPos p) { return p.getX() >= minX && p.getX() <= maxX && p.getZ() >= minZ && p.getZ() <= maxZ; }
	public boolean overlaps(Plot p) { return dimension.equals(p.dimension) && minX <= p.maxX && maxX >= p.minX && minZ <= p.maxZ && maxZ >= p.minZ; }
	public boolean admits(UUID id, java.util.Set<String> memberRoles) {
		return owner.filter(id.toString()::equals).isPresent() || coOwners.contains(id.toString()) || trusted.contains(id.toString()) || managers.contains(id.toString()) || roles.stream().anyMatch(memberRoles::contains);
	}
}
