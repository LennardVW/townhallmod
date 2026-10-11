# Prüfung von Townhall 1.16.1

Stand: 5. Oktober 2026. Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.161.0+26.3 und Java 25.

## Admin-Schlüssel

Drei neue GameTests prüfen über die registrierten Fabric-Interaktionshandler:

- `/key admin` gibt einen weiterhin kompatiblen Generalschlüssel aus. Ein Operator kann damit jede registrierte Vanilla-Türvariante am oberen Türblock abschließen. Ohne Schlüssel bleibt der Zugriff gesperrt. Die Schlossdaten bleiben beim Codec-Roundtrip erhalten.
- Ohne OP lässt sich mit einem Generalschlüssel weder abschließen noch öffnen. Entzogene OP-Rechte wirken sofort. Auch ein normaler Schlüssel mit der reservierten Admin-Schloss-ID darf dieses Schloss nicht öffnen oder beim Schleichen entfernen.
- Bei einer bereits mit einem Hausschlüssel verknüpften Tür bleiben Schlüssel-ID, Besitzer und Schlossname nach Admin-Rechtsklick und Admin-Schleichen unverändert. Der Originalschlüssel funktioniert weiter, Fremde bleiben ausgeschlossen. Erst der ausdrückliche Befehl `/key unlock` entfernt das Schloss.

Die vollständige Suite mit 152 erforderlichen GameTests bestand fünf aufeinanderfolgende Durchläufe. `./gradlew build --offline` war erfolgreich. Ein isolierter Dedicated Server auf `127.0.0.1:25581` startete ohne Mixin-Fehler; Konsolenhilfe für `key` und sauberes Stoppen wurden geprüft. Die Meldung zur fehlenden Flatworld ist auf diesem isolierten Server erwartbar, weil dort keine Multiworld-Mod installiert ist.

## Grenzen der Prüfung

Es wurde kein echter Minecraft-Client auf AzubiCraft verbunden. Die Tests prüfen Serverzustand, Zugriff und Speicherung; eine echte Client-Interaktion und die Kombination aller produktiven Mods bleiben ein Praxistest. Die Speicherung wurde per Codec-Roundtrip geprüft, nicht durch einen Neustart mit einem realen Spieler.

Eine geöffnete Tür ist in der gemeinsamen Minecraft-Welt für alle sichtbar und passierbar. Der Admin-Schlüssel vergibt keine fremden Schlüsselrechte, ermöglicht aber keine private Öffnung nur für einen Client. Mit dem Admin-Schlüssel neu abgeschlossene Türen sind für Operatoren mit Generalschlüssel zugänglich, nicht ausschließlich für den Ersteller. Bestehende Schlösser behalten ihren Hausschlüssel.
