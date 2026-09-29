package dev.townhall.nick;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.townhall.util.Text;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Nicknames set by operators, keyed by UUID. Saved with the world in data/townhall/nicknames.dat.
 * Shown in chat (Player.getDisplayName, PlayerMixin) and in the tab list (ServerPlayer.getTabListDisplayName, ServerPlayerMixin).
 * Display names are read very often, so the built components live in a static map that is filled from the saved data.
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
	/** Real name → invisible profile token, for NickPackets. */
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
	}

	public static Map<String, String> headNamesByRealName() {
		return headNames;
	}


	/** Keeps the stored real name current (players can rename their Mojang account). Call on join, before others see them. */
	public static void onJoin(ServerPlayer player) {
		Nicknames data = get(player.level().getServer());
		Entry e = data.entries.get(player.getUUID());
		if (e != null && !e.name().equals(player.getGameProfile().name())) {
			data.entries.put(player.getUUID(), new Entry(e.nick(), player.getGameProfile().name()));
			data.setDirty();
			data.rebuild();
		}
	}

	/** The nickname to show instead of the player's name, if any. */
	public static Optional<Component> of(UUID player) {
		return Optional.ofNullable(shown.get(player));
	}

	public Map<UUID, Entry> entries() {
		return Map.copyOf(entries);
	}

	/** Visible text without color codes, lower case, for comparing names. */
	public static String plain(String text) {
		return text.replaceAll("[&§][0-9a-fk-orA-FK-OR]", "").strip().toLowerCase(Locale.ROOT);
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
		ServerPlayer online = server.getPlayerList().getPlayer(player);
		if (online != null) NickPackets.refresh(online);
	}

	private void rebuild() {
		Map<UUID, Component> map = new HashMap<>();
		// §r at the end so colors of the nickname don't spill into the chat message.
		Map<String, String> heads = new HashMap<>();
		entries.forEach((uuid, e) -> {
			map.put(uuid, Text.of(e.nick() + "&r"));
			heads.put(e.name(), NickPackets.token(uuid));
		});
		shown = Map.copyOf(map);
		headNames = Map.copyOf(heads);
	}
}
