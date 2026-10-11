package dev.townhall.shop.sign;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.townhall.shop.Shop;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/** One sign shop: the stock and the takings live in the real chest; this record only stores the offer. */
public record SignShop(UUID owner, Identifier dimension, BlockPos sign, BlockPos chest, ItemStack template,
                       int amount, Shop.Currency currency, int price) {
    public static final int MAX_PRICE = 2304;
    public static final Codec<SignShop> CODEC = RecordCodecBuilder.create(i -> i.group(
        UUIDUtil.STRING_CODEC.fieldOf("owner").forGetter(SignShop::owner),
        Identifier.CODEC.fieldOf("dimension").forGetter(SignShop::dimension),
        BlockPos.CODEC.fieldOf("sign").forGetter(SignShop::sign),
        BlockPos.CODEC.fieldOf("chest").forGetter(SignShop::chest),
        ItemStack.CODEC.fieldOf("template").forGetter(SignShop::template),
        Codec.intRange(1, 64).fieldOf("amount").forGetter(SignShop::amount),
        Shop.Currency.CODEC.fieldOf("currency").forGetter(SignShop::currency),
        Codec.intRange(1, MAX_PRICE).fieldOf("price").forGetter(SignShop::price)
    ).apply(i, SignShop::new));

    public SignShop {
        sign = sign.immutable();
        chest = chest.immutable();
        if (template.isEmpty() || amount < 1 || amount > 64 || price < 1 || price > MAX_PRICE)
            throw new IllegalArgumentException("Invalid sign shop");
        template = template.copyWithCount(1);
    }
    @Override public ItemStack template() { return template.copy(); }
}
