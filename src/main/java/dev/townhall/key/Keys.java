package dev.townhall.key;

import dev.townhall.TownhallMod;
import dev.townhall.protection.Protection;
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
 * Linking needs build rights at the door (Protection.mayBuild), so nobody locks public doors in build:false worlds.
 * A locked door can't be removed around the lock: it keeps standing without its support block (DoorBlockMixin),
 * pistons can't push or pop it (PistonStructureResolverMixin), explosions skip it (LockedDoorExplosionMixin), zombies can't break it
 * (BreakDoorGoalMixin). Any lock whose door block goes away is deleted, and a new door never inherits an old lock.
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
			if (!(player instanceof ServerPlayer sp)) return true;
			if (!(state.getBlock() instanceof DoorBlock)) return maySupportBreak(sp, (ServerLevel) level, pos);
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

	/** The block right under a locked door: only players who may open the door (or operators) break it. */
	private static boolean maySupportBreak(ServerPlayer player, ServerLevel level, BlockPos pos) {
		BlockPos above = pos.above();
		BlockState door = level.getBlockState(above);
		if (!(door.getBlock() instanceof DoorBlock) || door.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) return true;
		Optional<DoorLocks.Lock> lock = lockAt(level, above);
		if (lock.isEmpty() || mayOpen(player, lock.get()) || TownhallMod.isOperator(player.permissions())) return true;
		player.sendOverlayMessage(Component.literal("This block holds a locked door. You need the key \"" + lock.get().keyName() + "\".").withStyle(ChatFormatting.RED));
		return false;
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

	/** Any key (normal or admin). Cheap item check first: only runs when a crafting grid changes. */
	public static boolean isKey(ItemStack stack) {
		return stack.is(Items.TRIPWIRE_HOOK) && stack.has(DataComponents.CUSTOM_DATA) && (keyId(stack).isPresent() || isAdminKey(stack));
	}

	/** Keys are free (/key new), so they must not become tripwire hooks for trapped chests or crossbows. */
	public static boolean containsKey(List<ItemStack> items) {
		for (ItemStack stack : items) if (isKey(stack)) return true;
		return false;
	}

	// ------------------------------------------------------------ doors

	static BlockPos lowerHalf(BlockPos pos, BlockState state) {
		return state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
	}

	/** Lock of the door whose lower half is here; drops the entry if the door is gone. */
	public static Optional<DoorLocks.Lock> lockAt(ServerLevel level, BlockPos lower) {
		DoorLocks locks = DoorLocks.get(level.getServer());
		if (locks.size() == 0) return Optional.empty(); // most servers have no locks: DoorBlockMixin asks on every neighbor update
		Optional<DoorLocks.Lock> lock = locks.get(level.dimension(), lower);
		if (lock.isPresent() && !(level.getBlockState(lower).getBlock() instanceof DoorBlock)) {
			locks.remove(level.dimension(), lower);
			return Optional.empty();
		}
		return lock;
	}

	/** A door's lower half was replaced by another block (any cause): its lock goes with it. */
	public static void onDoorRemoved(ServerLevel level, BlockPos pos, BlockState oldState) {
		if (oldState.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) return;
		DoorLocks locks = DoorLocks.get(level.getServer());
		if (locks.size() > 0 && locks.remove(level.dimension(), pos)) {
			TownhallMod.LOGGER.info("Removed the lock of the door at {} in {}: the door is gone", pos.toShortString(), level.dimension().identifier());
		}
	}

	/** A new door was placed (lower half, not just opened or closed): it never inherits a stale lock from an old door there. */
	public static void onDoorPlaced(ServerLevel level, BlockPos pos, BlockState state, BlockState oldState) {
		if (state.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER || oldState.is(state.getBlock())) return;
		DoorLocks locks = DoorLocks.get(level.getServer());
		if (locks.size() > 0 && locks.remove(level.dimension(), pos)) {
			TownhallMod.LOGGER.info("Dropped a stale lock at {} in {}: a new door was placed there", pos.toShortString(), level.dimension().identifier());
		}
	}

	/** Lock of the door at any half; used by the door mixin (redstone, mobs). */
	public static boolean isLocked(Level level, BlockPos pos, BlockState state) {
		return level instanceof ServerLevel server && state.getBlock() instanceof DoorBlock
				&& lockAt(server, lowerHalf(pos, state)).isPresent();
	}

	/**
	 * Compares each stack's custom data with a prebuilt {townhall_key: id} tag (CustomData.matchedBy reads in place),
	 * instead of copying the whole custom data of every stack. Our keys always store UUID.toString().
	 */
	public static boolean carriesKey(Player player, UUID keyId) {
		CompoundTag wanted = new CompoundTag();
		wanted.putString(KEY_TAG, keyId.toString());
		return player.getInventory().contains(stack -> {
			CustomData data = stack.get(DataComponents.CUSTOM_DATA);
			return data != null && data.matchedBy(wanted);
		});
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
			if (!Protection.mayBuildAt(player, level, lower)) {
				// No build rights here (public door, build:false world): the door just works like a normal door.
				player.sendOverlayMessage(Component.literal("You can't lock doors here.").withStyle(ChatFormatting.RED));
				return InteractionResult.PASS;
			}
			DoorLocks.get(level.getServer()).put(level.dimension(), lower, new DoorLocks.Lock(heldKey.get().toString(), keyName(held),
					player.getUUID().toString(), player.getGameProfile().name()));
			dev.townhall.audit.AuditLog.record(level.getServer(), player, "door.lock", "Tür " + lower.toShortString());
			TownhallMod.LOGGER.info("{} locked the door at {} in {} with key {}", player.getPlainTextName(), lower.toShortString(), level.dimension().identifier(), keyName(held));
			player.sendOverlayMessage(Component.literal("Door locked with key \"" + keyName(held) + "\".").withStyle(ChatFormatting.GREEN));
			resync(player, level, lower);
			return InteractionResult.SUCCESS;
		}

		if (player.isShiftKeyDown() && heldKey.filter(lock.get().key()::equals).isPresent() && Protection.mayBuildAt(player, level, lower)) {
			DoorLocks.get(level.getServer()).remove(level.dimension(), lower);
			dev.townhall.audit.AuditLog.record(level.getServer(), player, "door.unlock", "Tür " + lower.toShortString());
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
