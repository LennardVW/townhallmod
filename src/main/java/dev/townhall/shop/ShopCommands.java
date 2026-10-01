package dev.townhall.shop;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.townhall.TownhallMod;
import dev.townhall.audit.AuditLog;
import dev.townhall.city.CityAccess;
import dev.townhall.command.Feedback;
import dev.townhall.command.TownhallCommand;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Comparator;

/** Parent registers this dispatcher API alongside its other commands. */
public final class ShopCommands {
    private ShopCommands() {}
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("shop").executes(c -> list(c, 1))
            .then(Commands.literal("list").executes(c -> list(c, 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1)).executes(c -> list(c, IntegerArgumentType.getInteger(c, "page")))))
            .then(Commands.literal("info").then(id().executes(ShopCommands::info)))
            .then(Commands.literal("create").requires(CityAccess::isAdmin).then(id()
                .then(Commands.argument("owner", GameProfileArgument.gameProfile()).executes(ShopCommands::create))))
            .then(Commands.literal("delete").requires(CityAccess::isAdmin).then(id().executes(c -> reply(c, ShopService.delete(c.getSource(), id(c))))))
            .then(Commands.literal("enabled").requires(CityAccess::isAdmin)
                .then(Commands.argument("value", BoolArgumentType.bool()).executes(ShopCommands::enabled)))
            .then(ownerCommand("owner")).then(ownerCommand("change-owner"));
        var price = Commands.argument("price", IntegerArgumentType.integer(1, Shop.MAX_PRICE));
        for (Shop.Currency currency : Shop.Currency.values()) price.then(Commands.literal(currency.name().toLowerCase(java.util.Locale.ROOT))
            .executes(c -> reply(c, ShopService.offer(c.getSource(), id(c), IntegerArgumentType.getInteger(c, "amount"),
                IntegerArgumentType.getInteger(c, "price"), currency))));
        root.then(Commands.literal("offer").then(id().then(Commands.argument("amount", IntegerArgumentType.integer(1, 64)).then(price))));
        root.then(Commands.literal("stock").then(id().executes(c -> reply(c, ShopService.stock(c.getSource(), id(c), 0)))
            .then(Commands.argument("count", IntegerArgumentType.integer(1, (int) Shop.MAX_STOCK))
                .executes(c -> reply(c, ShopService.stock(c.getSource(), id(c), IntegerArgumentType.getInteger(c, "count")))))));
        root.then(Commands.literal("withdraw").then(withdraw(false)));
        root.then(Commands.literal("collect").then(collect(false)));
        root.then(Commands.literal("buy").then(id().executes(c -> reply(c, ShopService.buy(c.getSource(), id(c), 1)))
            .then(Commands.argument("transactions", IntegerArgumentType.integer(1, (int) Shop.MAX_STOCK))
                .executes(c -> reply(c, ShopService.buy(c.getSource(), id(c), IntegerArgumentType.getInteger(c, "transactions")))))));
        // Explicit recovery moves value only into the executing admin's own inventory, never into an arbitrary target.
        var recoverId = id().then(Commands.literal("stock").executes(c -> reply(c, ShopService.withdraw(c.getSource(), id(c), 0, true)))
            .then(Commands.argument("count", IntegerArgumentType.integer(1, (int) Shop.MAX_STOCK))
                .executes(c -> reply(c, ShopService.withdraw(c.getSource(), id(c), IntegerArgumentType.getInteger(c, "count"), true)))))
            .then(earningsRecovery());
        root.then(Commands.literal("recover").requires(CityAccess::isAdmin).then(recoverId));
        dispatcher.register(root);
    }
    private static RequiredArgumentBuilder<CommandSourceStack, String> id() {
        return Commands.argument("id", StringArgumentType.word()).suggests((c, b) -> {
            ShopData.get(c.getSource().getServer()).shops().keySet().stream().sorted()
                .filter(s -> s.startsWith(b.getRemaining())).limit(100).forEach(b::suggest);
            return b.buildFuture();
        });
    }
    private static String id(CommandContext<CommandSourceStack> c) { return StringArgumentType.getString(c, "id"); }
    private static int reply(CommandContext<CommandSourceStack> c, ShopService.Result result) {
        return result.success() ? Feedback.ok(c.getSource(), result.message()) : Feedback.fail(c.getSource(), result.message());
    }
    private static LiteralArgumentBuilder<CommandSourceStack> ownerCommand(String name) {
        return Commands.literal(name).requires(CityAccess::isAdmin).then(id()
            .then(Commands.argument("owner", GameProfileArgument.gameProfile()).executes(c -> {
                var profiles = GameProfileArgument.getGameProfiles(c, "owner");
                if (profiles.size() != 1) return Feedback.fail(c.getSource(), "Bitte genau einen Eigentümer angeben.");
                return reply(c, ShopService.owner(c.getSource(), id(c), profiles.iterator().next().id()));
            })));
    }
    private static RequiredArgumentBuilder<CommandSourceStack, String> withdraw(boolean recovery) {
        return id().executes(c -> reply(c, ShopService.withdraw(c.getSource(), id(c), 0, recovery)))
            .then(Commands.argument("count", IntegerArgumentType.integer(1, (int) Shop.MAX_STOCK))
                .executes(c -> reply(c, ShopService.withdraw(c.getSource(), id(c), IntegerArgumentType.getInteger(c, "count"), recovery))));
    }
    private static RequiredArgumentBuilder<CommandSourceStack, String> collect(boolean recovery) {
        var node = id().executes(c -> reply(c, ShopService.collect(c.getSource(), id(c), null, 0, recovery)));
        for (Shop.Currency currency : Shop.Currency.values()) node.then(currencyCollect(currency, recovery));
        return node;
    }
    private static LiteralArgumentBuilder<CommandSourceStack> currencyCollect(Shop.Currency currency, boolean recovery) {
        return Commands.literal(currency.name().toLowerCase(java.util.Locale.ROOT))
            .executes(c -> reply(c, ShopService.collect(c.getSource(), id(c), currency, 0, recovery)))
            .then(Commands.argument("count", IntegerArgumentType.integer(1, 2304))
                .executes(c -> reply(c, ShopService.collect(c.getSource(), id(c), currency, IntegerArgumentType.getInteger(c, "count"), recovery))));
    }
    private static LiteralArgumentBuilder<CommandSourceStack> earningsRecovery() {
        var node = Commands.literal("earnings").executes(c -> reply(c, ShopService.collect(c.getSource(), id(c), null, 0, true)));
        for (Shop.Currency currency : Shop.Currency.values()) node.then(currencyCollect(currency, true));
        return node;
    }
    private static int create(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        if (!(c.getSource().getEntity() instanceof ServerPlayer p)) return Feedback.fail(c.getSource(), "Bitte als Spieler das Fass ansehen.");
        HitResult hit = p.pick(6, 1, false);
        if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) return Feedback.fail(c.getSource(), "Bitte ein leeres Fass in Reichweite ansehen.");
        var profiles = GameProfileArgument.getGameProfiles(c, "owner");
        if (profiles.size() != 1) return Feedback.fail(c.getSource(), "Bitte genau einen Eigentümer angeben.");
        return reply(c, ShopService.create(c.getSource(), id(c), profiles.iterator().next().id(), block.getBlockPos()));
    }
    private static int enabled(CommandContext<CommandSourceStack> c) {
        if (!CityAccess.isAdmin(c.getSource())) return Feedback.fail(c.getSource(), "Nur Admins dürfen Läden umschalten.");
        if (!TownhallMod.CONFIG.canSave()) return Feedback.fail(c.getSource(), "Config nicht gespeichert: zuerst die ungültige Config reparieren und neu laden.");
        var settings = TownhallMod.CONFIG.get().city;
        boolean previous = settings.shopsEnabled;
        settings.shopsEnabled = BoolArgumentType.getBool(c, "value");
        if (!TownhallCommand.saveConfig(c.getSource())) { settings.shopsEnabled = previous; return 0; }
        AuditLog.record(c.getSource(), "shop.enabled", "before=" + previous + " after=" + settings.shopsEnabled);
        return Feedback.okAdmin(c.getSource(), settings.shopsEnabled ? "Spielerläden aktiviert." : "Spielerläden deaktiviert. Werte bleiben geschützt und abholbar.");
    }
    private static int info(CommandContext<CommandSourceStack> c) {
        Shop shop = ShopData.get(c.getSource().getServer()).shop(id(c));
        if (shop == null) return Feedback.fail(c.getSource(), "Unbekannter Laden.");
        boolean owner = c.getSource().getEntity() instanceof ServerPlayer p && p.getUUID().equals(shop.owner());
        String goods = shop.template().isEmpty() ? "kein Angebot" : shop.amount() + " × " + shop.template().getHoverName().getString()
            + " für " + shop.price() + " " + shop.currency().label();
        return Feedback.ok(c.getSource(), id(c) + ": " + goods + "; Bestand " + shop.stock() + "; Eigentümer " + shop.owner()
            + "; " + shop.dimension() + " " + shop.pos().toShortString()
            + (owner || CityAccess.isAdmin(c.getSource()) ? "; Einnahmen " + shop.diamonds() + " Diamanten, " + shop.emeralds() + " Smaragde" : ""));
    }
    private static int list(CommandContext<CommandSourceStack> c, int page) {
        var shops = ShopData.get(c.getSource().getServer()).shops();
        String entries = shops.entrySet().stream().sorted(Comparator.comparing(java.util.Map.Entry::getKey))
            .skip(((long) page - 1) * 10).limit(10).map(e -> e.getKey() + " (" + e.getValue().stock() + " Stück)")
            .collect(java.util.stream.Collectors.joining(", "));
        return Feedback.ok(c.getSource(), "Läden – Seite " + page + ": " + (entries.isEmpty() ? "keine" : entries));
    }
}
