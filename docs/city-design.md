# Azubi-Stadt: Umsetzung vom 1. Oktober 2026

Der Nutzer hat Grundstücke als optionale Funktion, frei erstellbare Stadtrollen mit Grundrollen, eine physische Buchwahl im Rathaus, Spielerläden und ein Änderungsprotokoll ausgewählt. Alle Funktionen bleiben serverseitig und mit Vanilla-Clients nutzbar. Einrichtung und Rechtevergabe sind ausschließlich Admins vorbehalten.

## Rollen und Grundstücke

Grundrollen: Bürgermeister, Polizei, Händler, Architekt, Wahlhelfer. Rollen verleihen ausschließlich explizit erlaubte Stadtfunktionen; weder OP noch Creative oder WorldEdit werden automatisch vergeben. Mitgliedschaften werden nach UUID gespeichert und lassen sich offline ändern. Chat und Tab zeigen den Rollenpräfix zusammen mit dem vorhandenen Nicknamen.

Grundstücksschutz ist standardmäßig ausgeschaltet. Admins können zwei Eckpunkte wählen, einen Bereich anlegen, Eigentümer/Mitbauer festlegen und den Schutz später aktivieren. Ungeschützte Flächen folgen weiterhin den Weltregeln. Bereichsrechte erlauben Survival-Bauen auch in einer sonst geschützten Welt. Überlappende Grundstücke werden abgelehnt. Aktivierter Schutz berücksichtigt Türen, Container, Platzieren, Abbauen, Projektile, Pistons und Explosionen; WorldEdit darf bei aktiviertem Grundstücksschutz nicht pauschal an den Bereichen vorbeischreiben.

## Buchwahl

Admins legen Wahl, Kandidaten, Wahlraum und Urne fest und benennen Wahlhelfer. Spieler erhalten ein serverseitig registriertes beschreibbares Buch und geben es an der Urne ab. Registrierung und Abgabe funktionieren nach UUID und über Neustarts; höchstens eine Stimme je Spieler und Wahl. Ein verlorener Zettel kann neu ausgegeben werden, wobei der alte ungültig wird. Abgegebene Bücher werden für die Auszählung anonymisiert und unveränderlich archiviert. Die Mod wertet deren Inhalt nicht automatisch aus. Wahlhelfer lesen die Bücher nach Wahlschluss und tragen Kandidatenzahlen und ungültige Stimmen per Befehl ein. Veröffentlichung verlangt eine vollständige Summe und erfolgt durch einen Admin; eine Rolle wird nicht automatisch vergeben. Das Protokoll enthält keine Verbindung zwischen Spieler und Buchinhalt.

## Spielerläden

Admins ordnen einem leeren Fass einen Laden und einen Eigentümer zu. Eigentümer mit Händlerrecht konfigurieren Angebot, Bestand und Preis; Käufer bezahlen mit echten Diamanten oder Smaragden. Bestand und Einnahmen liegen in SavedData. Einkäufe sind in Fassnähe möglich und werden vollständig vorab auf Bestand, Bezahlung und Inventarplatz geprüft. Offline-Verkäufer erhalten die Einnahmen beim späteren Abholen. Bezahlt wird mit Vanilla-Gegenständen. Angebote und Einzahlungen sind im Creative-Modus gesperrt; die Herkunft zuvor erzeugter Gegenstände lässt sich damit nicht prüfen. Das Fass ist eine geschützte Markierung, kein frei zugängliches Warenlager.

## Protokoll

Begrenztes, natives SavedData-Protokoll erfolgreicher Änderungen und Verwaltungsaktionen, mit Zeitpunkt, UUID/Name, Dimension/Position und Vorher/Nachher. Admins können Einträge am Zielblock oder in der Umgebung abfragen. Keine Chatnachrichten oder Stimmzettelinhalte. Keine synchronen Dateioperationen pro Tick und kein automatischer Rollback.

## Prüfung und Lieferung

Neue GameTests decken Rechte, standardmäßig freie Welt, Offline-UUIDs, Buch-Doppelabgabe, Wiederherstellung aus Codecs, Handzählung, vollständige Wahlergebnisse, Shop-Transaktionen/Inventarplatz, Protokoll und bisherige Funktionen ab. Abschließend vollständiger Gradle-Build, Dedicated-Server-Start, deutsche Nutzer-/Admin-Dokumentation und englische Agent-Dokumentation. Die neue Feature-Version ist 1.15.0. Ein PR gegen main enthält auch den vollständigen bisherigen Stand 1.14.2; main wird nicht automatisch gemergt.
