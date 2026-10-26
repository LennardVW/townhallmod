package dev.townhall.display;

import dev.townhall.TownhallMod;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.List;
import java.util.Locale;

/**
 * A name tag (or command) with a configured name such as "halts maul" makes a creature silent; any other name or no
 * name makes it loud again, but only if this mod silenced it (marker tag), so silence set by commands, other mods or
 * saved in the world is never touched.
 */
public final class SilentNames {

	/** Entity tag that marks "silenced by this mod". */
	public static final String TAG = "townhall_silenced";

	private SilentNames() {}

	/** Lower case, trimmed, runs of whitespace collapsed to one space; null becomes "". */
	public static String normalize(String name) {
		if (name == null) return "";
		return name.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
	}

	/** True if the normalized name equals a normalized entry of the list. Blank names never match. */
	public static boolean matches(String name, List<String> silentNames) {
		String wanted = normalize(name);
		if (wanted.isEmpty() || silentNames == null) return false;
		for (String entry : silentNames) {
			if (entry != null && wanted.equals(normalize(entry))) return true;
		}
		return false;
	}

	/** Called after setCustomName(name) on the server: silence or un-silence the entity. */
	public static void onNameChanged(Entity entity, Component name) {
		if (!(entity instanceof LivingEntity) || entity instanceof Player) return;
		if (entity.level().isClientSide()) return;
		boolean silentName = name != null && matches(name.getString(), TownhallMod.CONFIG.get().mobs.silentNames);
		if (silentName) {
			if (!entity.isSilent()) {
				entity.setSilent(true);
				entity.addTag(TAG);
			}
		} else if (entity.entityTags().contains(TAG)) {
			entity.removeTag(TAG);
			entity.setSilent(false);
		}
	}
}
