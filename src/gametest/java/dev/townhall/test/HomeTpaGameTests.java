package dev.townhall.test;

import dev.townhall.TownhallMod;
import dev.townhall.command.TpaCommands;
import dev.townhall.storage.HomeStorage;
import dev.townhall.storage.PlayerState;
import dev.townhall.storage.ReturnPositionStorage;
import dev.townhall.teleport.TeleportRules;
import dev.townhall.teleport.Warmup;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/** /sethome, /home, /tpa and the standing-still warmup. */
public class HomeTpaGameTests {

	private static void setup(GameTestHelper h) {
		for (int x = 0; x < 9; x++) for (int z = 0; z < 9; z++) h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
		var config = TownhallMod.CONFIG.get();
		config.onboarding.enabled = false;
		config.teleport.warmupSeconds = 1;
		config.teleport.cooldownSeconds = 0;
		config.homes.maxHomes = 1;
		TpaCommands.reset();
		TeleportRules.reset();
	}

	private static ServerPlayer playerAt(GameTestHelper h, double x, double z) {
		ServerPlayer p = h.makeMockServerPlayerInLevel();
		Vec3 abs = h.absoluteVec(new Vec3(x, 1, z));
		p.absSnapTo(abs.x, abs.y, abs.z, 0f, 0f);
		return p;
	}

	private static void move(GameTestHelper h, ServerPlayer p, double x, double z) {
		Vec3 abs = h.absoluteVec(new Vec3(x, 1, z));
		p.absSnapTo(abs.x, abs.y, abs.z, 0f, 0f);
	}

	private static void run(ServerPlayer p, String command) {
		p.level().getServer().getCommands().performPrefixedCommand(p.createCommandSourceStack(), command);
	}

	private static void near(GameTestHelper h, ServerPlayer p, double x, double z, String what) {
		Vec3 abs = h.absoluteVec(new Vec3(x, 1, z));
		h.assertTrue(p.position().distanceTo(abs) < 1.5, what + ": expected near " + abs + " but was " + p.position());
	}

	@GameTest(maxTicks = 100)
	public void homeTeleportsAfterWarmup(GameTestHelper h) {
		setup(h);
		ServerPlayer p = playerAt(h, 1.5, 1.5);
		run(p, "sethome");
		h.assertTrue(HomeStorage.get(h.getLevel().getServer()).get(p.getUUID(), "home").isPresent(), "home saved");
		move(h, p, 6.5, 6.5);
		run(p, "home");
		h.assertTrue(Warmup.isWaiting(p), "warmup running");
		h.runAfterDelay(8, () -> near(h, p, 6.5, 6.5, "still waiting"));
		h.runAfterDelay(35, () -> {
			near(h, p, 1.5, 1.5, "arrived at home");
			h.assertFalse(Warmup.isWaiting(p), "warmup done");
			h.succeed();
		});
	}

	@GameTest(maxTicks = 100)
	public void movementCancelsHome(GameTestHelper h) {
		setup(h);
		ServerPlayer p = playerAt(h, 1.5, 1.5);
		run(p, "sethome");
		move(h, p, 6.5, 6.5);
		run(p, "home");
		h.runAfterDelay(5, () -> p.absSnapTo(p.getX() + 1, p.getY(), p.getZ(), 0f, 0f));
		h.runAfterDelay(40, () -> {
			h.assertFalse(Warmup.isWaiting(p), "cancelled");
			near(h, p, 7.5, 6.5, "stayed where moved");
			h.succeed();
		});
	}

	@GameTest(maxTicks = 100)
	public void headTurnDoesNotCancelButDamageDoes(GameTestHelper h) {
		setup(h);
		ServerPlayer p = playerAt(h, 1.5, 1.5);
		run(p, "sethome");
		move(h, p, 6.5, 6.5);
		run(p, "home");
		h.runAfterDelay(5, () -> p.absSnapTo(p.getX(), p.getY(), p.getZ(), 90f, 30f));
		h.runAfterDelay(10, () -> h.assertTrue(Warmup.isWaiting(p), "turning the head keeps the warmup"));
		h.runAfterDelay(12, () -> Warmup.damaged(p));
		h.runAfterDelay(40, () -> {
			h.assertFalse(Warmup.isWaiting(p), "damage cancelled");
			near(h, p, 6.5, 6.5, "not teleported");
			h.succeed();
		});
	}

