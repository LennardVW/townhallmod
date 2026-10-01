package dev.townhall.shop;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.Map;

/** World autosave/shutdown owns all IO. The position index is rebuilt on decoding and updated on mutation. */
public final class ShopData extends SavedData {
    public static final Codec<ShopData> CODEC = RecordCodecBuilder.<ShopData>create(i -> i.group(
        Codec.intRange(1, 2).optionalFieldOf("version", 1).forGetter(s -> 2),
        Codec.unboundedMap(Codec.STRING, Shop.CODEC).optionalFieldOf("shops", Map.of()).forGetter(s -> s.shops)
    ).apply(i, (version, shops) -> new ShopData(shops))).validate(s -> s.positions.size() != s.shops.size()
        ? DataResult.error(() -> "Duplicate shop position") : DataResult.success(s));
    public static final SavedDataType<ShopData> TYPE = new SavedDataType<>(
        Identifier.fromNamespaceAndPath("townhall", "shops"), ShopData::new, CODEC, null);
    private record Position(Identifier dimension, BlockPos pos) {}
    private final Map<String, Shop> shops;
    private final Map<Position, String> positions = new HashMap<>();

    public ShopData() { this(Map.of()); }
    private ShopData(Map<String, Shop> shops) {
        this.shops = new HashMap<>(shops);
        shops.forEach((id, shop) -> positions.put(new Position(shop.dimension(), shop.pos()), id));
    }
    public static ShopData get(MinecraftServer server) { return server.getDataStorage().computeIfAbsent(TYPE); }
    public Shop shop(String id) { return shops.get(id); }
    public Map<String, Shop> shops() { return Map.copyOf(shops); }
    public String idAt(Identifier dimension, BlockPos pos) { return positions.get(new Position(dimension, pos)); }
    public void put(String id, Shop shop) {
        if (shop.stock() > 0 && shop.template().isEmpty()) throw new IllegalArgumentException("Shop stock has no template");
        String other = idAt(shop.dimension(), shop.pos());
        if (other != null && !other.equals(id)) throw new IllegalArgumentException("Shop position already used");
        Shop previous = shops.put(id, shop);
        if (previous != null) positions.remove(new Position(previous.dimension(), previous.pos()));
        positions.put(new Position(shop.dimension(), shop.pos()), id);
        setDirty();
    }
    /** Rejects deleting value even when called outside the command tree. */
    public boolean removeEmpty(String id) {
        Shop shop = shops.get(id);
        if (shop == null || !shop.empty()) return false;
        shops.remove(id);
        positions.remove(new Position(shop.dimension(), shop.pos()));
        setDirty();
        return true;
    }
}
