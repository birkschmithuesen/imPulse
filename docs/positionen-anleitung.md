# LED-Positionen aufnehmen — Anleitung

Diese Anleitung beschreibt, wie `data/ledPositions.txt` von Hand aufgenommen wird: die Liste der Stellen, an denen eine bestimmte LED des Netzes in der Draufsicht **wirklich hängt**, in Metern. Der Sketch rechnet daraus die Position *jeder* LED und schickt sie an die Klangmaschine — erst damit kommt ein Ton aus der Richtung, in der man das Licht sieht.

Geschrieben für die Garbicz-Fassung: 15 Controller, 30 Stripes à 600 LEDs, Grundfläche 14 × 8 m, vier Lautsprecher auf den Seitenmitten. Die Tastenbelegung im Einzelnen steht in `CLAUDE.md` unter „LED-Positionen und Spatialisierung"; hier geht es um das Vorgehen.

## Vor dem Anfangen

- **Die Kreuzungen müssen aufgenommen sein.** `data/nodeCrossings.txt` ist die Grundlage: jede Kreuzung wird zu einem Eintrag der Arbeitsliste. Fehlt sie, fehlen die Einträge, und die Positionen der Knoten — also genau die Punkte, die Töne auslösen — wären nur geraten. Zuerst also `docs/kalibrierung-anleitung.md` durcharbeiten.
- **Der Sketch wird aus der Processing-IDE gestartet.** Wie bei der Kreuzungskalibrierung: `imPulse.pde` öffnen, Play.
- **Der Master-Pegel bleibt in der Regel bei 0,1, darf für eine Abdeckungsprüfung aber kurz hoch.** Der Positionsmodus läuft anders als die Testbilder auf dem Show-Fader `/master/level` (Auslieferungswert 0,1). Bei diesem Pegel ist die blau/rote Einfärbung der Abdeckung (siehe „Farben im Netz") mit rund 1,5/255 pro Kanal praktisch unsichtbar — die einzige verlässliche Anzeige ist der blinkende weisse Punkt, und der reicht für die eigentliche Aufnahme locker. Will man sich stattdessen einen Überblick verschaffen, wo noch Rot oder Dunkel liegt, `/master/level` kurz hochziehen und danach wieder auf 0,1 zurück; dauerhaft oben lassen sollte man ihn nicht, die Stripes sind nicht für volle Helligkeit ausgelegt.
- **Ein Meterband oder wenigstens ein Plan der Halle.** Angeklickt wird eine Draufsicht der Grundfläche mit 1-m-Raster; ohne eine Vorstellung davon, wo im Raum die Netzmitte liegt, klickt man ins Blaue.
- Die Controller müssen erreichbar sein. Kurzprobe: `ping 2.2.2.2`.

## Das Koordinatensystem

Ursprung ist der Punkt **senkrecht unter der Netzmitte**. X zeigt nach rechts, Y nach vorn, Einheit Meter. Die Grundfläche ist 14 m breit (X von −7 bis +7) und 8 m tief (Y von −4 bis +4). Eine Höhe gibt es nicht: vier Lautsprecher in einer Ebene können sie ohnehin nicht darstellen.

Die vier Lautsprecher stehen auf den **Seitenmitten**, nicht in den Ecken: vorn (0, +4), rechts (+7, 0), hinten (0, −4), links (−7, 0). Im Sketch sind sie als orange Quadrate eingezeichnet — nach ihnen kann man sich beim Klicken ausrichten.

## Das Prinzip

`P` schaltet den Positionsmodus ein. Links im Fenster erscheint die **Draufsicht-Fläche** (525 × 300 Pixel für 14 × 8 m, ein Pixel also 2,67 cm), rechts daneben eine verkleinerte LED-Vorschau; das HUD sitzt darunter — genauer: unterhalb der Draufsicht-Fläche links im Fenster, nicht unter der Vorschau.

Ein **Eintrag** der Arbeitsliste ist ein *physischer Punkt*, nicht eine LED. Eine Kreuzung ist damit ein Eintrag mit zwei (selten mehr) LEDs auf zwei Stripes — sie hängen ja an derselben Stelle. Ein Stripe-Ende ist ein Eintrag mit einer LED.

Der aktuelle Eintrag **blinkt weiss im Netz**, 0,4 s an, 0,4 s aus. Bei einer Kreuzung blinken beide LEDs auf beiden Stripes gleichzeitig; damit man sie im Gewirr überhaupt findet, glimmen die beteiligten Stripes zusätzlich schwach grün auf ihrer ganzen Länge — bei einem Stripe-Ende ist das einer, bei einer Kreuzung zwei.

Der Ablauf ist dann kurz:

1. **Hinsehen**, wo im Raum der blinkende Punkt hängt.
2. **In die Draufsicht klicken**, an die entsprechende Stelle.
3. Fertig — der nächste Eintrag mit `.` oder `o`.

Der Klick setzt beide LEDs einer Kreuzung auf einmal. Ziehen mit gedrückter Maustaste verschiebt den Punkt, solange man in der Fläche bleibt.

## Der Vorschlag

Ab dem **ersten** gesetzten Punkt eines Stripes rechnet der Sketch für jeden weiteren Eintrag eine Position vor und zeigt sie als **hohlen weissen Ring** — nach nur einem Anker allerdings noch ohne Richtung, dazu gleich mehr. Gesetzte Einträge zeigt er als gefüllte Scheibe; alle anderen bereits gesetzten Einträge sieht man daneben als kleine graue Punkte in der Fläche, praktisch für den Überblick, was in der Umgebung schon erledigt ist.

- Zwischen zwei Ankern wird interpoliert, jenseits des äussersten der Vektor der letzten beiden fortgesetzt.
- **Stimmt der Vorschlag**, `ENTER` — er wird damit zum Anker.
- **Stimmt er fast**, mit den **Pfeiltasten** nachschieben. `F` schaltet die Schrittweite zwischen **1, 5 und 25 cm** um (Start: 5 cm). Auch das Nachschieben macht aus dem Vorschlag einen Anker, man muss danach nicht noch `ENTER` drücken.
- **Stimmt er nicht**, einfach neu klicken.

Nach dem *ersten* Anker eines Stripes zeigt der Ring noch keine Richtung — er sitzt genau auf diesem einen Punkt, weil ein einzelner Anker keine Richtung hergibt. Das ist kein Fehler, sondern der Zustand „ich weiss nur, dass der Stripe hier irgendwo langläuft".

Solange ein Stripe gar keinen Anker hat, gibt es keinen Vorschlag; das HUD zeigt bei „Position" dann schlicht „keine Position". Erst ein Versuch, ihn zu übernehmen — `ENTER` oder eine Pfeiltaste —, bekommt die eigentliche Meldung „Kein Vorschlag moeglich - dieser Stripe hat noch keinen Anker" zurück, in der Meldungszeile und zusätzlich auf der Konsole.

## Reihenfolge: stripeweise arbeiten

Die Arbeitsliste ist nach dem **kleinsten globalen LED-Index** eines Eintrags sortiert. Praktisch heisst das: erst alles, was zu Stripe 0 gehört, dann Stripe 1 und so weiter.

- `.` blättert vorwärts, `,` rückwärts.
- **`o` springt zum nächsten noch offenen Eintrag** hinter dem aktuellen. Das ist die Taste, mit der man arbeitet. Gibt es dahinter keinen mehr, bleibt der Zeiger stehen und das HUD nennt die Gesamtzahl der offenen Einträge — dann mit `,` zurück oder von vorn beginnen.

**Womit man rechnen muss:** ein Kreuzungs-Eintrag steht in der Liste an der Stelle seiner *kleinsten* LED, also im Abschnitt des Stripes mit der **niedrigeren Nummer**. Arbeitet man den höheren Stripe durch, taucht diese Kreuzung dort nicht noch einmal auf. Sie ist trotzdem vollständig gesetzt — der eine Klick hat beide LEDs erfasst. Ein Stripe ist also nicht dann fertig, wenn sein Abschnitt der Liste abgearbeitet ist, sondern dann, wenn im Netz kein Rot mehr auf ihm liegt (siehe nächster Abschnitt).

Zuerst die **beiden Enden** eines Stripes zu setzen, lohnt sich: danach liegt jede seiner LEDs zwischen zwei Ankern, alle Vorschläge auf diesem Stripe sind Interpolationen statt Fortsetzungen, und das Rot verschwindet.

## Farben im Netz

Der Positionsmodus färbt das ganze Netz nach dem Zustand seiner Positionskarte:

| Farbe | Bedeutung |
|---|---|
| **dunkel** | dieser Stripe hat noch keinen einzigen Anker — hier ist gar nichts bekannt |
| **rot (schwach)** | Position nur geraten: die LED liegt jenseits des äussersten Ankers ihres Stripes |
| **blau (schwach)** | Position gestützt: die LED liegt zwischen zwei Ankern (oder genau auf einem) |
| **grün (schwach)** | Stripe des aktuellen Eintrags — überschreibt blau/rot auf diesem Stripe |
| **weiss, blinkend** | die LED(s) des aktuellen Eintrags |

Rot verschwindet auf einem Stripe genau dann, wenn beide Enden gesetzt sind. Ein Netz ohne Rot und ohne Dunkel ist die Zielgerade.

## Zwischendurch sichern

`S` schreibt die vollständige Liste nach `data/ledPositions.txt`. Wie bei den Kreuzungen wird in eine Nebendatei geschrieben und anschliessend umbenannt: gefahrlos beliebig oft, es wird nichts angehängt und nichts verdoppelt. **Nach jeweils ein paar Punkten drücken.**

## Gegenprüfen, ohne neu zu starten

- **`R`** rechnet die Positionskarte neu, überträgt sie auf die Knoten und baut die Arbeitsliste neu auf. Ab da schickt `/net/hitNode` die neuen Koordinaten, ohne Neustart.
- **`T`** schreibt den Abdeckungsbericht auf die Konsole und ins HUD: wieviele LEDs gar keine Position haben, wieviele nur extrapoliert sind, und welche Stripes noch **ohne jeden Anker** sind. Der Bericht nennt die Stripes ohne Anker beim Namen — das ist die schnellste Antwort auf „woran muss ich noch ran?".

Zum Hören: `P` verlässt den Positionsmodus, die Show läuft wieder, ein Rohr anschlagen (oder per OSC an Port 8001 ein `/tube/trigger` mit der Stripe-Nummer schicken, 1-basiert) und darauf achten, ob der Ton aus der Ecke kommt, in der das Licht steht. `P` bringt einen zurück, und beim Wiedereintritt wird ohnehin neu gerechnet.

## Korrigieren

Mit `,` / `.` (oder `o`) zum betreffenden Eintrag blättern — er blinkt dann weiss, man sieht also im Netz, welchen Punkt man gerade in der Hand hat.

- **Neu klicken** überschreibt die Position sofort. Das ist der Normalfall.
- **`BACKSPACE`** löscht die Anker *dieses* Eintrags ganz — bei einer Kreuzung beide. Die Anzeige fällt danach auf den Vorschlag zurück (hohler Ring), der Eintrag gilt wieder als offen und `o` findet ihn wieder.

Anders als bei den Kreuzungen gibt es kein „letzte Aufnahme zurücknehmen": `BACKSPACE` wirkt immer auf den aktuellen Eintrag, und es macht keinen Unterschied, ob dessen Anker aus der Datei kam oder aus dieser Sitzung. Der Zähler im HUD („x geladen + y neu") unterscheidet das trotzdem, damit man sieht, wie weit man heute gekommen ist. Ein Anker, den man in dieser Sitzung angefasst hat, zählt als neu.

