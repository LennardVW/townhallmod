package dev.townhall.shop;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.UUID;

/** Immutable value record. ItemStack is copied on entry and access; quantities are item units, never money balances. */
public record Shop(UUID owner, Identifier dimension, BlockPos pos, ItemStack template, int amount,
                   int price, Currency currency, long stock, long diamonds, long emeralds) {
    public static final int MAX_PRICE = 1_000_000;
    public static final long MAX_STOCK = 27L * 64;

    public enum Currency {
        DIAMOND, EMERALD;
        public static final Codec<Currency> CODEC = Codec.STRING.comapFlatMap(s -> switch (s) {
            case "diamond" -> DataResult.success(DIAMOND);
            case "emerald" -> DataResult.success(EMERALD);
            default -> DataResult.error(() -> "Unknown shop currency: " + s);
        }, c -> c.name().toLowerCase(java.util.Locale.ROOT));
        public ItemStack item() { return new ItemStack(this == DIAMOND ? Items.DIAMOND : Items.EMERALD); }
        public String label() { return this == DIAMOND ? "Diamanten" : "Smaragde"; }
    }

    /* Version 1 stored one earnings field in the active currency. Read it into the matching physical item bucket;
       encode only the current buckets. Missing fields preserve empty shops from the first schema. */
    private record Stored(Shop shop, long legacy) {}
    private static final Codec<Long> UNITS = Codec.LONG.validate(Codec.checkRange(0L, Long.MAX_VALUE));
    public static final Codec<Shop> CODEC = RecordCodecBuilder.<Stored>create(i -> i.group(
        UUIDUtil.STRING_CODEC.fieldOf("owner").forGetter(s -> s.shop().owner()),
        Identifier.CODEC.fieldOf("dimension").forGetter(s -> s.shop().dimension()),
        BlockPos.CODEC.fieldOf("pos").forGetter(s -> s.shop().pos()),
        ItemStack.OPTIONAL_CODEC.optionalFieldOf("template", ItemStack.EMPTY).forGetter(s -> s.shop().template()),
        Codec.intRange(1, 64).optionalFieldOf("amount", 1).forGetter(s -> s.shop().amount()),
        Codec.intRange(1, MAX_PRICE).optionalFieldOf("price", 1).forGetter(s -> s.shop().price()),
        Currency.CODEC.optionalFieldOf("currency", Currency.DIAMOND).forGetter(s -> s.shop().currency()),
        Codec.LONG.validate(Codec.checkRange(0L, MAX_STOCK)).optionalFieldOf("stock", 0L).forGetter(s -> s.shop().stock()),
        UNITS.optionalFieldOf("diamonds", 0L).forGetter(s -> s.shop().diamonds()),
        UNITS.optionalFieldOf("emeralds", 0L).forGetter(s -> s.shop().emeralds()),
        UNITS.optionalFieldOf("earnings", 0L).forGetter(Stored::legacy)
    ).apply(i, (owner, dim, pos, template, amount, price, currency, stock, diamonds, emeralds, legacy) ->
        new Stored(new Shop(owner, dim, pos, template, amount, price, currency, stock, diamonds, emeralds), legacy)))
        .comapFlatMap(stored -> {
            Shop shop = stored.shop();
            if (stored.legacy() > Long.MAX_VALUE - shop.earnings(shop.currency()))
                return DataResult.error(() -> "Legacy shop earnings overflow");
            return DataResult.success(shop.quantities(shop.stock(),
                shop.diamonds() + (shop.currency() == Currency.DIAMOND ? stored.legacy() : 0),
                shop.emeralds() + (shop.currency() == Currency.EMERALD ? stored.legacy() : 0)));
        }, shop -> new Stored(shop, 0))
        .validate(s -> s.stock > 0 && s.template.isEmpty()
            ? DataResult.error(() -> "Shop stock has no item template") : DataResult.success(s));

    public Shop {
        pos = pos.immutable();
        template = template.isEmpty() ? ItemStack.EMPTY : template.copyWithCount(1);
        if (amount < 1 || amount > 64 || price < 1 || price > MAX_PRICE || stock < 0 || stock > MAX_STOCK
            || diamonds < 0 || emeralds < 0)
            throw new IllegalArgumentException("Invalid shop quantities");
    }
    @Override public ItemStack template() { return template.copy(); }
    public boolean empty() { return stock == 0 && diamonds == 0 && emeralds == 0; }
    public long earnings(Currency c) { return c == Currency.DIAMOND ? diamonds : emeralds; }
    public Shop quantities(long stock, long diamonds, long emeralds) {
        return new Shop(owner, dimension, pos, template, amount, price, currency, stock, diamonds, emeralds);
    }
    public Shop withOwner(UUID owner) {
        return new Shop(owner, dimension, pos, template, amount, price, currency, stock, diamonds, emeralds);
    }
}
