package dev.townhall.test;

import com.mojang.brigadier.CommandDispatcher;
import dev.townhall.TownhallMod;
import dev.townhall.audit.AuditStorage;
import dev.townhall.city.RoleStorage;
import dev.townhall.election.ElectionService;
import dev.townhall.election.ElectionStorage;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Synchronous, isolated fixtures: no global event registration, file IO or asynchronous state restoration. */
public class ElectionGameTests {
    private static final class Fixture implements AutoCloseable {
        final GameTestHelper h;
        final MinecraftServer server;
        final ElectionStorage previous;
        final AuditStorage previousAudit;
        final boolean onboarding;
        final ServerPlayer admin;
        final NameAndId adminProfile;
        final CommandSourceStack console;
        final CommandDispatcher<CommandSourceStack> commands = new CommandDispatcher<>();
        final String id = "e" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        final BlockPos urn;
        final BlockState previousBlock;
        final Vec3 inside;

        Fixture(GameTestHelper h) throws Exception {
            this.h = h;
            server = h.getLevel().getServer();
            previous = ElectionStorage.get(server);
            previousAudit = AuditStorage.get(server);
            server.getDataStorage().set(ElectionStorage.TYPE, new ElectionStorage());
            server.getDataStorage().set(AuditStorage.TYPE, new AuditStorage());
            onboarding = TownhallMod.CONFIG.get().onboarding.enabled;
            TownhallMod.CONFIG.get().onboarding.enabled = false;
            inside = h.absoluteVec(new Vec3(1.5, 1, 1.5));
            admin = player();
            adminProfile = new NameAndId(admin.getGameProfile());
            server.getPlayerList().op(adminProfile, Optional.of(LevelBasedPermissionSet.GAMEMASTER), Optional.empty());
            console = server.createCommandSourceStack();
            urn = h.absolutePos(new BlockPos(3, 2, 1));
            previousBlock = h.getLevel().getBlockState(urn);
            h.getLevel().setBlockAndUpdate(urn, Blocks.BARREL.defaultBlockState());
            try {
                ElectionService.register(commands);
                ElectionService.create(console, id, "Buchwahl");
                ElectionService.candidate(console, id, "Ada", true);
                ElectionService.candidate(console, id, "Ben", true);
                ElectionService.room(admin.createCommandSourceStack(), id, 4);
                ElectionService.urn(admin.createCommandSourceStack(), id, urn);
                ElectionService.open(console, id);
            } catch (Exception | AssertionError ex) { close(); throw ex; }
        }
        ServerPlayer player() {
            ServerPlayer p = h.makeMockServerPlayerInLevel();
            p.absSnapTo(inside.x, inside.y, inside.z, -90, 0);
            p.getInventory().clearContent();
            return p;
        }
        ElectionStorage.Election election() { return ElectionStorage.get(server).election(id).orElseThrow(); }
        ItemStack issued(ServerPlayer p, String text) {
            ItemStack book = ElectionService.issue(p, id);
            book.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(List.of(Filterable.passThrough(text))));
            p.setItemInHand(InteractionHand.MAIN_HAND, book);
            return book;
        }
        int command(CommandSourceStack source, String command) throws Exception { return commands.execute(command, source); }
        void roundtrip() {
            ElectionStorage storage = ElectionStorage.get(server);
            Tag encoded = ElectionStorage.CODEC.encodeStart(NbtOps.INSTANCE, storage).getOrThrow();
            server.getDataStorage().set(ElectionStorage.TYPE, ElectionStorage.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow());
        }
        @Override public void close() {
            server.getPlayerList().deop(adminProfile);
            h.getLevel().setBlockAndUpdate(urn, previousBlock);
            server.getDataStorage().set(ElectionStorage.TYPE, previous);
            server.getDataStorage().set(AuditStorage.TYPE, previousAudit);
            TownhallMod.CONFIG.get().onboarding.enabled = onboarding;
        }
    }
    private static void rejected(GameTestHelper h, String expected, Runnable action) {
        try { action.run(); throw new AssertionError("Expected rejection: " + expected); }
        catch (ElectionService.ElectionException ex) {
            h.assertTrue(ex.getMessage().contains(expected), "Specific rejection: " + expected + "; got " + ex.getMessage());
        }
    }

    @GameTest
    public void forgedAndForeignBooksCannotConsumeVote(GameTestHelper h) throws Exception {
        try (Fixture f = new Fixture(h)) {
            ServerPlayer voter = f.player();
            ItemStack genuine = f.issued(voter, "freier Kandidatentext").copy();
            ItemStack forged = genuine.copy();
            CustomData.update(DataComponents.CUSTOM_DATA, forged, tag -> tag.putString("townhall_ballot_token", UUID.randomUUID().toString()));
            voter.setItemInHand(InteractionHand.MAIN_HAND, forged);
            rejected(h, "ungültig", () -> ElectionService.submit(voter, f.id));
            voter.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WRITABLE_BOOK));
            rejected(h, "registrierter", () -> ElectionService.submit(voter, f.id));
            ServerPlayer other = f.player();
            other.setItemInHand(InteractionHand.MAIN_HAND, genuine.copy());
            rejected(h, "ungültig", () -> ElectionService.submit(other, f.id));
            h.assertTrue(f.election().ballotCount() == 0 && !f.election().voters().contains(voter.getUUID()), "Failed books change no eligibility/archive");
            voter.setItemInHand(InteractionHand.MAIN_HAND, genuine);
            ElectionService.submit(voter, f.id);
            h.assertTrue(voter.getMainHandItem().isEmpty() && f.election().ballotCount() == 1, "Positive control: genuine book consumed and archived");
            h.assertTrue(f.election().archive().getFirst().content().pages().getFirst().raw().equals("freier Kandidatentext"), "Free text is preserved without interpreting a candidate");
        }
        h.succeed();
    }

    @GameTest
    public void copiedBookAndSecondIssueRejectedAfterVote(GameTestHelper h) throws Exception {
        try (Fixture f = new Fixture(h)) {
            ServerPlayer p = f.player();
            ItemStack copy = f.issued(p, "Ada").copy();
            ElectionService.submit(p, f.id);
            p.setItemInHand(InteractionHand.MAIN_HAND, copy);
            rejected(h, "bereits", () -> ElectionService.submit(p, f.id));
            rejected(h, "bereits", () -> ElectionService.issue(p, f.id));
            h.assertTrue(f.election().ballotCount() == 1 && copy.getCount() == 1, "Copied book cannot add a second vote or be consumed");
            ServerPlayer q = f.player();
            f.issued(q, "Ben");
            ElectionService.submit(q, f.id);
            h.assertTrue(f.election().ballotCount() == 2, "Positive control: distinct UUID can submit");
        }
        h.succeed();
    }

    @GameTest
    public void fullInventoryDoesNotRegisterOrInvalidateToken(GameTestHelper h) throws Exception {
        try (Fixture f = new Fixture(h)) {
            ServerPlayer p = f.player();
            for (int slot = 0; slot < 36; slot++) p.getInventory().setItem(slot, new ItemStack(Items.STONE, 64));
            rejected(h, "Inventar ist voll", () -> ElectionService.issue(p, f.id));
            h.assertTrue(!f.election().tokens().containsKey(p.getUUID()) && !f.election().voters().contains(p.getUUID()), "Full inventory does not consume eligibility");
            p.getInventory().setItem(0, ItemStack.EMPTY);
            ItemStack original = f.issued(p, "Ada").copy();
            UUID token = f.election().tokens().get(p.getUUID());
            for (int slot = 0; slot < 36; slot++) p.getInventory().setItem(slot, new ItemStack(Items.STONE, 64));
            rejected(h, "Inventar ist voll", () -> ElectionService.issue(p, f.id));
            h.assertTrue(token.equals(f.election().tokens().get(p.getUUID())), "Failed replacement retains old token");
            p.setItemInHand(InteractionHand.MAIN_HAND, original);
            ElectionService.submit(p, f.id);
            h.assertTrue(f.election().ballotCount() == 1, "Positive control: previous token still usable");
        }
        h.succeed();
    }

    @GameTest
    public void replacementInvalidatesEarlierCopies(GameTestHelper h) throws Exception {
        try (Fixture f = new Fixture(h)) {
            ServerPlayer p = f.player();
            ItemStack old = f.issued(p, "Ada").copy();
            UUID token = f.election().tokens().get(p.getUUID());
            ItemStack replacement = f.issued(p, "Ben").copy();
            h.assertTrue(!token.equals(f.election().tokens().get(p.getUUID())) && f.election().tokens().size() == 1, "One replacement token per UUID");
            p.setItemInHand(InteractionHand.MAIN_HAND, old);
            rejected(h, "ersetzt", () -> ElectionService.submit(p, f.id));
            p.setItemInHand(InteractionHand.MAIN_HAND, replacement);
            ElectionService.submit(p, f.id);
            h.assertTrue(f.election().ballotCount() == 1 && f.election().archive().getFirst().content().pages().getFirst().raw().equals("Ben"), "Only current replacement submitted");
        }
        h.succeed();
    }

    @GameTest
    public void codecRestoresTokensVotersPagesAndUrnIndex(GameTestHelper h) throws Exception {
        try (Fixture f = new Fixture(h)) {
            ServerPlayer p = f.player();
            ServerPlayer helper = f.player();
            ElectionService.helper(f.console, f.id, List.of(new NameAndId(helper.getGameProfile())), true);
            ItemStack original = f.issued(p, "Seite 1");
            original.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(List.of(
                    new Filterable<>("Seite 1", Optional.of("gefiltert")), Filterable.passThrough("Seite 2"))));
            ItemStack copy = original.copy();
            UUID token = f.election().tokens().get(p.getUUID());
            f.roundtrip();
            h.assertTrue(token.equals(f.election().tokens().get(p.getUUID())) && f.election().helpers().containsKey(helper.getUUID()), "UUID token and helpers survive native NBT codec");
            h.assertTrue(ElectionService.protectedAt(h.getLevel(), f.urn) && ElectionService.canUse(p, h.getLevel(), f.urn), "Urn index restored; persisted token still valid");
            ElectionService.submit(p, f.id);
            f.roundtrip();
            h.assertTrue(f.election().voters().contains(p.getUUID()) && f.election().tokens().isEmpty(), "Persisted voter prevents replay after restart");
            var pages = f.election().archive().getFirst().content().pages();
            h.assertTrue(pages.size() == 2 && pages.getFirst().filtered().orElseThrow().equals("gefiltert") && pages.get(1).raw().equals("Seite 2"), "All submitted page variants preserved");
            p.setItemInHand(InteractionHand.MAIN_HAND, copy);
            rejected(h, "bereits", () -> ElectionService.submit(p, f.id));
            ElectionService.close(f.console, f.id);
            ElectionService.tally(helper.createCommandSourceStack(), f.id, "Ada", 1);
            ElectionService.tally(helper.createCommandSourceStack(), f.id, "Ben", 0);
            ElectionService.invalid(helper.createCommandSourceStack(), f.id, 0);
            ElectionService.publish(f.console, f.id);
            f.roundtrip();
            h.assertTrue(f.election().state() == ElectionStorage.State.PUBLISHED && f.election().complete(), "Published manual totals survive codec");
        }
        h.succeed();
    }

    @GameTest
    public void roomDimensionAndUrnDistanceEnforced(GameTestHelper h) throws Exception {
        try (Fixture f = new Fixture(h)) {
            ServerPlayer p = f.player();
            ItemStack original = f.issued(p, "Ada").copy();
            p.absSnapTo(f.inside.x - 5, f.inside.y, f.inside.z, 0, 0);
            rejected(h, "Wahlraum", () -> ElectionService.issue(p, f.id));
            rejected(h, "Wahlraum", () -> ElectionService.submit(p, f.id));
            p.teleportTo(f.server.getLevel(Level.NETHER), f.inside.x, f.inside.y, f.inside.z, Set.of(), 0, 0, true);
            rejected(h, "Wahlraum", () -> ElectionService.issue(p, f.id));
            rejected(h, "Wahlraum", () -> ElectionService.submit(p, f.id));
            p.teleportTo(h.getLevel(), f.inside.x, f.inside.y, f.inside.z, Set.of(), 0, 0, true);
            p.setItemInHand(InteractionHand.MAIN_HAND, original);
            ElectionService.submit(p, f.id);
            h.assertTrue(f.election().ballotCount() == 1, "Positive control: same book valid again inside room/dimension");
            String id = "d" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            ElectionService.create(f.console, id, "Distanz");
            ElectionService.candidate(f.console, id, "Ada", true);
            ElectionService.room(f.admin.createCommandSourceStack(), id, 32);
            BlockPos second = f.urn.offset(0, 0, 2);
            BlockState beforeSecond = h.getLevel().getBlockState(second);
            h.getLevel().setBlockAndUpdate(second, Blocks.BARREL.defaultBlockState());
            ElectionService.urn(f.admin.createCommandSourceStack(), id, second);
            ElectionService.open(f.console, id);
            try {
                p.absSnapTo(f.inside.x - 12, f.inside.y, f.inside.z, 0, 0);
                ItemStack distant = ElectionService.issue(p, id);
                p.setItemInHand(InteractionHand.MAIN_HAND, distant);
                rejected(h, "höchstens 6", () -> ElectionService.submit(p, id));
                p.absSnapTo(f.inside.x, f.inside.y, f.inside.z, 0, 0);
                ElectionService.submit(p, id);
                h.assertTrue(ElectionStorage.get(f.server).election(id).orElseThrow().ballotCount() == 1, "Positive control: room alone insufficient, proximity allows submission");
            } finally { h.getLevel().setBlockAndUpdate(second, beforeSecond); }
        }
        h.succeed();
    }

    @GameTest
    public void manualTotalsRequireCompleteSumAndLockAfterPublication(GameTestHelper h) throws Exception {
        try (Fixture f = new Fixture(h)) {
            ServerPlayer voter = f.player();
            f.issued(voter, "kein bekannter Kandidat");
            ElectionService.submit(voter, f.id);
            h.assertTrue(f.election().totals().isEmpty() && f.election().invalid().isEmpty(), "No automatic tally, even unknown candidate text");
            rejected(h, "Wahlschluss", () -> ElectionService.tally(f.console, f.id, "Ada", 1));
            rejected(h, "geschlossene", () -> ElectionService.publish(f.console, f.id));
            ElectionService.close(f.console, f.id);
            rejected(h, "Summe", () -> ElectionService.publish(f.console, f.id));
            rejected(h, "zwischen", () -> ElectionService.tally(f.console, f.id, "Ada", -1));
            rejected(h, "zwischen", () -> ElectionService.invalid(f.console, f.id, Integer.MAX_VALUE));
            ElectionService.tally(f.console, f.id, "Ada", 1);
            ElectionService.invalid(f.console, f.id, 0);
            rejected(h, "Summe", () -> ElectionService.publish(f.console, f.id)); // Ben must explicitly be zero.
            ElectionService.tally(f.console, f.id, "Ben", 1);
            rejected(h, "Summe", () -> ElectionService.publish(f.console, f.id));
            ElectionService.tally(f.console, f.id, "Ben", 0);
            ElectionService.publish(f.console, f.id);
            h.assertTrue(f.election().complete() && f.election().counted() == 1, "Positive control: fully entered matching manual sum publishes");
            rejected(h, "Veröffentlichung", () -> ElectionService.invalid(f.console, f.id, 1));
            rejected(h, "Veröffentlichung", () -> ElectionService.tally(f.console, f.id, "Ada", 0));
            rejected(h, "geschlossene", () -> ElectionService.publish(f.console, f.id));
            h.assertTrue(RoleStorage.get(f.server).memberships(voter.getUUID()).isEmpty(), "Publication grants no role");
            h.assertTrue(f.command(voter.createCommandSourceStack(), "wahl info " + f.id) == 1, "Published results readable by ordinary players");
        }
        h.succeed();
    }

    @GameTest
    public void helpersCapabilitiesAndSignEscalation(GameTestHelper h) throws Exception {
        try (Fixture f = new Fixture(h)) {
            ServerPlayer helper = f.player();
            ServerPlayer voter = f.player();
            f.issued(voter, "Ada");
            ElectionService.submit(voter, f.id);
            rejected(h, "Wahlhelfer", () -> ElectionService.tally(helper.createCommandSourceStack(), f.id, "Ada", 1));
            String offlineName = "Count" + UUID.randomUUID().toString().replace("-", "").substring(0, 9);
            f.server.services().nameToIdCache().add(new NameAndId(helper.getUUID(), offlineName));
            h.assertTrue(f.command(f.console, "wahl helper add " + f.id + " " + offlineName) == 1, "GameProfileArgument resolves offline UUID");
            h.assertTrue(f.election().helpers().containsKey(helper.getUUID()), "Helper stored by UUID");
            rejected(h, "Wahlschluss", () -> ElectionService.tally(helper.createCommandSourceStack(), f.id, "Ada", 1));
            ElectionService.close(f.console, f.id);
            ElectionService.tally(helper.createCommandSourceStack(), f.id, "Ada", 1);
            ElectionService.countBook(helper.createCommandSourceStack(), f.id, 1);
            h.assertTrue(f.command(f.console, "wahl helper remove " + f.id + " " + offlineName) == 1, "Offline helper removal resolves same UUID");
            CommandSourceStack elevated = helper.createCommandSourceStack().withPermission(LevelBasedPermissionSet.GAMEMASTER);
            rejected(h, "Wahlhelfer", () -> ElectionService.tally(elevated, f.id, "Ada", 0));
            String forbidden = "s" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            rejected(h, "Nur Admins", () -> ElectionService.create(elevated, forbidden, "Sign escalation"));
            try { f.command(elevated, "wahl admin create " + forbidden + " Sign"); }
            catch (com.mojang.brigadier.exceptions.CommandSyntaxException expected) { /* hidden admin root */ }
            h.assertTrue(ElectionStorage.get(f.server).election(forbidden).isEmpty(), "Elevated sign source cannot create election");
            RoleStorage roles = RoleStorage.get(f.server);
            try {
                roles.grant(helper.getUUID(), offlineName, "wahlhelfer");
                h.assertTrue(ElectionService.mayCount(helper, f.election()), "Positive control: parent's election.count capability");
                ElectionService.invalid(elevated, f.id, 0);
            } finally { roles.revoke(helper.getUUID(), "wahlhelfer"); }
            rejected(h, "Wahlhelfer", () -> ElectionService.invalid(elevated, f.id, 0));
        }
        h.succeed();
    }

    @GameTest
    public void signedBooksAreFrozenWithoutMetadataOrClickEvents(GameTestHelper h) throws Exception {
        try (Fixture f = new Fixture(h)) {
            ServerPlayer p = f.player();
            ItemStack writable = f.issued(p, "Ada");
            ItemStack signed = writable.transmuteCopy(Items.WRITTEN_BOOK);
            signed.remove(DataComponents.WRITABLE_BOOK_CONTENT); // Same conversion as vanilla signBook.
            signed.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough("Secret title"),
                    "Original author", 0, List.of(new Filterable<Component>(
                    Component.literal("Ada").withStyle(s -> s.withClickEvent(new ClickEvent.RunCommand("/op someone"))),
                    Optional.of(Component.literal("filtered")))), false));
            signed.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(p.getUUID().toString()))));
            p.setItemInHand(InteractionHand.MAIN_HAND, signed);
            ElectionService.submit(p, f.id);
            // Mutating the source stack cannot mutate the authoritative frozen pages.
            signed.set(DataComponents.WRITTEN_BOOK_CONTENT, WrittenBookContent.EMPTY);
            ElectionService.close(f.console, f.id);
            ItemStack book = ElectionService.countBook(f.admin.createCommandSourceStack(), f.id, 1);
            WrittenBookContent content = book.get(DataComponents.WRITTEN_BOOK_CONTENT);
            h.assertTrue(book.is(Items.WRITTEN_BOOK) && !book.has(DataComponents.WRITABLE_BOOK_CONTENT)
                    && !book.has(DataComponents.CUSTOM_DATA) && !book.has(DataComponents.LORE), "Read-only fresh stack has no submitted components/token/UUID");
            h.assertTrue(content.author().equals("Wahlurne") && content.resolved() && !content.title().raw().equals("Secret title"), "Author/title anonymized and resolution disabled");
            h.assertTrue(content.pages().getFirst().raw().getString().equals("Ada")
                    && content.pages().getFirst().raw().getStyle().getClickEvent() == null
                    && content.pages().getFirst().filtered().orElseThrow().getString().equals("filtered"), "Text/filtered text preserved without executable components");
            String archive = ElectionStorage.Ballot.CODEC.encodeStart(NbtOps.INSTANCE, f.election().archive().getFirst()).getOrThrow().toString();
            h.assertTrue(!archive.contains(p.getUUID().toString()) && !archive.contains("Original author")
                    && !archive.contains("townhall_ballot_token"), "Archived ballot has no metadata association");
            h.assertTrue(f.election().archive().getFirst().content().pages().getFirst().raw().equals("Ada"), "Positive control: original submitted pages remain frozen");
        }
        h.succeed();
    }

    @GameTest
    public void urnRightClickAndVanillaMenuGuard(GameTestHelper h) throws Exception {
        try (Fixture f = new Fixture(h)) {
            ServerPlayer p = f.player();
            f.issued(p, "Ada");
            h.assertTrue(ElectionService.canUse(p, h.getLevel(), f.urn), "Registered voter may reach submission handler");
            BarrelBlockEntity barrel = (BarrelBlockEntity) h.getLevel().getBlockEntity(f.urn);
            h.assertTrue(!barrel.canOpen(p) && !barrel.canOpen(f.admin), "ElectionUrnContainerMixin blocks vanilla editable menus for voters and admins");
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(f.urn), Direction.UP, f.urn, false);
            InteractionResult result = UseBlockCallback.EVENT.invoker().interact(p, h.getLevel(), InteractionHand.MAIN_HAND, hit);
            h.assertTrue(result == InteractionResult.SUCCESS && f.election().ballotCount() == 1 && p.getMainHandItem().isEmpty(), "Registered event accepts right-click ballot exactly once");
            h.assertTrue(!ElectionService.canUse(p, h.getLevel(), f.urn) && barrel.isEmpty(), "Voter cannot reopen urn; physical inventory contains no voter mapping");
            h.assertTrue(ElectionService.blocksAutomation(h.getLevel(), f.urn), "Urn blocks hopper automation");
            BlockPos plain = f.urn.offset(0, 0, 2);
            BlockState before = h.getLevel().getBlockState(plain);
            try {
                h.getLevel().setBlockAndUpdate(plain, Blocks.BARREL.defaultBlockState());
                h.assertTrue(((BarrelBlockEntity) h.getLevel().getBlockEntity(plain)).canOpen(p)
                        && !ElectionService.blocksAutomation(h.getLevel(), plain), "Positive control: ordinary barrel retains vanilla access");
            } finally { h.getLevel().setBlockAndUpdate(plain, before); }
            ElectionService.close(f.console, f.id);
            h.assertTrue(!barrel.canOpen(f.admin) && ElectionService.protectedAt(h.getLevel(), f.urn), "Closed urn remains reserved/protected");
        }
        h.succeed();
    }

    @GameTest
    public void lifecycleSetupAndCancellationGuards(GameTestHelper h) throws Exception {
        try (Fixture f = new Fixture(h)) {
            rejected(h, "Einrichtung", () -> ElectionService.candidate(f.console, f.id, "Extra", true));
            rejected(h, "Einrichtung", () -> ElectionService.open(f.console, f.id));
            ServerPlayer p = f.player();
            ItemStack book = f.issued(p, "Ada").copy();
            String other = "o" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            ElectionService.create(f.console, other, "Zweite Wahl");
            ElectionService.room(f.admin.createCommandSourceStack(), other, 4);
            try { ElectionService.urn(f.admin.createCommandSourceStack(), other, f.urn); throw new AssertionError("Overlapping urn accepted"); }
            catch (ElectionService.ElectionException ex) { h.assertTrue(ex.getMessage().contains("reserviert"), "Same physical barrel cannot serve two elections"); }
            ElectionService.submit(p, f.id);
            ServerPlayer q = f.player();
            f.issued(q, "Ben");
            ElectionService.cancel(f.console, f.id);
            h.assertTrue(f.election().archive().size() == 1 && f.election().tokens().isEmpty(), "Cancel preserves archive and invalidates outstanding tokens");
            p.setItemInHand(InteractionHand.MAIN_HAND, book);
            rejected(h, "nicht geöffnet", () -> ElectionService.submit(p, f.id));
            rejected(h, "nicht geöffnet", () -> ElectionService.issue(q, f.id));
            rejected(h, "Einrichtung", () -> ElectionService.open(f.console, f.id));
            rejected(h, "Wahlschluss", () -> ElectionService.tally(f.console, f.id, "Ada", 1));
            h.assertTrue(ElectionService.protectedAt(h.getLevel(), f.urn), "Cancelled election remains protected");
            f.roundtrip();
            h.assertTrue(f.election().state() == ElectionStorage.State.CANCELLED && f.election().archive().size() == 1, "Cancelled state and original archive persist");
        }
        h.succeed();
    }

    @GameTest
    public void helperBookAccessIsElectionScopedAndRequiresCloseAndSpace(GameTestHelper h) throws Exception {
        try (Fixture f = new Fixture(h)) {
            ServerPlayer helper = f.player();
            ServerPlayer stranger = f.player();
            ElectionService.helper(f.console, f.id, List.of(new NameAndId(helper.getGameProfile())), true);
            f.issued(stranger, "Ada");
            ElectionService.submit(stranger, f.id);
            try { ElectionService.countBook(helper.createCommandSourceStack(), f.id, 1); throw new AssertionError("Open ballot archive readable"); }
            catch (ElectionService.ElectionException ex) { h.assertTrue(ex.getMessage().contains("Wahlschluss"), "No archive access during voting"); }
            ElectionService.close(f.console, f.id);
            try { ElectionService.countBook(stranger.createCommandSourceStack(), f.id, 1); throw new AssertionError("Non-helper read archive"); }
            catch (ElectionService.ElectionException ex) { h.assertTrue(ex.getMessage().contains("Wahlhelfer"), "No voter access to countbooks"); }
            for (int slot = 0; slot < 36; slot++) helper.getInventory().setItem(slot, new ItemStack(Items.STONE, 64));
            try { ElectionService.countBook(helper.createCommandSourceStack(), f.id, 1); throw new AssertionError("Countbook silently deleted by full inventory"); }
            catch (ElectionService.ElectionException ex) { h.assertTrue(ex.getMessage().contains("Inventar ist voll"), "Full helper inventory explicitly rejected"); }
            helper.getInventory().setItem(0, ItemStack.EMPTY);
            ElectionService.countBook(helper.createCommandSourceStack(), f.id, 1);
            h.assertTrue(helper.getInventory().getItem(0).is(Items.WRITTEN_BOOK) && f.election().ballotCount() == 1, "Positive control: helper receives physical book without altering archive");
            String other = "h" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            ElectionService.create(f.console, other, "Andere Wahl");
            h.assertTrue(!ElectionService.mayCount(helper, ElectionStorage.get(f.server).election(other).orElseThrow()), "Per-election helper has no rights in another election");
        }
        h.succeed();
    }

    @GameTest
    public void assigningUrnClosesExistingVanillaWindow(GameTestHelper h) throws Exception {
        try (Fixture f = new Fixture(h)) {
            String id = "m" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            ElectionService.create(f.console, id, "Menüschutz");
            ElectionService.room(f.admin.createCommandSourceStack(), id, 4);
            BlockPos pos = f.urn.offset(0, 0, 2);
            BlockState before = h.getLevel().getBlockState(pos);
            try {
                h.getLevel().setBlockAndUpdate(pos, Blocks.BARREL.defaultBlockState());
                ServerPlayer viewer = f.player();
                BarrelBlockEntity barrel = (BarrelBlockEntity) h.getLevel().getBlockEntity(pos);
                h.assertTrue(barrel.canOpen(viewer), "Positive control: empty unassigned barrel is usable");
                viewer.openMenu(barrel);
                h.assertTrue(viewer.containerMenu instanceof ChestMenu && viewer.containerMenu != viewer.inventoryMenu,
                        "Positive control: real vanilla window opened before assignment");
                ChestMenu oldMenu = (ChestMenu) viewer.containerMenu;
                ElectionService.urn(f.admin.createCommandSourceStack(), id, pos);
                h.assertTrue(viewer.containerMenu == viewer.inventoryMenu && !oldMenu.stillValid(viewer)
                        && !barrel.canOpen(viewer), "Assignment closes existing window and invalidates stale menu through ElectionUrnMenuMixin");
            } finally { h.getLevel().setBlockAndUpdate(pos, before); }
        }
        h.succeed();
    }

    @GameTest
    public void candidateAndPageLimitsHavePositiveControls(GameTestHelper h) throws Exception {
        try (Fixture f = new Fixture(h)) {
            String id = "l" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            h.assertTrue(f.command(f.console, "wahl admin create " + id + " Grenzen") == 1, "Explicit admin command registers");
            h.assertTrue(f.command(f.console, "wahl candidate add " + id + " \"Ada Beispiel\"") == 1, "String candidate supports full name");
            h.assertTrue(f.command(f.console, "wahl candidate remove " + id + " \"Ada Beispiel\"") == 1, "Full-name candidate removable");
            for (int i = 0; i < ElectionStorage.MAX_CANDIDATES; i++) ElectionService.candidate(f.console, id, "K" + i, true);
            rejected(h, "Höchstens", () -> ElectionService.candidate(f.console, id, "Extra", true));
            rejected(h, "bereits", () -> ElectionService.candidate(f.console, id, "K0", true));
            rejected(h, "Kandidaten, Wahlraum", () -> ElectionService.open(f.console, id));
            ServerPlayer p = f.player();
            ItemStack book = f.issued(p, "Ada");
            book.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(List.of(Filterable.passThrough("x".repeat(1025)))));
            rejected(h, "zu große", () -> ElectionService.submit(p, f.id));
            h.assertTrue(f.election().ballotCount() == 0 && f.election().tokens().containsKey(p.getUUID()), "Oversized pages preserve registration/eligibility");
            book.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(List.of(Filterable.passThrough("x".repeat(1024)))));
            ElectionService.submit(p, f.id);
            h.assertTrue(f.election().ballotCount() == 1, "Positive control: native page edit limit accepted");
            Tag encoded = ElectionStorage.CODEC.encodeStart(NbtOps.INSTANCE, ElectionStorage.get(f.server)).getOrThrow();
            CompoundTag malformed = ((CompoundTag) encoded).copy();
            malformed.getCompound(f.id).orElseThrow().putString("id", "different_key");
            h.assertTrue(ElectionStorage.CODEC.parse(NbtOps.INSTANCE, malformed).error().isPresent(), "Codec rejects inconsistent persisted election identity");
        }
        h.succeed();
    }

    @GameTest
    public void persistedTokenLimitStillAllowsReplacement(GameTestHelper h) throws Exception {
        try (Fixture f = new Fixture(h)) {
            ServerPlayer registered = f.player();
            ServerPlayer outsider = f.player();
            CompoundTag encoded = (CompoundTag) ElectionStorage.CODEC.encodeStart(NbtOps.INSTANCE, ElectionStorage.get(f.server)).getOrThrow();
            CompoundTag tokens = new CompoundTag();
            tokens.putString(registered.getUUID().toString(), UUID.randomUUID().toString());
            for (int i = 1; i < ElectionStorage.MAX_VOTERS; i++) tokens.putString(UUID.randomUUID().toString(), UUID.randomUUID().toString());
            encoded.getCompound(f.id).orElseThrow().put("tokens", tokens);
            f.server.getDataStorage().set(ElectionStorage.TYPE, ElectionStorage.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow());
            rejected(h, "Grenze", () -> ElectionService.issue(outsider, f.id));
            h.assertTrue(outsider.getInventory().isEmpty() && f.election().tokens().size() == ElectionStorage.MAX_VOTERS,
                    "Limit refuses unregistered UUID without creating book");
            ItemStack replacement = ElectionService.issue(registered, f.id);
            registered.setItemInHand(InteractionHand.MAIN_HAND, replacement);
            ElectionService.submit(registered, f.id);
            h.assertTrue(f.election().tokens().size() == ElectionStorage.MAX_VOTERS - 1 && f.election().voters().size() == 1,
                    "Positive control: replacement and vote permitted at full registry without increasing capacity");
        }
        h.succeed();
    }

    @GameTest
    public void ballotActionsNeverEnterAudit(GameTestHelper h) throws Exception {
        try (Fixture f = new Fixture(h)) {
            ServerPlayer p = f.player();
            int before = AuditStorage.get(f.server).size();
            ItemStack book = f.issued(p, "Secret voter choice " + UUID.randomUUID());
            String token = book.get(DataComponents.CUSTOM_DATA).copyTag().getString("townhall_ballot_token").orElseThrow();
            ElectionService.submit(p, f.id);
            h.assertTrue(AuditStorage.get(f.server).size() == before, "Issuance/submission have no voter/book audit trace");
            ElectionService.close(f.console, f.id);
            String audit = AuditStorage.CODEC.encodeStart(NbtOps.INSTANCE, AuditStorage.get(f.server)).getOrThrow().toString();
            h.assertTrue(!audit.contains(token) && !audit.contains("Secret voter choice") && !audit.contains(p.getUUID().toString()), "Aggregate audit contains neither ballot contents nor voter/token association");
            h.assertTrue(AuditStorage.get(f.server).size() > before, "Positive control: closing election is audited");
        }
        h.succeed();
    }
    @GameTest
    public void restrictedVoterAndUrnReleaseKeepArchive(GameTestHelper h) throws Exception {
        try (Fixture f = new Fixture(h)) {
            ServerPlayer voter = f.player();
            ItemStack book = f.issued(voter, "Ada");
            var states = dev.townhall.storage.ReturnPositionStorage.get(f.server);
            try {
                states.set(voter.getUUID(), dev.townhall.storage.PlayerState.EMPTY.withStay("gefaengnis", true, Optional.of(60000L)));
                rejected(h, "Haft", () -> ElectionService.submit(voter, f.id));
                h.assertTrue(ElectionService.onUseUrn(voter, h.getLevel(), InteractionHand.MAIN_HAND, f.urn) == InteractionResult.FAIL,
                        "right-click handler cannot bypass confinement");
                h.assertTrue(f.election().ballotCount() == 0 && book.getCount() == 1, "denied vote preserves ballot and eligibility");
                states.remove(voter.getUUID());
                ElectionService.submit(voter, f.id);
                rejected(h, "zuerst", () -> ElectionService.releaseUrn(f.console, f.id));
                ElectionService.close(f.console, f.id);
                ElectionService.releaseUrn(f.console, f.id);
                h.assertTrue(!ElectionService.protectedAt(h.getLevel(), f.urn), "closed urn marker released");
                f.roundtrip();
                h.assertTrue(f.election().ballotCount() == 1 && f.election().urn().isEmpty(), "ballot archive persists after marker release");
                h.assertTrue(ElectionService.countBook(f.admin.createCommandSourceStack(), f.id, 1).is(Items.WRITTEN_BOOK),
                        "helpers can still count archived books after marker release");
            } finally { states.remove(voter.getUUID()); }
        }
        h.succeed();
    }

}
