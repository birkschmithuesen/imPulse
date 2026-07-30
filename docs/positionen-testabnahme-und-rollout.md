# Testabnahme und Ausrollen — LED-Positionen und Vierkanal-Spatialisierung

Stand 2026-07-30. Gehört zu [`superpowers/plans/2026-07-30-led-positionen-spatialisierung-fortschritt.md`](superpowers/plans/2026-07-30-led-positionen-spatialisierung-fortschritt.md), das den Umsetzungslauf protokolliert. **Dieses Dokument beantwortet zwei Fragen: was ist geprüft, und was fehlt noch bis zum Live-Betrieb.**

Das Feature ist fertig implementiert und liegt auf `grabicz26`. Es ist **nicht** Teil des Live-Betriebs — nach der Branch-Konvention in `CLAUDE.md` ist `master` der Working State und ein Merge dorthin eine bewusste Einzelentscheidung.

## Lage der Arbeit

| | |
|---|---|
| Vollständig auf | `grabicz26` (lokal auf dem Linux-Server) |
| Teilweise auf | `master` — Aufgaben 9–15, **ohne** 16, 17 und die Nachbesserungen |
| `origin/grabicz26` | veraltet auf `c9d77bc`, drei Stunden hinter dem lokalen Stand |

`master` fehlen unter anderem der Critical-Fix aus Aufgabe 16 (die Drehung im Encoder), `docs/positionen-anleitung.md`, der Positions-Abschnitt in `CLAUDE.md` und die Nachbesserungen aus dem Gesamtreview. `master` ist damit **kein** vollständiger Stand dieses Features und darf nicht als solcher gelesen werden.

## Was automatisch geprüft ist

```bash
cd ~/github/imPulse && test/run.sh && test/build.sh    # beide Exit 0
```

Auf einem Rechner ohne Processing braucht es vorher die drei Pfade aus `~/.bashrc` (`IMPULSE_CORE_JAR`, `IMPULSE_PROCESSING_JAVA`, JDK im `PATH`).

Neun Suiten, alle grün:

| Suite | Prüfungen | deckt ab |
|---|---:|---|
| ArtNetOutputTest | 78 722 | Adressrechnung, Paketbau |
| ArtNetDecoderTest | 54 001 | Gegenprobe über einen unabhängigen Decoder |
| LedPositionCalibrationTest | 272 | Arbeitsliste, Umrechnung, Befehle, Rückmeldung |
| LedAnchorStoreTest | 139 | Anker, Weglängen-Warnung, Datei-Ein/Ausgabe |
| LedPositionMapTest | 80 | Interpolation, Extrapolation, Abdeckungsbericht |
| NodeCrossingStoreTest | 72 | Kreuzungsdatei, Undo, Löschen |
| ImpulseOscThrottleTest | 45 | Sendetakt, Auswahl, Aus-Schalter |
| ApplyCrossingsTest | 27 | Node-Zuordnung, `applyPositions` |
| NodeSelectionTest | 18 | Auswahlzeiger der Kalibrierung |

`test/build.sh` übersetzt zusätzlich den **kompletten** Sketch samt `imPulse.pde` headless. Gegenprobe, dass die Prüfung Zähne hat: ein entferntes Semikolon meldet `imPulse.pde:NN:C: Syntax error, maybe a missing semicolon?` und endet mit Status 1.

Ausserdem wurde die SuperCollider-Datei nicht nur gelesen, sondern **ausgeführt** — `sclang` 3.11.2 liegt auf dem Server. Erst serverlos mit gestubbtem `waitForBoot`, dann gegen `scsynth` auf einem Dummy-JACK-Device. Damit sind gemessen statt behauptet: alle vier SynthDefs bauen, beide OSC-Handler laufen durch, und der Drohnen-Lebenszyklus stimmt (61 gemeldete Impulse → genau 32, Timeout räumt ab, danach wieder aufnahmebereit, Wiederauswertung häuft keine OSCdefs, Busse oder Gruppen an).