	@GameTest
	public void homeLimitOverwritesOnlySameName(GameTestHelper h) {
		setup(h);
		ServerPlayer p = playerAt(h, 1.5, 1.5);
		HomeStorage st = HomeStorage.get(h.getLevel().getServer());
		run(p, "sethome a");
		move(h, p, 3.5, 3.5);
		run(p, "sethome b");
		h.assertTrue(st.get(p.getUUID(), "b").isEmpty() && st.homes(p.getUUID()).size() == 1, "second name refused at the limit");
		run(p, "sethome a");
		h.assertTrue(st.homes(p.getUUID()).size() == 1 && Math.abs(st.get(p.getUUID(), "a").orElseThrow().x() - p.getX()) < 1e-6, "same name overwritten");
		TownhallMod.CONFIG.get().homes.maxHomes = 2;
		run(p, "sethome b");
		h.assertTrue(st.homes(p.getUUID()).size() == 2, "limit 2 allows a second");
		TownhallMod.CONFIG.get().homes.maxHomes = 1;
		h.succeed();
	}

	@GameTest
	public void delhomeRemoves(GameTestHelper h) {
		setup(h);
		ServerPlayer p = playerAt(h, 1.5, 1.5);
		HomeStorage st = HomeStorage.get(h.getLevel().getServer());
		run(p, "sethome");
		run(p, "delhome");
		h.assertTrue(st.homes(p.getUUID()).isEmpty(), "home deleted");
		h.succeed();
	}

	@GameTest
	public void homeStorageCodecRoundtrip(GameTestHelper h) {
		setup(h);
		HomeStorage st = new HomeStorage();
		var id = java.util.UUID.randomUUID();
		var home = new HomeStorage.Home(Level.NETHER, 1.5, 70.0, -3.25, 90f, 12f);
		st.set(id, "mine", home);
		var tag = HomeStorage.CODEC.encodeStart(NbtOps.INSTANCE, st).getOrThrow();
		HomeStorage back = HomeStorage.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
		h.assertTrue(back.get(id, "mine").equals(Optional.of(home)), "home survives the codec");
		h.succeed();
	}

	@GameTest(maxTicks = 100)
	public void tpaAcceptTeleportsAfterWarmup(GameTestHelper h) {
		setup(h);
		ServerPlayer a = playerAt(h, 1.5, 1.5);
		ServerPlayer b = playerAt(h, 6.5, 6.5);
		h.assertTrue(TpaCommands.request(a, b, false) == null, "request sent");
		h.assertTrue(TpaCommands.open(a.getUUID()).isPresent(), "request open");
		h.assertTrue(TpaCommands.accept(b, null) == null, "accepted");
		h.assertTrue(Warmup.isWaiting(a) && !Warmup.isWaiting(b), "requester waits");
		h.runAfterDelay(35, () -> {
			near(h, a, 6.5, 6.5, "requester arrived");
			h.succeed();
		});
	}

	@GameTest(maxTicks = 100)
	public void tpahereMovesTheTarget(GameTestHelper h) {
		setup(h);
		ServerPlayer a = playerAt(h, 1.5, 1.5);
		ServerPlayer b = playerAt(h, 6.5, 6.5);
		h.assertTrue(TpaCommands.request(a, b, true) == null, "request sent");
		h.assertTrue(TpaCommands.accept(b, a.getUUID()) == null, "accepted");
		h.assertTrue(Warmup.isWaiting(b) && !Warmup.isWaiting(a), "target waits");
		h.runAfterDelay(35, () -> {
			near(h, b, 1.5, 1.5, "target arrived");
			h.succeed();
		});
	}

	@GameTest
	public void tpaDenyAndCancelClearTheRequest(GameTestHelper h) {
		setup(h);
		ServerPlayer a = playerAt(h, 1.5, 1.5);
		ServerPlayer b = playerAt(h, 6.5, 6.5);
		TpaCommands.request(a, b, false);
		h.assertTrue(TpaCommands.deny(b, null) == null && TpaCommands.open(a.getUUID()).isEmpty(), "denied");
		h.assertFalse(Warmup.isWaiting(a), "no teleport after deny");
		TpaCommands.request(a, b, false);
		TpaCommands.request(a, b, true);
		h.assertTrue(TpaCommands.open(a.getUUID()).orElseThrow().here(), "one open request per requester (replaced)");
		h.assertTrue(TpaCommands.cancel(a) == null && TpaCommands.open(a.getUUID()).isEmpty(), "cancelled");
		h.assertTrue(TpaCommands.accept(b, null) != null, "nothing left to accept");
		h.succeed();
	}

