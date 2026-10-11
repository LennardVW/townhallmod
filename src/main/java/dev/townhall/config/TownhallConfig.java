package dev.townhall.config;

import com.google.gson.annotations.SerializedName;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IllegalFormatException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Mirrors config/townhall.json. Field names are the JSON keys. */
public final class TownhallConfig {

	private static final Pattern COMMAND_NAME = Pattern.compile("[\\p{L}\\p{N}_.:\\-]+");

	/** Every place players can be sent to. The key is the location id; each gets its own command. */
	public Map<String, Location> locations = defaultLocations();
	@SerializedName("return")
	public Return returnSettings = new Return();
	public SafeTeleport safeTeleport = new SafeTeleport();
	public Fallback fallback = new Fallback();
	public Commands commands = new Commands();
	public Confinement confinement = new Confinement();
	public Onboarding onboarding = new Onboarding();
	/** Rules per dimension id (any world, also ones added by other mods), e.g. "minecraft:flatworld": {"difficulty": "peaceful"}. */
	public Map<String, DimensionRules> dimensions = new LinkedHashMap<>();
	public Messages messages = new Messages();
	public boolean debugLogging = false;
	/** Red number of deaths next to every name in the tab list. */
	public boolean deathsInTab = true;
	/** False = end portals don't lead to the End (ops can still /tp there). */
	public boolean endEnabled = false;
	/** Minutes without activity until a player is shown as AFK (0 = only with /afk). */
	public int afkMinutes = 5;
	public JoinMessages joinMessages = new JoinMessages();
	public TabList tabList = new TabList();
	public dev.townhall.city.CitySettings city = new dev.townhall.city.CitySettings();

	/** Own join/leave messages; {player} = name (nickname if set), &-colors. Empty text = no message. */
	public static final class JoinMessages {
		public boolean enabled = true;
		public String join = "&a+ &f{player} &7ist AzubiCraft beigetreten.";
		public String leave = "&c- &f{player} &7hat AzubiCraft verlassen.";
		public String firstJoin = "&6&l★ &e{player} &6ist zum ersten Mal auf AzubiCraft! Herzlich willkommen!";
		/** Personal join messages: player UUID → text. */
		public Map<String, String> players = new LinkedHashMap<>();
	}

	/** Tab list header/footer lines. Placeholders: {player} {online} {max} {ping} {playtime} {world}. */
	public static final class TabList {
		public boolean enabled = true;
		public List<String> header = List.of("", "&6&lAzubiCraft", "&7Willkommen, &f{player}&7!", "");
		public List<String> footer = List.of("", "&7Online: &a{online}&7/&a{max}   &7Ping: &a{ping} ms", "&7Deine Spielzeit: &f{playtime}", "");
	}

	public static final class Location {
		/** Command name without slash, e.g. "townhall" gives /townhall. */
		public String command;
		public String displayName;
		/** Dimension id. Multiworld mods often use their own namespace; /townhall status lists loaded dimensions. */
		public String dimension = "minecraft:flatworld";
		public Spot spawn = new Spot(0.5, 4.0, 0.5, 0f, 0f);
		/** Only operators can use the command (to go there themselves or to send players). */
		public boolean adminOnly = false;
		/**
		 * false = players here can't leave on their own: only allowed commands work, they are pulled back if they get out,
		 * and they respawn here. Only an operator (or the end of a timed stay) lets them out. Operators going there themselves are never confined.
		 */
		public Boolean escapable;
		/** For non-escapable locations: max horizontal distance from the spawn before a player is pulled back (0 = only the world is checked). */
		public int confineRadius = 32;
		/** Old name of !escapable, still read so older config files keep working. */
		public Boolean confineSentPlayers;
		public String arrivedMessage = "Willkommen!";
		/** Big title on arrival (empty = none). &-color codes work, e.g. "&6Townhall". */
		public String title = "";
		public String subtitle = "";
		public String alreadyHereMessage = "Du bist schon hier. Mit /%s return kommst du zurück.";

		public Location() {}

		Location(String command, String displayName, Spot spawn, boolean adminOnly, boolean escapable, String arrived, String alreadyHere) {
			this.command = command;
			this.displayName = displayName;
			this.spawn = spawn;
			this.adminOnly = adminOnly;
			this.escapable = escapable;
			this.arrivedMessage = arrived;
			this.alreadyHereMessage = alreadyHere;
		}

