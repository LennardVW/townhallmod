package dev.townhall.display;

import dev.townhall.TownhallMod;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.numbers.StyledFormat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

/**
 * Red death count next to every name in the tab list, using a vanilla scoreboard objective (works with vanilla clients).
 * The objective uses the deathCount criterion, so vanilla adds 1 on every death. On join and respawn the score is set
 * from the player's statistics, so deaths from before the mod was installed count too.
 */
public final class DeathsInTab {

	public static final String OBJECTIVE = "townhall_deaths";
	static final StyledFormat RED = new StyledFormat(Style.EMPTY.withColor(ChatFormatting.RED));

	private DeathsInTab() {}

	/** Shows or hides the tab list numbers according to the config. Call on start and after changes. */
	public static void apply(MinecraftServer server) {
		ServerScoreboard scoreboard = server.getScoreboard();
		Objective objective = scoreboard.getObjective(OBJECTIVE);
		if (!TownhallMod.CONFIG.get().deathsInTab) {
			if (objective != null && scoreboard.getDisplayObjective(DisplaySlot.LIST) == objective) scoreboard.setDisplayObjective(DisplaySlot.LIST, null);
			return;
		}
		if (objective == null) {
			objective = scoreboard.addObjective(OBJECTIVE, ObjectiveCriteria.DEATH_COUNT, Component.literal("Deaths"),
					ObjectiveCriteria.RenderType.INTEGER, false, RED);
		} else if (!RED.equals(objective.numberFormat())) {
			objective.setNumberFormat(RED);
		}
		scoreboard.setDisplayObjective(DisplaySlot.LIST, objective);
		server.getPlayerList().getPlayers().forEach(DeathsInTab::sync);
	}

	/** Sets the player's number to their death statistic. */
	public static void sync(ServerPlayer player) {
		if (!TownhallMod.CONFIG.get().deathsInTab) return;
		ServerScoreboard scoreboard = player.level().getServer().getScoreboard();
		Objective objective = scoreboard.getObjective(OBJECTIVE);
		if (objective == null) return;
		scoreboard.getOrCreatePlayerScore(player, objective).set(deaths(player));
	}

	public static int deaths(ServerPlayer player) {
		return player.getStats().getValue(Stats.CUSTOM.get(Stats.DEATHS));
	}
}
