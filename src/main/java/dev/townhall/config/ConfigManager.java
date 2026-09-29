package dev.townhall.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.townhall.TownhallMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.function.Function;

/**
 * Loads config/townhall.json. A broken file never replaces a working config.
 * While the file on disk is not the active config (last load failed), nothing is saved, so the admin's file is never overwritten.
 */
public final class ConfigManager {

	/** Told to the admin by every command whose change could not be written. */
	public static final String NOT_SAVED = "Config not saved: fix config/townhall.json and run /townhall reload (see the server log)."
			+ " The change is active until then, but a reload or restart undoes it.";

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private final Path path;
	private volatile TownhallConfig current = new TownhallConfig();
	private boolean loadedFromFile;
	/** The file on disk is the active config. False after a failed load, so save() can't overwrite the admin's edits. */
	private boolean fileIsActive;

	public ConfigManager() {
		this(FabricLoader.getInstance().getConfigDir().resolve("townhall.json"));
	}

	public ConfigManager(Path path) {
		this.path = path;
	}

	public TownhallConfig get() {
		return current;
	}

	public Path path() {
		return path;
	}

	public boolean loadedFromFile() {
		return loadedFromFile;
	}

	/** False while the last load or reload failed: saving is paused until a reload succeeds. */
	public boolean canSave() {
		return fileIsActive;
	}

	/** First load at startup: writes defaults if the file is missing. */
	public void loadOrCreate() {
		if (!Files.exists(path)) {
			current = new TownhallConfig();
			fileIsActive = true;
			save();
			loadedFromFile = true;
			TownhallMod.LOGGER.info("Created default config at {}", path);
			return;
		}
		List<String> errors = reload();
		if (!errors.isEmpty()) TownhallMod.LOGGER.error("Using built-in defaults until config/townhall.json is fixed; commands won't save config changes until /townhall reload succeeds");
	}

	/** Reads and validates the file; on any problem the previous config stays active. Returns the problems. */
	public List<String> reload() {
		return reload(config -> List.of());
	}

	/**
	 * Like {@link #reload()}, with extra checks that need the running server (e.g. players still held at a removed location).
	 * {@code check} only runs on a config that passed validate().
	 */
	public List<String> reload(Function<TownhallConfig, List<String>> check) {
		TownhallConfig parsed;
		try (Reader reader = Files.newBufferedReader(path)) {
			parsed = GSON.fromJson(reader, TownhallConfig.class);
		} catch (IOException | JsonParseException e) {
			String error = "Could not read " + path.getFileName() + ": " + e.getMessage();
			TownhallMod.LOGGER.error(error);
			fileIsActive = false;
			return List.of(error);
		}
		if (parsed == null) {
			TownhallMod.LOGGER.error("{} is empty", path);
			fileIsActive = false;
			return List.of(path.getFileName() + " is empty");
		}
		List<String> errors = parsed.validate();
		if (errors.isEmpty()) errors = check.apply(parsed);
		if (!errors.isEmpty()) {
			errors.forEach(e -> TownhallMod.LOGGER.error("Invalid config: {}", e));
			fileIsActive = false;
			return errors;
		}
		current = parsed;
		loadedFromFile = true;
		fileIsActive = true;
		TownhallMod.LOGGER.info("Loaded config from {}", path);
		return List.of();
	}

	/**
	 * Writes the active config (used by every config-changing command). Atomic replace so a crash can't leave half a file.
	 * Returns false (and writes nothing) while the last load failed, or when writing failed.
	 */
	public boolean save() {
		if (!fileIsActive) {
			TownhallMod.LOGGER.warn("Not saving {}: the file has errors and is not the active config. Fix it and run /townhall reload.", path);
			return false;
		}
		try {
			Files.createDirectories(path.getParent());
			Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
			try (Writer writer = Files.newBufferedWriter(tmp)) {
				GSON.toJson(current, writer);
			}
			try {
				Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
			}
			return true;
		} catch (IOException e) {
			TownhallMod.LOGGER.error("Could not write {}", path, e);
			return false;
		}
	}
}
