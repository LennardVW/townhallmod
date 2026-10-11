package dev.townhall.protection;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import dev.townhall.TownhallMod;
import dev.townhall.mixin.BlockInputTagAccessor;
import dev.townhall.onboarding.Onboarding;
import dev.townhall.storage.ReturnPositionStorage;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.blocks.BlockInput;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.GameMasterBlock;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Vanilla command pasting for dimension builders, without granting a permission level. */
public final class BuilderSchematics {
	public static final int MAX_BLOCKS = 32768;
	public static final int MAX_COMMANDS_PER_TICK = 64;
	private static final DynamicCommandExceptionType DENIED = new DynamicCommandExceptionType(
			message -> Component.literal(String.valueOf(message)));

	private BuilderSchematics() {}

	/** Command-tree visibility; Creative is checked at execution so switching modes does not need a new tree. */
	public static boolean mayUse(CommandSourceStack source) {
		if (!(source.getEntity() instanceof ServerPlayer player) || source.getLevel() != player.level()) return false;
		return Protection.isBuilderHere(player) && !player.isSpectator() && !Onboarding.isRestricted(player)
				&& !ReturnPositionStorage.get(source.getServer()).state(player.getUUID()).confined();
	}

	/** Validate the entire command before vanilla clears containers, destroys blocks or writes any position. */
	public static void check(CommandSourceStack source, BoundingBox bounds, BlockInput block) throws CommandSyntaxException {
		if (!(source.getEntity() instanceof ServerPlayer player) || TownhallMod.isOperator(player.permissions())) return;
		if (!mayUse(source)) throw DENIED.create("Schematic-Befehle sind nur in deiner freigegebenen Bauwelt erlaubt.");
		if (!player.gameMode.isCreative()) throw DENIED.create("Nutze zuerst /builder creative zum Einfügen einer Schematic.");
		if (block.getState().getBlock() instanceof GameMasterBlock) throw DENIED.create("Admin-Blöcke können Bauhelfer nicht per Schematic platzieren.");
		var nbt = ((BlockInputTagAccessor) block).townhall$getTag();
		if (nbt != null && !nbt.isEmpty()) throw DENIED.create("NBT-Daten sind beim Bauhelfer-Paste gesperrt. Aktiviere pasteIgnoreInventories in Litematica.");
		long x = (long) bounds.maxX() - bounds.minX() + 1;
		long y = (long) bounds.maxY() - bounds.minY() + 1;
		long z = (long) bounds.maxZ() - bounds.minZ() + 1;
		if (x > MAX_BLOCKS || y > MAX_BLOCKS || z > MAX_BLOCKS || x * y * z > MAX_BLOCKS) {
			throw DENIED.create("Ein Bauhelfer-Befehl darf höchstens " + MAX_BLOCKS + " Blöcke umfassen.");
		}
		var level = source.getLevel();
		if (!level.getWorldBorder().isWithinBounds(new BlockPos(bounds.minX(), bounds.minY(), bounds.minZ()))
				|| !level.getWorldBorder().isWithinBounds(new BlockPos(bounds.maxX(), bounds.maxY(), bounds.maxZ()))) {
			throw DENIED.create("Der Zielbereich liegt außerhalb der Weltgrenze.");
		}
		for (BlockPos pos : BlockPos.betweenClosed(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ())) {
			if (!level.hasChunkAt(pos)) throw DENIED.create("Der Zielbereich ist nicht vollständig geladen.");
			if (!Protection.mayBuildAt(player, level, pos)) throw DENIED.create("Geschützter Bereich bei " + pos.toShortString() + ". Der Befehl wurde nicht ausgeführt.");
			// Commands bypass normal mining. Consult the same vetoes, including door keys and optional ChestLock.
			var state = level.getBlockState(pos);
			if (!state.isAir() && !PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(level, player, pos, state, level.getBlockEntity(pos))) {
				throw DENIED.create("Dieser Block ist gegen Änderungen geschützt: " + pos.toShortString());
			}
		}
	}
}