		public boolean isEscapable() {
			if (escapable != null) return escapable;
			return confineSentPlayers == null || !confineSentPlayers;
		}

		public ResourceKey<Level> dimensionKey() {
			return ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension));
		}
	}

	public static final class Return {
		public boolean clearAfterSuccessfulReturn = true;
	}

	public static final class SafeTeleport {
		public boolean enabled = true;
		public int horizontalRadius = 5;
		public int verticalRadius = 5;
	}

	public static final class Fallback {
		public String dimension = "minecraft:overworld";
		public boolean useWorldSpawn = true;
		public Spot position = new Spot(0.5, 70.0, 0.5, 0f, 0f);
	}

	public static final class Commands {
		public int cooldownSeconds = 3;
		public boolean operatorsBypassCooldown = true;
		/** 1 moderator, 2 gamemaster (vanilla /tp level), 3 admin, 4 owner. */
		public int operatorPermissionLevel = 2;
	}

	/** Rules for one world. Every field is optional: missing = normal Minecraft behavior. */
	public static final class DimensionRules {
		/** peaceful, easy, normal or hard. */
		public String difficulty;
		/** false = players can't hurt each other. */
		public Boolean pvp;
		/** false = only operators can place/break blocks or change the world (doors, buttons and chests still work). */
		public Boolean build;
		/** false = no hunger; food is filled up when entering the world. */
		public Boolean hunger;
		/** false = no fall damage. */
		public Boolean fallDamage;
		/** Fixed time of day: day, noon, night, midnight or ticks 0-23999 (e.g. 6000). Missing = time runs normally. */
		public String time;
		/** Fixed weather: clear, rain or thunder. Missing = normal weather. */
		public String weather;
		/** false = no natural mob spawning, no spawners, no patrols/phantoms/wandering traders (spawn eggs and commands still work). */
		public Boolean mobs;
		/** false = fire goes out right away and never spreads or burns blocks. */
		public Boolean fire;
		/** false = explosions don't break blocks (they still hurt). */
		public Boolean explosions;
		/** false = leaves never decay. */
		public Boolean leafDecay;
		/** Players who may build here even when build is false: UUID → last known name (the name is only for display). */
		public Map<String, String> builders;
	}

	public static final List<String> WORLD_RULES = List.of("pvp", "build", "hunger", "fallDamage", "mobs", "fire", "explosions", "leafDecay");

	public static final Set<String> DIFFICULTIES = Set.of("peaceful", "easy", "normal", "hard");

	/** Named times for dimensions.&lt;id&gt;.time, in ticks of the day. */
	public static final Map<String, Long> TIMES = Map.of("day", 1000L, "noon", 6000L, "night", 13000L, "midnight", 18000L);

	public static final Set<String> WEATHERS = Set.of("clear", "rain", "thunder");

	/** Ticks of the day for a time value (name or number), or null if it isn't valid. */
	public static Long parseTime(String value) {
		if (value == null) return null;
		Long named = TIMES.get(value);
		if (named != null) return named;
		try {
			long ticks = Long.parseLong(value);
			return ticks >= 0 && ticks < 24000 ? ticks : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** First join: welcome text, tutorial and rules that must be accepted before playing. &-color codes work. */
	public static final class Onboarding {
		public boolean enabled = true;
		/** Raise this after changing the rules: everyone has to accept them again. */
		public int rulesVersion = 1;
		/** Until accepted: no moving away, chat, commands, building or damage (operators are only shown the texts). */
		public boolean restrictUntilAccepted = true;
		public int reminderSeconds = 30;
		public List<String> welcome = List.of(
				"&6&lWillkommen auf dem Server, %player%!",
				"&7Schön, dass du da bist. Lies dir kurz durch, wie alles funktioniert.");
		public List<String> tutorial = List.of(
				"&e&lSo funktioniert's:",
				"&f• &a/townhall &7– ins Rathaus, &a/townhall return &7– zurück",
				"&f• &7Kisten abschließen: Schild an die Kiste, &a[Private] &7in Zeile 1",
				"&f• &7Fragen? Schreib einem Admin mit &a/msg");
		public List<String> rules = List.of(
				"&c&lRegeln:",
				"&f1. &7Sei freundlich zu allen.",
				"&f2. &7Kein Griefing, kein Stehlen.",
				"&f3. &7Keine Hacks oder Cheats.");
		public String acceptButton = "&a&l[Regeln akzeptieren]";
		public String acceptHint = "&7Klick auf den Knopf oder tippe &a/rules accept";
		public String accepted = "&aDanke! Viel Spaß auf dem Server.";
		public String acceptedTitle = "&6Willkommen!";
		public String reminder = "&eBitte lies die Regeln und akzeptiere sie zuerst.";
	}

	public static final class Confinement {
		/** The only commands confined players may use (first word, without slash). Everything else is blocked. */
		public List<String> allowedCommands = List.of("msg", "tell", "w", "r", "me", "teammsg", "tm", "list", "help", "trigger");
	}

	public static final class Messages {
		public String returned = "Du bist wieder an deinem vorherigen Ort.";
		public String returnedNearby = "Dein alter Platz war blockiert, daher wurdest du in die Nähe gesetzt.";
		public String returnedFallback = "Dein alter Platz war nicht sicher, daher wurdest du zum Spawn geschickt.";
		public String noReturnPosition = "Es ist keine Rückkehrposition gespeichert.";
		public String locationUnavailable = "Nicht verfügbar: %s.";
		public String confined = "Du kannst %s nicht von allein verlassen. Ein Admin muss dich freilassen.";
		public String confinedTimed = "Du kannst %s noch %s lang nicht verlassen.";
		public String commandBlocked = "Du kannst /%s hier nicht benutzen.";
		public String pulledBack = "Du kannst %s nicht verlassen.";
		public String timeLeft = "%s: noch %s";
		public String released = "Deine Zeit ist um. Du bist wieder frei.";
		public String cooldown = "Bitte warte %d Sekunden, bevor du den Befehl erneut benutzt.";
	}

	public static final class Spot {
		public double x;
		public double y;
		public double z;
		public float yaw;
		public float pitch;

		public Spot() {}

		public Spot(double x, double y, double z, float yaw, float pitch) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.yaw = yaw;
			this.pitch = pitch;
		}

		/** Where the player stands and looks. */
		public static Spot of(ServerPlayer player) {
			return new Spot(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
		}
	}

	private static Map<String, Location> defaultLocations() {
		Map<String, Location> map = new LinkedHashMap<>();
		Location townhall = new Location("townhall", "das Rathaus", new Spot(0.5, 4.0, 0.5, 0f, 0f), false, true,
				"Willkommen im Rathaus.", "Du bist schon im Rathaus. Mit /townhall return kommst du zurück.");
		townhall.title = "&6Rathaus";
		townhall.subtitle = "&7Willkommen!";
		map.put("townhall", townhall);
		Location prison = new Location("gefaengnis", "das Gefängnis", new Spot(100.5, 4.0, 100.5, 0f, 0f), true, false,
				"Du wurdest ins Gefängnis geschickt.", "Du bist schon im Gefängnis.");
		prison.title = "&cGefängnis";
		prison.subtitle = "&7Du wurdest eingesperrt";
		map.put("gefaengnis", prison);
		return map;
	}

	public static boolean isValidCommandName(String name) {
		return name != null && COMMAND_NAME.matcher(name).matches();
	}

	/** True if another location than {@code exceptId} already uses this command name (case-insensitive). */
	public boolean commandTaken(String name, String exceptId) {
		return locations.entrySet().stream().anyMatch(e -> !e.getKey().equals(exceptId) && e.getValue().command.equalsIgnoreCase(name));
	}

	public Optional<Location> location(String id) {
		return Optional.ofNullable(locations.get(id));
	}

	public String displayName(String id) {
		Location loc = locations.get(id);
		return loc == null || loc.displayName == null ? id : loc.displayName;
	}

	/** True if the player is standing in the world of any configured location. */
	public boolean isLocationDimension(ResourceKey<Level> dimension) {
		return locations.values().stream().anyMatch(l -> l.dimensionKey().equals(dimension));
	}

	public ResourceKey<Level> fallbackDimension() {
		return ResourceKey.create(Registries.DIMENSION, Identifier.parse(fallback.dimension));
	}

	/**
	 * Texts that still equal an old English default are replaced by the German default, so servers with an old config.json
	 * become German too. Texts that were changed by hand are kept. Returns true if anything changed.
	 */
	public boolean migrateEnglishDefaults() {
		boolean changed = false;
		if (messages != null) {
			Messages m = messages;
			String[] before = {m.returned, m.returnedNearby, m.returnedFallback, m.noReturnPosition, m.locationUnavailable, m.confined,
					m.confinedTimed, m.commandBlocked, m.pulledBack, m.timeLeft, m.released, m.cooldown};
			m.returned = legacy(m.returned, "Returned to your previous location.", "Du bist wieder an deinem vorherigen Ort.");
			m.returnedNearby = legacy(m.returnedNearby, "Your old spot was blocked, so you were placed nearby.", "Dein alter Platz war blockiert, daher wurdest du in die Nähe gesetzt.");
			m.returnedFallback = legacy(m.returnedFallback, "Your old spot wasn't safe, so you were sent to spawn.", "Dein alter Platz war nicht sicher, daher wurdest du zum Spawn geschickt.");
			m.noReturnPosition = legacy(m.noReturnPosition, "No return position is stored.", "Es ist keine Rückkehrposition gespeichert.");
			m.locationUnavailable = legacy(m.locationUnavailable, "%s is currently unavailable.", "Nicht verfügbar: %s.");
			m.confined = legacy(m.confined, "You can't leave %s on your own. An admin has to release you.", "Du kannst %s nicht von allein verlassen. Ein Admin muss dich freilassen.");
			m.confinedTimed = legacy(m.confinedTimed, "You can't leave %s for another %s.", "Du kannst %s noch %s lang nicht verlassen.");
			m.commandBlocked = legacy(m.commandBlocked, "You can't use /%s here.", "Du kannst /%s hier nicht benutzen.");
			m.pulledBack = legacy(m.pulledBack, "You can't leave %s.", "Du kannst %s nicht verlassen.");
			m.timeLeft = legacy(m.timeLeft, "%s: %s left", "%s: noch %s");
			m.released = legacy(m.released, "Your time is up. You are free again.", "Deine Zeit ist um. Du bist wieder frei.");
			m.cooldown = legacy(m.cooldown, "Please wait %d seconds before using this command again.", "Bitte warte %d Sekunden, bevor du den Befehl erneut benutzt.");
			String[] after = {m.returned, m.returnedNearby, m.returnedFallback, m.noReturnPosition, m.locationUnavailable, m.confined,
					m.confinedTimed, m.commandBlocked, m.pulledBack, m.timeLeft, m.released, m.cooldown};
			changed |= !java.util.Arrays.equals(before, after);
		}
		if (locations != null) {
			for (Map.Entry<String, Location> e : locations.entrySet()) {
				Location l = e.getValue();
				if (l == null) continue;
				String id = e.getKey();
				String[] before = {l.arrivedMessage, l.alreadyHereMessage, l.displayName, l.title};
				l.alreadyHereMessage = legacy(l.alreadyHereMessage, "You are already here. Use /%s return to go back.", "Du bist schon hier. Mit /%s return kommst du zurück.");
				l.alreadyHereMessage = legacy(l.alreadyHereMessage, "You are already in the Townhall. Use /townhall return to go back.", "Du bist schon im Rathaus. Mit /townhall return kommst du zurück.");
				l.alreadyHereMessage = legacy(l.alreadyHereMessage, "You are already in the prison.", "Du bist schon im Gefängnis.");
				l.arrivedMessage = legacy(l.arrivedMessage, "Welcome!", "Willkommen!");
				l.arrivedMessage = legacy(l.arrivedMessage, "Welcome to the Townhall.", "Willkommen im Rathaus.");
				l.arrivedMessage = legacy(l.arrivedMessage, "You have been sent to prison.", "Du wurdest ins Gefängnis geschickt.");
				l.arrivedMessage = legacy(l.arrivedMessage, "Welcome to " + id + "!", "Willkommen in " + id + "!");
				l.arrivedMessage = legacy(l.arrivedMessage, "You were sent to " + id + ".", "Du wurdest nach " + id + " geschickt.");
				l.displayName = legacy(l.displayName, "the Townhall", "das Rathaus");
				l.displayName = legacy(l.displayName, "the prison", "das Gefängnis");
				if ("townhall".equals(id)) l.title = legacy(l.title, "&6Townhall", "&6Rathaus");
				changed |= !java.util.Arrays.equals(before, new String[] {l.arrivedMessage, l.alreadyHereMessage, l.displayName, l.title});
			}
		}
		return changed;
	}

	private static String legacy(String value, String oldDefault, String newDefault) {
		return oldDefault.equals(value) ? newDefault : value;
	}

	/** Returns every problem found; empty means the config is usable. Missing sections count as problems. */
	public List<String> validate() {
		List<String> errors = new ArrayList<>();
		if (locations == null || locations.isEmpty()) errors.add("locations muss mindestens einen Ort enthalten");
		else {
			Set<String> commandNames = new HashSet<>();
			for (Map.Entry<String, Location> e : locations.entrySet()) {
				String field = "locations." + e.getKey();
				Location loc = e.getValue();
				if (loc == null) {
					errors.add(field + " ist leer");
					continue;
				}
				if (loc.command == null || loc.command.isBlank()) loc.command = e.getKey();
				if (!COMMAND_NAME.matcher(loc.command).matches()) errors.add(field + ".command darf nur Buchstaben, Ziffern und _ . : - enthalten");
				if (!commandNames.add(loc.command.toLowerCase(Locale.ROOT))) errors.add(field + ".command '" + loc.command + "' wird doppelt verwendet");
				checkDimension(errors, field + ".dimension", loc.dimension);
				if (loc.spawn == null) errors.add(field + ".spawn fehlt");
				else checkSpot(errors, field + ".spawn", loc.spawn);
				if (loc.dimension != null && isSharedWorld(loc.dimension)) {
					// Only a warning: an existing live config must keep loading. The /location commands refuse it.
					dev.townhall.TownhallMod.LOGGER.warn("{}.dimension '{}' is the overworld/fallback world; a location needs a world of its own"
							+ " (otherwise enter and return break for everyone in that world)", field, loc.dimension);
				}
				if (loc.arrivedMessage == null) loc.arrivedMessage = "";
				if (loc.alreadyHereMessage == null) loc.alreadyHereMessage = "";
				checkFormat(errors, field + ".alreadyHereMessage", loc.alreadyHereMessage, "townhall");
				if (loc.confineRadius < 0) errors.add(field + ".confineRadius muss >= 0 sein");
			}
		}
		if (returnSettings == null) errors.add("return fehlt");
		if (safeTeleport == null) errors.add("safeTeleport fehlt");
		else {
			if (safeTeleport.horizontalRadius < 0 || safeTeleport.horizontalRadius > 16) errors.add("safeTeleport.horizontalRadius muss 0-16 sein");
			if (safeTeleport.verticalRadius < 0 || safeTeleport.verticalRadius > 16) errors.add("safeTeleport.verticalRadius muss 0-16 sein");
		}
		if (fallback == null) errors.add("fallback fehlt");
		else {
			checkDimension(errors, "fallback.dimension", fallback.dimension);
			if (!fallback.useWorldSpawn) {
				if (fallback.position == null) errors.add("fallback.position fehlt");
				else checkSpot(errors, "fallback.position", fallback.position);
			}
		}
		if (commands == null) errors.add("commands fehlt");
		else {
			if (commands.cooldownSeconds < 0) errors.add("commands.cooldownSeconds muss >= 0 sein");
			if (commands.operatorPermissionLevel < 1 || commands.operatorPermissionLevel > 4) errors.add("commands.operatorPermissionLevel muss 1-4 sein");
		}
		if (dimensions == null) dimensions = new LinkedHashMap<>();
		dimensions.forEach((id, rules) -> {
			checkDimension(errors, "dimensions key", id);
			if (rules != null && rules.difficulty != null && !DIFFICULTIES.contains(rules.difficulty)) {
				errors.add("dimensions." + id + ".difficulty muss peaceful, easy, normal oder hard sein");
			}
			if (rules != null && rules.time != null && parseTime(rules.time) == null) {
				errors.add("dimensions." + id + ".time muss day, noon, night, midnight oder 0-23999 sein");
			}
			if (rules != null && rules.builders != null) {
				for (String uuid : rules.builders.keySet()) {
					try {
						java.util.UUID.fromString(uuid);
					} catch (IllegalArgumentException e) {
						errors.add("dimensions." + id + ".builders: '" + uuid + "' ist keine Spieler-UUID");
					}
				}
			}
			if (rules != null && rules.weather != null && !WEATHERS.contains(rules.weather)) {
				errors.add("dimensions." + id + ".weather muss clear, rain oder thunder sein");
			}
		});
		if (onboarding == null) errors.add("onboarding fehlt");
		else {
			if (hasNullText(onboarding.welcome) || hasNullText(onboarding.tutorial) || hasNullText(onboarding.rules)) {
				errors.add("onboarding.welcome/tutorial/rules müssen Listen von Texten sein");
			}
			checkText(errors, "onboarding.acceptButton", onboarding.acceptButton);
			checkText(errors, "onboarding.acceptHint", onboarding.acceptHint);
			checkText(errors, "onboarding.accepted", onboarding.accepted);
			checkText(errors, "onboarding.acceptedTitle", onboarding.acceptedTitle);
			checkText(errors, "onboarding.reminder", onboarding.reminder);
		}
		if (confinement == null || confinement.allowedCommands == null) errors.add("confinement.allowedCommands fehlt");
		if (messages == null) errors.add("messages fehlt");
		else {
			// Plain texts, and texts filled in with String.formatted(...): the sample arguments have the types the code passes.
			checkText(errors, "messages.returned", messages.returned);
			checkText(errors, "messages.returnedNearby", messages.returnedNearby);
			checkText(errors, "messages.returnedFallback", messages.returnedFallback);
			checkText(errors, "messages.noReturnPosition", messages.noReturnPosition);
			checkText(errors, "messages.released", messages.released);
			checkFormat(errors, "messages.locationUnavailable", messages.locationUnavailable, "minecraft:flatworld");
			checkFormat(errors, "messages.confined", messages.confined, "das Gefängnis");
			checkFormat(errors, "messages.confinedTimed", messages.confinedTimed, "das Gefängnis", "1:15");
			checkFormat(errors, "messages.commandBlocked", messages.commandBlocked, "home");
			checkFormat(errors, "messages.pulledBack", messages.pulledBack, "das Gefängnis");
			checkFormat(errors, "messages.timeLeft", messages.timeLeft, "Das Gefängnis", "1:15");
			checkFormat(errors, "messages.cooldown", messages.cooldown, 3L);
		}
		if (joinMessages == null || joinMessages.players == null) errors.add("joinMessages fehlt");
		if (tabList == null || tabList.header == null || tabList.footer == null) errors.add("tabList.header/footer müssen Listen sein");
		if (afkMinutes < 0) errors.add("afkMinutes muss >= 0 sein");
		if (city == null) errors.add("city fehlt");
		else city.validate(errors);
		return errors;
	}

	/** True for the overworld and the fallback world: a location there would break enter/return for everyone in it. */
	public boolean isSharedWorld(String dimension) {
		return dimension.equals("minecraft:overworld") || (fallback != null && dimension.equals(fallback.dimension));
	}

	/** Null if the text can be filled in with {@code text.formatted(sampleArgs)}, otherwise what is wrong (e.g. a single %). */
	public static String formatProblem(String text, Object... sampleArgs) {
		try {
			String.format(Locale.ROOT, text, sampleArgs);
			return null;
		} catch (IllegalFormatException e) {
			return "ungültiger %-Platzhalter (" + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()) + "); schreibe %% für ein Prozentzeichen";
		}
	}

	/** Missing list or a missing line in it (List.of(...).contains(null) would throw, so no contains). */
	private static boolean hasNullText(List<String> lines) {
		return lines == null || lines.stream().anyMatch(java.util.Objects::isNull);
	}

	private static void checkText(List<String> errors, String field, String value) {
		if (value == null) errors.add(field + " fehlt");
	}

	private static void checkFormat(List<String> errors, String field, String value, Object... sampleArgs) {
		if (value == null) {
			errors.add(field + " fehlt");
			return;
		}
		String problem = formatProblem(value, sampleArgs);
		if (problem != null) errors.add(field + ": " + problem);
	}

	private static void checkDimension(List<String> errors, String field, String value) {
		if (value == null || !value.contains(":") || Identifier.tryParse(value) == null) {
			errors.add(field + " muss eine Dimensions-Kennung wie minecraft:overworld sein, ist aber '" + value + "'");
		}
	}

	private static void checkSpot(List<String> errors, String field, Spot spot) {
		if (!Double.isFinite(spot.x) || !Double.isFinite(spot.y) || !Double.isFinite(spot.z)
				|| Math.abs(spot.x) > 30_000_000 || Math.abs(spot.z) > 30_000_000 || Math.abs(spot.y) > 20_000) {
			errors.add(field + " hat ungültige Koordinaten");
		}
		if (!Float.isFinite(spot.yaw) || !Float.isFinite(spot.pitch) || spot.pitch < -90 || spot.pitch > 90) {
			errors.add(field + " hat ungültigen Yaw/Pitch");
		}
	}
}
