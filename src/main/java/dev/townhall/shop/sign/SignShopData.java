package dev.townhall.shop.sign;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Persistent sign shops, indexed by sign and chest position. Entries with clashing positions are dropped on load. */
public final class SignShopData extends SavedData {
    public static final Codec<SignShopData> CODEC = RecordCodecBuilder.<SignShopData>create(i -> i.group(
        SignShop.CODEC.listOf().optionalFieldOf("shops", List.of()).forGetter(d -> new ArrayList<>(d.shops.values()))
    ).apply(i, SignShopData::new));
    public static final SavedDataType<SignShopData> TYPE = new SavedDataType<>(
        Identifier.fromNamespaceAndPath("townhall", "sign_shops"), SignShopData::new, CODEC, null);
    private record Key(Identifier dimension, BlockPos pos) {}
    private final Map<Key, SignShop> shops = new HashMap<>();   // by sign position
    private final Map<Key, SignShop> index = new HashMap<>();   // sign and chest position

    public SignShopData() {}
    private SignShopData(List<SignShop> list) { for (SignShop s : list) if (free(s)) add(s); }

    public static SignShopData get(MinecraftServer server) { return server.getDataStorage().computeIfAbsent(TYPE); }
    private boolean free(SignShop s) {
        return !index.containsKey(new Key(s.dimension(), s.sign())) && !index.containsKey(new Key(s.dimension(), s.chest()));
    }
    private void add(SignShop s) {
        shops.put(new Key(s.dimension(), s.sign()), s);
        index.put(new Key(s.dimension(), s.sign()), s);
        index.put(new Key(s.dimension(), s.chest()), s);
    }
    public boolean isEmpty() { return shops.isEmpty(); }
    public List<SignShop> all() { return List.copyOf(shops.values()); }
    public SignShop find(Identifier dimension, BlockPos pos) { return index.get(new Key(dimension, pos)); }
    public boolean put(SignShop shop) {
        if (!free(shop)) return false;
        add(shop);
        setDirty();
        return true;
    }
    public boolean remove(SignShop shop) {
        if (shops.remove(new Key(shop.dimension(), shop.sign())) == null) return false;
        index.remove(new Key(shop.dimension(), shop.sign()));
        index.remove(new Key(shop.dimension(), shop.chest()));
        setDirty();
        return true;
    }
}
