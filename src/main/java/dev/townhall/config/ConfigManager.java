package dev.townhall.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.townhall.TownhallMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Loads config/townhall.json. A broken file never replaces a working config. */
public final class ConfigManager {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private final Path path;
	private volatile TownhallConfig current = new TownhallConfig();
	private boolean loadedFromFile;

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

	/** First load at startup: writes defaults if the file is missing. */
	public void loadOrCreate() {
		if (!Files.exists(path)) {
			current = new TownhallConfig();
			save();
			loadedFromFile = true;
			TownhallMod.LOGGER.info("Created default config at {}", path);
			return;
		}
		List<String> errors = reload();
		if (!errors.isEmpty()) TownhallMod.LOGGER.error("Using built-in defaults until config/townhall.json is fixed");
	}

	/** Reads and validates the file; on any problem the previous config stays active. Returns the problems. */
	public List<String> reload() {
		TownhallConfig parsed;
		try (Reader reader = Files.newBufferedReader(path)) {
			parsed = GSON.fromJson(reader, TownhallConfig.class);
		} catch (IOException | JsonParseException e) {
			String error = "Could not read " + path.getFileName() + ": " + e.getMessage();
			TownhallMod.LOGGER.error(error);
			return List.of(error);
		}
		if (parsed == null) {
			TownhallMod.LOGGER.error("{} is empty", path);
			return List.of(path.getFileName() + " is empty");
		}
		List<String> errors = parsed.validate();
		if (!errors.isEmpty()) {
			errors.forEach(e -> TownhallMod.LOGGER.error("Invalid config: {}", e));
			return errors;
		}
		current = parsed;
		loadedFromFile = true;
		TownhallMod.LOGGER.info("Loaded config from {}", path);
		return List.of();
	}

	/** Writes the active config (used by /townhall setspawn). Atomic replace so a crash can't leave half a file. */
	public void save() {
		try {
			Files.createDirectories(path.getParent());
			Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
			try (Writer writer = Files.newBufferedWriter(tmp)) {
				GSON.toJson(current, writer);
			}
			Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			TownhallMod.LOGGER.error("Could not write {}", path, e);
		}
	}
}
