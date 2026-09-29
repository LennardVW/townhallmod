package dev.townhall.config;

import com.google.gson.annotations.SerializedName;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashSet;
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
	/** Minutes without activity until a player is shown as AFK (0 = only with /afk). */
	public int afkMinutes = 5;
	public JoinMessages joinMessages = new JoinMessages();
	public TabList tabList = new TabList();

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
		public String arrivedMessage = "Welcome!";
		/** Big title on arrival (empty = none). &-color codes work, e.g. "&6Townhall". */
		public String title = "";
		public String subtitle = "";
		public String alreadyHereMessage = "You are already here. Use /%s return to go back.";

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
		public String returned = "Returned to your previous location.";
		public String returnedNearby = "Your old spot was blocked, so you were placed nearby.";
		public String returnedFallback = "Your old spot wasn't safe, so you were sent to spawn.";
		public String noReturnPosition = "No return position is stored.";
		public String locationUnavailable = "%s is currently unavailable.";
		public String confined = "You can't leave %s on your own. An admin has to release you.";
		public String confinedTimed = "You can't leave %s for another %s.";
		public String commandBlocked = "You can't use /%s here.";
		public String pulledBack = "You can't leave %s.";
		public String timeLeft = "%s: %s left";
		public String released = "Your time is up. You are free again.";
		public String cooldown = "Please wait %d seconds before using this command again.";
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
	}

	private static Map<String, Location> defaultLocations() {
		Map<String, Location> map = new LinkedHashMap<>();
		Location townhall = new Location("townhall", "the Townhall", new Spot(0.5, 4.0, 0.5, 0f, 0f), false, true,
				"Welcome to the Townhall.", "You are already in the Townhall. Use /townhall return to go back.");
		townhall.title = "&6Townhall";
		townhall.subtitle = "&7Willkommen!";
		map.put("townhall", townhall);
		Location prison = new Location("gefaengnis", "the prison", new Spot(100.5, 4.0, 100.5, 0f, 0f), true, false,
				"You have been sent to prison.", "You are already in the prison.");
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

	/** Returns every problem found; empty means the config is usable. Missing sections count as problems. */
	public List<String> validate() {
		List<String> errors = new ArrayList<>();
		if (locations == null || locations.isEmpty()) errors.add("locations must contain at least one location");
		else {
			Set<String> commandNames = new HashSet<>();
			for (Map.Entry<String, Location> e : locations.entrySet()) {
				String field = "locations." + e.getKey();
				Location loc = e.getValue();
				if (loc == null) {
					errors.add(field + " is empty");
					continue;
				}
				if (loc.command == null || loc.command.isBlank()) loc.command = e.getKey();
				if (!COMMAND_NAME.matcher(loc.command).matches()) errors.add(field + ".command may only use letters, digits and _ . : -");
				if (!commandNames.add(loc.command.toLowerCase(Locale.ROOT))) errors.add(field + ".command '" + loc.command + "' is used twice");
				checkDimension(errors, field + ".dimension", loc.dimension);
				if (loc.spawn == null) errors.add(field + ".spawn is missing");
				else checkSpot(errors, field + ".spawn", loc.spawn);
				if (loc.arrivedMessage == null) loc.arrivedMessage = "";
				if (loc.alreadyHereMessage == null) loc.alreadyHereMessage = "";
				if (loc.confineRadius < 0) errors.add(field + ".confineRadius must be >= 0");
			}
		}
		if (returnSettings == null) errors.add("return is missing");
		if (safeTeleport == null) errors.add("safeTeleport is missing");
		else {
			if (safeTeleport.horizontalRadius < 0 || safeTeleport.horizontalRadius > 16) errors.add("safeTeleport.horizontalRadius must be 0-16");
			if (safeTeleport.verticalRadius < 0 || safeTeleport.verticalRadius > 16) errors.add("safeTeleport.verticalRadius must be 0-16");
		}
		if (fallback == null) errors.add("fallback is missing");
		else {
			checkDimension(errors, "fallback.dimension", fallback.dimension);
			if (!fallback.useWorldSpawn) {
				if (fallback.position == null) errors.add("fallback.position is missing");
				else checkSpot(errors, "fallback.position", fallback.position);
			}
		}
		if (commands == null) errors.add("commands is missing");
		else {
			if (commands.cooldownSeconds < 0) errors.add("commands.cooldownSeconds must be >= 0");
			if (commands.operatorPermissionLevel < 1 || commands.operatorPermissionLevel > 4) errors.add("commands.operatorPermissionLevel must be 1-4");
		}
		if (dimensions == null) dimensions = new LinkedHashMap<>();
		dimensions.forEach((id, rules) -> {
			checkDimension(errors, "dimensions key", id);
			if (rules != null && rules.difficulty != null && !DIFFICULTIES.contains(rules.difficulty)) {
				errors.add("dimensions." + id + ".difficulty must be peaceful, easy, normal or hard");
			}
			if (rules != null && rules.time != null && parseTime(rules.time) == null) {
				errors.add("dimensions." + id + ".time must be day, noon, night, midnight or 0-23999");
			}
			if (rules != null && rules.builders != null) {
				for (String uuid : rules.builders.keySet()) {
					try {
						java.util.UUID.fromString(uuid);
					} catch (IllegalArgumentException e) {
						errors.add("dimensions." + id + ".builders: '" + uuid + "' is not a player UUID");
					}
				}
			}
			if (rules != null && rules.weather != null && !WEATHERS.contains(rules.weather)) {
				errors.add("dimensions." + id + ".weather must be clear, rain or thunder");
			}
		});
		if (onboarding == null) errors.add("onboarding is missing");
		else if (onboarding.welcome == null || onboarding.tutorial == null || onboarding.rules == null) errors.add("onboarding.welcome/tutorial/rules must be lists");
		if (confinement == null || confinement.allowedCommands == null) errors.add("confinement.allowedCommands is missing");
		if (messages == null) errors.add("messages is missing");
		if (joinMessages == null || joinMessages.players == null) errors.add("joinMessages is missing");
		if (tabList == null || tabList.header == null || tabList.footer == null) errors.add("tabList.header/footer must be lists");
		if (afkMinutes < 0) errors.add("afkMinutes must be >= 0");
		return errors;
	}

	private static void checkDimension(List<String> errors, String field, String value) {
		if (value == null || !value.contains(":") || Identifier.tryParse(value) == null) {
			errors.add(field + " must be a dimension id like minecraft:overworld, got '" + value + "'");
		}
	}

	private static void checkSpot(List<String> errors, String field, Spot spot) {
		if (!Double.isFinite(spot.x) || !Double.isFinite(spot.y) || !Double.isFinite(spot.z)
				|| Math.abs(spot.x) > 30_000_000 || Math.abs(spot.z) > 30_000_000 || Math.abs(spot.y) > 20_000) {
			errors.add(field + " has invalid coordinates");
		}
		if (!Float.isFinite(spot.yaw) || !Float.isFinite(spot.pitch) || spot.pitch < -90 || spot.pitch > 90) {
			errors.add(field + " has invalid yaw/pitch");
		}
	}
}
