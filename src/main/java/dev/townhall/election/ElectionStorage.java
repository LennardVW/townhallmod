package dev.townhall.election;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Native world storage. The archive contains pages only, never an author, token or voter association. */
public final class ElectionStorage extends SavedData {
    public static final int MAX_ELECTIONS = 64;
    public static final int MAX_CANDIDATES = 32;
    public static final int MAX_VOTERS = 4096;
    public static final int MAX_HELPERS = 128;
    public static final int MAX_ROOM_RADIUS = 32;

    public enum State {
        DRAFT("Vorbereitung"), OPEN("Geöffnet"), CLOSED("Geschlossen"),
        PUBLISHED("Veröffentlicht"), CANCELLED("Abgebrochen");
        private final String display;
        State(String display) { this.display = display; }
        public String display() { return display; }
        static final Codec<State> CODEC = Codec.STRING.comapFlatMap(name -> {
            try { return DataResult.success(valueOf(name)); }
            catch (IllegalArgumentException ex) { return DataResult.error(() -> "Unknown election state: " + name); }
        }, State::name);
    }

    public record Room(String dimension, BlockPos center, int radius) {
        public static final Codec<Room> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("dimension").forGetter(Room::dimension),
                BlockPos.CODEC.fieldOf("center").forGetter(Room::center),
                Codec.intRange(1, MAX_ROOM_RADIUS).fieldOf("radius").forGetter(Room::radius)
        ).apply(i, Room::new));
        public Room { center = center.immutable(); }
    }

    public record Urn(String dimension, BlockPos pos) {
        public static final Codec<Urn> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("dimension").forGetter(Urn::dimension),
                BlockPos.CODEC.fieldOf("pos").forGetter(Urn::pos)
        ).apply(i, Urn::new));
        public Urn { pos = pos.immutable(); }
    }

    /** Immutable text, including vanilla's raw/filtered variants; custom item components are discarded. */
    public record Ballot(WritableBookContent content) {
        public static final Codec<Ballot> CODEC = WritableBookContent.CODEC
                .validate(content -> ElectionService.validPages(content)
                        ? DataResult.success(content) : DataResult.error(() -> "Invalid ballot pages"))
                .xmap(Ballot::new, Ballot::content);
        public Ballot { content = new WritableBookContent(List.copyOf(content.pages())); }
    }

    public static final class Election {
        public static final Codec<Election> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("id").forGetter(Election::id),
                Codec.STRING.fieldOf("title").forGetter(Election::title),
                State.CODEC.fieldOf("state").forGetter(Election::state),
                Codec.STRING.listOf().fieldOf("candidates").forGetter(Election::candidates),
                Room.CODEC.optionalFieldOf("room").forGetter(Election::room),
                Urn.CODEC.optionalFieldOf("urn").forGetter(Election::urn),
                Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.STRING).optionalFieldOf("helpers", Map.of()).forGetter(Election::helpers),
                Codec.unboundedMap(UUIDUtil.STRING_CODEC, UUIDUtil.STRING_CODEC).optionalFieldOf("tokens", Map.of()).forGetter(Election::tokens),
                UUIDUtil.STRING_CODEC.listOf().optionalFieldOf("voters", List.of()).forGetter(e -> List.copyOf(e.voters)),
                Ballot.CODEC.listOf().optionalFieldOf("archive", List.of()).forGetter(Election::archive),
                Codec.unboundedMap(Codec.STRING, Codec.intRange(0, MAX_VOTERS)).optionalFieldOf("totals", Map.of()).forGetter(Election::totals),
                Codec.intRange(0, MAX_VOTERS).optionalFieldOf("invalid").forGetter(Election::invalid)
        ).apply(i, Election::new));

        private final String id;
        private final String title;
        State state;
        final List<String> candidates;
        Optional<Room> room;
        Optional<Urn> urn;
        final Map<UUID, String> helpers;
        final Map<UUID, UUID> tokens;
        final Set<UUID> voters;
        final List<Ballot> archive;
        final Map<String, Integer> totals;
        Optional<Integer> invalid;

        Election(String id, String title) {
            this(id, title, State.DRAFT, List.of(), Optional.empty(), Optional.empty(),
                    Map.of(), Map.of(), List.of(), List.of(), Map.of(), Optional.empty());
        }

        private Election(String id, String title, State state, List<String> candidates,
                         Optional<Room> room, Optional<Urn> urn, Map<UUID, String> helpers,
                         Map<UUID, UUID> tokens, List<UUID> voters, List<Ballot> archive,
                         Map<String, Integer> totals, Optional<Integer> invalid) {
            this.id = id; this.title = title; this.state = state;
            this.candidates = new ArrayList<>(candidates); this.room = room; this.urn = urn;
            this.helpers = new LinkedHashMap<>(helpers); this.tokens = new HashMap<>(tokens);
            this.voters = new HashSet<>(voters); this.archive = new ArrayList<>(archive);
            this.totals = new LinkedHashMap<>(totals); this.invalid = invalid;
        }

        public String id() { return id; }
        public String title() { return title; }
        public State state() { return state; }
        public List<String> candidates() { return List.copyOf(candidates); }
        public Optional<Room> room() { return room; }
        public Optional<Urn> urn() { return urn; }
        public Map<UUID, String> helpers() { return Map.copyOf(helpers); }
        public Map<UUID, UUID> tokens() { return Map.copyOf(tokens); }
        public Set<UUID> voters() { return Set.copyOf(voters); }
        public List<Ballot> archive() { return List.copyOf(archive); }
        public int ballotCount() { return archive.size(); }
        public Map<String, Integer> totals() { return Map.copyOf(totals); }
        public Optional<Integer> invalid() { return invalid; }
        public long counted() {
            return totals.values().stream().mapToLong(Integer::longValue).sum() + invalid.orElse(0);
        }
        public boolean complete() {
            return invalid.isPresent() && totals.keySet().equals(new HashSet<>(candidates))
                    && counted() == archive.size();
        }
    }

    public static final Codec<ElectionStorage> CODEC = Codec.unboundedMap(Codec.STRING, Election.CODEC)
            .xmap(ElectionStorage::new, storage -> storage.elections).validate(ElectionStorage::validate);
    public static final SavedDataType<ElectionStorage> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("townhall", "elections"), ElectionStorage::new, CODEC, null);

    private final Map<String, Election> elections;
    private final Map<String, String> urns = new HashMap<>();

    public ElectionStorage() { this(Map.of()); }
    private ElectionStorage(Map<String, Election> elections) {
        this.elections = new LinkedHashMap<>(elections);
        rebuildUrns();
    }
    public static ElectionStorage get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }
    public Optional<Election> election(String id) { return Optional.ofNullable(elections.get(id)); }
    public List<Election> elections() { return List.copyOf(elections.values()); }
    public Optional<Election> at(String dimension, BlockPos pos) {
        return election(urns.get(key(dimension, pos)));
    }
    void add(Election election) { elections.put(election.id(), election); setDirty(); }
    void changedUrn() { rebuildUrns(); setDirty(); }
    private void rebuildUrns() {
        urns.clear();
        elections.values().forEach(e -> e.urn.ifPresent(u -> urns.put(key(u.dimension(), u.pos()), e.id())));
    }
    private static String key(String dimension, BlockPos pos) { return dimension + "|" + pos.asLong(); }

    private static DataResult<ElectionStorage> validate(ElectionStorage storage) {
        if (storage.elections.size() > MAX_ELECTIONS) return bad("Too many elections");
        Set<String> sites = new HashSet<>();
        for (var entry : storage.elections.entrySet()) {
            Election e = entry.getValue();
            if (!entry.getKey().equals(e.id()) || !ElectionService.validId(e.id())
                    || !ElectionService.validLabel(e.title(), 96)) return bad("Invalid election identity");
            if (e.candidates.size() > MAX_CANDIDATES || new HashSet<>(e.candidates).size() != e.candidates.size()
                    || e.candidates.stream().anyMatch(c -> !ElectionService.validLabel(c, 64))) return bad("Invalid candidates");
            if (e.helpers.size() > MAX_HELPERS || e.tokens.size() + e.voters.size() > MAX_VOTERS
                    || e.archive.size() != e.voters.size() || e.tokens.keySet().stream().anyMatch(e.voters::contains)
                    || new HashSet<>(e.tokens.values()).size() != e.tokens.size()) return bad("Invalid voter registry");
            if (!e.candidates.containsAll(e.totals.keySet())
                    || e.totals.values().stream().anyMatch(n -> n < 0 || n > e.archive.size())
                    || e.invalid.filter(n -> n < 0 || n > e.archive.size()).isPresent()) return bad("Invalid manual totals");
            if (e.urn.isPresent() && !sites.add(key(e.urn.get().dimension(), e.urn.get().pos()))) return bad("Overlapping urns");
            if (e.room.filter(r -> Identifier.tryParse(r.dimension()) == null).isPresent()
                    || e.urn.filter(u -> Identifier.tryParse(u.dimension()) == null).isPresent()) return bad("Invalid dimension");
            if (e.state == State.DRAFT && (!e.tokens.isEmpty() || !e.voters.isEmpty())) return bad("Draft contains ballots");
            if ((e.state == State.DRAFT || e.state == State.OPEN) && (!e.totals.isEmpty() || e.invalid.isPresent()))
                return bad("Counting before close");
            if ((e.state == State.OPEN || e.state == State.CLOSED || e.state == State.PUBLISHED)
                    && (e.candidates.isEmpty() || e.room.isEmpty()
                    || (e.state == State.OPEN && e.urn.isEmpty())
                    || e.urn.filter(u -> !ElectionService.contains(e.room.get(), u.dimension(), net.minecraft.world.phys.Vec3.atCenterOf(u.pos()))).isPresent()))
                return bad("Incomplete election setup");
            if (e.state != State.OPEN && !e.tokens.isEmpty()) return bad("Tokens outside open election");
            if (e.state == State.PUBLISHED && !e.complete()) return bad("Incomplete published results");
        }
        return DataResult.success(storage);
    }
    private static DataResult<ElectionStorage> bad(String message) { return DataResult.error(() -> message); }
}
