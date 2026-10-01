package dev.townhall.audit;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;

import java.util.Optional;
import java.util.UUID;

/** Block states contain only the block id/properties, never block entity NBT, books or chat. */
public record AuditEntry(long id, long timestamp, UUID actorId, String actorName, String action,
		Optional<Identifier> dimension, Optional<BlockPos> position, String before, String after, String detail) {
	public static final int MAX_TEXT = 512;
	static final Codec<Long> ID_CODEC = Codec.LONG.validate(id -> id > 0 ? DataResult.success(id)
			: DataResult.error(() -> "Audit ids must be positive"));
	public static final Codec<AuditEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			ID_CODEC.fieldOf("id").forGetter(AuditEntry::id),
			Codec.LONG.fieldOf("timestamp").forGetter(AuditEntry::timestamp),
			UUIDUtil.STRING_CODEC.fieldOf("actor_id").forGetter(AuditEntry::actorId),
			Codec.string(0, 64).fieldOf("actor_name").forGetter(AuditEntry::actorName),
			Codec.string(0, 64).fieldOf("action").forGetter(AuditEntry::action),
			Identifier.CODEC.optionalFieldOf("dimension").forGetter(AuditEntry::dimension),
			BlockPos.CODEC.optionalFieldOf("position").forGetter(AuditEntry::position),
			Codec.string(0, MAX_TEXT).optionalFieldOf("before", "").forGetter(AuditEntry::before),
			Codec.string(0, MAX_TEXT).optionalFieldOf("after", "").forGetter(AuditEntry::after),
			Codec.string(0, MAX_TEXT).optionalFieldOf("detail", "").forGetter(AuditEntry::detail)
	).apply(instance, AuditEntry::new));

	public AuditEntry {
		actorName = shortText(actorName, 64);
		action = shortText(action, 64);
		position = position.map(BlockPos::immutable);
		before = shortText(before, MAX_TEXT);
		after = shortText(after, MAX_TEXT);
		detail = shortText(detail, MAX_TEXT);
	}

	/** Single line, no formatting/control characters; bound work even on a very large caller string. */
	public static String shortText(String text, int limit) {
		if (text == null) return "";
		StringBuilder out = new StringBuilder(Math.min(text.length(), limit));
		for (int i = 0; i < Math.min(text.length(), limit); i++) {
			char c = text.charAt(i);
			out.append(Character.isISOControl(c) || c == '\u00a7' ? ' ' : c);
		}
		if (!out.isEmpty() && Character.isHighSurrogate(out.charAt(out.length() - 1))) out.setLength(out.length() - 1);
		return out.toString();
	}
}
