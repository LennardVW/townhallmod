package dev.townhall.test;

import dev.townhall.TownhallMod;
import dev.townhall.config.TownhallConfig;
import dev.townhall.display.SilentNames;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.List;

/** 1.19.1: names from mobs.silentNames silence creatures (name tag, command, NBT), only the mod's own silence is undone. */
public class SilentNameGameTests {

	private static final List<String> DEFAULTS = List.of("halts maul", "halt die schnauze", "psst", "leise");

	private static LivingEntity mob(GameTestHelper h, EntityType<? extends LivingEntity> type) {
		return type.create(h.getLevel(), EntitySpawnReason.TRIGGERED);
	}

	/** Runs the body with the given list and puts the old list back afterwards. */
	private static void withList(List<String> list, Runnable body) {
		TownhallConfig.Mobs mobs = TownhallMod.CONFIG.get().mobs;
		List<String> before = mobs.silentNames;
		mobs.silentNames = new ArrayList<>(list);
		try {
			body.run();
		} finally {
			mobs.silentNames = before;
		}
	}

	@GameTest
	public void normalizeAndMatch(GameTestHelper h) {
		h.assertTrue(SilentNames.normalize("  HALTS   Maul ").equals("halts maul"), "case, edge and double spaces");
		h.assertTrue(SilentNames.normalize(null).isEmpty(), "null is empty");
		h.assertTrue(SilentNames.matches("HALTS  MAUL ", DEFAULTS), "spelling variant matches");
		h.assertTrue(SilentNames.matches("halts maul", DEFAULTS), "exact matches");
		h.assertTrue(SilentNames.matches("Psst", DEFAULTS), "other default entry matches");
		h.assertFalse(SilentNames.matches("Halts Maul Bitte", DEFAULTS), "longer name does not match");
		h.assertFalse(SilentNames.matches("Bello", DEFAULTS), "other name does not match");
		h.assertFalse(SilentNames.matches("   ", List.of("", " ")), "blank never matches");
		h.assertFalse(SilentNames.matches("halts maul", List.of()), "empty list matches nothing");
		h.assertFalse(SilentNames.matches(null, DEFAULTS), "null name never matches");
		h.succeed();
	}

	@GameTest
	public void defaultConfigAndValidation(GameTestHelper h) {
		TownhallConfig fresh = new TownhallConfig();
		h.assertTrue(fresh.mobs.silentNames.equals(DEFAULTS), "default list");
		h.assertTrue(fresh.validate().stream().noneMatch(e -> e.contains("silentNames")), "default list is valid");
		fresh.mobs.silentNames = new ArrayList<>(java.util.Arrays.asList("a", null));
		h.assertTrue(fresh.validate().stream().anyMatch(e -> e.contains("silentNames")), "null entry rejected");
		fresh.mobs.silentNames = java.util.Collections.nCopies(51, "x");
		h.assertTrue(fresh.validate().stream().anyMatch(e -> e.contains("silentNames")), "more than 50 rejected");
		fresh.mobs.silentNames = List.of("x".repeat(65));
		h.assertTrue(fresh.validate().stream().anyMatch(e -> e.contains("silentNames")), "name over 64 characters rejected");
		fresh.mobs.silentNames = List.of();
		h.assertTrue(fresh.validate().stream().noneMatch(e -> e.contains("silentNames")), "empty list is valid");
		fresh.mobs = null;
		h.assertTrue(fresh.validate().stream().anyMatch(e -> e.contains("silentNames")), "missing section rejected");
		h.succeed();
	}

	@GameTest
	public void nameTagSilencesCowAndZombie(GameTestHelper h) {
		withList(DEFAULTS, () -> {
			for (var type : List.of(EntityTypes.COW, EntityTypes.ZOMBIE)) {
				for (String name : List.of("Halts Maul", "HALTS  MAUL ", "halts maul")) {
					LivingEntity e = mob(h, type);
					e.setCustomName(Component.literal(name));
					h.assertTrue(e.isSilent(), type + " silent for '" + name + "'");
					h.assertTrue(e.entityTags().contains(SilentNames.TAG), "marker tag set");
				}
				LivingEntity other = mob(h, type);
				other.setCustomName(Component.literal("Bello"));
				h.assertFalse(other.isSilent(), type + " not silent for another name");
				h.assertFalse(other.entityTags().contains(SilentNames.TAG), "no marker tag");
			}
		});
		h.succeed();
	}

