package dev.townhall.shop;

import dev.townhall.TownhallMod;
import dev.townhall.audit.AuditLog;
import dev.townhall.city.CityAccess;
import dev.townhall.city.RoleService;
import dev.townhall.election.ElectionService;
import dev.townhall.onboarding.Onboarding;
import dev.townhall.protection.Protection;
import dev.townhall.storage.ReturnPositionStorage;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ContainerComponent;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;

import java.util.List;
import java.util.UUID;

/** Server-thread transactions; no tick task, asynchronous callbacks, custom item, or virtual economy. */
public final class ShopService {
    public record Result(boolean success, String message) {}
    private static Result fail(String text) { return new Result(false, text); }
    private static Result ok(String text) { return new Result(true, text); }
    private ShopService() {}

    public static void registerEvents() {
        ServerLifecycleEvents.SERVER_STARTED.register(ShopService::initialize);
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!(player instanceof ServerPlayer sp) || !(level instanceof ServerLevel server)
                || canUse(sp, server, hit.getBlockPos())) return InteractionResult.PASS;
            sp.sendOverlayMessage(Component.literal("Ladenfass: /shop info und /shop buy verwenden."));
            return InteractionResult.FAIL;
        });
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) -> {
            if (!(level instanceof ServerLevel server) || !protectedAt(server, pos)) return true;
            player.sendSystemMessage(Component.literal("Ladenfass geschützt. Erst den leeren Laden mit /shop delete entfernen."));
            return false;
        });
    }
    /** Also usable by a parent's existing startup handler; load before ticks can ask protection predicates. */
    public static void initialize(net.minecraft.server.MinecraftServer server) { ShopData.get(server); }
    /** Protection stays active while trading is disabled and even when the marker was externally removed. */
    public static boolean protectedAt(ServerLevel level, BlockPos pos) {
        return ShopData.get(level.getServer()).idAt(level.dimension().identifier(), pos) != null;
    }
    public static boolean canUse(ServerPlayer player, ServerLevel level, BlockPos pos) { return !protectedAt(level, pos); }
    public static boolean blocksAutomation(ServerLevel level, BlockPos pos) { return protectedAt(level, pos); }

    private static void thread(CommandSourceStack source) {
        if (!source.getServer().isSameThread()) throw new IllegalStateException("Shop mutation outside server thread");
    }
    private static ServerPlayer player(CommandSourceStack source) {
        return source.getEntity() instanceof ServerPlayer p ? p : null;
    }
    private static String restricted(ServerPlayer player) {
        if (Onboarding.isRestricted(player)) return "Bitte zuerst die Serverregeln akzeptieren.";
        if (!TownhallMod.isOperator(player.permissions())
            && ReturnPositionStorage.get(player.level().getServer()).state(player.getUUID()).confined())
            return "Während der Haft sind Ladenaktionen gesperrt.";
        if (player.gameMode.getGameModeForPlayer() == GameType.SPECTATOR) return "Zuschauer können nicht handeln.";
        return null;
    }
    private static String near(ServerPlayer player, Shop shop) {
        ServerLevel level = player.level();
        if (!level.dimension().identifier().equals(shop.dimension()) || !level.hasChunkAt(shop.pos()))
            return "Das Ladenfass muss in deiner geladenen Welt sein.";
        if (player.position().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(shop.pos())) > 36)
            return "Du musst höchstens 6 Blöcke vom Ladenfass entfernt sein.";
        if (!(level.getBlockEntity(shop.pos()) instanceof BarrelBlockEntity barrel) || barrel.getLootTable() != null
            || !barrel.isEmpty()) return "Das Ladenfass fehlt oder ist nicht leer. Bitte einen Admin um Wiederherstellung bitten.";
        return null;
    }
    private static String ownerAccess(CommandSourceStack source, Shop shop, boolean manage, boolean recovery) {
        ServerPlayer player = player(source);
        if (player == null) return "Dieser Befehl benötigt einen Spieler.";
        String restricted = restricted(player);
        if (restricted != null) return restricted;
        if (recovery) return CityAccess.isAdmin(source) ? null : "Nur Admins dürfen fremde Ladenwerte wiederherstellen.";
        if (!shop.owner().equals(player.getUUID())) return "Dieser Laden gehört dir nicht.";
        if (manage && !RoleService.hasPermission(player, "shop.manage")) return "Dir fehlt das Händlerrecht shop.manage.";
        if (manage && !TownhallMod.CONFIG.get().city.shopsEnabled) return "Spielerläden sind deaktiviert. Abholen bleibt möglich.";
        if (manage && player.gameMode.getGameModeForPlayer() == GameType.CREATIVE)
            return "Angebote und Einzahlungen sind nur ohne Creative möglich.";
        return near(player, shop);
    }
    private static void audit(CommandSourceStack source, String action, String id, Shop before, Shop after) {
        // No item component/book contents enter the log.
        Shop at = after == null ? before : after;
        AuditLog.record(source, "shop." + action, "id=" + id + " dimension=" + at.dimension() + " pos=" + at.pos().toShortString()
            + " before=" + summary(before) + " after=" + summary(after));
    }
    private static String summary(Shop shop) {
        return shop == null ? "-" : "owner=" + shop.owner() + ",stock=" + shop.stock() + ",diamonds=" + shop.diamonds()
            + ",emeralds=" + shop.emeralds() + ",amount=" + shop.amount() + ",price=" + shop.price() + ",currency=" + shop.currency();
    }
    private static void store(CommandSourceStack source, String action, String id, Shop before, Shop after, ShopInventory inventory) {
        ShopData.get(source.getServer()).put(id, after);
        if (inventory != null) inventory.commit();
        audit(source, action, id, before, after);
    }
    public static Result create(CommandSourceStack source, String id, UUID owner, BlockPos pos) {
        thread(source);
        if (!CityAccess.isAdmin(source)) return fail("Nur Admins dürfen Läden anlegen.");
        ServerPlayer p = player(source);
        if (p == null) return fail("Zum Anlegen musst du selbst das Fass ansehen.");
        String restricted = restricted(p);
        if (restricted != null) return fail(restricted);
        if (!id.matches("[a-z0-9_-]{1,48}")) return fail("Laden-ID: 1–48 Kleinbuchstaben, Ziffern, _ oder -.");
        ShopData data = ShopData.get(source.getServer());
        if (data.shop(id) != null || protectedAt(p.level(), pos)) return fail("Laden-ID oder Fass ist bereits vergeben.");
        if (!p.level().hasChunkAt(pos) || p.getEyePosition().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) > 36)
            return fail("Das Fass muss geladen und in Reichweite sein.");
        if (!p.level().getBlockState(pos).is(Blocks.BARREL)
            || !(p.level().getBlockEntity(pos) instanceof BarrelBlockEntity barrel)
            || barrel.getLootTable() != null || !barrel.isEmpty()) return fail("Bitte ein leeres einzelnes Fass ohne Beutetabelle auswählen.");
        if (!Protection.mayBuildAt(p, p.level(), pos)) return fail("Du darfst an diesem Fass nicht bauen.");
        if (ElectionService.protectedAt(p.level(), pos)) return fail("Das Fass gehört bereits zu einer Wahlstelle.");
        Shop shop = new Shop(owner, p.level().dimension().identifier(), pos, ItemStack.EMPTY, 1, 1, Shop.Currency.DIAMOND, 0, 0, 0);
        // A menu opened before registration must not retain an editable reference to the marker.
        for (ServerPlayer viewer : source.getServer().getPlayerList().getPlayers())
            if (viewer.containerMenu instanceof ChestMenu menu && menu.getContainer() == barrel) viewer.closeContainer();
        data.put(id, shop);
        audit(source, "create", id, null, shop);
        return ok("Laden " + id + " angelegt; Eigentümer " + owner + ".");
    }
    public static Result delete(CommandSourceStack source, String id) {
        thread(source);
        if (!CityAccess.isAdmin(source)) return fail("Nur Admins dürfen Läden löschen.");
        ShopData data = ShopData.get(source.getServer());
        Shop before = data.shop(id);
        if (before == null) return fail("Unbekannter Laden.");
        if (!before.empty()) return fail("Der Laden enthält Werte. Eigentümer abholen lassen oder /shop recover ausdrücklich verwenden.");
        String marker = emptyMarker(source, before);
        if (marker != null) return fail(marker);
        data.removeEmpty(id);
        audit(source, "delete", id, before, null);
        return ok("Leeren Laden " + id + " gelöscht; das Fass bleibt stehen.");
    }
    public static Result owner(CommandSourceStack source, String id, UUID owner) {
        thread(source);
        if (!CityAccess.isAdmin(source)) return fail("Nur Admins dürfen Eigentümer ändern.");
        Shop before = ShopData.get(source.getServer()).shop(id);
        if (before == null) return fail("Unbekannter Laden.");
        if (!before.empty()) return fail("Eigentümerwechsel nur bei leerem Bestand und leeren Einnahmen. Zuerst abholen oder ausdrücklich wiederherstellen.");
        String marker = emptyMarker(source, before);
        if (marker != null) return fail(marker);
        store(source, "owner", id, before, before.withOwner(owner), null);
        return ok("Eigentümer von " + id + " geändert.");
    }
    private static String emptyMarker(CommandSourceStack source, Shop shop) {
        ServerLevel level = source.getServer().getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, shop.dimension()));
        if (level == null || !level.hasChunkAt(shop.pos())) return "Vor dem Löschen oder Eigentümerwechsel muss die Ladenposition geladen sein.";
        var be = level.getBlockEntity(shop.pos());
        if (be instanceof RandomizableContainerBlockEntity container && container.getLootTable() != null
            || be instanceof net.minecraft.world.Container inventory && !inventory.isEmpty())
            return "Am Ladenblock liegt noch echtes Inventar. Zuerst ohne Verlust wiederherstellen; Laden bleibt geschützt.";
        return null;
    }
    public static Result offer(CommandSourceStack source, String id, int amount, int price, Shop.Currency currency) {
        thread(source);
        Shop before = ShopData.get(source.getServer()).shop(id);
        if (before == null) return fail("Unbekannter Laden.");
        String access = ownerAccess(source, before, true, false);
        if (access != null) return fail(access);
        ItemStack template = player(source).getMainHandItem();
        if (template.isEmpty() || forbiddenGoods(template)) return fail("Diese Ware ist nicht zulässig: keine Wahlzettel, Schlüssel oder amtlichen Gegenstände.");
        if (amount < 1 || amount > 64 || price < 1 || price > Shop.MAX_PRICE) return fail("Menge muss 1–64 und Preis 1–1000000 sein.");
        if (before.stock() > 0 && !ItemStack.isSameItemSameComponents(before.template(), template))
            return fail("Vor einem Warenwechsel den gesamten Bestand abholen.");
        Shop after = new Shop(before.owner(), before.dimension(), before.pos(), template, amount, price, currency,
            before.stock(), before.diamonds(), before.emeralds());
        store(source, "offer", id, before, after, null);
        return ok("Angebot: " + amount + " × " + template.getHoverName().getString() + " für " + price + " " + currency.label() + ".");
    }
    /** count=0 means all matching carried items; a full merchant stock rejects the entire deposit. */
    public static Result stock(CommandSourceStack source, String id, int count) {
        thread(source);
        Shop before = ShopData.get(source.getServer()).shop(id);
        if (before == null) return fail("Unbekannter Laden.");
        String access = ownerAccess(source, before, true, false);
        if (access != null) return fail(access);
        ItemStack template = before.template();
        if (template.isEmpty() || forbiddenGoods(template)) return fail("Zuerst ein zulässiges Angebot festlegen.");
        ShopInventory inventory = new ShopInventory(player(source));
        long units = count == 0 ? inventory.count(template) : count;
        if (units < 1) return fail("Keine passenden Waren im Inventar.");
        if (units > Shop.MAX_STOCK - before.stock()) return fail("Der Ladenbestand ist voll (höchstens 1728 Stück).");
        if (!inventory.take(template, units)) return fail("Nicht genug exakt passende Waren im Inventar.");
        store(source, "stock", id, before, before.quantities(before.stock() + units, before.diamonds(), before.emeralds()), inventory);
        return ok(units + " Stück eingelagert.");
    }
    public static Result withdraw(CommandSourceStack source, String id, int count, boolean recovery) {
        thread(source);
        Shop before = ShopData.get(source.getServer()).shop(id);
        if (before == null) return fail("Unbekannter Laden.");
        String access = ownerAccess(source, before, false, recovery);
        if (access != null) return fail(access);
        long units = count == 0 ? before.stock() : count;
        if (units < 1 || units > before.stock()) return fail("Nicht genug Ladenbestand.");
        ShopInventory inventory = new ShopInventory(player(source));
        if (!inventory.give(before.template(), units)) return fail("Dein Inventar hat nicht genug Platz. Bitte eine kleinere Menge abholen.");
        store(source, recovery ? "recover.stock" : "withdraw", id, before,
            before.quantities(before.stock() - units, before.diamonds(), before.emeralds()), inventory);
        return ok(units + " Stück ins eigene Inventar abgeholt.");
    }
    /** Default collects both currencies. The optional currency/count permits safe batches when earnings exceed carry space. */
    public static Result collect(CommandSourceStack source, String id, Shop.Currency currency, int count, boolean recovery) {
        thread(source);
        Shop before = ShopData.get(source.getServer()).shop(id);
        if (before == null) return fail("Unbekannter Laden.");
        String access = ownerAccess(source, before, false, recovery);
        if (access != null) return fail(access);
        long diamonds = currency == Shop.Currency.EMERALD ? 0 : before.diamonds();
        long emeralds = currency == Shop.Currency.DIAMOND ? 0 : before.emeralds();
        if (count < 0 || count > 0 && (currency == null || count > before.earnings(currency))) return fail("Nicht genug Einnahmen dieser Währung.");
        if (count > 0) { diamonds = currency == Shop.Currency.DIAMOND ? count : 0; emeralds = currency == Shop.Currency.EMERALD ? count : 0; }
        if (diamonds == 0 && emeralds == 0) return fail("Keine Einnahmen vorhanden.");
        ShopInventory inventory = new ShopInventory(player(source));
        if (!inventory.give(Shop.Currency.DIAMOND.item(), diamonds) || !inventory.give(Shop.Currency.EMERALD.item(), emeralds))
            return fail("Dein Inventar hat nicht genug Platz. Mit /shop collect <id> <diamond|emerald> <count> kleinere Mengen abholen.");
        store(source, recovery ? "recover.earnings" : "collect", id, before,
            before.quantities(before.stock(), before.diamonds() - diamonds, before.emeralds() - emeralds), inventory);
        return ok(diamonds + " Diamanten und " + emeralds + " Smaragde ins eigene Inventar abgeholt.");
    }
    public static Result buy(CommandSourceStack source, String id, int transactions) {
        thread(source);
        ServerPlayer p = player(source);
        if (p == null) return fail("Dieser Befehl benötigt einen Spieler.");
        String restricted = restricted(p);
        if (restricted != null) return fail(restricted);
        if (!TownhallMod.CONFIG.get().city.shopsEnabled) return fail("Spielerläden sind deaktiviert.");
        if (p.gameMode.getGameModeForPlayer() == GameType.CREATIVE) return fail("Einkäufe sind nur ohne Creative möglich.");
        Shop before = ShopData.get(source.getServer()).shop(id);
        if (before == null) return fail("Unbekannter Laden.");
        String near = near(p, before);
        if (near != null) return fail(near);
        if (transactions < 1 || transactions > Shop.MAX_STOCK) return fail("Ungültige Anzahl Käufe (1–1728).");
        if (before.template().isEmpty() || forbiddenGoods(before.template())) return fail("Kein zulässiges Angebot vorhanden.");
        long units = (long) before.amount() * transactions;
        long payment = (long) before.price() * transactions;
        if (units > before.stock()) return fail("Nicht genug Ladenbestand.");
        if (payment > Long.MAX_VALUE - before.earnings(before.currency())) return fail("Einnahmenspeicher voll; der Eigentümer muss zuerst abholen.");
        ShopInventory inventory = new ShopInventory(p);
        if (!inventory.take(before.currency().item(), payment)) return fail("Nicht genug unveränderte " + before.currency().label() + ".");
        // Capacity is checked after simulated payment: the spent currency may free the necessary slot.
        if (!inventory.give(before.template(), units)) return fail("Dein Inventar hat nicht genug Platz.");
        long diamonds = before.diamonds() + (before.currency() == Shop.Currency.DIAMOND ? payment : 0);
        long emeralds = before.emeralds() + (before.currency() == Shop.Currency.EMERALD ? payment : 0);
        store(source, "buy", id, before, before.quantities(before.stock() - units, diamonds, emeralds), inventory);
        return ok(units + " Stück für " + payment + " " + before.currency().label() + " gekauft.");
    }

    /** Reserved civic data is rejected on any item type and inside all vanilla item containers. */
    public static boolean forbiddenGoods(ItemStack stack) { return forbiddenGoods(stack, 0, new int[]{0}); }
    private static boolean forbiddenGoods(ItemStack stack, int depth, int[] examined) {
        if (++examined[0] > 1024 || depth > 16 || stack.has(DataComponents.CONTAINER_LOOT)) return true;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data != null && data.copyTag().keySet().stream().anyMatch(k -> k.startsWith("townhall"))) return true;
        for (ContainerComponent<?> contents : new ContainerComponent<?>[]{stack.get(DataComponents.CONTAINER),
            stack.get(DataComponents.BUNDLE_CONTENTS), stack.get(DataComponents.CHARGED_PROJECTILES)})
            if (contents != null && contents.itemCopies().anyMatch(s -> forbiddenGoods(s, depth + 1, examined))) return true;
        return false;
    }
    public static boolean containsForbiddenGoods(List<ItemStack> items) { return items.stream().anyMatch(ShopService::forbiddenGoods); }
}