## Was die Prüfungen strukturell NICHT abdecken

Das ist der wichtigere Teil dieser Seite.

- **Kein Ton.** Es gibt keine Audio-Hardware auf dem Prüfrechner. Alle Aussagen zur Spatialisierung sind NRT-Zahlen und Zustandsprüfungen, kein Hörergebnis.
- **Kein Blick auf die Fläche.** Die Draufsicht-Fläche im Sketch ist übersetzt, aber **noch nie gelaufen**. Farbnamen in der Anleitung sind Auslegung von RGB-Werten, nicht Beobachtung.
- **Kein echtes Netz.** `LedNetworkTransportEffect` hängt an `oscP5` und lässt sich von `test/run.sh` nicht übersetzen — genau deshalb wurde die Drossellogik in `ImpulseOscThrottle` herausgezogen. Was im Effekt selbst steht, ist durch Lesen und `test/build.sh` abgesichert, nicht durch Tests.
- **Zwei Konstanten sind ausdrücklich ungemessen** — siehe nächster Abschnitt.

## Ausstehende Tests, in dieser Reihenfolge

Schritte 1–4 vor jeder Benutzung, 5–8 vor der Eröffnung. Die ausführliche Fassung steht in [`positionen-anleitung.md`](positionen-anleitung.md).

**1. Erster Start, Konsole lesen.**
Erwartet: Zuordnungstabelle der Controller, dann `n Positionen geladen`. Solange `data/ledPositions.txt` nur seinen Kopf hat, meldet der Sketch 0 und gibt den Warnblock aus — das ist richtig, nicht kaputt.

**2. Fläche ansehen.** *(nie geprüft — hier ist mit Überraschungen zu rechnen)*
`P` drücken. Erwartet: Rechteck 525 × 300 px bei (10, 10) mit 1-m-Raster; vier orange Quadrate auf den Seitenmitten; LED-Vorschau bei x = 560; fünf HUD-Zeilen ab y = 330. Prüfen, dass alles ins 1400 × 450-Fenster passt und die Vorschau nicht überlappt.

**3. Rückmeldung im Netz prüfen.**
Bei Pegel 0,1 ist die blau/rote Schattierung rund 1,5/255 und praktisch unsichtbar; nur der blinkende weisse Punkt (26/255) liest sich klar. Für die Abdeckungsprüfung `/master/level` kurz hochziehen, danach zurückstellen.

**4. Positionen aufnehmen.**
**Erst die Kreuzungen vollständig, dann die Positionen** — nicht verschränkt. Wird eine Kreuzung später mit `X` gelöscht, bleiben ihre Anker als unerreichbarer Zustand im Store zurück und steuern weiter die Interpolation.
Abnahmekriterium: `T` meldet `0 LEDs ohne Position, 0 nur extrapoliert` und nennt keine Stripes. Dann `S`, Sketch neu starten, der Warnblock muss weg sein.

**5. Kanalzuordnung messen.** — `~testChannels.()`
Notieren, welche physische Box bei Kanal 0, 1, 2, 3 klingt. Ist ein Kanal stumm, ist der Server mit zwei Ausgangskanälen gebootet: `s.reboot`, Datei neu auswerten. **Der Startbanner ist hier nicht vertrauenswürdig** — er liest die angeforderte Kanalzahl, nicht die laufende.

**6. `~azimuthOffset` messen.** — `~testAzimuth.()` **(ungemessen)**
Erwartet bei `~azimuthOffset = 0`, dass alle vier Töne *zwischen* zwei Boxen sitzen, um 45° verdreht. Das ist der vorhergesagte Ausgangszustand, kein Fehler: `DecodeB2`s fester Grundriss liegt auf ±45°/±135°, die Boxen stehen auf den Seitenmitten. `~azimuthOffset` auf −0,25 oder +0,25 setzen, neu auswerten, bis jeder Ton *in* der genannten Box sitzt. Umstecken hilft nicht — eine 45°-Drehung lässt sich nicht verkabeln.

