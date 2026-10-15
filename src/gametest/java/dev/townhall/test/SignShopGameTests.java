package dev.townhall.test;

import dev.townhall.TownhallMod;
import dev.townhall.city.RoleDefinition;
import dev.townhall.city.RoleStorage;
import dev.townhall.onboarding.Onboarding;
import dev.townhall.shop.Shop;
import dev.townhall.shop.ShopService;
import dev.townhall.shop.sign.SignShop;
import dev.townhall.shop.sign.SignShopData;
import dev.townhall.shop.sign.SignShopService;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.FilteredText;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Sign shops ("Schilderladen"). Each test restores config, roles, ops and saved shops in close(). */
public class SignShopGameTests {
    private static final class Fixture implements AutoCloseable {
        final GameTestHelper h;
        final String role = "ss_" + UUID.randomUUID().toString().substring(0, 8);
        final BlockPos chest, sign;
        final ServerPlayer owner;
        final SignShopData data;
        final boolean onboarding, enabled, audit;
        final List<ServerPlayer> players = new ArrayList<>(), operators = new ArrayList<>();
        Fixture(GameTestHelper h) {
            this.h = h;
            chest = h.absolutePos(new BlockPos(3, 2, 3));
            var config = TownhallMod.CONFIG.get();
            onboarding = config.onboarding.enabled; enabled = config.city.shopsEnabled; audit = config.city.auditEnabled;
            config.onboarding.enabled = false; config.city.shopsEnabled = true; config.city.auditEnabled = false;
            data = SignShopData.get(h.getLevel().getServer());
            h.getLevel().setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
            sign = chest.south();
            h.getLevel().setBlockAndUpdate(sign, Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, Direction.SOUTH));
            owner = player();
            RoleStorage.get(h.getLevel().getServer()).put(role, new RoleDefinition("Test", "", List.of("shop.manage"), 0));
            RoleStorage.get(h.getLevel().getServer()).grant(owner.getUUID(), owner.getGameProfile().name(), role);
            chest().clearContent();
        }
        BlockPos pos(int x, int y, int z) { return h.absolutePos(new BlockPos(x, y, z)); }
        ChestBlockEntity chest() { return (ChestBlockEntity) h.getLevel().getBlockEntity(chest); }
        SignBlockEntity sign() { return (SignBlockEntity) h.getLevel().getBlockEntity(sign); }
        ServerPlayer player() {
            ServerPlayer p = h.makeMockServerPlayerInLevel();
            Vec3 at = h.absoluteVec(new Vec3(3.5, 1, 5.5));
            p.absSnapTo(at.x, at.y, at.z, 180, 0);
            p.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
            p.getInventory().clearContent();
            players.add(p);
            return p;
        }
        void op(ServerPlayer p) {
            h.getLevel().getServer().getPlayerList().op(new NameAndId(p.getGameProfile()), Optional.of(LevelBasedPermissionSet.GAMEMASTER), Optional.empty());
            operators.add(p);
        }
        void write(String l1, String l2, String l3) {
            List<Component> lines = List.of(Component.literal(l1), Component.literal(l2), Component.literal(l3), Component.literal(""));
            sign().setText(new SignText(lines, lines, DyeColor.BLACK, false), SignTextSlot.FRONT);
        }
        /** Opens a standard shop: 16 items for 2 emeralds, chest full of oak logs. */
        SignShop open(int logs) {
            chest().setItem(0, new ItemStack(Items.OAK_LOG, logs));
            write("[Laden]", "16", "2 Smaragde");
            ShopService.Result r = SignShopService.create(owner, sign());
            h.assertTrue(r != null && r.success(), "shop created: " + (r == null ? "null" : r.message()));
            return data.find(h.getLevel().dimension().identifier(), sign);
        }
        @Override public void close() {
            SignShop s = data.find(h.getLevel().dimension().identifier(), sign);
            if (s != null) data.remove(s);
            RoleStorage.get(h.getLevel().getServer()).remove(role);
            operators.forEach(p -> h.getLevel().getServer().getPlayerList().deop(new NameAndId(p.getGameProfile())));
            players.forEach(p -> { Onboarding.onDisconnect(p); p.getInventory().clearContent(); });
            TownhallMod.CONFIG.get().onboarding.enabled = onboarding;
            TownhallMod.CONFIG.get().city.shopsEnabled = enabled;
            TownhallMod.CONFIG.get().city.auditEnabled = audit;
        }
    }
    private static long count(ServerPlayer p, ItemStack wanted) {
        long n = 0;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) if (ItemStack.isSameItemSameComponents(p.getInventory().getItem(i), wanted)) n += p.getInventory().getItem(i).getCount();
        return n;
    }
    private static long count(ChestBlockEntity c, ItemStack wanted) {
        long n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (ItemStack.isSameItemSameComponents(c.getItem(i), wanted)) n += c.getItem(i).getCount();
        return n;
    }
    private static List<ItemStack> snapshot(net.minecraft.world.Container c) {
        List<ItemStack> l = new ArrayList<>();
        for (int i = 0; i < c.getContainerSize(); i++) l.add(c.getItem(i).copy());
        return l;
    }
    private static void same(GameTestHelper h, List<ItemStack> before, net.minecraft.world.Container c, String what) {
        for (int i = 0; i < before.size(); i++) h.assertTrue(ItemStack.matches(before.get(i), c.getItem(i)), what + " slot " + i + " unchanged");
    }
    private static ItemStack emerald() { return new ItemStack(Items.EMERALD); }
    private static ItemStack log() { return new ItemStack(Items.OAK_LOG); }

    @GameTest public void validSignIsFormattedWaxedAndSaved(GameTestHelper h) {
        try (Fixture f = new Fixture(h)) {
            f.chest().setItem(0, new ItemStack(Items.OAK_LOG, 64));
            // Goes through the real edit path (SignShopSignMixin on SignBlockEntity.updateSignText).
            f.sign().setAllowedPlayerEditor(f.owner.getUUID());
            f.sign().updateSignText(f.owner, SignTextSlot.FRONT, List.of(FilteredText.passThrough("[Laden]"),
                FilteredText.passThrough("16"), FilteredText.passThrough("2 Smaragde"), FilteredText.passThrough("")));
            SignShop shop = f.data.find(h.getLevel().dimension().identifier(), f.sign);
            h.assertTrue(shop != null, "shop saved");
            h.assertTrue(f.sign().isWaxed(), "sign waxed");
            List<Component> text = f.sign().getText(SignTextSlot.FRONT).getMessages(false);
            h.assertTrue(text.get(0).getString().equals("[Laden]"), "line 1");
            h.assertTrue(text.get(1).getString().startsWith("16x "), "line 2: " + text.get(1).getString());
            h.assertTrue(text.get(2).getString().equals("2 Smaragde"), "line 3");
            h.assertTrue(text.get(3).getString().equals(f.owner.getGameProfile().name()), "line 4 owner");
            h.assertTrue(shop.owner().equals(f.owner.getUUID()) && shop.amount() == 16 && shop.price() == 2
                && shop.currency() == Shop.Currency.EMERALD && shop.chest().equals(f.chest)
                && ItemStack.isSameItemSameComponents(shop.template(), log()), "saved fields");
        }
        h.succeed();
    }
    @GameTest public void emptyChestAndBadInputKeepNormalSign(GameTestHelper h) {
        try (Fixture f = new Fixture(h)) {
            f.write("[Laden]", "16", "2 Smaragde");
            ShopService.Result r = SignShopService.create(f.owner, f.sign());
            h.assertTrue(r != null && !r.success() && r.message().contains("Leg zuerst die Ware in die Truhe."), "empty chest error");
            f.chest().setItem(0, new ItemStack(Items.OAK_LOG, 64));
            for (String[] bad : new String[][]{{"0", "2 Smaragde"}, {"65", "2 Smaragde"}, {"x", "2 Smaragde"}, {"16", "zwei Smaragde"}, {"16", "2 Gold"}, {"16", "0 Diamanten"}}) {
                f.write("[Laden]", bad[0], bad[1]);
                r = SignShopService.create(f.owner, f.sign());
                h.assertTrue(r != null && !r.success(), "rejected " + bad[0] + " / " + bad[1]);
            }
            h.assertTrue(!f.sign().isWaxed() && f.data.isEmpty(), "sign stays normal, nothing saved");
            f.write("Hallo", "16", "2 Smaragde");
            h.assertTrue(SignShopService.create(f.owner, f.sign()) == null, "other sign is ignored");
            ServerPlayer stranger = f.player();
            f.write("[Shop]", "16", "2 emeralds");
            r = SignShopService.create(stranger, f.sign());
            h.assertTrue(r != null && !r.success() && f.data.isEmpty(), "player without merchant role cannot create");
            r = SignShopService.create(f.owner, f.sign());
            h.assertTrue(r != null && r.success(), "[Shop] and english currency accepted");
        }
        h.succeed();
    }
    @GameTest public void purchaseMovesGoodsAndPayment(GameTestHelper h) {
        try (Fixture f = new Fixture(h)) {
            SignShop shop = f.open(64);
            ServerPlayer buyer = f.player();
            buyer.getInventory().setItem(0, new ItemStack(Items.EMERALD, 3));
            ShopService.Result r = SignShopService.buy(buyer, h.getLevel(), shop);
            h.assertTrue(r.success() && r.message().contains("gekauft"), r.message());
            h.assertTrue(count(buyer, log()) == 16 && count(buyer, emerald()) == 1, "buyer got goods and paid 2");
            h.assertTrue(count(f.chest(), log()) == 48 && count(f.chest(), emerald()) == 2, "chest lost goods and received payment");
            h.assertTrue(SignShopService.info(h.getLevel(), shop).message().contains("Bestand: 48"), "owner info");
        }
        h.succeed();
    }
    @GameTest public void tooLittleMoneyChangesNothing(GameTestHelper h) {
        try (Fixture f = new Fixture(h)) {
            SignShop shop = f.open(64);
            ServerPlayer buyer = f.player();
            buyer.getInventory().setItem(0, new ItemStack(Items.EMERALD, 1));
            buyer.getInventory().setItem(1, new ItemStack(Items.DIAMOND, 5));
            var inv = snapshot(buyer.getInventory()); var chest = snapshot(f.chest());
            ShopService.Result r = SignShopService.buy(buyer, h.getLevel(), shop);
            h.assertTrue(!r.success() && r.message().equals("Du hast nicht genug Smaragde (2 nötig)."), r.message());
            same(h, inv, buyer.getInventory(), "buyer"); same(h, chest, f.chest(), "chest");
            buyer.gameMode.changeGameModeForPlayer(GameType.CREATIVE);
            buyer.getInventory().setItem(0, new ItemStack(Items.EMERALD, 2));
            h.assertTrue(!SignShopService.buy(buyer, h.getLevel(), shop).success() && count(f.chest(), log()) == 64, "creative cannot buy");
        }
        h.succeed();
    }
    @GameTest public void soldOutChangesNothing(GameTestHelper h) {
        try (Fixture f = new Fixture(h)) {
            SignShop shop = f.open(64);
            f.chest().clearContent();
            f.chest().setItem(0, new ItemStack(Items.OAK_LOG, 15));
            ItemStack named = new ItemStack(Items.OAK_LOG, 30); named.set(DataComponents.CUSTOM_NAME, Component.literal("anders"));
            f.chest().setItem(1, named); // same item, other components: does not count as stock
            ServerPlayer buyer = f.player();
            buyer.getInventory().setItem(0, new ItemStack(Items.EMERALD, 2));
            var inv = snapshot(buyer.getInventory()); var chest = snapshot(f.chest());
            ShopService.Result r = SignShopService.buy(buyer, h.getLevel(), shop);
            h.assertTrue(!r.success() && r.message().equals("Der Laden ist ausverkauft."), r.message());
            same(h, inv, buyer.getInventory(), "buyer"); same(h, chest, f.chest(), "chest");
        }
        h.succeed();
    }
    @GameTest public void fullChestAndFullInventoryChangeNothing(GameTestHelper h) {
        try (Fixture f = new Fixture(h)) {
            SignShop shop = f.open(64);
            for (int i = 0; i < 27; i++) f.chest().setItem(i, new ItemStack(Items.OAK_LOG, 64));
            ServerPlayer buyer = f.player();
            buyer.getInventory().setItem(0, new ItemStack(Items.EMERALD, 2));
            var inv = snapshot(buyer.getInventory()); var chest = snapshot(f.chest());
            ShopService.Result r = SignShopService.buy(buyer, h.getLevel(), shop);
            h.assertTrue(!r.success() && r.message().contains("Ladentruhe ist voll"), r.message());
            same(h, inv, buyer.getInventory(), "buyer"); same(h, chest, f.chest(), "chest");
            f.chest().clearContent(); f.chest().setItem(0, new ItemStack(Items.OAK_LOG, 64));
            for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) buyer.getInventory().setItem(i, new ItemStack(Items.STONE, 64));
            buyer.getInventory().setItem(0, new ItemStack(Items.EMERALD, 3));
            inv = snapshot(buyer.getInventory()); chest = snapshot(f.chest());
            r = SignShopService.buy(buyer, h.getLevel(), shop);
            h.assertTrue(!r.success() && r.message().equals("Dein Inventar ist voll."), r.message());
            same(h, inv, buyer.getInventory(), "buyer"); same(h, chest, f.chest(), "chest");
        }
        h.succeed();
    }
    @GameTest public void strangerIsLockedOutOwnerIsNot(GameTestHelper h) {
        try (Fixture f = new Fixture(h)) {
            f.open(64);
            ServerPlayer stranger = f.player(), admin = f.player();
            f.op(admin);
            var level = h.getLevel();
            ChestBlockEntity chest = f.chest();
            h.assertFalse(chest.canOpen(stranger), "stranger cannot open");
            h.assertFalse(chest.stillValid(stranger), "stranger menu invalid");
            h.assertTrue(chest.canOpen(f.owner) && chest.canOpen(admin), "owner and admin open");
            h.assertFalse(PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(level, stranger, f.chest, chest.getBlockState(), chest), "stranger cannot break chest");
            h.assertFalse(PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(level, stranger, f.sign, f.sign().getBlockState(), f.sign()), "stranger cannot break sign");
            h.assertFalse(PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(level, admin, f.chest, chest.getBlockState(), chest), "admin cannot break chest");
            h.assertTrue(PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(level, admin, f.sign, f.sign().getBlockState(), f.sign()), "admin may remove sign");
            BlockPos hopperPos = f.chest.below();
            level.setBlockAndUpdate(hopperPos, Blocks.HOPPER.defaultBlockState());
            HopperBlockEntity hopper = (HopperBlockEntity) level.getBlockEntity(hopperPos);
            ItemStack incoming = new ItemStack(Items.DIAMOND, 5);
            h.assertTrue(HopperBlockEntity.addItem(hopper, chest, incoming, Direction.DOWN).getCount() == 5, "hopper cannot insert");
            h.assertFalse(HopperBlockEntity.suckInItems(level, hopper), "hopper cannot pull");
            new ServerExplosion(level, null, null, null, Vec3.atCenterOf(f.chest), 3, false, Explosion.BlockInteraction.DESTROY).explode();
            h.assertTrue(level.getBlockState(f.chest).is(Blocks.CHEST) && level.getBlockState(f.sign).is(Blocks.OAK_WALL_SIGN), "explosion spares shop");
            level.setBlockAndUpdate(hopperPos, Blocks.AIR.defaultBlockState());
            // Owner may break the chest; the shop disappears.
            h.assertTrue(PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(level, f.owner, f.chest, chest.getBlockState(), chest), "owner may break chest");
            PlayerBlockBreakEvents.AFTER.invoker().afterBlockBreak(level, f.owner, f.chest, chest.getBlockState(), chest);
            h.assertTrue(f.data.isEmpty(), "shop removed with chest");
            h.assertTrue(chest.canOpen(stranger), "ordinary chest afterwards");
        }
        h.succeed();
    }
    @GameTest public void doubleChestProtectsBothHalves(GameTestHelper h) {
        try (Fixture f = new Fixture(h)) {
            var level = h.getLevel();
            BlockPos other = f.chest.east();
            level.setBlockAndUpdate(f.chest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.LEFT));
            level.setBlockAndUpdate(other, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.RIGHT));
            f.open(64);
            ServerPlayer stranger = f.player();
            ChestBlockEntity partner = (ChestBlockEntity) level.getBlockEntity(other);
            h.assertTrue(SignShopService.protectedAt(level, other), "second half protected");
            h.assertFalse(partner.canOpen(stranger), "stranger cannot open second half");
            h.assertTrue(partner.canOpen(f.owner), "owner opens second half");
            h.assertFalse(PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(level, stranger, other, partner.getBlockState(), partner), "second half cannot be broken");
            h.assertFalse(dev.townhall.protection.Protection.mayBuildAt(stranger, level, other), "no building on the second half");
            // Stock may sit in the second half; it counts and is paid from.
            f.chest().clearContent(); partner.setItem(0, new ItemStack(Items.OAK_LOG, 32));
            ServerPlayer buyer = f.player();
            buyer.getInventory().setItem(0, new ItemStack(Items.EMERALD, 2));
            h.assertTrue(SignShopService.buy(buyer, level, f.data.find(level.dimension().identifier(), f.sign)).success(), "buy from double chest");
            h.assertTrue(count(partner, log()) == 16, "goods taken from second half");
            partner.clearContent(); f.chest().clearContent();
            level.setBlockAndUpdate(other, Blocks.AIR.defaultBlockState());
        }
        h.succeed();
    }
    @GameTest public void codecRoundtrip(GameTestHelper h) {
        try (Fixture f = new Fixture(h)) {
            ItemStack special = new ItemStack(Items.APPLE); special.set(DataComponents.CUSTOM_NAME, Component.literal("Altbestand"));
            SignShop shop = new SignShop(f.owner.getUUID(), h.getLevel().dimension().identifier(), f.sign, f.chest, special, 7, Shop.Currency.DIAMOND, 12);
            var ops = h.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
            SignShopData one = new SignShopData();
            h.assertTrue(one.put(shop) && !one.put(shop), "duplicate position refused");
            var tag = SignShopData.CODEC.encodeStart(ops, one).getOrThrow();
            SignShopData decoded = SignShopData.CODEC.parse(ops, tag).getOrThrow();
            SignShop back = decoded.find(shop.dimension(), f.chest);
            h.assertTrue(back != null && decoded.find(shop.dimension(), f.sign) == back, "both positions indexed");
            h.assertTrue(back.owner().equals(shop.owner()) && back.amount() == 7 && back.price() == 12 && back.currency() == Shop.Currency.DIAMOND
                && back.sign().equals(f.sign) && back.chest().equals(f.chest)
                && ItemStack.isSameItemSameComponents(back.template(), special), "all fields and components roundtrip");
        }
        h.succeed();
    }
}
