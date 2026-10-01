package dev.townhall.election;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.townhall.city.CityAccess;
import dev.townhall.command.Feedback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.GameProfileArgument;

import static dev.townhall.election.ElectionStorage.*;

/** German vanilla command UX. Candidate names use Brigadier strings (quote names containing spaces). */
public final class ElectionCommand {
    private ElectionCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("wahl").executes(c -> Feedback.ok(c.getSource(),
                "/wahl list | info <id> | stimmzettel <id> | abgeben <id>. Wahlhelfer: buch <id> <nummer>, tally <id> <kandidat> <anzahl>, invalid <id> <anzahl>."));
        root.then(Commands.literal("list").executes(ElectionCommand::list));
        root.then(Commands.literal("info").then(id().executes(ElectionCommand::info)));
        root.then(Commands.literal("stimmzettel").then(id().executes(c -> run(c, () -> {
            ElectionService.issue(c.getSource().getPlayerOrException(), electionId(c));
            return "Stimmzettel erhalten; frühere Zettel sind jetzt ungültig. Schreibe deine Wahl in das Buch und gib es an der Urne mit /wahl abgeben " + electionId(c) + " oder per Rechtsklick ab.";
        }))));
        root.then(Commands.literal("abgeben").then(id().executes(c -> run(c, () -> {
            ElectionService.submit(c.getSource().getPlayerOrException(), electionId(c));
            return "Dein Stimmzettel wurde anonym abgegeben.";
        }))));
        root.then(Commands.literal("buch").then(id().then(Commands.argument("index", IntegerArgumentType.integer(1))
                .executes(c -> run(c, () -> {
                    ElectionService.countBook(c.getSource(), electionId(c), IntegerArgumentType.getInteger(c, "index"));
                    return "Anonymes, schreibgeschütztes Buch erhalten. Lies die Seiten und zähle von Hand; die Mod wertet keinen Inhalt aus.";
                })))));
        root.then(Commands.literal("tally").then(id().then(candidate().then(Commands.argument("count", IntegerArgumentType.integer(0))
                .executes(c -> run(c, () -> {
                    ElectionService.tally(c.getSource(), electionId(c), StringArgumentType.getString(c, "candidate"), IntegerArgumentType.getInteger(c, "count"));
                    return "Kandidatenzahl gespeichert (ersetzt den bisherigen Wert).";
                }))))));
        root.then(Commands.literal("invalid").then(id().then(Commands.argument("count", IntegerArgumentType.integer(0))
                .executes(c -> run(c, () -> {
                    ElectionService.invalid(c.getSource(), electionId(c), IntegerArgumentType.getInteger(c, "count"));
                    return "Anzahl ungültiger Stimmen gespeichert (ersetzt den bisherigen Wert).";
                })))));
        adminCommands(root);
        var admin = Commands.literal("admin").requires(CityAccess::isAdmin);
        adminCommands(admin);
        root.then(admin); // Also accept the explicit /wahl admin ... spelling.
        dispatcher.register(root);
    }

    private static void adminCommands(LiteralArgumentBuilder<CommandSourceStack> parent) {
        parent.then(Commands.literal("create").requires(CityAccess::isAdmin)
                .then(id().then(Commands.argument("title", StringArgumentType.greedyString()).executes(c -> run(c, () -> {
                    ElectionService.create(c.getSource(), electionId(c), StringArgumentType.getString(c, "title"));
                    return "Wahl angelegt. Richte Kandidaten, Raum, Urne und Wahlhelfer ein; danach /wahl open " + electionId(c) + ".";
                })))));
        var candidates = Commands.literal("candidate").requires(CityAccess::isAdmin);
        for (boolean add : new boolean[]{true, false}) candidates.then(Commands.literal(add ? "add" : "remove")
                .then(id().then(candidate().executes(c -> run(c, () -> {
                    ElectionService.candidate(c.getSource(), electionId(c), StringArgumentType.getString(c, "candidate"), add);
                    return add ? "Kandidat eingetragen." : "Kandidat entfernt.";
                })))));
        parent.then(candidates);
        parent.then(Commands.literal("room").requires(CityAccess::isAdmin).then(id()
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, MAX_ROOM_RADIUS)).executes(c -> run(c, () -> {
                    ElectionService.room(c.getSource(), electionId(c), IntegerArgumentType.getInteger(c, "radius"));
                    return "Wahlraum um deine Position und in deiner Dimension festgelegt (kugelförmiger Radius).";
                })))));
        parent.then(Commands.literal("urn").requires(CityAccess::isAdmin).then(id().executes(c -> run(c, () -> {
            ElectionService.aimedUrn(c.getSource(), electionId(c));
            return "Leeres Fass als geschützte Wahlurne reserviert.";
        }))));
        parent.then(Commands.literal("releaseurn").requires(CityAccess::isAdmin).then(id().executes(c -> run(c, () -> {
            ElectionService.releaseUrn(c.getSource(), electionId(c));
            return "Wahlurne freigegeben. Bücher und Ergebnisse bleiben gespeichert.";
        }))));
        var helpers = Commands.literal("helper").requires(CityAccess::isAdmin);
        for (boolean add : new boolean[]{true, false}) helpers.then(Commands.literal(add ? "add" : "remove")
                .then(id().then(Commands.argument("player", GameProfileArgument.gameProfile()).executes(c -> run(c, () -> {
                    ElectionService.helper(c.getSource(), electionId(c), GameProfileArgument.getGameProfiles(c, "player"), add);
                    return add ? "Wahlhelfer eingetragen (UUID, auch offline)." : "Wahlhelfer entfernt.";
                })))));
        parent.then(helpers);
        for (String action : new String[]{"open", "close", "cancel", "publish"}) parent.then(Commands.literal(action)
                .requires(CityAccess::isAdmin).then(id().executes(c -> run(c, () -> {
                    switch (action) {
                        case "open" -> ElectionService.open(c.getSource(), electionId(c));
                        case "close" -> ElectionService.close(c.getSource(), electionId(c));
                        case "cancel" -> ElectionService.cancel(c.getSource(), electionId(c));
                        case "publish" -> ElectionService.publish(c.getSource(), electionId(c));
                        default -> throw new IllegalStateException(action);
                    }
                    return "Wahl " + electionId(c) + ": " + ElectionService.find(c.getSource(), electionId(c)).state().display() + ".";
                }))));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> id() {
        return Commands.argument("id", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(
                ElectionStorage.get(c.getSource().getServer()).elections().stream().map(Election::id), b));
    }
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> candidate() {
        return Commands.argument("candidate", StringArgumentType.string()).suggests((c, b) -> SharedSuggestionProvider.suggest(
                ElectionStorage.get(c.getSource().getServer()).election(electionId(c)).map(Election::candidates)
                        .orElse(java.util.List.of()).stream().map(s -> s.contains(" ") ? "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" : s), b));
    }
    private static String electionId(CommandContext<CommandSourceStack> c) { return StringArgumentType.getString(c, "id"); }

    private static int list(CommandContext<CommandSourceStack> c) {
        String entries = String.join("\n", ElectionStorage.get(c.getSource().getServer()).elections().stream()
                .map(e -> e.id() + " – " + e.title() + ": " + e.state().display()).toList());
        return Feedback.ok(c.getSource(), entries.isEmpty() ? "Keine Wahlen eingerichtet." : entries);
    }
    private static int info(CommandContext<CommandSourceStack> c) {
        return run(c, () -> {
            Election e = ElectionService.find(c.getSource(), electionId(c));
            StringBuilder text = new StringBuilder(e.title()).append(" [").append(e.id()).append("] – ").append(e.state().display())
                    .append("\nKandidaten: ").append(String.join(", ", e.candidates()))
                    .append("\nArchivierte Bücher: ").append(e.ballotCount());
            e.room().ifPresent(r -> text.append("\nWahlraum: ").append(r.dimension()).append(" ").append(r.center().toShortString()).append(", Radius ").append(r.radius()));
            e.urn().ifPresent(u -> text.append("\nUrne: ").append(u.dimension()).append(" ").append(u.pos().toShortString()));
            if (e.state() == State.PUBLISHED || (e.state() == State.CLOSED && ElectionService.mayCount(c.getSource(), e))) {
                text.append("\nManuelle Auszählung:");
                for (String candidate : e.candidates()) text.append("\n ").append(candidate).append(": ")
                        .append(e.totals().containsKey(candidate) ? e.totals().get(candidate) : "nicht eingetragen");
                text.append("\n Ungültig: ").append(e.invalid().map(Object::toString).orElse("nicht eingetragen"))
                        .append("\nSumme: ").append(e.counted()).append(" / ").append(e.ballotCount());
            }
            if (e.state() == State.CLOSED && ElectionService.mayCount(c.getSource(), e))
                text.append("\nBücher einzeln lesen: /wahl buch ").append(e.id()).append(" <1–").append(e.ballotCount()).append(">");
            return text.toString();
        });
    }
    @FunctionalInterface private interface Action { String run() throws CommandSyntaxException; }
    private static int run(CommandContext<CommandSourceStack> c, Action action) {
        try { return Feedback.ok(c.getSource(), action.run()); }
        catch (ElectionService.ElectionException | CommandSyntaxException ex) { return Feedback.fail(c.getSource(), ex.getMessage()); }
    }
}