	@GameTest
	public void renameOnlyUnsilencesOwnSilence(GameTestHelper h) {
		withList(DEFAULTS, () -> {
			LivingEntity ours = mob(h, EntityTypes.COW);
			ours.setCustomName(Component.literal("Psst"));
			ours.setCustomName(Component.literal("Else"));
			h.assertFalse(ours.isSilent(), "renamed: loud again");
			h.assertFalse(ours.entityTags().contains(SilentNames.TAG), "marker removed");

			LivingEntity again = mob(h, EntityTypes.COW);
			again.setCustomName(Component.literal("Psst"));
			again.setCustomName(null);
			h.assertFalse(again.isSilent(), "name removed: loud again");

			LivingEntity swap = mob(h, EntityTypes.COW);
			swap.setCustomName(Component.literal("Psst"));
			swap.setCustomName(Component.literal("Leise"));
			h.assertTrue(swap.isSilent() && swap.entityTags().contains(SilentNames.TAG), "silent name to silent name stays silent");

			LivingEntity before = mob(h, EntityTypes.ZOMBIE);
			before.setSilent(true);
			before.setCustomName(Component.literal("Halts Maul"));
			h.assertFalse(before.entityTags().contains(SilentNames.TAG), "already silent: no marker");
			before.setCustomName(Component.literal("Else"));
			h.assertTrue(before.isSilent(), "already silent stays silent after rename");
			before.setCustomName(null);
			h.assertTrue(before.isSilent(), "already silent stays silent after name removal");

			LivingEntity other = mob(h, EntityTypes.COW);
			other.setSilent(true);
			other.setCustomName(Component.literal("Else"));
			h.assertTrue(other.isSilent(), "silent by someone else, unrelated name: untouched");
		});
		h.succeed();
	}

	@GameTest
	public void playersAndEmptyListAreUntouched(GameTestHelper h) {
		var player = h.makeMockServerPlayerInLevel();
		withList(DEFAULTS, () -> {
			player.setCustomName(Component.literal("Halts Maul"));
			h.assertFalse(player.isSilent(), "players are never silenced");
		});
		withList(List.of(), () -> {
			LivingEntity cow = mob(h, EntityTypes.COW);
			cow.setCustomName(Component.literal("Halts Maul"));
			h.assertFalse(cow.isSilent(), "empty list: nothing happens");
		});
		h.succeed();
	}

	@GameTest
	public void commandAddsAndRemoves(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		List<String> before = TownhallMod.CONFIG.get().mobs.silentNames;
		try {
			TownhallMod.CONFIG.get().mobs.silentNames = new ArrayList<>(DEFAULTS);
			var src = server.createCommandSourceStack();
			server.getCommands().performPrefixedCommand(src, "townhall silentnames add  Sei   RUHIG ");
			h.assertTrue(TownhallMod.CONFIG.get().mobs.silentNames.contains("sei ruhig"), "add stores the normalized name");
			server.getCommands().performPrefixedCommand(src, "townhall silentnames add SEI ruhig");
			h.assertTrue(TownhallMod.CONFIG.get().mobs.silentNames.stream().filter("sei ruhig"::equals).count() == 1, "no duplicate");
			server.getCommands().performPrefixedCommand(src, "townhall silentnames remove SEI RUHIG");
			h.assertFalse(TownhallMod.CONFIG.get().mobs.silentNames.contains("sei ruhig"), "remove deletes it");
			server.getCommands().performPrefixedCommand(src, "townhall silentnames remove psst");
			h.assertFalse(SilentNames.matches("psst", TownhallMod.CONFIG.get().mobs.silentNames), "default entry removed");
			server.getCommands().performPrefixedCommand(src, "townhall silentnames");
		} finally {
			TownhallMod.CONFIG.get().mobs.silentNames = before;
			TownhallMod.CONFIG.save();
		}
		h.succeed();
	}
}
