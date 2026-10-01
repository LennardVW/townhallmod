package dev.townhall.city;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;

public record RoleDefinition(String title, String prefix, List<String> permissions, int priority) {
	public static final Codec<RoleDefinition> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.STRING.fieldOf("title").forGetter(RoleDefinition::title),
			Codec.STRING.optionalFieldOf("prefix", "").forGetter(RoleDefinition::prefix),
			Codec.STRING.listOf().optionalFieldOf("permissions", List.of()).forGetter(RoleDefinition::permissions),
			Codec.INT.optionalFieldOf("priority", 0).forGetter(RoleDefinition::priority)
	).apply(i, RoleDefinition::new));
	public RoleDefinition { permissions = List.copyOf(permissions); }
}