**7. `~azimuthSign` messen.** — `~testSweep.()` **(ungemessen)**
Muss vorn → rechts → hinten → links wandern. Läuft es andersherum, `~azimuthSign = -1`, neu auswerten, Schritte 6 und 7 wiederholen.

**8. Gegenprobe auf dem Show-Rechner.**
`~setOrientation.(0.5)` — hörbare Änderung oder nicht. Die vorliegende Messung („`DecodeB2` ignoriert `orientation`") stammt von einem Linux-Server mit SC 3.11.2, nicht vom Show-Rechner. Danach beide gemessenen Werte **mit Datum** ins File eintragen und die `UNGEMESSEN`-Blöcke ersetzen. Zum Schluss ein Rohr anschlagen und prüfen, dass der Klang von dort kommt, wo das Licht ist.

**Die Installation sollte nicht öffnen, bevor 5–8 erledigt sind.** Ein falsches Vorzeichen spiegelt das ganze Klangbild, und das merkt man erst am Eröffnungsabend.

## Ausrollen nach `master`

Nach der Branch-Konvention ist das eine bewusste Einzelentscheidung, kein automatischer Merge. Sinnvoller Zeitpunkt: **nachdem Schritt 4 und Schritt 8 durch sind** — vorher bringt das Feature im Live-Betrieb nichts ausser Risiko, weil ohne aufgenommene Positionen jede Koordinate (0,0) ist.

Vor dem Merge ist `git push` fällig — der vollständige Stand liegt derzeit **nur lokal** (siehe „Lage der Arbeit").

Drei Stellen, an denen ein Merge Aufmerksamkeit braucht:

- **`test/ArtNetOutputTest.java` — hier liegt der einzige echte Konflikt.** `master` hat mit `054d462` den Boot-Default von `ArtNetOutput.masterLevel` von `0.1f` auf **`1.0f`** gesetzt (Working-State für den Show-Betrieb) und die zugehörige Sicherheitsprüfung aus der Suite entfernt. `grabicz26` trägt beides noch in der alten Form. **Beim Merge muss der Working-State gewinnen** — ein „Konflikt gelöst" zugunsten von `grabicz26` würde den Live-Pegel still auf 0,1 zurückdrehen und die Show dunkel machen. Nach dem Merge gegenprüfen: `grep masterLevel ArtNetOutput.java`.
- **`CLAUDE.md`** ändert sich auf beiden Seiten (Branch-Konvention auf `master`, Positions-Abschnitt auf `grabicz26`). Beides behalten, nichts verwerfen.
- **`supercollider/klangnetz_bells_zoom.scd`** existiert nur auf `master` (Laptop-Live-Stand mit −10 dB Amplituden-Fix). `grabicz26` bringt die stark erweiterte `klangnetz_bells.scd` mit. Vor dem Merge klären, welche der beiden Dateien der Show-Rechner tatsächlich lädt — sonst läuft nach dem Merge womöglich die falsche.

Und wie in `CLAUDE.md` vermerkt: vor einem Merge den IST-Zustand auf dem Laptop per SSH verifizieren (`git log -1 --oneline` im dortigen Checkout), nicht raten.

## Bekannte Schwächen, bewusst nicht behoben

Beides steht mit ausführlicher Begründung in der Übergabe:

- **Verwaiste Anker** nach dem Löschen einer Kreuzung — unsichtbar, mit BACKSPACE nicht erreichbar, steuern aber weiter die Interpolation. Vorschlag: `coverageReport()` zusätzlich Anker ohne Arbeitslisteneintrag zählen lassen.
- **`LedPositionMap.apply()` kostet gemessene ~70 ms** bei 30 × 600 und läuft bei gedrückter Maus in jedem Frame; das Ziehen des Punktes läuft damit bei ~13 fps. Der Weg auf ~28 ms ist ermittelt, die naheliegende Abkürzung (`subMap().navigableKeySet()` direkt zurückgeben) ist nachweislich eine Sackgasse — sie wirft `IllegalArgumentException` aus `arcLengthWarning`.
