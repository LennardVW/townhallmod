package dev.townhall.storage;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.Optional;

/**
 * Where a player stood before entering the Townhall.
 *
 * @param playerName last known name, for /townhall debug only; lookups always use the UUID key
 */
public record ReturnLocation(ResourceKey<Level> dimension, double x, double y, double z, float yaw, float pitch,
		long timestamp, Optional<String> playerName) {

	public static final Codec<ReturnLocation> CODEC = RecordCodecBuilder.create(i -> i.group(
			Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(ReturnLocation::dimension),
			Codec.DOUBLE.fieldOf("x").forGetter(ReturnLocation::x),
			Codec.DOUBLE.fieldOf("y").forGetter(ReturnLocation::y),
			Codec.DOUBLE.fieldOf("z").forGetter(ReturnLocation::z),
			Codec.FLOAT.fieldOf("yaw").forGetter(ReturnLocation::yaw),
			Codec.FLOAT.fieldOf("pitch").forGetter(ReturnLocation::pitch),
			Codec.LONG.fieldOf("timestamp").forGetter(ReturnLocation::timestamp),
			Codec.STRING.optionalFieldOf("player_name").forGetter(ReturnLocation::playerName)
	).apply(i, ReturnLocation::new));

	public static ReturnLocation of(ServerPlayer player) {
		return new ReturnLocation(player.level().dimension(), player.getX(), player.getY(), player.getZ(),
				player.getYRot(), player.getXRot(), System.currentTimeMillis(), Optional.of(player.getPlainTextName()));
	}
}
