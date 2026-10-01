package dev.townhall.test;

import dev.townhall.TownhallMod;
import dev.townhall.city.RoleDefinition;
import dev.townhall.city.RoleStorage;
import dev.townhall.key.Keys;
import dev.townhall.onboarding.Onboarding;
import dev.townhall.shop.Shop;
import dev.townhall.shop.ShopCommands;
import dev.townhall.shop.ShopData;
import dev.townhall.shop.ShopService;
import dev.townhall.storage.PlayerState;
import dev.townhall.storage.ReturnPositionStorage;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Synchronous fixtures use unique IDs and restore config, role membership, SavedData and op status in finally. */
public class ShopGameTests {
    private static final class Fixture implements AutoCloseable {
        final GameTestHelper h;
        final String id = "shop_" + UUID.randomUUID().toString().replace("-", "");
        final String role = "st_" + UUID.randomUUID().toString().substring(0, 8);
        final BlockPos pos;
        final ServerPlayer owner;
        final ShopData data;
        final boolean onboarding, enabled, audit;
        final List<ServerPlayer> players = new ArrayList<>();
        final List<ServerPlayer> operators = new ArrayList<>();
        Fixture(GameTestHelper h) {
            this.h = h;
            var config = TownhallMod.CONFIG.get();
            onboarding = config.onboarding.enabled; enabled = config.city.shopsEnabled; audit = config.city.auditEnabled;
            config.onboarding.enabled = false; config.city.shopsEnabled = true; config.city.auditEnabled = false;
            pos = h.absolutePos(new BlockPos(3, 2, 3));
            h.getLevel().setBlockAndUpdate(pos, Blocks.BARREL.defaultBlockState());
            data = ShopData.get(h.getLevel().getServer());
            owner = player();
            RoleStorage.get(h.getLevel().getServer()).put(role, new RoleDefinition("Test", "", List.of("shop.manage"), 0));
            RoleStorage.get(h.getLevel().getServer()).grant(owner.getUUID(), owner.getGameProfile().name(), role);
            data.put(id, new Shop(owner.getUUID(), h.getLevel().dimension().identifier(), pos,
                new ItemStack(Items.APPLE), 2, 3, Shop.Currency.DIAMOND, 12, 0, 0));
            // Parent owns real command registration. Register only if a diagnostic test server omitted it.
            if (h.getLevel().getServer().getCommands().getDispatcher().getRoot().getChild("shop") == null)
                ShopCommands.register(h.getLevel().getServer().getCommands().getDispatcher());
        }
        ServerPlayer player() {
            ServerPlayer p = h.makeMockServerPlayerInLevel();
            Vec3 at = h.absoluteVec(new Vec3(3.5, 1, 5.5));
            p.absSnapTo(at.x, at.y, at.z, 180, 0);
            // Mock gameMode() always reports creative. Use the authoritative vanilla controller used by the service.
            p.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
            p.getInventory().clearContent();
            players.add(p);
            return p;
        }
        void op(ServerPlayer p) {
            h.getLevel().getServer().getPlayerList().op(new NameAndId(p.getGameProfile()), Optional.of(LevelBasedPermissionSet.GAMEMASTER), Optional.empty());
            operators.add(p);
        }
        Shop shop() { return data.shop(id); }
        void set(ItemStack template, int amount, int price, Shop.Currency currency, long stock, long diamonds, long emeralds) {
            data.put(id, new Shop(owner.getUUID(), h.getLevel().dimension().identifier(), pos, template, amount, price, currency, stock, diamonds, emeralds));
        }
        @Override public void close() {
            Shop current = shop();
            if (current != null) { data.put(id, current.quantities(0, 0, 0)); data.removeEmpty(id); }
            RoleStorage.get(h.getLevel().getServer()).remove(role);
            operators.forEach(p -> h.getLevel().getServer().getPlayerList().deop(new NameAndId(p.getGameProfile())));
            players.forEach(p -> {
                ReturnPositionStorage.get(h.getLevel().getServer()).remove(p.getUUID());
                dev.townhall.onboarding.OnboardingStorage.get(h.getLevel().getServer()).reset(p.getUUID());
                Onboarding.onDisconnect(p); p.getInventory().clearContent();
            });
            TownhallMod.CONFIG.get().onboarding.enabled = onboarding;
            TownhallMod.CONFIG.get().city.shopsEnabled = enabled;
            TownhallMod.CONFIG.get().city.auditEnabled = audit;
        }
    }
    private static long count(ServerPlayer p, ItemStack wanted) {
        long count = 0;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack stack = p.getInventory().getItem(i);
            if (ItemStack.isSameItemSameComponents(stack, wanted)) count += stack.getCount();
        }
        return count;
    }
    private static List<ItemStack> inventory(ServerPlayer p) {
        List<ItemStack> result = new ArrayList<>();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) result.add(p.getInventory().getItem(i).copy());
        return result;
    }
    private static void unchanged(GameTestHelper h, ServerPlayer p, List<ItemStack> before, Shop shop, Fixture f) {
        for (int i = 0; i < before.size(); i++) h.assertTrue(ItemStack.matches(before.get(i), p.getInventory().getItem(i)), "slot " + i + " unchanged");
        h.assertTrue(f.shop().equals(shop), "shop quantities unchanged");
    }
    private static void success(GameTestHelper h, ShopService.Result result) { h.assertTrue(result.success(), result.message()); }
    private static void failure(GameTestHelper h, ShopService.Result result, String reason) {
        h.assertFalse(result.success(), "must fail: " + reason);
        h.assertTrue(result.message().contains(reason), "specific error: " + result.message());
    }
    private static final class Capture implements CommandSource {
        final List<String> messages = new ArrayList<>();
        @Override public void sendSystemMessage(Component message) { messages.add(message.getString()); }
        @Override public boolean acceptsSuccess() { return true; }
        @Override public boolean acceptsFailure() { return true; }
        @Override public boolean shouldInformAdmins() { return false; }
        boolean said(String text) { return messages.stream().anyMatch(m -> m.contains(text)); }
    }
    private static Capture command(ServerPlayer p, String command) {
        Capture capture = new Capture();
        p.level().getServer().getCommands().performPrefixedCommand(p.createCommandSourceStack().withSource(capture), command);
        return capture;
    }

    @GameTest public void purchaseAndSuccessiveBuyersCannotOversell(GameTestHelper h) {
        try (Fixture f = new Fixture(h)) {
            f.set(new ItemStack(Items.APPLE), 2, 3, Shop.Currency.DIAMOND, 2, 0, 0);
            ServerPlayer first = f.player(), second = f.player();
            first.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
            second.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
            h.assertTrue(command(first, "shop buy " + f.id).said("gekauft"), "public command purchase succeeds");
            h.assertTrue(count(first, new ItemStack(Items.APPLE)) == 2 && count(first, new ItemStack(Items.DIAMOND)) == 0, "goods/payment physical transfer");
            List<ItemStack> before = inventory(second); Shop saved = f.shop();
            h.assertTrue(command(second, "shop buy " + f.id).said("Nicht genug Ladenbestand"), "second buyer sees sold-out stock");
            unchanged(h, second, before, saved, f);
            h.assertTrue(f.shop().diamonds() == 3 && f.shop().stock() == 0, "single persisted payment");
        }
        h.succeed();
    }
    @GameTest public void fullInventoryAndNoFundsAreAtomic(GameTestHelper h) {
        try (Fixture f = new Fixture(h)) {
            ServerPlayer buyer = f.player();
            for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) buyer.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
            buyer.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 64));
            List<ItemStack> before = inventory(buyer); Shop saved = f.shop();
            failure(h, ShopService.buy(buyer.createCommandSourceStack(), f.id, 1), "nicht genug Platz");
            unchanged(h, buyer, before, saved, f);
            buyer.getInventory().clearContent();
            buyer.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 2));
            before = inventory(buyer);
            failure(h, ShopService.buy(buyer.createCommandSourceStack(), f.id, 1), "Nicht genug unveränderte");
            unchanged(h, buyer, before, saved, f);
            // Positive control: a fully consumed payment stack frees exactly the goods slot.
            for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) buyer.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
            buyer.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
            success(h, ShopService.buy(buyer.createCommandSourceStack(), f.id, 1));
            h.assertTrue(buyer.getInventory().getItem(0).is(Items.APPLE), "freed slot receives goods");
        }
        h.succeed();
    }
    @GameTest public void exactComponentsForGoodsAndCurrency(GameTestHelper h) {
        try (Fixture f = new Fixture(h)) {
            ItemStack special = new ItemStack(Items.APPLE);
            special.set(DataComponents.CUSTOM_NAME, Component.literal("Apfel A"));
            CompoundTag tag = new CompoundTag(); tag.putInt("quality", 7);
            CustomData.set(DataComponents.CUSTOM_DATA, special, tag);
            f.set(special, 1, 1, Shop.Currency.DIAMOND, 0, 0, 0);
            f.owner.getInventory().setItem(0, new ItemStack(Items.APPLE, 4));
            List<ItemStack> before = inventory(f.owner); Shop saved = f.shop();
            failure(h, ShopService.stock(f.owner.createCommandSourceStack(), f.id, 1), "exakt passende");
            unchanged(h, f.owner, before, saved, f);
            f.owner.getInventory().setItem(1, special.copyWithCount(4));
            success(h, ShopService.stock(f.owner.createCommandSourceStack(), f.id, 2));
            h.assertTrue(f.owner.getInventory().getItem(0).getCount() == 4 && f.owner.getInventory().getItem(1).getCount() == 2, "ordinary apples were not taken");
            ServerPlayer buyer = f.player();
            ItemStack namedDiamond = new ItemStack(Items.DIAMOND, 2); namedDiamond.set(DataComponents.CUSTOM_NAME, Component.literal("Nicht bezahlen"));
            buyer.getInventory().setItem(0, namedDiamond);
            before = inventory(buyer); saved = f.shop();
            failure(h, ShopService.buy(buyer.createCommandSourceStack(), f.id, 1), "unveränderte");
            unchanged(h, buyer, before, saved, f);
            buyer.getInventory().setItem(1, new ItemStack(Items.DIAMOND));
            success(h, ShopService.buy(buyer.createCommandSourceStack(), f.id, 1));
            h.assertTrue(count(buyer, special) == 1 && count(buyer, namedDiamond) == 2, "exact metadata goods transferred and special payment kept");
        }
        h.succeed();
    }
    @GameTest public void offlineMerchantAndBothCurrencyEarnings(GameTestHelper h) {
        try (Fixture f = new Fixture(h)) {
            UUID offline = UUID.randomUUID();
            f.data.put(f.id, f.shop().withOwner(offline));
            h.assertTrue(h.getLevel().getServer().getPlayerList().getPlayer(offline) == null, "merchant really absent");
            ServerPlayer buyer = f.player();
            buyer.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 9));
            success(h, ShopService.buy(buyer.createCommandSourceStack(), f.id, 2));
            Shop snapshot = roundtrip(h, f.data).shop(f.id);
            h.assertTrue(snapshot.owner().equals(offline) && snapshot.stock() == 8 && snapshot.diamonds() == 6, "offline credited physical units survive CODEC");
            f.data.put(f.id, snapshot.withOwner(f.owner.getUUID()));
            f.owner.getInventory().setItem(0, new ItemStack(Items.APPLE));
            success(h, ShopService.offer(f.owner.createCommandSourceStack(), f.id, 2, 2, Shop.Currency.EMERALD));
            buyer.getInventory().setItem(1, new ItemStack(Items.EMERALD, 2));
            success(h, ShopService.buy(buyer.createCommandSourceStack(), f.id, 1));
            success(h, ShopService.collect(f.owner.createCommandSourceStack(), f.id, null, 0, false));
            h.assertTrue(count(f.owner, new ItemStack(Items.DIAMOND)) == 6 && count(f.owner, new ItemStack(Items.EMERALD)) == 2, "currency change preserved both earned item types");
        }
        h.succeed();
    }
    @GameTest public void strangerCannotTakeStockOrCollect(GameTestHelper h) {
        try (Fixture f = new Fixture(h)) {
            f.set(new ItemStack(Items.APPLE), 2, 3, Shop.Currency.DIAMOND, 10, 4, 0);
            ServerPlayer other = f.player();
            other.getInventory().setItem(0, new ItemStack(Items.APPLE, 5));
            List<ItemStack> before = inventory(other); Shop saved = f.shop();
            failure(h, ShopService.stock(other.createCommandSourceStack(), f.id, 1), "gehört dir nicht");
            failure(h, ShopService.withdraw(other.createCommandSourceStack(), f.id, 1, false), "gehört dir nicht");
            failure(h, ShopService.collect(other.createCommandSourceStack(), f.id, null, 0, false), "gehört dir nicht");
            failure(h, ShopService.withdraw(other.createCommandSourceStack(), f.id, 1, true), "Nur Admins");
            unchanged(h, other, before, saved, f);
            success(h, ShopService.withdraw(f.owner.createCommandSourceStack(), f.id, 1, false));
            success(h, ShopService.collect(f.owner.createCommandSourceStack(), f.id, null, 0, false));
        }
        h.succeed();
    }
    @GameTest public void roleLossAndDisabledShopsAllowRecovery(GameTestHelper h) {
        try (Fixture f = new Fixture(h)) {
            f.set(new ItemStack(Items.APPLE), 2, 3, Shop.Currency.DIAMOND, 10, 4, 0);
            RoleStorage.get(h.getLevel().getServer()).revoke(f.owner.getUUID(), f.role);
            f.owner.getInventory().setItem(0, new ItemStack(Items.APPLE, 5));
            failure(h, ShopService.stock(f.owner.createCommandSourceStack(), f.id, 1), "Händlerrecht");
            failure(h, ShopService.offer(f.owner.createCommandSourceStack(), f.id, 2, 3, Shop.Currency.DIAMOND), "Händlerrecht");
            TownhallMod.CONFIG.get().city.shopsEnabled = false;
            h.assertTrue(ShopService.protectedAt(h.getLevel(), f.pos) && ShopService.blocksAutomation(h.getLevel(), f.pos), "disabled shops retain protection");
            failure(h, ShopService.buy(f.player().createCommandSourceStack(), f.id, 1), "deaktiviert");
            success(h, ShopService.withdraw(f.owner.createCommandSourceStack(), f.id, 0, false));
            success(h, ShopService.collect(f.owner.createCommandSourceStack(), f.id, null, 0, false));
            h.assertTrue(f.shop().empty() && count(f.owner, new ItemStack(Items.APPLE)) == 15 && count(f.owner, new ItemStack(Items.DIAMOND)) == 4, "all original value recovered");
        }
        h.succeed();
    }
    @GameTest public void fullMerchantAndRecoveryInventoriesRejectWithoutLoss(GameTestHelper h) {
        try (Fixture f = new Fixture(h)) {
            f.set(new ItemStack(Items.APPLE), 1, 1, Shop.Currency.DIAMOND, Shop.MAX_STOCK, 8, 5);
            f.owner.getInventory().setItem(0, new ItemStack(Items.APPLE, 4));
            List<ItemStack> before = inventory(f.owner); Shop saved = f.shop();
            failure(h, ShopService.stock(f.owner.createCommandSourceStack(), f.id, 1), "Ladenbestand ist voll");
            unchanged(h, f.owner, before, saved, f);
            for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) f.owner.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
            before = inventory(f.owner);
            failure(h, ShopService.withdraw(f.owner.createCommandSourceStack(), f.id, 1, false), "nicht genug Platz");
            failure(h, ShopService.collect(f.owner.createCommandSourceStack(), f.id, null, 0, false), "nicht genug Platz");
            unchanged(h, f.owner, before, saved, f);
            f.owner.getInventory().setItem(0, ItemStack.EMPTY);
            // Diamond insertion could fit; failed emerald insertion must not leave a partial diamond collection.
            failure(h, ShopService.collect(f.owner.createCommandSourceStack(), f.id, null, 0, false), "nicht genug Platz");
            h.assertTrue(f.owner.getInventory().getItem(0).isEmpty() && f.shop().diamonds() == 8, "collection atomic across both currencies");
            success(h, ShopService.collect(f.owner.createCommandSourceStack(), f.id, Shop.Currency.DIAMOND, 4, false));
            h.assertTrue(f.shop().diamonds() == 4 && count(f.owner, new ItemStack(Items.DIAMOND)) == 4, "batch withdrawal positive control");
        }
        h.succeed();
    }
    @GameTest public void commandsOfferStockWithdrawAndPublicInfo(GameTestHelper h) {
        try (Fixture f = new Fixture(h)) {
            f.set(new ItemStack(Items.APPLE), 1, 1, Shop.Currency.DIAMOND, 0, 0, 0);
            f.owner.getInventory().setItem(f.owner.getInventory().getSelectedSlot(), new ItemStack(Items.APPLE, 8));
            h.assertTrue(command(f.owner, "shop offer " + f.id + " 4 2 emerald").said("Angebot"), "owner offer command");
            h.assertTrue(command(f.owner, "shop stock " + f.id + " 8").said("eingelagert"), "stock command positive control");
            h.assertTrue(f.shop().stock() == 8 && count(f.owner, new ItemStack(Items.APPLE)) == 0, "stock actually removed from owner");
            ServerPlayer publicPlayer = f.player();
            h.assertTrue(command(publicPlayer, "shop info " + f.id).said("Smaragde"), "public info");
            h.assertTrue(command(publicPlayer, "shop list").said(f.id), "public list");
            h.assertTrue(command(f.owner, "shop withdraw " + f.id + " 3").said("abgeholt"), "owner withdrawal command");
            h.assertTrue(f.shop().stock() == 5 && count(f.owner, new ItemStack(Items.APPLE)) == 3, "physical stock recovery");
        }
        h.succeed();
    }
    @GameTest public void signSourceCannotManageOrRecover(GameTestHelper h) {
        try (Fixture f = new Fixture(h)) {
            ServerPlayer other = f.player();
            CommandSourceStack sign = other.createCommandSourceStack().withSource(CommandSource.NULL).withPermission(LevelBasedPermissionSet.GAMEMASTER);
            failure(h, ShopService.delete(sign, f.id), "Nur Admins");
            failure(h, ShopService.owner(sign, f.id, other.getUUID()), "Nur Admins");
            failure(h, ShopService.withdraw(sign, f.id, 1, true), "Nur Admins");
            RoleStorage.get(h.getLevel().getServer()).revoke(f.owner.getUUID(), f.role);
            CommandSourceStack ownerSign = f.owner.createCommandSourceStack().withSource(CommandSource.NULL).withPermission(LevelBasedPermissionSet.GAMEMASTER);
            f.owner.getInventory().setItem(0, new ItemStack(Items.APPLE));
            failure(h, ShopService.offer(ownerSign, f.id, 1, 1, Shop.Currency.DIAMOND), "Händlerrecht");
            // Actual operator permissions, not sign source permissions, unlock explicit recovery.
            f.op(other);
            success(h, ShopService.withdraw(other.createCommandSourceStack(), f.id, 1, true));
            h.assertTrue(count(other, new ItemStack(Items.APPLE)) == 1, "operator recovers into own inventory");
        }
        h.succeed();
    }
    @GameTest public void adminDeleteTransferAndOfflineCreate(GameTestHelper h) {
        try (Fixture f = new Fixture(h)) {
            f.op(f.owner);
            failure(h, ShopService.delete(f.owner.createCommandSourceStack(), f.id), "enthält Werte");
            failure(h, ShopService.owner(f.owner.createCommandSourceStack(), f.id, UUID.randomUUID()), "nur bei leerem");
            success(h, ShopService.withdraw(f.owner.createCommandSourceStack(), f.id, 0, false));
            UUID offline = UUID.randomUUID();
            success(h, ShopService.owner(f.owner.createCommandSourceStack(), f.id, offline));
            h.assertTrue(f.shop().owner().equals(offline), "offline UUID owner transfer");
            success(h, ShopService.delete(f.owner.createCommandSourceStack(), f.id));
            String name = "shop" + UUID.randomUUID().toString().substring(0, 8);
            h.getLevel().getServer().services().nameToIdCache().add(new NameAndId(offline, name));
            Vec3 direction = Vec3.atCenterOf(f.pos).subtract(f.owner.getEyePosition());
            f.owner.setYRot((float) Math.toDegrees(Math.atan2(-direction.x, direction.z)));
            f.owner.setYHeadRot(f.owner.getYRot());
            f.owner.setXRot((float) -Math.toDegrees(Math.atan2(direction.y, Math.sqrt(direction.x * direction.x + direction.z * direction.z))));
            Capture create = command(f.owner, "shop create " + f.id + " " + name);
            h.assertTrue(create.said("angelegt"), "actual ray-target create command resolves offline profile: " + create.messages + " hit=" + f.owner.pick(6, 1, false) + " barrel=" + f.pos + " eye=" + f.owner.getEyePosition() + " vector=" + f.owner.getViewVector(1));
            h.assertTrue(f.shop().owner().equals(offline) && f.shop().empty(), "empty physical barrel registered to offline owner");
            success(h, ShopService.delete(f.owner.createCommandSourceStack(), f.id));
            h.assertFalse(ShopService.protectedAt(h.getLevel(), f.pos), "empty deletion releases marker");
        }
        h.succeed();
    }
    private static ShopData roundtrip(GameTestHelper h, ShopData data) {
        var ops = h.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
        var encoded = ShopData.CODEC.encodeStart(ops, data).getOrThrow();
        return ShopData.CODEC.parse(ops, encoded).getOrThrow();
    }
    @GameTest public void codecRoundtripMigrationAndOverflow(GameTestHelper h) {
        try (Fixture f = new Fixture(h)) {
            ItemStack special = new ItemStack(Items.APPLE); special.set(DataComponents.CUSTOM_NAME, Component.literal("Altbestand"));
            f.set(special, 64, Shop.MAX_PRICE, Shop.Currency.EMERALD, 128, 11, 17);
            ShopData decoded = roundtrip(h, f.data);
            h.assertTrue(decoded.idAt(f.shop().dimension(), f.pos).equals(f.id), "position index reconstructed");
            h.assertTrue(decoded.shop(f.id).owner().equals(f.owner.getUUID()) && decoded.shop(f.id).stock() == 128
                && decoded.shop(f.id).diamonds() == 11 && decoded.shop(f.id).emeralds() == 17
                && ItemStack.isSameItemSameComponents(decoded.shop(f.id).template(), special), "all saved value and exact components roundtrip");
            var ops = h.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
            CompoundTag shopTag = (CompoundTag) Shop.CODEC.encodeStart(ops, f.shop()).getOrThrow();
            shopTag.remove("diamonds"); shopTag.remove("emeralds"); shopTag.putLong("earnings", 19);
            CompoundTag shops = new CompoundTag(); shops.put(f.id, shopTag);
            CompoundTag legacy = new CompoundTag(); legacy.put("shops", shops); // no version: schema 1
            ShopData migrated = ShopData.CODEC.parse(ops, legacy).getOrThrow();
            h.assertTrue(migrated.shop(f.id).emeralds() == 19 && migrated.shop(f.id).diamonds() == 0, "legacy earnings become the same currency's item units");
            CompoundTag current = (CompoundTag) ShopData.CODEC.encodeStart(ops, migrated).getOrThrow();
            h.assertTrue(current.getInt("version").orElseThrow() == 2, "migration writes current schema");
            shopTag.putLong("emeralds", Long.MAX_VALUE);
            h.assertTrue(Shop.CODEC.parse(ops, shopTag).error().isPresent(), "migration overflow rejected without wrapping or throwing");
            f.set(new ItemStack(Items.APPLE), 1, 1, Shop.Currency.DIAMOND, 10, Long.MAX_VALUE, 0);
            ServerPlayer buyer = f.player(); buyer.getInventory().setItem(0, new ItemStack(Items.DIAMOND));
            List<ItemStack> before = inventory(buyer); Shop saved = f.shop();
            failure(h, ShopService.buy(buyer.createCommandSourceStack(), f.id, 1), "Einnahmenspeicher voll");
            unchanged(h, buyer, before, saved, f);
            failure(h, ShopService.buy(buyer.createCommandSourceStack(), f.id, Integer.MAX_VALUE), "Ungültige Anzahl");
            unchanged(h, buyer, before, saved, f);
        }
        h.succeed();
    }
    @GameTest public void civicGoodsKeysAndContainerSmugglingRejected(GameTestHelper h) {
        try (Fixture f = new Fixture(h)) {
            h.assertTrue(ShopService.forbiddenGoods(Keys.newKey("Test")) && ShopService.forbiddenGoods(Keys.adminKey()), "normal/admin keys rejected");
            ItemStack ballot = new ItemStack(Items.WRITABLE_BOOK);
            CompoundTag tag = new CompoundTag(); tag.putString("townhall_ballot_token", "test");
            CustomData.set(DataComponents.CUSTOM_DATA, ballot, tag);
            h.assertTrue(ShopService.forbiddenGoods(ballot), "ballot rejected");
            ItemStack boxed = new ItemStack(Items.SHULKER_BOX);
            boxed.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(ballot)));
            h.assertTrue(ShopService.forbiddenGoods(boxed), "container cannot launder ballot");
            f.owner.getInventory().setItem(0, ballot);
            failure(h, ShopService.offer(f.owner.createCommandSourceStack(), f.id, 1, 1, Shop.Currency.DIAMOND), "nicht zulässig");
            f.set(new ItemStack(Items.APPLE), 1, 1, Shop.Currency.DIAMOND, 0, 0, 0);
            f.owner.getInventory().setItem(0, new ItemStack(Items.WRITABLE_BOOK));
            success(h, ShopService.offer(f.owner.createCommandSourceStack(), f.id, 1, 1, Shop.Currency.EMERALD));
            h.assertFalse(ShopService.containsForbiddenGoods(List.of(new ItemStack(Items.WRITABLE_BOOK), new ItemStack(Items.TRIPWIRE_HOOK))), "ordinary vanilla ingredients still allowed");
            ItemStack civicBook = new ItemStack(Items.BOOK);
            CustomData.set(DataComponents.CUSTOM_DATA, civicBook, tag);
            var markedRecipe = net.minecraft.world.item.crafting.CraftingInput.of(3, 1,
                List.of(civicBook, new ItemStack(Items.INK_SAC), new ItemStack(Items.FEATHER)));
            var normalRecipe = net.minecraft.world.item.crafting.CraftingInput.of(3, 1,
                List.of(new ItemStack(Items.BOOK), new ItemStack(Items.INK_SAC), new ItemStack(Items.FEATHER)));
            h.assertTrue(net.minecraft.world.level.block.CrafterBlock.getPotentialResults(h.getLevel(), markedRecipe).isEmpty(), "crafter cannot strip civic metadata");
            h.assertTrue(net.minecraft.world.level.block.CrafterBlock.getPotentialResults(h.getLevel(), normalRecipe).isPresent(), "ordinary book crafting positive control");
            var menu = new net.minecraft.world.inventory.CraftingMenu(1, f.owner.getInventory(),
                net.minecraft.world.inventory.ContainerLevelAccess.create(h.getLevel(), f.pos));
            menu.getSlot(1).set(civicBook.copy());
            menu.getSlot(2).set(new ItemStack(Items.INK_SAC));
            menu.getSlot(3).set(new ItemStack(Items.FEATHER));
            h.assertTrue(menu.getSlot(0).getItem().isEmpty(), "crafting table cannot strip civic metadata");
            menu.getSlot(1).set(new ItemStack(Items.BOOK));
            h.assertTrue(menu.getSlot(0).getItem().is(Items.WRITABLE_BOOK), "ordinary crafting table positive control");
        }
        h.succeed();
    }
    @GameTest public void nearbyLoadedDimensionAndConfinementChecks(GameTestHelper h) {
        try (Fixture f = new Fixture(h)) {
            ServerPlayer buyer = f.player(); buyer.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 6));
            Vec3 home = buyer.position();
            buyer.absSnapTo(home.x + 20, home.y, home.z, 0, 0);
            failure(h, ShopService.buy(buyer.createCommandSourceStack(), f.id, 1), "höchstens 6 Blöcke");
            buyer.teleportTo(h.getLevel().getServer().getLevel(Level.NETHER), home.x, home.y, home.z, java.util.Set.of(), 0, 0, false);
            failure(h, ShopService.buy(buyer.createCommandSourceStack(), f.id, 1), "geladenen Welt");
            buyer.teleportTo(h.getLevel(), home.x, home.y, home.z, java.util.Set.of(), 0, 0, false);
            ReturnPositionStorage.get(h.getLevel().getServer()).set(buyer.getUUID(), new PlayerState(Optional.empty(), Optional.empty(), true, Optional.empty()));
            failure(h, ShopService.buy(buyer.createCommandSourceStack(), f.id, 1), "Haft");
            ReturnPositionStorage.get(h.getLevel().getServer()).remove(buyer.getUUID());
            success(h, ShopService.buy(buyer.createCommandSourceStack(), f.id, 1));
            var onboarding = TownhallMod.CONFIG.get().onboarding;
            boolean restrict = onboarding.restrictUntilAccepted;
            int version = onboarding.rulesVersion;
            try {
                onboarding.enabled = true; onboarding.restrictUntilAccepted = true; onboarding.rulesVersion = Math.max(1, version);
                Onboarding.onJoin(buyer);
                failure(h, ShopService.buy(buyer.createCommandSourceStack(), f.id, 1), "Serverregeln akzeptieren");
                h.assertTrue(Onboarding.accept(buyer), "rules accepted");
                success(h, ShopService.buy(buyer.createCommandSourceStack(), f.id, 1));
            } finally {
                onboarding.enabled = false; onboarding.restrictUntilAccepted = restrict; onboarding.rulesVersion = version;
            }
        }
        h.succeed();
    }
    @GameTest public void markerContainerBreakHopperAndExplosionProtection(GameTestHelper h) {
        try (Fixture f = new Fixture(h)) {
            BarrelBlockEntity barrel = (BarrelBlockEntity) h.getLevel().getBlockEntity(f.pos);
            h.assertFalse(barrel.canOpen(f.owner), "owner cannot open marker inventory");
            h.assertFalse(barrel.stillValid(f.owner), "previously open marker menu invalidated");
            h.assertFalse(PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(h.getLevel(), f.owner, f.pos, barrel.getBlockState(), barrel), "marker break denied");
            h.assertFalse(PistonBaseBlock.isPushable(barrel.getBlockState(), h.getLevel(), f.pos, Direction.EAST, true, Direction.EAST), "piston denies marker");
            BlockPos hopperPos = f.pos.below();
            h.getLevel().setBlockAndUpdate(hopperPos, Blocks.HOPPER.defaultBlockState());
            HopperBlockEntity hopper = (HopperBlockEntity) h.getLevel().getBlockEntity(hopperPos);
            ItemStack incoming = new ItemStack(Items.DIAMOND, 5);
            h.assertTrue(HopperBlockEntity.addItem(hopper, barrel, incoming, Direction.DOWN).getCount() == 5 && barrel.isEmpty(), "automation insertion retains incoming items");
            h.assertFalse(HopperBlockEntity.suckInItems(h.getLevel(), hopper), "hopper extraction blocked before transfer fallback");
            new ServerExplosion(h.getLevel(), null, null, null, Vec3.atCenterOf(f.pos), 3, false, Explosion.BlockInteraction.DESTROY).explode();
            h.assertTrue(h.getLevel().getBlockState(f.pos).is(Blocks.BARREL) && f.shop().stock() == 12, "explosion preserves marker and saved stock");
            success(h, ShopService.withdraw(f.owner.createCommandSourceStack(), f.id, 0, false));
            f.op(f.owner); success(h, ShopService.delete(f.owner.createCommandSourceStack(), f.id));
            h.assertTrue(barrel.canOpen(f.owner), "ordinary barrel opens after empty shop deletion");
            h.assertTrue(HopperBlockEntity.addItem(hopper, barrel, incoming, Direction.DOWN).isEmpty() && !barrel.isEmpty(), "ordinary barrel accepts automation positive control");
            barrel.clearContent();
        }
        h.succeed();
    }
    @GameTest public void creativeCannotMintStockOrCurrency(GameTestHelper h) {
        try (Fixture f = new Fixture(h)) {
            f.owner.getInventory().setItem(0, new ItemStack(Items.APPLE, 4));
            f.owner.gameMode.changeGameModeForPlayer(GameType.CREATIVE);
            List<ItemStack> before = inventory(f.owner); Shop saved = f.shop();
            failure(h, ShopService.stock(f.owner.createCommandSourceStack(), f.id, 1), "Creative");
            failure(h, ShopService.offer(f.owner.createCommandSourceStack(), f.id, 1, 1, Shop.Currency.DIAMOND), "Creative");
            unchanged(h, f.owner, before, saved, f);
            ServerPlayer buyer = f.player(); buyer.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
            buyer.gameMode.changeGameModeForPlayer(GameType.CREATIVE);
            before = inventory(buyer);
            failure(h, ShopService.buy(buyer.createCommandSourceStack(), f.id, 1), "Creative");
            unchanged(h, buyer, before, saved, f);
            buyer.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
            success(h, ShopService.buy(buyer.createCommandSourceStack(), f.id, 1));
            f.owner.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
            success(h, ShopService.stock(f.owner.createCommandSourceStack(), f.id, 1));
        }
        h.succeed();
    }
    @GameTest public void orphanedMarkerExplicitRecoveryKeepsAllValue(GameTestHelper h) {
        try (Fixture f = new Fixture(h)) {
            f.set(new ItemStack(Items.APPLE), 2, 3, Shop.Currency.DIAMOND, 12, 7, 5);
            h.getLevel().setBlockAndUpdate(f.pos, Blocks.AIR.defaultBlockState()); // simulate external world edit, never discard the record
            failure(h, ShopService.withdraw(f.owner.createCommandSourceStack(), f.id, 0, false), "Ladenfass fehlt");
            h.assertTrue(ShopService.protectedAt(h.getLevel(), f.pos), "orphan record remains protected");
            ServerPlayer admin = f.player(); f.op(admin);
            failure(h, ShopService.delete(admin.createCommandSourceStack(), f.id), "enthält Werte");
            success(h, ShopService.withdraw(admin.createCommandSourceStack(), f.id, 0, true));
            success(h, ShopService.collect(admin.createCommandSourceStack(), f.id, null, 0, true));
            h.assertTrue(count(admin, new ItemStack(Items.APPLE)) == 12 && count(admin, new ItemStack(Items.DIAMOND)) == 7
                && count(admin, new ItemStack(Items.EMERALD)) == 5 && f.shop().empty(), "all value recovered to executing admin");
            success(h, ShopService.delete(admin.createCommandSourceStack(), f.id));
        }
        h.succeed();
    }
    @GameTest public void codecDefaultsMalformedStockAndDuplicateMarkers(GameTestHelper h) {
        try (Fixture f = new Fixture(h)) {
            var ops = h.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
            Shop empty = new Shop(f.owner.getUUID(), f.shop().dimension(), f.pos, ItemStack.EMPTY, 1, 1, Shop.Currency.DIAMOND, 0, 0, 0);
            CompoundTag minimal = (CompoundTag) Shop.CODEC.encodeStart(ops, empty).getOrThrow();
            Shop loaded = Shop.CODEC.parse(ops, minimal).getOrThrow();
            h.assertTrue(loaded.empty() && loaded.template().isEmpty() && loaded.amount() == 1 && loaded.price() == 1, "first-schema empty shop defaults survive");
            minimal.putLong("stock", 1);
            h.assertTrue(Shop.CODEC.parse(ops, minimal).error().isPresent(), "stock without a template rejects decoding without throwing");
            minimal.putLong("stock", -1);
            h.assertTrue(Shop.CODEC.parse(ops, minimal).error().isPresent(), "negative stock rejects decoding");
            CompoundTag encoded = (CompoundTag) Shop.CODEC.encodeStart(ops, f.shop()).getOrThrow();
            CompoundTag shops = new CompoundTag(); shops.put("one", encoded); shops.put("two", encoded.copy());
            CompoundTag duplicate = new CompoundTag(); duplicate.put("shops", shops);
            h.assertTrue(ShopData.CODEC.parse(ops, duplicate).error().isPresent(), "duplicate marker positions rejected instead of hiding stored value");
        }
        h.succeed();
    }
}
