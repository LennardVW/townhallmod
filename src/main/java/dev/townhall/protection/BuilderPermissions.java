package dev.townhall.protection;

import net.fabricmc.fabric.api.permission.v1.PermissionContext;
import net.fabricmc.fabric.api.permission.v1.PermissionEvents;
import net.fabricmc.fabric.api.permission.v1.PermissionNode;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * Gives builders WorldEdit permissions, but only while they stand in a world where they are builders.
 * WorldEdit asks through Fabric's permission API (fabric-permission-api-v1); the first handler that answers wins,
 * null means "no opinion" (WorldEdit then falls back to its normal operator check).
 * Admin-only WorldEdit features stay operator-only (see DENIED).
 */
public final class BuilderPermissions {

	/** Parts of WorldEdit a builder never gets: server-wide or dangerous. */
	static final List<String> DENIED = List.of("reload", "report", "debugpaste", "trace", "limit.unrestricted",
			"timeout.unrestricted", "snapshots", "world", "delchunks", "butcher", "remove", "fast", "perf", "globalmask",
			"scripting", "schematic.delete", "setnbt");

	private BuilderPermissions() {}

	public static void register() {
		PermissionEvents.ON_REQUEST.register(BuilderPermissions::handle);
	}

	private static <T> T handle(PermissionContext context, PermissionNode<T> node) {
		if (dev.townhall.TownhallMod.CONFIG.get().city.claimsEnabled
				&& context.get(PermissionContext.ENTITY) instanceof ServerPlayer protectedPlayer
				&& !dev.townhall.TownhallMod.isOperator(protectedPlayer.permissions()) && worldEditPath(node.key()) != null) {
			try { return node.cast(Boolean.FALSE); } catch (RuntimeException notBoolean) { return null; }
		}
		// Cheap builder check first: for everyone else (almost every request) no string work at all.
		if (!(context.get(PermissionContext.ENTITY) instanceof ServerPlayer player) || !Protection.isBuilderHere(player)) return null;
		String path = worldEditPath(node.key());
		if (path == null || isDenied(path)) return null;
		try {
			return node.cast(Boolean.TRUE);
		} catch (RuntimeException notABooleanNode) {
			return null;
		}
	}

	/** "region.set" for both worldedit:region.set and [ns:]worldedit.region.set; null if it isn't a WorldEdit node. */
	static String worldEditPath(Identifier key) {
		if (key.getNamespace().equals("worldedit")) return key.getPath().startsWith("worldedit.") ? key.getPath().substring(10) : key.getPath();
		if (key.getPath().startsWith("worldedit.")) return key.getPath().substring(10);
		return null;
	}

	static boolean isDenied(String path) {
		for (String d : DENIED) {
			// path is d itself or starts with "d." (checked without building "d." each time)
			if (path.startsWith(d) && (path.length() == d.length() || path.charAt(d.length()) == '.')) return true;
		}
		return false;
	}
}
