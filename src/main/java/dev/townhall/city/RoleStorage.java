package dev.townhall.city;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import java.util.*;

/** Definitions and offline memberships keyed by UUID. Names are only for display. */
public final class RoleStorage extends SavedData {
	public record Member(String name, List<String> roles) {
		public static final Codec<Member> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.STRING.fieldOf("name").forGetter(Member::name),
				Codec.STRING.listOf().fieldOf("roles").forGetter(Member::roles)
		).apply(i, Member::new));
		public Member { roles = List.copyOf(roles); }
	}
	public static final Codec<RoleStorage> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.unboundedMap(Codec.STRING, RoleDefinition.CODEC).fieldOf("roles").forGetter(s -> s.roles),
			Codec.unboundedMap(UUIDUtil.STRING_CODEC, Member.CODEC).optionalFieldOf("members", Map.of()).forGetter(s -> s.members)
	).apply(i, RoleStorage::new));
	public static final SavedDataType<RoleStorage> TYPE = new SavedDataType<>(
			Identifier.fromNamespaceAndPath("townhall", "roles"), RoleStorage::new, CODEC, null);
	private final Map<String, RoleDefinition> roles;
	private final Map<UUID, Member> members;
	public RoleStorage() { this(defaults(), Map.of()); }
	private RoleStorage(Map<String, RoleDefinition> roles, Map<UUID, Member> members) {
		this.roles = new LinkedHashMap<>(roles);
		this.members = new HashMap<>(members);
	}
	private static Map<String, RoleDefinition> defaults() {
		Map<String, RoleDefinition> r = new LinkedHashMap<>();
		r.put("buergermeister", new RoleDefinition("Bürgermeister", "&6[Bürgermeister] &r", List.of("city.announce"), 100));
		r.put("polizei", new RoleDefinition("Polizei", "&9[Polizei] &r", List.of("police.jail", "police.release"), 80));
		r.put("haendler", new RoleDefinition("Händler", "&a[Händler] &r", List.of("shop.manage"), 40));
		r.put("architekt", new RoleDefinition("Architekt", "&b[Architekt] &r", List.of(), 50));
		r.put("wahlhelfer", new RoleDefinition("Wahlhelfer", "&e[Wahlhelfer] &r", List.of("election.count"), 30));
		return r;
	}
	public static RoleStorage get(MinecraftServer server) { return server.getDataStorage().computeIfAbsent(TYPE); }
	public Map<String, RoleDefinition> definitions() { return Collections.unmodifiableMap(roles); }
	public Map<UUID, Member> members() { return Collections.unmodifiableMap(members); }
	public Set<String> memberships(UUID id) { Member m = members.get(id); return m == null ? Set.of() : Set.copyOf(m.roles()); }
	public boolean has(UUID id, String permission) {
		Member m = members.get(id);
		if (m == null) return false;
		for (String role : m.roles()) { RoleDefinition d = roles.get(role); if (d != null && d.permissions().contains(permission)) return true; }
		return false;
	}
	public void put(String id, RoleDefinition definition) { roles.put(id, definition); setDirty(); }
	public boolean remove(String id) {
		if (roles.remove(id) == null) return false;
		for (UUID uuid : List.copyOf(members.keySet())) {
			Member m = members.get(uuid);
			List<String> left = m.roles().stream().filter(r -> !r.equals(id)).toList();
			if (left.isEmpty()) members.remove(uuid); else members.put(uuid, new Member(m.name(), left));
		}
		setDirty(); return true;
	}
	public boolean grant(UUID uuid, String name, String role) {
		if (!roles.containsKey(role)) return false;
		Set<String> all = new LinkedHashSet<>(memberships(uuid));
		boolean changed = all.add(role);
		members.put(uuid, new Member(name, List.copyOf(all))); setDirty(); return changed;
	}
	public boolean revoke(UUID uuid, String role) {
		Member m = members.get(uuid); if (m == null || !m.roles().contains(role)) return false;
		List<String> left = m.roles().stream().filter(r -> !r.equals(role)).toList();
		if (left.isEmpty()) members.remove(uuid); else members.put(uuid, new Member(m.name(), left));
		setDirty(); return true;
	}
}
