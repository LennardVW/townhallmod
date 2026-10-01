package dev.townhall.election;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.townhall.audit.AuditLog;
import dev.townhall.city.CityAccess;
import dev.townhall.city.RoleService;
import dev.townhall.TownhallMod;
import dev.townhall.onboarding.Onboarding;
import dev.townhall.storage.ReturnPositionStorage;
import dev.townhall.protection.Protection;
import dev.townhall.shop.ShopService;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.security.SecureRandom;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import static dev.townhall.election.ElectionStorage.*;

/** Physical vanilla books, UUID eligibility and manual counting. All mutations run on the server thread. */
public final class ElectionService {
    public static final double URN_DISTANCE = 6.0;
    private static final String ELECTION_TAG = "townhall_election";
    private static final String TOKEN_TAG = "townhall_ballot_token";
    private static final SecureRandom RANDOM = new SecureRandom();
    private ElectionService() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) { ElectionCommand.register(dispatcher); }

    /** Register once from the main entrypoint, after civic protection knows about canUse. */
    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(ElectionService::initialize);
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!(player instanceof ServerPlayer sp) || !(level instanceof ServerLevel sl)) return InteractionResult.PASS;
            return onUseUrn(sp, sl, hand, hit.getBlockPos());
        });
    }

    /** Load through Minecraft before protection predicates are used by ticking entities/automation. */
    public static void initialize(MinecraftServer server) { ElectionStorage.get(server); }

    public static boolean protectedAt(ServerLevel level, BlockPos pos) {
        return ElectionStorage.get(level.getServer()).at(dimension(level), pos).isPresent();
    }

    /** A valid submission may bypass world/plot container restrictions; this never grants menu access. */
    public static boolean canUse(ServerPlayer player, ServerLevel level, BlockPos pos) {
        Optional<Election> election = ElectionStorage.get(level.getServer()).at(dimension(level), pos);
        if (election.isEmpty()) return true;
        if (player.level() != level) return false;
        try { checkSubmission(player, election.get(), player.getMainHandItem()); return true; }
        catch (ElectionException ex) { return false; }
    }

    /** Remains true after close/cancel/publish: the reserved barrel is never an editable ballot inventory. */
    public static boolean blocksAutomation(ServerLevel level, BlockPos pos) { return protectedAt(level, pos); }

    public static InteractionResult onUseUrn(ServerPlayer player, ServerLevel level, InteractionHand hand, BlockPos pos) {
        Optional<Election> election = ElectionStorage.get(level.getServer()).at(dimension(level), pos);
        if (election.isEmpty()) return InteractionResult.PASS;
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.FAIL;
        try {
            submit(player, election.get().id());
            player.sendSystemMessage(Component.literal("Dein Stimmzettel wurde anonym abgegeben."));
            return InteractionResult.SUCCESS;
        } catch (ElectionException ex) {
            player.sendSystemMessage(Component.literal(ex.getMessage()));
            return InteractionResult.FAIL;
        }
    }

    public static Election create(CommandSourceStack source, String id, String title) {
        requireAdmin(source);
        require(validId(id), "Wahl-ID: 1–32 Zeichen, beginnend mit a–z; erlaubt sind a–z, 0–9, _ und -.");
        require(validLabel(title, 96), "Der Titel muss 1–96 lesbare Zeichen enthalten.");
        ElectionStorage storage = ElectionStorage.get(source.getServer());
        require(storage.election(id).isEmpty(), "Diese Wahl-ID existiert bereits.");
        require(storage.elections().size() < MAX_ELECTIONS, "Die Grenze von " + MAX_ELECTIONS + " Wahlen ist erreicht.");
        Election election = new Election(id, title);
        storage.add(election);
        audit(source, election, "create", "Vorbereitung");
        return election;
    }

    public static void candidate(CommandSourceStack source, String id, String candidate, boolean add) {
        requireAdmin(source);
        Election election = find(source, id);
        requireDraft(election);
        require(validLabel(candidate, 64), "Kandidat: 1–64 lesbare Zeichen; Namen mit Leerzeichen in Anführungszeichen setzen.");
        if (add) {
            require(!election.candidates.contains(candidate), "Dieser Kandidat ist bereits eingetragen.");
            require(election.candidates.size() < MAX_CANDIDATES, "Höchstens " + MAX_CANDIDATES + " Kandidaten.");
            election.candidates.add(candidate);
        } else require(election.candidates.remove(candidate), "Dieser Kandidat ist nicht eingetragen.");
        changed(source, election, add ? "candidate.add" : "candidate.remove", "Kandidaten=" + election.candidates.size());
    }

    public static void room(CommandSourceStack source, String id, int radius) throws CommandSyntaxException {
        requireAdmin(source);
        Election election = find(source, id);
        requireDraft(election);
        require(radius >= 1 && radius <= MAX_ROOM_RADIUS, "Der Wahlraumradius muss 1–32 Blöcke betragen.");
        ServerPlayer player = source.getPlayerOrException();
        Room room = new Room(dimension(player.level()), player.blockPosition(), radius);
        require(election.urn.isEmpty() || contains(room, election.urn.get().dimension(), Vec3.atCenterOf(election.urn.get().pos())),
                "Die bestehende Urne muss im neuen Wahlraum liegen.");
        election.room = Optional.of(room);
        changed(source, election, "room", "Radius=" + radius);
    }

    public static void aimedUrn(CommandSourceStack source, String id) throws CommandSyntaxException {
        requireAdmin(source);
        ServerPlayer player = source.getPlayerOrException();
        HitResult hit = player.pick(URN_DISTANCE, 1, false);
        require(hit instanceof BlockHitResult && hit.getType() == HitResult.Type.BLOCK,
                "Schaue auf ein leeres Fass in höchstens 6 Blöcken Entfernung.");
        urn(source, id, ((BlockHitResult) hit).getBlockPos());
    }

    /** Explicit position variant for integrations/tests; performs the same administrator/build/site checks. */
    public static void urn(CommandSourceStack source, String id, BlockPos pos) throws CommandSyntaxException {
        requireAdmin(source);
        Election election = find(source, id);
        requireDraft(election);
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = player.level();
        require(player.position().distanceToSqr(Vec3.atCenterOf(pos)) <= URN_DISTANCE * URN_DISTANCE,
                "Die Urne muss höchstens 6 Blöcke entfernt sein.");
        require(level.hasChunkAt(pos) && level.getBlockState(pos).is(Blocks.BARREL)
                        && level.getBlockEntity(pos) instanceof BarrelBlockEntity barrel && barrel.isEmpty(),
                "Die Urne muss ein leeres, geladenes Fass sein.");
        require(election.room.isPresent() && contains(election.room.get(), dimension(level), Vec3.atCenterOf(pos)),
                "Lege zuerst einen Wahlraum fest; die Urne muss darin liegen.");
        require(!protectedAt(level, pos) && !ShopService.protectedAt(level, pos),
                "Dieses Fass ist bereits als Urne oder Laden reserviert.");
        require(Protection.mayBuildAt(player, level, pos), "Du darfst hier keine Urne einrichten.");
        election.urn = Optional.of(new Urn(dimension(level), pos));
        ElectionStorage.get(source.getServer()).changedUrn();
        // A player may already be viewing the empty barrel when it becomes a civic marker.
        // Closing the existing window immediately also rejects queued clicks carrying its old window ID.
        for (ServerPlayer viewer : source.getServer().getPlayerList().getPlayers()) {
            if (viewer.containerMenu instanceof ChestMenu menu
                    && menu.getContainer() == level.getBlockEntity(pos)) viewer.closeContainer();
        }
        audit(source, election, "urn", "Urne eingerichtet");
    }

    public static void helper(CommandSourceStack source, String id, Collection<NameAndId> players, boolean add) {
        requireAdmin(source);
        Election election = find(source, id);
        require(election.state == State.DRAFT || election.state == State.OPEN || election.state == State.CLOSED,
                "Wahlhelfer können nur vor der Veröffentlichung geändert werden.");
        long newHelpers = players.stream().map(NameAndId::id).distinct().filter(uuid -> !election.helpers.containsKey(uuid)).count();
        require(!add || election.helpers.size() + newHelpers <= MAX_HELPERS, "Höchstens " + MAX_HELPERS + " Wahlhelfer.");
        require(add || players.stream().allMatch(p -> election.helpers.containsKey(p.id())), "Ein Spieler ist kein Wahlhelfer dieser Wahl.");
        for (NameAndId player : players) {
            if (add) election.helpers.put(player.id(), player.name());
            else election.helpers.remove(player.id());
        }
        changed(source, election, add ? "helper.add" : "helper.remove", "Helfer=" + election.helpers.size());
    }

    public static void open(CommandSourceStack source, String id) {
        requireAdmin(source);
        Election election = find(source, id);
        requireDraft(election);
        require(!election.candidates.isEmpty() && election.room.isPresent() && election.urn.isPresent(),
                "Kandidaten, Wahlraum und Urne müssen eingerichtet sein.");
        Urn urn = election.urn.orElseThrow();
        ServerLevel level = null;
        for (ServerLevel candidate : source.getServer().getAllLevels())
            if (dimension(candidate).equals(urn.dimension())) { level = candidate; break; }
        require(level != null && level.hasChunkAt(urn.pos()) && level.getBlockState(urn.pos()).is(Blocks.BARREL)
                        && level.getBlockEntity(urn.pos()) instanceof BarrelBlockEntity barrel && barrel.isEmpty(),
                "Die eingerichtete Urne muss ein leeres, geladenes Fass sein.");
        election.state = State.OPEN;
        changed(source, election, "open", "Geöffnet");
    }

    public static void close(CommandSourceStack source, String id) {
        requireAdmin(source);
        Election election = find(source, id);
        require(election.state == State.OPEN, "Nur eine geöffnete Wahl kann geschlossen werden.");
        election.state = State.CLOSED;
        election.tokens.clear();
        changed(source, election, "close", "Archivierte Bücher=" + election.ballotCount());
    }

    public static void cancel(CommandSourceStack source, String id) {
        requireAdmin(source);
        Election election = find(source, id);
        require(election.state == State.DRAFT || election.state == State.OPEN || election.state == State.CLOSED,
                "Diese Wahl kann nicht mehr abgebrochen werden.");
        election.state = State.CANCELLED;
        election.tokens.clear();
        changed(source, election, "cancel", "Archivierte Bücher=" + election.ballotCount());
    }

    /** Release the marker without deleting ballots, eligibility or the published result. */
    public static void releaseUrn(CommandSourceStack source, String id) {
        requireAdmin(source);
        Election election = find(source, id);
        require(election.state != State.OPEN, "Schließe oder beende zuerst die geöffnete Wahl.");
        require(election.urn.isPresent(), "Diese Wahl hat keine reservierte Urne.");
        election.urn = Optional.empty();
        ElectionStorage.get(source.getServer()).changedUrn();
        audit(source, election, "urn.release", "Fass freigegeben; Archiv bleibt erhalten");
    }

    /** Register only after a guaranteed slot is available; a failed replacement leaves the previous token valid. */
    public static ItemStack issue(ServerPlayer player, String id) {
        Election election = find(player.createCommandSourceStack(), id);
        requireOpenRoom(player, election);
        require(!election.voters.contains(player.getUUID()), "Du hast bei dieser Wahl bereits abgestimmt.");
        require(election.tokens.containsKey(player.getUUID()) || election.tokens.size() + election.voters.size() < MAX_VOTERS,
                "Die Grenze für Wahlberechtigte ist erreicht.");
        int slot = player.getInventory().getFreeSlot();
        require(slot >= 0, "Dein Inventar ist voll. Schaffe einen freien Platz für das Buch.");
        UUID token;
        do { token = UUID.randomUUID(); } while (election.tokens.containsValue(token));
        ItemStack book = new ItemStack(Items.WRITABLE_BOOK);
        CompoundTag tag = new CompoundTag();
        tag.putString(ELECTION_TAG, id);
        tag.putString(TOKEN_TAG, token.toString());
        CustomData.set(DataComponents.CUSTOM_DATA, book, tag);
        book.set(DataComponents.CUSTOM_NAME, Component.literal("Stimmzettel: " + election.title()));
        book.set(DataComponents.WRITABLE_BOOK_CONTENT, WritableBookContent.EMPTY);
        player.getInventory().setItem(slot, book);
        election.tokens.put(player.getUUID(), token);
        ElectionStorage.get(player.level().getServer()).setDirty();
        player.inventoryMenu.broadcastChanges();
        // Neither issuance nor submission is audited: no voter-to-book timing trail.
        return book;
    }

    public static void submit(ServerPlayer player, String id) {
        Election election = find(player.createCommandSourceStack(), id);
        ItemStack book = player.getMainHandItem();
        checkSubmission(player, election, book);
        WritableBookContent pages = pages(book).orElseThrow();
        // Random insertion hides chronological voter order; helpers cannot read until the order is frozen at close.
        election.archive.add(RANDOM.nextInt(election.archive.size() + 1), new Ballot(pages));
        election.voters.add(player.getUUID());
        election.tokens.remove(player.getUUID());
        book.shrink(1);
        ElectionStorage.get(player.level().getServer()).setDirty();
        player.inventoryMenu.broadcastChanges();
    }

    private static void checkSubmission(ServerPlayer player, Election election, ItemStack book) {
        requireOpenRoom(player, election);
        require(!election.voters.contains(player.getUUID()), "Du hast bei dieser Wahl bereits abgestimmt.");
        Urn urn = election.urn.orElseThrow(() -> new ElectionException("Diese Wahl hat keine Urne."));
        require(dimension(player.level()).equals(urn.dimension())
                        && player.position().distanceToSqr(Vec3.atCenterOf(urn.pos())) <= URN_DISTANCE * URN_DISTANCE,
                "Gehe zur Urne (höchstens 6 Blöcke Entfernung).");
        require(player.level().hasChunkAt(urn.pos()) && player.level().getBlockState(urn.pos()).is(Blocks.BARREL),
                "Die eingerichtete Urne ist nicht verfügbar.");
        require(book.getCount() == 1 && (book.is(Items.WRITABLE_BOOK) || book.is(Items.WRITTEN_BOOK)),
                "Halte deinen registrierten Stimmzettel einzeln in der Haupthand.");
        CustomData data = book.get(DataComponents.CUSTOM_DATA);
        require(data != null, "Dieses Buch ist kein registrierter Stimmzettel.");
        CompoundTag tag = data.copyTag();
        UUID registered = election.tokens.get(player.getUUID());
        require(registered != null && tag.getString(ELECTION_TAG).filter(election.id()::equals).isPresent()
                        && tag.getString(TOKEN_TAG).filter(registered.toString()::equals).isPresent(),
                "Dieser Stimmzettel ist ungültig, ersetzt oder gehört zu einem anderen Spieler/einer anderen Wahl.");
        require(pages(book).filter(ElectionService::validPages).isPresent(), "Dieses Buch enthält ungültige oder zu große Seiten.");
    }

    private static Optional<WritableBookContent> pages(ItemStack book) {
        if (book.is(Items.WRITABLE_BOOK)) return Optional.ofNullable(book.get(DataComponents.WRITABLE_BOOK_CONTENT));
        WrittenBookContent content = book.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (content == null) return Optional.empty();
        return Optional.of(new WritableBookContent(content.pages().stream().map(p -> p.map(Component::getString)).toList()));
    }

    static boolean validPages(WritableBookContent content) {
        return content.pages().size() <= WritableBookContent.MAX_PAGES && content.pages().stream()
                .allMatch(p -> p.raw().length() <= WritableBookContent.PAGE_EDIT_LENGTH
                        && p.filtered().map(s -> s.length() <= WritableBookContent.PAGE_EDIT_LENGTH).orElse(true));
    }

    public static boolean mayCount(ServerPlayer player, Election election) {
        return election.helpers.containsKey(player.getUUID()) || RoleService.hasPermission(player, "election.count");
    }

    public static boolean mayCount(CommandSourceStack source, Election election) {
        return source.getPlayer() == null ? CityAccess.isAdmin(source) : mayCount(source.getPlayer(), election);
    }

    public static ItemStack countBook(CommandSourceStack source, String id, int index) throws CommandSyntaxException {
        Election election = find(source, id);
        require(mayCount(source, election), "Du bist kein Wahlhelfer dieser Wahl und hast kein Auszählrecht.");
        require(election.state == State.CLOSED || election.state == State.PUBLISHED,
                "Die Bücher sind erst nach Wahlschluss für Wahlhelfer lesbar.");
        require(index >= 1 && index <= election.archive.size(), "Buchnummer: 1 bis " + election.archive.size() + ".");
        ServerPlayer player = source.getPlayerOrException();
        int slot = player.getInventory().getFreeSlot();
        require(slot >= 0, "Dein Inventar ist voll. Schaffe einen freien Platz für das Buch.");
        ItemStack book = anonymousBook(election.archive.get(index - 1), index);
        player.getInventory().setItem(slot, book);
        player.inventoryMenu.broadcastChanges();
        return book;
    }

    /** Fresh vanilla stack: no submitted author/title, custom data, lore, UUID, token, click or hover events. */
    public static ItemStack anonymousBook(Ballot ballot, int index) {
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.remove(DataComponents.LORE);
        book.remove(DataComponents.CUSTOM_DATA);
        book.remove(DataComponents.WRITABLE_BOOK_CONTENT);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
                Filterable.passThrough("Stimmzettel " + index), "Wahlurne", 0,
                ballot.content().pages().stream().map(p -> p.map(s -> (Component) Component.literal(s))).toList(), true));
        return book;
    }

    public static void tally(CommandSourceStack source, String id, String candidate, int count) {
        Election election = counting(source, id, count);
        require(election.candidates.contains(candidate), "Dieser Kandidat ist nicht eingetragen.");
        election.totals.put(candidate, count);
        changed(source, election, "tally", "Kandidat=" + candidate + ", Anzahl=" + count);
    }

    public static void invalid(CommandSourceStack source, String id, int count) {
        Election election = counting(source, id, count);
        election.invalid = Optional.of(count);
        changed(source, election, "invalid", "Anzahl=" + count);
    }

    private static Election counting(CommandSourceStack source, String id, int count) {
        Election election = find(source, id);
        require(mayCount(source, election), "Du bist kein Wahlhelfer dieser Wahl und hast kein Auszählrecht.");
        require(election.state == State.CLOSED, "Zahlen können nur nach Wahlschluss und vor der Veröffentlichung geändert werden.");
        require(count >= 0 && count <= election.archive.size(), "Die Anzahl muss zwischen 0 und " + election.archive.size() + " liegen.");
        return election;
    }

    public static void publish(CommandSourceStack source, String id) {
        requireAdmin(source);
        Election election = find(source, id);
        require(election.state == State.CLOSED, "Nur eine geschlossene Wahl kann veröffentlicht werden.");
        require(election.complete(), "Trage alle Kandidatenzahlen und die ungültigen Stimmen ein: Summe "
                + election.counted() + " / " + election.archive.size() + " Bücher.");
        election.state = State.PUBLISHED;
        changed(source, election, "publish", "Stimmen=" + election.counted() + ", ungültig=" + election.invalid.orElse(0));
    }

    public static Election find(CommandSourceStack source, String id) {
        requireThread(source);
        return ElectionStorage.get(source.getServer()).election(id)
                .orElseThrow(() -> new ElectionException("Diese Wahl existiert nicht: " + id));
    }
    private static void requireOpenRoom(ServerPlayer player, Election election) {
        require(!Onboarding.isRestricted(player), "Bitte zuerst die Serverregeln akzeptieren.");
        require(TownhallMod.isOperator(player.permissions())
                        || !ReturnPositionStorage.get(player.level().getServer()).state(player.getUUID()).confined(),
                "Während der Haft sind Wahlaktionen gesperrt.");
        require(election.state == State.OPEN, "Diese Wahl ist nicht geöffnet.");
        require(election.room.isPresent() && contains(election.room.get(), dimension(player.level()), player.position()),
                "Du musst im festgelegten Wahlraum dieser Wahl sein.");
        require(!player.isSpectator(), "Zuschauer können keinen Stimmzettel erhalten oder abgeben.");
    }
    static boolean contains(Room room, String dimension, Vec3 position) {
        return room.dimension().equals(dimension) && position.distanceToSqr(Vec3.atCenterOf(room.center())) <= (double) room.radius() * room.radius();
    }
    static boolean validId(String id) { return CityAccess.validId(id); }
    static boolean validLabel(String value, int max) {
        return value != null && !value.isBlank() && value.equals(value.strip()) && value.length() <= max
                && value.chars().noneMatch(c -> Character.isISOControl(c) || c == '§');
    }
    private static String dimension(ServerLevel level) { return level.dimension().identifier().toString(); }
    private static void requireAdmin(CommandSourceStack source) {
        requireThread(source);
        require(CityAccess.isAdmin(source), "Nur Admins dürfen Wahlen einrichten und veröffentlichen.");
    }
    private static void requireThread(CommandSourceStack source) {
        if (!source.getServer().isSameThread()) throw new IllegalStateException("Election mutation outside server thread");
    }
    private static void requireDraft(Election election) { require(election.state == State.DRAFT, "Die Einrichtung ist nur vor dem Öffnen möglich."); }
    private static void changed(CommandSourceStack source, Election election, String action, String detail) {
        ElectionStorage.get(source.getServer()).setDirty();
        audit(source, election, action, detail);
    }
    private static void audit(CommandSourceStack source, Election election, String action, String detail) {
        AuditLog.record(source, "election." + action, "Wahl=" + election.id() + ", " + detail);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new ElectionException(message); }
    public static final class ElectionException extends RuntimeException {
        public ElectionException(String message) { super(message); }
    }
}
