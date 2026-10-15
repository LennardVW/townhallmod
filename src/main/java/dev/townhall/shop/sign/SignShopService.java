package dev.townhall.shop.sign;

import dev.townhall.TownhallMod;
import dev.townhall.audit.AuditLog;
import dev.townhall.city.RoleService;
import dev.townhall.election.ElectionService;
import dev.townhall.protection.Protection;
import dev.townhall.shop.Shop;
import dev.townhall.shop.ShopInventory;
import dev.townhall.shop.ShopService;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Sign shops ("Schilderladen"): a sign on a chest/barrel sells the chest's goods. Everything runs on the server thread. */
public final class SignShopService {
    private static final Pattern PRICE = Pattern.compile("^(\\d{1,5})\\s*(smaragde?|emeralds?|diamanten?|diamonds?)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMOUNT = Pattern.compile("^(\\d{1,3})\\s*x?$", Pattern.CASE_INSENSITIVE);
    private static final int CLICK_COOLDOWN_TICKS = 5; // 250 ms
    private static final Map<UUID, Integer> LAST_CLICK = new HashMap<>();
    private SignShopService() {}

    private static ShopService.Result fail(String text) { return new ShopService.Result(false, text); }
    private static ShopService.Result ok(String text) { return new ShopService.Result(true, text); }

    public static void registerEvents() {
        Identifier early = Identifier.fromNamespaceAndPath("townhall", "sign_shop");
        UseBlockCallback.EVENT.addPhaseOrdering(early, Event.DEFAULT_PHASE);
        UseBlockCallback.EVENT.register(early, (player, level, hand, hit) -> {
            if (!(player instanceof ServerPlayer sp) || !(level instanceof ServerLevel server)) return InteractionResult.PASS;
            SignShopData data = SignShopData.get(server.getServer());
            if (data.isEmpty()) return InteractionResult.PASS;
            SignShop shop = data.find(server.dimension().identifier(), hit.getBlockPos());
            if (shop == null || !shop.sign().equals(hit.getBlockPos())) return InteractionResult.PASS;
            if (hand != InteractionHand.MAIN_HAND) return InteractionResult.SUCCESS;
            int now = server.getServer().getTickCount();
            Integer last = LAST_CLICK.get(sp.getUUID());
            if (last != null && now - last >= 0 && now - last < CLICK_COOLDOWN_TICKS) return InteractionResult.SUCCESS;
            LAST_CLICK.put(sp.getUUID(), now);
            ShopService.Result result = sp.getUUID().equals(shop.owner()) ? info(server, shop) : buy(sp, server, shop);
            sp.sendSystemMessage(Component.literal(result.message()).withStyle(result.success() ? ChatFormatting.GREEN : ChatFormatting.RED));
            return InteractionResult.SUCCESS;
        });
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, be) -> {
            if (!(level instanceof ServerLevel server)) return;
            SignShopData data = SignShopData.get(server.getServer());
            if (data.isEmpty()) return;
            SignShop shop = data.find(server.dimension().identifier(), pos);
            if (shop == null || !data.remove(shop)) return;
            if (player instanceof ServerPlayer sp) {
                AuditLog.record(server.getServer(), sp, "signshop.remove", describe(shop));
                sp.sendSystemMessage(Component.literal("Dein Laden wurde entfernt.").withStyle(ChatFormatting.YELLOW));
            }
        });
    }

    /* ---------- protection ---------- */

    /** The shop whose sign, chest or second chest half is at pos, or null. */
    public static SignShop shopAt(ServerLevel level, BlockPos pos) {
        SignShopData data = SignShopData.get(level.getServer());
        if (data.isEmpty()) return null;
        Identifier dim = level.dimension().identifier();
        SignShop shop = data.find(dim, pos);
        if (shop != null || !level.hasChunkAt(pos)) return shop;
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE)
            return data.find(dim, ChestBlock.getConnectedBlockPos(pos, state));
        return null;
    }
    /** Hard protection against explosions, pistons, fire, fluids and hoppers (hoppers have no owner). */
    public static boolean protectedAt(ServerLevel level, BlockPos pos) { return shopAt(level, pos) != null; }
    private static boolean isAdmin(ServerPlayer player) { return TownhallMod.isOperator(player.permissions()); }
    /** Opening: the sign is free to click; the chest is for the owner and admins. */
    public static boolean canUse(ServerPlayer player, ServerLevel level, BlockPos pos) {
        SignShop shop = shopAt(level, pos);
        return shop == null || shop.sign().equals(pos) || shop.owner().equals(player.getUUID()) || isAdmin(player);
    }
    /** Breaking/placing: owner only; admins may additionally remove the sign. */
    public static boolean deniesBuild(ServerPlayer player, ServerLevel level, BlockPos pos) {
        SignShop shop = shopAt(level, pos);
        if (shop == null || shop.owner().equals(player.getUUID())) return false;
        return !(shop.sign().equals(pos) && isAdmin(player));
    }

    /* ---------- helpers ---------- */

    static BlockPos chestFor(BlockPos signPos, BlockState state) {
        if (state.getBlock() instanceof WallSignBlock) return signPos.relative(state.getValue(WallSignBlock.FACING).getOpposite());
        if (state.getBlock() instanceof StandingSignBlock) return signPos.below();
        return null;
    }
    public static Container container(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock chest) return ChestBlock.getContainer(chest, state, level, pos, true);
        if (state.getBlock() instanceof BarrelBlock && level.getBlockEntity(pos) instanceof BarrelBlockEntity barrel) return barrel;
        return null;
    }
    private static boolean hasLootTable(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (level.getBlockEntity(pos) instanceof RandomizableContainerBlockEntity c && c.getLootTable() != null) return true;
        if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE)
            return level.getBlockEntity(ChestBlock.getConnectedBlockPos(pos, state)) instanceof RandomizableContainerBlockEntity c && c.getLootTable() != null;
        return false;
    }
    private static String name(ItemStack stack) { return stack.getHoverName().getString(); }
    private static String money(Shop.Currency currency, int n) {
        return n + " " + (n == 1 ? (currency == Shop.Currency.DIAMOND ? "Diamant" : "Smaragd") : currency.label());
    }
    private static String describe(SignShop s) {
        return "owner=" + s.owner() + " dimension=" + s.dimension() + " sign=" + s.sign().toShortString()
            + " chest=" + s.chest().toShortString() + " amount=" + s.amount() + " price=" + s.price() + " currency=" + s.currency();
    }
    private static boolean valid(ServerLevel level, SignShop shop) {
        if (!level.hasChunkAt(shop.sign()) || !level.hasChunkAt(shop.chest())) return true;
        return level.getBlockState(shop.sign()).getBlock() instanceof SignBlock && container(level, shop.chest()) != null;
    }

    /* ---------- creation ---------- */

    /** Called after a player edited a sign. Returns null when it is no shop sign (it then stays a normal sign). */
    public static ShopService.Result create(ServerPlayer player, SignBlockEntity sign) {
        if (!(sign.getLevel() instanceof ServerLevel level) || sign.isWaxed()) return null;
        List<Component> lines = sign.getText(SignTextSlot.FRONT).getMessages(false);
        String head = lines.get(0).getString().strip().toLowerCase(Locale.ROOT);
        if (!head.equals("[laden]") && !head.equals("[shop]")) return null;
        BlockPos signPos = sign.getBlockPos();
        SignShopData data = SignShopData.get(level.getServer());
        if (data.find(level.dimension().identifier(), signPos) != null) return null;
        ShopService.Result result = createChecked(player, level, sign, lines, data);
        player.sendSystemMessage(Component.literal(result.message()).withStyle(result.success() ? ChatFormatting.GREEN : ChatFormatting.RED));
        return result;
    }
    private static ShopService.Result createChecked(ServerPlayer player, ServerLevel level, SignBlockEntity sign,
                                                    List<Component> lines, SignShopData data) {
        String restricted = ShopService.restricted(player);
        if (restricted != null) return fail(restricted);
        if (!RoleService.hasPermission(player, "shop.manage")) return fail("Dir fehlt die Händlerrolle, um Läden anzulegen.");
        if (!TownhallMod.CONFIG.get().city.shopsEnabled) return fail("Spielerläden sind deaktiviert.");
        BlockPos signPos = sign.getBlockPos();
        BlockPos chestPos = chestFor(signPos, sign.getBlockState());
        Container container = chestPos == null ? null : container(level, chestPos);
        if (container == null) return fail("Das Schild muss als Wandschild an einer Truhe oder einem Fass hängen oder direkt darüber stehen.");
        if (shopAt(level, chestPos) != null) return fail("Diese Truhe hat schon ein Ladenschild.");
        if (ShopService.protectedAt(level, chestPos) || ElectionService.protectedAt(level, chestPos))
            return fail("Diese Truhe gehört schon zu einem Laden oder einer Wahlstelle.");
        if (hasLootTable(level, chestPos)) return fail("Diese Truhe ist als Laden nicht erlaubt.");
        if (!Protection.mayBuildAt(player, level, chestPos) || !Protection.mayBuildAt(player, level, signPos))
            return fail("Du darfst hier keinen Laden anlegen.");
        Matcher amountMatcher = AMOUNT.matcher(lines.get(1).getString().strip());
        int amount = amountMatcher.matches() ? Integer.parseInt(amountMatcher.group(1)) : 0;
        if (amount < 1 || amount > 64) return fail("Zeile 2: Menge von 1 bis 64 schreiben, zum Beispiel 16.");
        Matcher priceMatcher = PRICE.matcher(lines.get(2).getString().strip());
        if (!priceMatcher.matches()) return fail("Zeile 3: Preis wie 2 Smaragde oder 1 Diamant schreiben.");
        int price = Integer.parseInt(priceMatcher.group(1));
        Shop.Currency currency = priceMatcher.group(2).toLowerCase(Locale.ROOT).startsWith("d") ? Shop.Currency.DIAMOND : Shop.Currency.EMERALD;
        if (price < 1 || price > SignShop.MAX_PRICE) return fail("Zeile 3: Der Preis muss zwischen 1 und " + SignShop.MAX_PRICE + " liegen.");
        ItemStack template = new SlotSim(container).firstNonEmpty();
        if (template.isEmpty()) return fail("Leg zuerst die Ware in die Truhe.");
        if (ShopService.forbiddenGoods(template))
            return fail("Diese Ware ist nicht zulässig: keine Wahlzettel, Schlüssel oder amtlichen Gegenstände.");
        if (ItemStack.isSameItem(template, currency.item())) return fail("Ware und Bezahlung dürfen nicht dasselbe sein.");
        SignShop shop = new SignShop(player.getUUID(), level.dimension().identifier(), signPos, chestPos, template, amount, currency, price);
        if (!data.put(shop)) return fail("Hier gibt es schon einen Laden.");
        String goods = amount + "x " + name(template);
        if (goods.length() > 15) goods = goods.substring(0, 14) + "…";
        List<Component> text = List.of(
            Component.literal("[Laden]").withStyle(ChatFormatting.DARK_GREEN, ChatFormatting.BOLD),
            Component.literal(goods), Component.literal(money(currency, price)),
            Component.literal(player.getGameProfile().name()));
        sign.setText(new SignText(text, text, DyeColor.BLACK, false), SignTextSlot.FRONT);
        sign.setWaxed(true);
        AuditLog.record(level.getServer(), player, "signshop.create", describe(shop));
        return ok("Laden angelegt: " + amount + "x " + name(template) + " für " + money(currency, price) + ".");
    }

    /* ---------- trading ---------- */

    public static ShopService.Result info(ServerLevel level, SignShop shop) {
        Container container = container(level, shop.chest());
        if (container == null) return fail("Die Ladentruhe fehlt.");
        SlotSim sim = new SlotSim(container);
        long stock = sim.count(shop.template());
        return ok("Dein Laden: " + shop.amount() + "x " + name(shop.template()) + " für " + money(shop.currency(), shop.price())
            + ". Bestand: " + stock + " Stück (" + stock / shop.amount() + " Verkäufe). In der Truhe liegen "
            + sim.count(shop.currency().item()) + " " + shop.currency().label() + ".");
    }

    /** Atomic: all four checks pass before anything is changed. */
    public static ShopService.Result buy(ServerPlayer buyer, ServerLevel level, SignShop shop) {
        String restricted = ShopService.restricted(buyer);
        if (restricted != null) return fail(restricted);
        if (!TownhallMod.CONFIG.get().city.shopsEnabled) return fail("Spielerläden sind deaktiviert.");
        if (buyer.gameMode.getGameModeForPlayer() == GameType.CREATIVE) return fail("Im Kreativmodus kannst du nichts kaufen.");
        if (!valid(level, shop)) {
            SignShopData.get(level.getServer()).remove(shop);
            return fail("Dieser Laden ist nicht mehr gültig.");
        }
        Container container = container(level, shop.chest());
        if (container == null) return fail("Die Ladentruhe ist gerade nicht erreichbar.");
        ItemStack goods = shop.template();
        if (ShopService.forbiddenGoods(goods)) return fail("Diese Ware ist nicht zulässig.");
        ShopInventory player = new ShopInventory(buyer);
        if (!player.take(shop.currency().item(), shop.price()))
            return fail("Du hast nicht genug " + shop.currency().label() + " (" + shop.price() + " nötig).");
        SlotSim chest = new SlotSim(container);
        if (!chest.take(goods, shop.amount())) return fail("Der Laden ist ausverkauft.");
        if (!chest.give(shop.currency().item(), shop.price())) return fail("Die Ladentruhe ist voll, sag dem Besitzer Bescheid.");
        if (!player.give(goods, shop.amount())) return fail("Dein Inventar ist voll.");
        chest.commit();
        player.commit();
        String sold = shop.amount() + "x " + name(goods);
        AuditLog.record(level.getServer(), buyer, "signshop.buy", describe(shop));
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(shop.owner());
        if (owner != null) owner.sendSystemMessage(Component.literal(buyer.getGameProfile().name() + " hat bei dir " + sold
            + " gekauft (+" + money(shop.currency(), shop.price()) + ").").withStyle(ChatFormatting.GREEN));
        return ok("Du hast " + sold + " für " + money(shop.currency(), shop.price()) + " gekauft.");
    }

    /* ---------- /shop signs ---------- */

    public static List<String> listFor(ServerPlayer player, ServerLevel level) {
        List<String> lines = new ArrayList<>();
        for (SignShop shop : SignShopData.get(level.getServer()).all()) {
            if (!shop.owner().equals(player.getUUID())) continue;
            ServerLevel where = level.getServer().getLevel(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION, shop.dimension()));
            String stock = "Welt nicht geladen";
            if (where != null && where.hasChunkAt(shop.chest())) {
                Container c = container(where, shop.chest());
                stock = c == null ? "Truhe fehlt" : "Bestand " + new SlotSim(c).count(shop.template());
            }
            lines.add(shop.dimension().getPath() + " " + shop.sign().toShortString() + ": " + shop.amount() + "x "
                + name(shop.template()) + " für " + money(shop.currency(), shop.price()) + " – " + stock);
        }
        return lines;
    }
}
