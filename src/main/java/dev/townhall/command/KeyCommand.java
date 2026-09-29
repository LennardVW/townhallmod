package dev.townhall.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.townhall.TownhallMod;
import dev.townhall.key.DoorLocks;
import dev.townhall.key.Keys;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static dev.townhall.command.Feedback.fail;
import static dev.townhall.command.Feedback.ok;

/**
 * <pre>
 * /key new &lt;name&gt;        everyone: get a new key
 * /key copy [player]      everyone: copy of the key in your hand (for you or another player)
 * /key info               everyone: which key locks the door you look at
 * /key admin              operator: admin key that opens every door
 * /key unlock             operator: remove the lock of the door you look at
 * </pre>
 */
public final class KeyCommand {

	/** Keys are free, so /key new and /key copy have a cooldown per player (operators exempt). In memory only. */
	static final long COOLDOWN_MILLIS = 10_000;
	private static final Map<UUID, Long> LAST_NEW = new HashMap<>();
	private static final Map<UUID, Long> LAST_COPY = new HashMap<>();

	private KeyCommand() {}

	/** Seconds left, or 0 if the player may run it now (then the use is recorded). */
	private static long cooldownLeft(CommandSourceStack source, ServerPlayer player, Map<UUID, Long> last) {
		if (TownhallCommand.isOperator(source)) return 0;
		long now = System.currentTimeMillis();
		Long previous = last.get(player.getUUID());
		if (previous != null && now - previous < COOLDOWN_MILLIS) return (COOLDOWN_MILLIS - (now - previous) + 999) / 1000;
		last.values().removeIf(t -> now - t >= COOLDOWN_MILLIS); // keeps the map small
		last.put(player.getUUID(), now);
		return 0;
	}

	/** Server stopped: cooldowns are in memory only and start fresh with the next server. */
	public static void reset() {
		LAST_NEW.clear();
		LAST_COPY.clear();
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("key")
				.then(Commands.literal("new").then(Commands.argument("name", StringArgumentType.greedyString()).executes(KeyCommand::newKey)))
				.then(Commands.literal("copy").executes(ctx -> copy(ctx, null))
						.then(Commands.argument("player", EntityArgument.player()).executes(ctx -> copy(ctx, EntityArgument.getPlayer(ctx, "player")))))
				.then(Commands.literal("info").executes(KeyCommand::info))
				.then(Commands.literal("admin").requires(TownhallCommand::isOperator).executes(KeyCommand::admin))
				.then(Commands.literal("unlock").requires(TownhallCommand::isOperator).executes(KeyCommand::unlock)));
	}

	private static int newKey(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		String name = StringArgumentType.getString(ctx, "name").strip();
		if (name.isEmpty() || name.length() > 32) return fail(ctx.getSource(), "Key names need 1-32 characters.");
		long wait = cooldownLeft(ctx.getSource(), player, LAST_NEW);
		if (wait > 0) return fail(ctx.getSource(), "Please wait " + wait + " s before making another key.");
		give(player, Keys.newKey(name));
		TownhallMod.LOGGER.info("{} made the key {}", player.getPlainTextName(), name);
		return ok(ctx.getSource(), "New key \"" + name + "\". Right-click a door with it to lock the door.");
	}

	private static int copy(CommandContext<CommandSourceStack> ctx, ServerPlayer target) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		ItemStack held = player.getMainHandItem();
		boolean admin = Keys.isAdminKey(held);
		if (Keys.keyId(held).isEmpty() && !admin) return fail(ctx.getSource(), "Hold a key in your main hand.");
		if (admin && !TownhallCommand.isOperator(ctx.getSource())) return fail(ctx.getSource(), "Only operators can copy the admin key.");
		long wait = cooldownLeft(ctx.getSource(), player, LAST_COPY);
		if (wait > 0) return fail(ctx.getSource(), "Please wait " + wait + " s before copying another key.");
		ServerPlayer receiver = target == null ? player : target;
		give(receiver, Keys.copy(held));
		String name = admin ? "Admin Key" : Keys.keyName(held);
		TownhallMod.LOGGER.info("{} copied the key {} for {}", player.getPlainTextName(), name, receiver.getPlainTextName());
		if (receiver != player) receiver.sendSystemMessage(Component.literal(player.getGameProfile().name() + " gave you a copy of the key \"" + name + "\".").withStyle(ChatFormatting.GREEN));
		return ok(ctx.getSource(), "Copied the key \"" + name + "\"" + (receiver == player ? "." : " for " + receiver.getGameProfile().name() + "."));
	}

	private static int admin(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		give(player, Keys.adminKey());
		TownhallMod.LOGGER.info("{} took an admin key", player.getPlainTextName());
		return ok(ctx.getSource(), "Admin key: opens every locked door while you are an operator.");
	}

	private static int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		Optional<BlockPos> door = lookedAtDoor(player);
		if (door.isEmpty()) return fail(ctx.getSource(), "Look at a door.");
		Optional<DoorLocks.Lock> lock = Keys.lockAt(player.level(), door.get());
		if (lock.isEmpty()) return ok(ctx.getSource(), "This door is not locked.");
		return ok(ctx.getSource(), "Locked with the key \"" + lock.get().keyName() + "\" by " + lock.get().ownerName() + "."
				+ (Keys.carriesKey(player, lock.get().key()) ? " You have the key." : ""));
	}

	private static int unlock(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		Optional<BlockPos> door = lookedAtDoor(player);
		if (door.isEmpty()) return fail(ctx.getSource(), "Look at a door.");
		if (!DoorLocks.get(player.level().getServer()).remove(player.level().dimension(), door.get())) return fail(ctx.getSource(), "This door is not locked.");
		TownhallMod.LOGGER.info("{} removed the lock of the door at {}", player.getPlainTextName(), door.get().toShortString());
		return ok(ctx.getSource(), "Lock removed.");
	}

	/** Lower half of the door the player looks at (5 blocks). */
	private static Optional<BlockPos> lookedAtDoor(ServerPlayer player) {
		HitResult hit = player.pick(5.0, 0f, false);
		if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) return Optional.empty();
		ServerLevel level = player.level();
		BlockState state = level.getBlockState(blockHit.getBlockPos());
		if (!(state.getBlock() instanceof DoorBlock)) return Optional.empty();
		return Optional.of(state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? blockHit.getBlockPos().below() : blockHit.getBlockPos());
	}

	private static void give(ServerPlayer player, ItemStack stack) {
		if (!player.getInventory().add(stack)) player.spawnAtLocation(player.level(), stack);
	}
}