	@GameTest
	public void tpaRequestExpiresAfter60Seconds(GameTestHelper h) {
		setup(h);
		ServerPlayer a = playerAt(h, 1.5, 1.5);
		ServerPlayer b = playerAt(h, 6.5, 6.5);
		long base = System.currentTimeMillis();
		TeleportRules.CLOCK = () -> base;
		TpaCommands.request(a, b, false);
		TeleportRules.CLOCK = () -> base + 59_000;
		h.assertTrue(TpaCommands.open(a.getUUID()).isPresent(), "still valid at 59 s");
		TeleportRules.CLOCK = () -> base + 61_000;
		h.assertTrue(TpaCommands.accept(b, null) != null, "expired request cannot be accepted");
		h.assertFalse(Warmup.isWaiting(a), "no teleport");
		TeleportRules.reset();
		h.succeed();
	}

	@GameTest
	public void tpatoggleBlocksRequests(GameTestHelper h) {
		setup(h);
		ServerPlayer a = playerAt(h, 1.5, 1.5);
		ServerPlayer b = playerAt(h, 6.5, 6.5);
		h.assertTrue(TpaCommands.toggle(b), "blocking on");
		h.assertTrue(TpaCommands.request(a, b, false) != null && TpaCommands.open(a.getUUID()).isEmpty(), "request refused");
		h.assertFalse(TpaCommands.toggle(b), "blocking off");
		h.assertTrue(TpaCommands.request(a, b, false) == null, "request goes through again");
		h.succeed();
	}

	@GameTest
	public void confinedPlayerCannotUseHomeOrTpa(GameTestHelper h) {
		setup(h);
		ServerPlayer p = playerAt(h, 1.5, 1.5);
		ServerPlayer other = playerAt(h, 6.5, 6.5);
		run(p, "sethome");
		var st = ReturnPositionStorage.get(h.getLevel().getServer());
		st.set(p.getUUID(), new PlayerState(Optional.empty(), Optional.of("gefaengnis"), true, Optional.empty()));
		try {
			h.assertTrue(TeleportRules.blocked(p).isPresent(), "confined is blocked");
			move(h, p, 4.5, 4.5);
			run(p, "home");
			h.assertFalse(Warmup.isWaiting(p), "no warmup for a prisoner");
			h.assertTrue(TpaCommands.request(p, other, false) != null, "prisoner cannot send requests");
			h.assertTrue(TpaCommands.request(other, p, false) != null, "nobody can teleport to a prisoner");
		} finally {
			st.remove(p.getUUID());
		}
		h.succeed();
	}

	@GameTest
	public void operatorsGetWarmupButNoCooldown(GameTestHelper h) {
		setup(h);
		var server = h.getLevel().getServer();
		TownhallMod.CONFIG.get().teleport.warmupSeconds = 3;
		TownhallMod.CONFIG.get().teleport.cooldownSeconds = 60;
		ServerPlayer p = playerAt(h, 1.5, 1.5);
		server.getPlayerList().op(p.nameAndId(), Optional.of(LevelBasedPermissionSet.GAMEMASTER), Optional.empty());
		try {
			run(p, "sethome");
			move(h, p, 6.5, 6.5);
			run(p, "home");
			h.assertTrue(Warmup.isWaiting(p), "operators see the countdown too");
			Warmup.damaged(p);
			TeleportRules.stamp(p);
			h.assertTrue(TeleportRules.cooldownLeft(p) == 0, "no cooldown for operators");
		} finally {
			server.getPlayerList().deop(p.nameAndId());
		}
		h.succeed();
	}

	@GameTest
	public void cooldownBlocksSecondTeleport(GameTestHelper h) {
		setup(h);
		TownhallMod.CONFIG.get().teleport.cooldownSeconds = 30;
		ServerPlayer p = playerAt(h, 1.5, 1.5);
		run(p, "sethome");
		long base = System.currentTimeMillis();
		TeleportRules.CLOCK = () -> base;
		TeleportRules.stamp(p);
		move(h, p, 6.5, 6.5);
		run(p, "home");
		h.assertFalse(Warmup.isWaiting(p), "cooldown refuses /home");
		TeleportRules.CLOCK = () -> base + 31_000;
		h.assertTrue(TeleportRules.cooldownLeft(p) == 0, "cooldown over after 30 s");
		TeleportRules.reset();
		h.succeed();
	}
}
