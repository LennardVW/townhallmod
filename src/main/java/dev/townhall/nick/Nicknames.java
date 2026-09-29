package dev.townhall.nick;

import com.mojang.brigadier.StringReader;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.townhall.TownhallMod;
import dev.townhall.activity.Playtime;
import dev.townhall.util.Text;
import net.minecraft.ChatFormatting;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Nicknames set by operators, keyed by UUID. Saved with the world in data/townhall/nicknames.dat.
 * Shown in chat (Player.getDisplayName, PlayerMixin) and in the tab list (ServerPlayer.getTabListDisplayName, ServerPlayerMixin).
 * Display names are read very often, so the built components live in static maps that are filled from the saved data.
 * Commands accept a nickname wherever a player name is typed (EntitySelectorMixin, GameProfileArgumentMixin); real names win.
 */
public final class Nicknames extends SavedData {

	/** nick = text with &-color codes, name = real name when it was set (for /nick list). */
	public record Entry(String nick, String name) {
		static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.STRING.fieldOf("nick").forGetter(Entry::nick),
				Codec.STRING.fieldOf("name").forGetter(Entry::name)).apply(i, Entry::new));
	}

	public static final Codec<Nicknames> CODEC = Codec.unboundedMap(UUIDUtil.STRING_CODEC, Entry.CODEC).xmap(Nicknames::new, n -> n.entries);

	public static final SavedDataType<Nicknames> TYPE = new SavedDataType<>(
			Identifier.fromNamespaceAndPath("townhall", "nicknames"), Nicknames::new, CODEC, null);

	private static volatile Map<UUID, Component> shown = Map.of();
	/** UUID → nickname text with &-codes (tab list placeholder). */
	private static volatile Map<UUID, String> raw = Map.of();
	/** plain(nickname) → owner; nicknames that two players share (old data) are left out, so they resolve to nobody. */
	private static volatile Map<String, UUID> byPlain = Map.of();
	/** Current profile name of every ONLINE nicknamed player → invisible profile token, for NickPackets. */
	private static volatile Map<String, String> headNames = Map.of();

	private final Map<UUID, Entry> entries;

	public Nicknames() {
		this(Map.of());
	}

	private Nicknames(Map<UUID, Entry> entries) {
		this.entries = new HashMap<>(entries);
	}

	public static Nicknames get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	/** Loads the saved nicknames into the fast lookup; call on server start. */
	public static void load(MinecraftServer server) {
		get(server).rebuild();
		rebuildHeads(server, null, null);
	}

	public static Map<String, String> headNamesByRealName() {
		return headNames;
	}

	/**
	 * Keeps the stored real name current (players can rename their Mojang account) and adds the player to the head-name map.
	 * Call on join, before others see them. Warns operators if the real name is someone else's nickname (the real name wins).
	 */
	public static void onJoin(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		Nicknames data = get(server);
		String name = player.getGameProfile().name();
		Entry e = data.entries.get(player.getUUID());
		if (e != null && !e.name().equals(name)) {
			data.entries.put(player.getUUID(), new Entry(e.nick(), name));
			data.setDirty();
			data.rebuild();
		}
		rebuildHeads(server, null, player);
		UUID owner = byPlain.get(plain(name));
		if (owner != null && !owner.equals(player.getUUID())) {
			Entry taken = data.entries.get(owner);
			String who = taken == null ? owner.toString() : taken.name();
			TownhallMod.LOGGER.warn("{} joined, but {} has the nickname {}. Commands now mean {}; give {} another nickname with /nick set.",
					name, who, taken == null ? name : taken.nick(), name, who);
			Component warning = Component.literal("[Townhall] " + name + " has the same name as the nickname of " + who
					+ ". Commands with this name now mean " + name + ". Please use /nick set " + who + " <other nickname>.").withStyle(ChatFormatting.GOLD);
			for (ServerPlayer online : server.getPlayerList().getPlayers()) {
				if (TownhallMod.isOperator(online.permissions())) online.sendSystemMessage(warning);
			}
		}
	}

	/** Call when a player leaves (they are still in the player list), so their name isn't renamed in packets anymore. */
	public static void onLeave(ServerPlayer player) {
		rebuildHeads(player.level().getServer(), player, null);
	}

	/** True if any player has a nickname (fast exit for the packet hook). */
	public static boolean any() {
		return !shown.isEmpty();
	}

	/** The nickname to show instead of the player's name, if any. */
	public static Optional<Component> of(UUID player) {
		return Optional.ofNullable(shown.get(player));
	}

	/** The nickname text with &-codes, or null. Cheap (static map), for hot paths like the tab list. */
	public static String raw(UUID player) {
		return raw.get(player);
	}

	/**
	 * Name to show for a player in lists (e.g. /playtime): online = display name (nickname, hover shows the real player),
	 * offline with a nickname = the nickname with the real name on hover, otherwise the real name.
	 */
	public static Component displayName(MinecraftServer server, UUID player, String realName) {
		ServerPlayer online = server.getPlayerList().getPlayer(player);
		if (online != null) return online.getDisplayName();
		Component nick = shown.get(player);
		if (nick == null) return Component.literal(realName);
		return nick.copy().withStyle(s -> s.withHoverEvent(new HoverEvent.ShowText(Component.literal(realName))));
	}

	public Map<UUID, Entry> entries() {
		return Map.copyOf(entries);
	}

	/** Visible text without color codes, lower case, for comparing names. */
	public static String plain(String text) {
		return text.replaceAll("[&§][0-9a-fk-orA-FK-OR]", "").strip().toLowerCase(Locale.ROOT);
	}

	/** Visible text without color codes, case kept (what a player types). */
	public static String visible(String text) {
		return text.replaceAll("[&§][0-9a-fk-orA-FK-OR]", "").strip();
	}

	/** Owner of the nickname whose visible text is this (case and color codes ignored), if exactly one player has it. */
	public static Optional<UUID> ownerOf(String typed) {
		return Optional.ofNullable(byPlain.get(plain(typed)));
	}

	/**
	 * The online player a typed name means as a nickname, or empty. Only used after no online player has that real name;
	 * a real name that the server knows (also offline) still wins, so it never resolves to a nickname.
	 */
	public static Optional<ServerPlayer> onlineByNickname(MinecraftServer server, String typed) {
		UUID owner = byPlain.get(plain(typed));
		if (owner == null || isKnownRealName(server, typed)) return Optional.empty();
		return Optional.ofNullable(server.getPlayerList().getPlayer(owner));
	}

	/** Profile (also offline) a typed name means as a nickname, or empty if it isn't one or is a known real name. */
	public static Optional<NameAndId> profileByNickname(MinecraftServer server, String typed) {
		UUID owner = byPlain.get(plain(typed));
		if (owner == null || isKnownRealName(server, typed)) return Optional.empty();
		ServerPlayer online = server.getPlayerList().getPlayer(owner);
		if (online != null) return Optional.of(online.nameAndId());
		Entry e = get(server).entries.get(owner);
		return e == null ? Optional.empty() : Optional.of(new NameAndId(owner, e.name()));
	}

	/**
	 * Nicknames of online players for name completion, as typed (colors removed). Only nicknames that can be typed as one
	 * argument (letters, digits, _ - . +) and that aren't an online player's real name.
	 */
	public static List<String> suggestions(MinecraftServer server) {
		if (shown.isEmpty()) return List.of();
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		List<String> out = new ArrayList<>();
		for (ServerPlayer p : players) {
			String nick = raw.get(p.getUUID());
			if (nick == null) continue;
			String typed = visible(nick);
			if (typed.isEmpty() || !typed.chars().allMatch(c -> Character.isLetterOrDigit(c) || StringReader.isAllowedInUnquotedString((char) c))) continue;
			if (players.stream().noneMatch(o -> o.getGameProfile().name().equalsIgnoreCase(typed))) out.add(typed);
		}
		return out;
	}

	/**
	 * True if some player the server knows really has this name (color codes and case ignored): online players,
	 * the name cache (usercache.json), operators, whitelist, play time records and stored real names of nicknamed players.
	 * Only called by /nick set and when a typed name matches a nickname, never per tick.
	 */
	public static boolean isKnownRealName(MinecraftServer server, String name) {
		String plain = plain(name);
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (plain(p.getGameProfile().name()).equals(plain)) return true;
		}
		if (server.services().nameToIdCache() instanceof KnownNames cache && cache.townhall$knows(plain)) return true;
		for (String n : server.getPlayerList().getOpNames()) if (plain(n).equals(plain)) return true;
		for (String n : server.getPlayerList().getWhiteListNames()) if (plain(n).equals(plain)) return true;
		for (var e : Playtime.get(server).top(Integer.MAX_VALUE)) if (plain(e.getValue().name()).equals(plain)) return true;
		for (Entry e : get(server).entries.values()) if (plain(e.name()).equals(plain)) return true;
		return false;
	}

	public void set(MinecraftServer server, UUID player, String name, String nick) {
		entries.put(player, new Entry(nick, name));
		changed(server, player);
	}

	public boolean reset(MinecraftServer server, UUID player) {
		if (entries.remove(player) == null) return false;
		changed(server, player);
		return true;
	}

	private void changed(MinecraftServer server, UUID player) {
		setDirty();
		rebuild();
		rebuildHeads(server, null, null);
		ServerPlayer online = server.getPlayerList().getPlayer(player);
		if (online != null) NickPackets.refresh(online);
	}

	private void rebuild() {
		Map<UUID, Component> map = new HashMap<>();
		Map<UUID, String> texts = new HashMap<>();
		Map<String, UUID> plains = new HashMap<>();
		Set<String> shared = new HashSet<>();
		entries.forEach((uuid, e) -> {
			// §r at the end so colors of the nickname don't spill into the chat message.
			map.put(uuid, Text.of(e.nick() + "&r"));
			texts.put(uuid, e.nick());
			if (plains.putIfAbsent(plain(e.nick()), uuid) != null) shared.add(plain(e.nick()));
		});
		shared.forEach(plains::remove);
		shown = Map.copyOf(map);
		raw = Map.copyOf(texts);
		byPlain = Map.copyOf(plains);
	}

	/**
	 * Head-name map from the players that are online now, keyed by their current profile name (not the stored one, which can
	 * belong to someone else after a Mojang rename). Rebuilt on join, leave and nickname changes, never per packet.
	 */
	static void rebuildHeads(MinecraftServer server, ServerPlayer leaving, ServerPlayer joining) {
		Map<String, String> heads = new HashMap<>();
		List<ServerPlayer> players = new ArrayList<>(server.getPlayerList().getPlayers());
		if (joining != null && !players.contains(joining)) players.add(joining);
		for (ServerPlayer p : players) {
			if (p == leaving || !shown.containsKey(p.getUUID())) continue;
			heads.put(p.getGameProfile().name(), NickPackets.token(p.getUUID()));
		}
		headNames = Map.copyOf(heads);
	}
}
