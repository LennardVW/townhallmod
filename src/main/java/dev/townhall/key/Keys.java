package dev.townhall.key;

import dev.townhall.TownhallMod;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Door keys. A key is a tripwire hook with a key id in its custom data (vanilla item, so vanilla clients see it).
 * Right-click an unlinked door with a key: the door is linked to that key. Linked doors open only for players who
 * carry a key with the same id (copies share the id) or an operator holding the admin key. Sneak + right-click with
 * the key unlinks the door. Redstone and mobs can't open linked doors (DoorBlockMixin).
 */
public final class Keys {

	static final String KEY_TAG = "townhall_key";
	static final String ADMIN_TAG = "townhall_admin_key";

	private Keys() {}

	public static void register() {
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (!(player instanceof ServerPlayer sp) || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
			return onUseDoor(sp, (ServerLevel) level, hit.getBlockPos());
		});
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) -> {
			if (!(player instanceof ServerPlayer sp) || !(state.getBlock() instanceof DoorBlock)) return true;
			BlockPos lower = lowerHalf(pos, state);
			Optional<DoorLocks.Lock> lock = lockAt((ServerLevel) level, lower);
			if (lock.isEmpty()) return true;
			if (!mayOpen(sp, lock.get())) {
				sp.sendOverlayMessage(Component.literal("This door is locked. You need the key \"" + lock.get().keyName() + "\".").withStyle(ChatFormatting.RED));
				return false;
			}
			DoorLocks.get(sp.level().getServer()).remove(level.dimension(), lower);
			return true;
		});
	}

	// ------------------------------------------------------------ items

	public static ItemStack newKey(String name) {
		return keyItem(UUID.randomUUID(), name);
	}

	static ItemStack keyItem(UUID id, String name) {
		ItemStack stack = new ItemStack(Items.TRIPWIRE_HOOK);
		CompoundTag tag = new CompoundTag();
		tag.putString(KEY_TAG, id.toString());
		tag.putString("name", name);
		CustomData.set(DataComponents.CUSTOM_DATA, stack, tag);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal("Key: " + name).withStyle(s -> s.withColor(ChatFormatting.GOLD).withItalic(false)));
		stack.set(DataComponents.LORE, new ItemLore(List.of(
				Component.literal("Right-click a door to lock it to this key.").withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)),
				Component.literal("Sneak + right-click to unlock it again.").withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)))));
		return stack;
	}

	public static ItemStack adminKey() {
		ItemStack stack = new ItemStack(Items.TRIPWIRE_HOOK);
		CompoundTag tag = new CompoundTag();
		tag.putString(ADMIN_TAG, "1");
		CustomData.set(DataComponents.CUSTOM_DATA, stack, tag);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal("Admin Key").withStyle(s -> s.withColor(ChatFormatting.RED).withBold(true).withItalic(false)));
		stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		stack.set(DataComponents.LORE, new ItemLore(List.of(
				Component.literal("Opens every locked door (operators only).").withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)))));
		return stack;
	}

	/** A copy with the same key id (opens the same doors). */
	public static ItemStack copy(ItemStack key) {
		ItemStack copy = key.copy();
		copy.setCount(1);
		return copy;
	}

	public static Optional<UUID> keyId(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) return Optional.empty();
		try {
			return data.copyTag().getString(KEY_TAG).map(UUID::fromString);
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	public static String keyName(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data == null ? "" : data.copyTag().getString("name").orElse("");
	}

	public static boolean isAdminKey(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data != null && data.copyTag().getString(ADMIN_TAG).isPresent();
	}

	// ------------------------------------------------------------ doors

	static BlockPos lowerHalf(BlockPos pos, BlockState state) {
		return state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
	}

	/** Lock of the door whose lower half is here; drops the entry if the door is gone. */
	public static Optional<DoorLocks.Lock> lockAt(ServerLevel level, BlockPos lower) {
		DoorLocks locks = DoorLocks.get(level.getServer());
		Optional<DoorLocks.Lock> lock = locks.get(level.dimension(), lower);
		if (lock.isPresent() && !(level.getBlockState(lower).getBlock() instanceof DoorBlock)) {
			locks.remove(level.dimension(), lower);
			return Optional.empty();
		}
		return lock;
	}

	/** Lock of the door at any half; used by the door mixin (redstone, mobs). */
	public static boolean isLocked(Level level, BlockPos pos, BlockState state) {
		return level instanceof ServerLevel server && state.getBlock() instanceof DoorBlock
				&& lockAt(server, lowerHalf(pos, state)).isPresent();
	}

	public static boolean carriesKey(Player player, UUID keyId) {
		return player.getInventory().contains(stack -> keyId(stack).filter(keyId::equals).isPresent());
	}

	public static boolean mayOpen(ServerPlayer player, DoorLocks.Lock lock) {
		if (carriesKey(player, lock.key())) return true;
		return TownhallMod.isOperator(player.permissions()) && player.getInventory().contains(Keys::isAdminKey);
	}

	static InteractionResult onUseDoor(ServerPlayer player, ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof DoorBlock)) return InteractionResult.PASS;
		BlockPos lower = lowerHalf(pos, state);
		ItemStack held = player.getMainHandItem();
		Optional<UUID> heldKey = keyId(held);
		Optional<DoorLocks.Lock> lock = lockAt(level, lower);

		if (lock.isEmpty()) {
			if (heldKey.isEmpty()) return InteractionResult.PASS;
			DoorLocks.get(level.getServer()).put(level.dimension(), lower, new DoorLocks.Lock(heldKey.get().toString(), keyName(held),
					player.getUUID().toString(), player.getGameProfile().name()));
			TownhallMod.LOGGER.info("{} locked the door at {} in {} with key {}", player.getPlainTextName(), lower.toShortString(), level.dimension().identifier(), keyName(held));
			player.sendOverlayMessage(Component.literal("Door locked with key \"" + keyName(held) + "\".").withStyle(ChatFormatting.GREEN));
			resync(player, level, lower);
			return InteractionResult.SUCCESS;
		}

		if (player.isShiftKeyDown() && heldKey.filter(lock.get().key()::equals).isPresent()) {
			DoorLocks.get(level.getServer()).remove(level.dimension(), lower);
			TownhallMod.LOGGER.info("{} unlocked the door at {} in {}", player.getPlainTextName(), lower.toShortString(), level.dimension().identifier());
			player.sendOverlayMessage(Component.literal("Door is no longer locked.").withStyle(ChatFormatting.YELLOW));
			resync(player, level, lower);
			return InteractionResult.SUCCESS;
		}
		if (mayOpen(player, lock.get())) return InteractionResult.PASS;
		player.sendOverlayMessage(Component.literal("Locked. You need the key \"" + lock.get().keyName() + "\".").withStyle(ChatFormatting.RED));
		resync(player, level, lower);
		return InteractionResult.FAIL;
	}

	/** The client already swung the door in its own world; send the real state of both halves back. */
	private static void resync(ServerPlayer player, ServerLevel level, BlockPos lower) {
		player.connection.send(new ClientboundBlockUpdatePacket(level, lower));
		player.connection.send(new ClientboundBlockUpdatePacket(level, lower.above()));
	}
}
