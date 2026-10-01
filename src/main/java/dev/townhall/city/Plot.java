package dev.townhall.city;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** A whole-height rectangle; there are no hidden height gaps below cellars or above roofs. */
public record Plot(String dimension, int minX, int minZ, int maxX, int maxZ, Optional<String> owner,
		List<String> trusted, List<String> roles, boolean publicUse) {
	public static final Codec<Plot> CODEC = RecordCodecBuilder.<Plot>create(i -> i.group(
			Codec.STRING.fieldOf("dimension").forGetter(Plot::dimension),
			Codec.INT.fieldOf("minX").forGetter(Plot::minX), Codec.INT.fieldOf("minZ").forGetter(Plot::minZ),
			Codec.INT.fieldOf("maxX").forGetter(Plot::maxX), Codec.INT.fieldOf("maxZ").forGetter(Plot::maxZ),
			Codec.STRING.optionalFieldOf("owner").forGetter(Plot::owner),
			Codec.STRING.listOf().optionalFieldOf("trusted",List.of()).forGetter(Plot::trusted),
			Codec.STRING.listOf().optionalFieldOf("roles",List.of()).forGetter(Plot::roles),
			Codec.BOOL.optionalFieldOf("publicUse",true).forGetter(Plot::publicUse)
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
		} catch (IllegalArgumentException ex) {
			return com.mojang.serialization.DataResult.error(() -> "Invalid plot owner/trusted UUID");
		}
		return com.mojang.serialization.DataResult.success(p);
	}
	public Plot { trusted = List.copyOf(trusted); roles = List.copyOf(roles); }
	public boolean contains(BlockPos p) { return p.getX() >= minX && p.getX() <= maxX && p.getZ() >= minZ && p.getZ() <= maxZ; }
	public boolean overlaps(Plot p) { return dimension.equals(p.dimension) && minX <= p.maxX && maxX >= p.minX && minZ <= p.maxZ && maxZ >= p.minZ; }
	public boolean admits(UUID id, java.util.Set<String> memberRoles) {
		return owner.filter(id.toString()::equals).isPresent() || trusted.contains(id.toString()) || roles.stream().anyMatch(memberRoles::contains);
	}
}
