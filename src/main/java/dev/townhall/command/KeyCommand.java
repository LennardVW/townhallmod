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

import java.util.Optional;

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

	private KeyCommand() {}

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
		if (name.isEmpty() || name.length() > 32) return fail(ctx, "Key names need 1-32 characters.");
		give(player, Keys.newKey(name));
		TownhallMod.LOGGER.info("{} made the key {}", player.getPlainTextName(), name);
		return ok(ctx, "New key \"" + name + "\". Right-click a door with it to lock the door.");
	}

	private static int copy(CommandContext<CommandSourceStack> ctx, ServerPlayer target) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		ItemStack held = player.getMainHandItem();
		boolean admin = Keys.isAdminKey(held);
		if (Keys.keyId(held).isEmpty() && !admin) return fail(ctx, "Hold a key in your main hand.");
		if (admin && !TownhallCommand.isOperator(ctx.getSource())) return fail(ctx, "Only operators can copy the admin key.");
		ServerPlayer receiver = target == null ? player : target;
		give(receiver, Keys.copy(held));
		String name = admin ? "Admin Key" : Keys.keyName(held);
		TownhallMod.LOGGER.info("{} copied the key {} for {}", player.getPlainTextName(), name, receiver.getPlainTextName());
		if (receiver != player) receiver.sendSystemMessage(Component.literal(player.getGameProfile().name() + " gave you a copy of the key \"" + name + "\".").withStyle(ChatFormatting.GREEN));
		return ok(ctx, "Copied the key \"" + name + "\"" + (receiver == player ? "." : " for " + receiver.getGameProfile().name() + "."));
	}

	private static int admin(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		give(player, Keys.adminKey());
		TownhallMod.LOGGER.info("{} took an admin key", player.getPlainTextName());
		return ok(ctx, "Admin key: opens every locked door while you are an operator.");
	}

	private static int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		Optional<BlockPos> door = lookedAtDoor(player);
		if (door.isEmpty()) return fail(ctx, "Look at a door.");
		Optional<DoorLocks.Lock> lock = Keys.lockAt(player.level(), door.get());
		if (lock.isEmpty()) return ok(ctx, "This door is not locked.");
		return ok(ctx, "Locked with the key \"" + lock.get().keyName() + "\" by " + lock.get().ownerName() + "."
				+ (Keys.carriesKey(player, lock.get().key()) ? " You have the key." : ""));
	}

	private static int unlock(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		Optional<BlockPos> door = lookedAtDoor(player);
		if (door.isEmpty()) return fail(ctx, "Look at a door.");
		if (!DoorLocks.get(player.level().getServer()).remove(player.level().dimension(), door.get())) return fail(ctx, "This door is not locked.");
		TownhallMod.LOGGER.info("{} removed the lock of the door at {}", player.getPlainTextName(), door.get().toShortString());
		return ok(ctx, "Lock removed.");
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

	private static int ok(CommandContext<CommandSourceStack> ctx, String message) {
		ctx.getSource().sendSuccess(() -> Component.literal(message).withStyle(ChatFormatting.GREEN), false);
		return 1;
	}

	private static int fail(CommandContext<CommandSourceStack> ctx, String message) {
		ctx.getSource().sendFailure(Component.literal(message));
		return 0;
	}
}
