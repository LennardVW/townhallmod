package dev.townhall.test;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Every GameTest run starts like the first one. The tests change the live config (every command saves it) and write
 * world data (play time, nicknames, door locks, player states), so both are removed before Minecraft starts:
 * the world of build/run/gameTest ("world", the level-name Loom writes) and config/townhall.json (recreated with defaults).
 * Only the test mod has this entrypoint, and it only acts on the GameTest server.
 */
public final class FreshTestRun implements PreLaunchEntrypoint {

	@Override
	public void onPreLaunch() {
		if (System.getProperty("fabric-api.gametest") == null) return; // a normal dev server (runServer) keeps its world
		try {
			deleteTree(FabricLoader.getInstance().getGameDir().resolve("world"));
			Files.deleteIfExists(FabricLoader.getInstance().getConfigDir().resolve("townhall.json"));
		} catch (IOException e) {
			throw new UncheckedIOException("Could not reset the GameTest run directory", e);
		}
	}

	private static void deleteTree(Path dir) throws IOException {
		if (!Files.exists(dir)) return;
		try (Stream<Path> paths = Files.walk(dir)) {
			for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
		}
	}
}