Auf der Platte ändert sich durch all das nichts, bis `S` gedrückt wird.

### Alles verwerfen

Etwa weil die Aufnahme aus einer anderen Geometrie stammt: **`L`** zweimal drücken. Der erste Druck kündigt an und nennt die Anzahl, der zweite muss zwischen 0,3 und 5 Sekunden später kommen. Jede andere Taste bricht ab.

## Am Ende

1. `S` — letzter Stand geschrieben.
2. `T` — der Bericht muss „0 LEDs ohne Position, 0 nur extrapoliert" melden und darf keine Stripes ohne Anker nennen.
3. Sketch neu starten. Die Konsole meldet beim Laden „n Positionen geladen"; die Zahl muss stimmen, und es darf **keine** `WARNUNG`-Zeile über fehlende Positionen kommen. Kommt sie doch, senden die betroffenen LEDs (0,0) — die Netzmitte — als Klangposition.
4. Ein paar Rohre anschlagen und hinhören, ob Licht und Ton am selben Ort sind.

## Dateiformat

Eine Zeile je Anker, drei durch Leerzeichen getrennte Felder. Zeilen ab `#` sind Kommentar, der Kopf der Datei wird beim Speichern neu geschrieben:

```
# LED-Positionen in der Draufsicht. Eine Zeile je Anker:
#   ledIndex  x[m]  y[m]
412 -3.250 1.500
4358 2.000 -0.750
```

Der globale Index ist `stripe * 600 + ledInStripe`, also 0 bis 17999; x und y sind Meter mit Dezimalpunkt (nie Komma — die Datei wird unabhängig von der Sprache des Rechners geschrieben). Fehlerhafte Zeilen und Positionen ausserhalb der Grundfläche werden beim Laden gemeldet und übersprungen, nicht als Absturz weitergereicht.

Beide LEDs einer Kreuzung stehen als **zwei Zeilen mit derselben Position** darin. Das ist kein Fehler und keine Verdopplung: eine Kreuzung ist ein Punkt, aber zwei LEDs, und der Schlüssel der Datei ist die LED.

## Fallstricke

- **„Luftlinie länger als der Stripe hergibt"**: diese Warnung im HUD und auf der Konsole heisst fast immer, dass die **falsche Netzseite** angeklickt wurde — die Luftlinie zwischen zwei benachbarten Ankern desselben Stripes kann physikalisch nie länger sein als der Weg entlang des Stripes (Toleranz: 0,5 m). Die Position wird **trotzdem gesetzt**, und zwar mit Absicht: die Regel hängt an der Annahme, dass ein Stripe 10 m lang und durchgehend ist. Stimmt die vor Ort nicht, wäre ein hartes Nein ein Werkzeug, das sich mitten in der Arbeit selbst blockiert. Also: hinsehen, ob es wirklich ein Fehlklick war, und dann entweder korrigieren oder weitermachen.
- **Ändern sich `controllerOctets` oder `numLedsPerStripe`** in `imPulse.pde`, verschieben sich alle globalen Indizes und die Aufnahme ist hinüber — genau wie bei den Kreuzungen. Beides vor der Aufnahme festlegen, nicht danach.
- **Kommen später Kreuzungen dazu, bleiben die alten Positionen gültig.** Der Schlüssel der Datei ist der LED-Index, nicht die Knoten-Nummer: eine physische LED wandert nicht, wenn eine Kreuzung nachgetragen oder gelöscht wird. Die neuen Kreuzungen tauchen nach `R` (oder beim nächsten Eintritt in den Positionsmodus) als offene Einträge auf.
- **`P` und `c` schliessen sich gegenseitig aus.** Beide Modi belegen `,` `.` `S` `R` `F` `L` — wer den einen einschaltet, schaltet den anderen ab. Das ist auch der Grund, warum es beim Umschalten nichts zu retten gibt: gespeichert wird in getrennte Dateien.
- **Die Draufsicht-Fläche liegt in der linken oberen Fensterecke, und ein Klick setzt ohne Rückfrage.** Wer das Fenster nur anklickt, um es in den Vordergrund zu holen, überschreibt damit den Anker des aktuellen Eintrags. Auf der Platte ist erst nach dem nächsten `S` etwas verloren; wenn es stört, lassen sich `paneX`/`paneY` in `imPulse.pde` auf 10/10 setzen (Einzeiler, alles läuft über dieselbe Umrechnung).
- **Ein Fader von 0 bis 1 auf einem Int-Parameter setzt ihn auf sein Minimum.** Betrifft nicht die Aufnahme, wohl aber jede Fernsteuerung, die man daneben laufen lässt: `RemoteControlledIntParameter` schneidet einen Float-Wert ab, *bevor* er auf den Bereich abgebildet wird. Für `/net/impulse/oscMaxCount` heisst das 0 — also Stille, die wie funktionierende Software aussieht. Int-Parameter immer als echte Ganzzahl senden.

## Und danach: der Klang

Die Positionen allein machen noch keine Räumlichkeit. `supercollider/klangnetz_bells.scd` verteilt sie auf die vier Lautsprecher, und **zwei Werte in dieser Datei sind ausdrücklich ungemessen** — `~azimuthSign` (ist das Klangbild links/rechts gespiegelt?) und `~azimuthOffset` (ist es um 45° verdreht?). Beides lässt sich nur mit vier angeschlossenen Boxen und einem Paar Ohren feststellen, beides geht ohne Fehlermeldung durch und fällt sonst erst zur Eröffnung auf.

Die Datei bringt dafür drei Messhilfen mit (`~testChannels.()`, `~testAzimuth.()`, `~testSweep.()`) und beschreibt den Ablauf Schritt für Schritt in ihrem Kopf. **Die Installation darf nicht öffnen, bevor diese Messung gemacht und das Ergebnis mit Datum in die Datei eingetragen ist.** Sie braucht eine interaktive SuperCollider-Sitzung (IDE oder `sclang`-REPL), im headless-Betrieb gibt es nichts, woraus man die Funktionen aufruft.
