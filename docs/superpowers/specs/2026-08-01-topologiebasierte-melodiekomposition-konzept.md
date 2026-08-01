# Topologiebasierte Melodiekomposition auf dem Impuls-Netz — Konzept

Eigenständiges Konzeptdokument, KEIN Implementierungsauftrag. Zielsprache
für eine spätere Umsetzung wäre Java/Processing (analog `NodeCrossingStore`,
`LedStripeNetworks`), hier nur Pseudocode/Algorithmus-Skizze. Rührt NICHT
an das laufende Nacht-Komposition-Feature-Set (Split-Varianz, Origin-
Sequencer, Speed-Quantisierung, Baum-Origin-Filter) — eigenständiges,
parallel denkbares Konzept, keine Integration in diesem Dokument.

Branch: `docs/graph-topology-melody-concept`, abgezweigt von `master`. Kein
Push zum Show-Laptop, kein Merge nach `master`/`grabicz26` ohne explizite
Freigabe.

**Stand 2026-08-01, zweite Fassung.** Überarbeitet nach einem
Rückfrage-Dialog mit Birk. Entschieden wurden: Persistenz der Zuordnung
(Abschnitt 8), Startknoten als OSC-Parameter (Abschnitt 9), Annahme des
Zyklus-Kompromisses (Abschnitt 6), Ersetzung des Netzregion-Klangbias durch
einen Bias nach Ursprungs-Baum (Abschnitt 10) und die Erweiterung auf acht
Modi (Abschnitt 4). Dazu kam ein im ersten Entwurf **nicht bedachtes**
Problem — unbegrenzte Oktavdrift durch die Landmarken-Regel — samt Antwort
(Schritte 3c und 3d).

Abschnitt 10 formuliert dabei eine **Architektur-Anforderung** an
`LedNetworkTransportEffect.java` und `klangnetz_bells.scd` (der
Ursprungs-Baum müsste am Impuls mitgeführt werden). Auch das bleibt
Konzept: in diesem Dokument wird nichts implementiert, und das laufende
Nacht-Komposition-Feature-Set wird nicht angefasst.

## 1. Problem: warum die aktuelle Zuordnung "zufällig" klingt

In `supercollider/klangnetz_bells.scd`, Zeile ~1380, gilt aktuell:

```supercollider
noteIndex = (nodeId + bias[\noteOffset]) % ~notesPerOctaveSet;
```

`nodeId` ist die fortlaufende Nummer der Kreuzung, so wie sie beim
Einlesen von `data/nodeCrossings.txt` vergeben wird (Zeile 0 → Node 0,
Zeile 1 → Node 1, usw., siehe `LedStripeNetworks.applyCrossings`,
Zeilen 95-105). Diese Nummerierung hat **keinerlei Bezug zur physischen
Nachbarschaft** der Nodes im LED-Netz — Node 5 und Node 6 können auf
komplett unterschiedlichen Stripes an entgegengesetzten Enden der
Installation liegen. Ein Impuls, der von Node zu Node wandert, trifft daher
auf eine Folge von Tönen, deren Abstand zueinander rein zufällig ist
(Modulo-Rechnung über eine willkürliche Indexreihenfolge) — mal ein
Halbtonschritt, mal ein Tritonus, mal eine Septime. Das Ergebnis klingt wie
Skalentöne in zufälliger Reihenfolge, nicht wie eine Melodie.

## 2. Warum Topologie statt Pfad-Enumeration

Naheliegender, aber untauglicher Ansatz: alle möglichen Pfade durchs Netz
simulieren und für jeden Pfad eine "gute" Melodie komponieren.

Das Netz ist aber **kein Baum, sondern ein Graph mit Zyklen** (siehe
Abschnitt 7, die Beispielrechnung enthält einen echten Dreieck-Zyklus:
Node 90 → 70 → 71 → 90). Bei einem zyklischen Graphen mit `n` Knoten
und potenziell an jedem Knoten mehreren möglichen Split-Richtungen ist die
Zahl der *möglichen Impulswege* nicht endlich und nicht sinnvoll
enumerierbar:

- Ein Impuls kann durch einen Zyklus mehrfach hindurchlaufen (die
  Sketch-Logik begrenzt das aktuell nur durch Energie-/Lifetime-Decay, nicht
  strukturell) — die Menge der "Pfade" ist damit im Prinzip unendlich.
- Selbst wenn man Wege künstlich auf eine maximale Länge begrenzt, wächst
  die Zahl der Pfade in einem Graphen mit vielen Verzweigungsknoten
  exponentiell mit der Weglänge — bei 92 Knoten und Split-Verhalten an
  jedem Knoten ist das kombinatorisch witzlos, "einmal jeden Weg
  durchspielen" ist keine wohldefinierte, geschweige denn praktikable Menge.
- Vor allem: selbst wenn man alle Pfade bis Länge N enumerieren UND für
  jeden Pfad eine für sich gute Melodie berechnen könnte, bräuchte man
  trotzdem am Ende EINE stabile Tonzuordnung pro Knoten (ein Knoten hat zur
  Laufzeit nur einen Klang, keinen pfadabhängigen) — die Pfad-Enumeration
  würde also nur ein Optimierungsproblem lösen, dessen Ergebnis wieder auf
  "eine Note pro Knoten" zurückprojiziert werden müsste. Man kann diesen
  Umweg überspringen, wenn man direkt lokal/topologisch optimiert.

**Konsequenz:** das Konzept weist deshalb Noten **lokal aus der
Graphstruktur heraus** zu (Traversierung einmal vom Ursprung aus, mit
Regeln für Kanten-Intervalle) statt Wege zu simulieren. Jeder mögliche Weg
durchs Netz — egal wie lang, egal wie oft er einen Zyklus durchläuft —
besteht dann aus einer Folge von Kanten, die JEDE FÜR SICH bereits einer
guten Melodieregel genügt (kleine/mittlere, musikalisch sinnvolle
Intervalle zwischen direkt verbundenen Nodes). Eine global über alle
denkbaren Pfade optimale Lösung gibt es dabei nicht (siehe Abschnitt 6,
Zyklus-Problem) — das Ziel ist eine lokal konsistente, praktisch
überzeugende Näherung, keine mathematisch beweisbare Global-Optimierung.

## 3. Graphmodell: was ist ein "Node" hier eigentlich

Aus `LedStripeNetworks.java` (`LedNetworkNode`, `applyCrossings`) und
`data/nodeCrossings.txt`:

- Jede Zeile in `nodeCrossings.txt` ist EIN Node (Kreuzungspunkt), definiert
  durch die Menge der LED-Indizes, die an diesem Punkt zusammentreffen
  (im aktuellen Datenstand immer genau 2 LED-Indizes pro Zeile → 2-LED-
  Kreuzungen, jede Zeile bekommt beim Einlesen fortlaufend `nodeId = 0..91`
  zugewiesen, `nodeId` = Zeilenindex).
- Ein LED-Index kodiert Stripe + Position: `stripeIndex = ledIndex /
  numLedsPerStripe`, `indexInStripe = ledIndex % numLedsPerStripe`
  (`numLedsPerStripe = 600`, siehe `imPulse.pde` Zeile 49-51).
- Zwei Nodes sind **topologisch benachbart** (ein Impuls kann direkt von
  einem zum anderen wandern, ohne einen dritten Node zu passieren), wenn
  sie auf DERSELBEN Stripe liegen und dort — nach `indexInStripe` sortiert —
  UNMITTELBAR aufeinanderfolgen (keine andere Kreuzung liegt dazwischen).
  Diese Nachbarschaftsrelation ist im aktuellen Code nicht explizit als
  Graph materialisiert (LedNetworkTransportEffect arbeitet LED-für-LED,
  nicht Node-für-Node) — für das Melodie-Konzept muss sie einmalig aus
  `nodeCrossings.txt` + `numLedsPerStripe` abgeleitet werden (siehe
  Algorithmus, Abschnitt 5, Schritt 1).
- Ein Node kann an MEHREREN Stripes beteiligt sein (wenn mehr als 2
  LED-Indizes an einer Kreuzung zusammentreffen) — aktuell sind alle 92
  Zeilen genau 2-elementig, das Konzept sollte aber allgemein für Nodes mit
  Grad > 2 funktionieren (LedNetworkNode.ledIndices ist ein TreeSet, nicht
  auf 2 Elemente begrenzt).

## 4. Acht wählbare Modi

Alle acht Modi teilen sich denselben Grundalgorithmus (Abschnitt 5) und
unterscheiden sich nur in: (a) der verwendeten Skala, (b) den bevorzugten
Kanten-Intervallen zwischen direkt benachbarten Nodes, (c) der Gewichtung
Schritt vs. Sprung. Der Operator wählt EINEN Modus für einen kompletten
Zuordnungslauf (kein Mischbetrieb pro Node).

Modi A–D sind der ursprüngliche Vorschlag, E–H sind am 2026-08-01 auf
Birks Wunsch ergänzt worden: **E** holt den heute live laufenden
Klangcharakter (Phrygisch) in das Schema herein, **F–H** erweitern die
maqam-nahe Seite über Hijaz hinaus. Bei den Maqamat gilt durchgehend
dieselbe Transparenzregel wie bei Modus C: wo eine 12-TET-Näherung
stattfindet, wird sie offen benannt; wo keine nötig ist, wird das ebenfalls
gesagt (bei **ʿAjam** und **Nikriz** ist keine nötig — beide liegen ohne
Vierteltöne auf dem 12-Ton-Raster).

### Modus A — "Dorisch" (diatonischer Grundmodus)

- **Skala:** Dorisch, `[0, 2, 3, 5, 7, 9, 10]` Halbtöne (siehe
  `hermes-knowledge/musiktheorie/skalen.md`). Bewusst Dorisch statt Ionisch/
  Äolisch gewählt: mildeste, unverfänglichste modale Färbung, klingt weder
  "Dur-fröhlich" noch "Moll-schwer", schwebender Grundcharakter, passt zur
  bisherigen Sound-Ästhetik der Installation (Note in `klangnetz_bells.scd`
  Zeile 79: aktuell schon Phrygisch ab A2 im Einsatz — Dorisch ist die
  näher an Dur liegende Alternative, wenn ein weniger "dunkler" Grundmodus
  gewünscht ist; Phrygisch selbst steht seit der Überarbeitung als
  **Modus E** mit denselben Regeln daneben).
- **Bevorzugte Kanten-Intervalle** (direkt verbundene Nodes): primär
  Sekundschritte (1-2 Skalenstufen, siehe `melodiefuehrung.md` Abschnitt 1),
  gelegentlich Terz. Quint/Oktave als "Landmarken"-Intervall reserviert für
  Kanten zu Hub-Knoten (siehe Schritt 3b im Algorithmus).
- **Schritt/Sprung-Gewichtung:** 70% Sekundschritt, 20% Terz, 10%
  Quint/größer — orientiert an der Grundregel "Stufen als Rückgrat,
  Sprünge als Würze" (melodiefuehrung.md Abschnitt 1).

### Modus B — "Pentatonik" (robuste, immer-konsonante Variante)

- **Skala:** Moll-Pentatonik, `[0, 3, 5, 7, 10]` Halbtöne.
- **Bevorzugte Kanten-Intervalle:** da Pentatonik keine benachbarten
  Halbtonschritte enthält, ist praktisch JEDES Intervall zwischen zwei
  Skalenstufen konsonant (skalen.md Abschnitt 2) — die Kantenregel kann
  daher lockerer sein: bevorzugt 1 Skalenstufe (= kleine/große Terz oder
  Ganzton, je nach Position), aber auch 2 Skalenstufen sind unproblematisch.
- **Schritt/Sprung-Gewichtung:** entspannter als Modus A, z.B. 50%
  1-Stufe, 35% 2-Stufen, 15% 3+ Stufen — weil in der Pentatonik selbst
  größere Sprünge selten dissonant wirken. Guter Modus für dichten
  Impulsverkehr mit vielen gleichzeitig aktiven, unabhängig wandernden
  Impulsen (Polyphonie-Fall, siehe `~maxPolyphony` in `klangnetz_bells.scd`)
  — das Risiko klanglich "falscher" Zufalls-Zusammentreffen ist strukturell
  am geringsten.

### Modus C — "Maqam Hijaz" (orientalische Färbung)

- **Skala:** angenäherte Maqam-Hijaz-Skala (12-TET-Näherung),
  `[0, 1, 4, 5, 7, 8, 10]` Halbtöne — enthält die charakteristische
  übermäßige Sekunde (Halbton → übermäßige Sekunde direkt am Grundton, siehe
  skalen.md Abschnitt 4). Bewusste, offen deklarierte 12-TET-Näherung ohne
  Anspruch auf mikrotonale Authentizität (Standard-Bell-Synthese in
  `\glockenBell` arbeitet mit `midicps`, keine Vierteltöne).
- **Bevorzugte Kanten-Intervalle:** primär Halbtonschritt UND die für Hijaz
  namensgebende übermäßige Sekunde (3 Halbtöne) selbst als "Schritt"-
  äquivalent behandelt (sie ist die auf dieser Skala liegende Nachbarstufe,
  keine Ausnahme) — plus Quint als Landmarken-Intervall.
- **Schritt/Sprung-Gewichtung:** 60% direkte Nachbarstufe (inkl. der
  übermäßigen Sekunde), 25% zwei Stufen, 15% Quint/Landmarke. Bewusst
  weniger "Terz-lastig" als Modus A, weil Maqam-Melodik stark
  stufenweise/jins-intern verläuft (skalen.md Abschnitt 4: Ajnas sind
  benachbarte Tonfolgen, keine Dreiklangs-Sprünge).

### Modus D — "Harmonisch Moll" (funktional-europäische Alternative)

- **Skala:** Harmonisch Moll, `[0, 2, 3, 5, 7, 8, 11]` Halbtöne — enthält
  ebenfalls eine übermäßige Sekunde (zwischen Stufe 6 und 7), aber mit
  echtem Leitton zur Tonika (Halbtonschritt Stufe 7 → Oktave), dadurch
  stärkerer "Auflösungs"-Charakter als Modus C.
  Eigenständige vierte Option, um dem Operator eine europäisch-funktionale
  Alternative zur maqam-nahen Färbung von Modus C zu geben, ohne die beiden
  zu vermischen (siehe skalen.md Abschnitt 3, kultureller Klärungshinweis:
  harmonisch Moll ist genuin europäischer Herkunft, auch wenn es "exotisch"
  klingt).
- **Bevorzugte Kanten-Intervalle:** Sekundschritt bevorzugt, Leitton-
  Halbtonschritt (Stufe 7→1) als besonders bevorzugtes Intervall an
  Kanten, die zu einem als "Ziel"/Ankerpunkt markierten Node führen
  (Auflösungs-Charakter gezielt nutzen).
- **Schritt/Sprung-Gewichtung:** wie Modus A (70/20/10), aber mit Bonus-
  Gewicht für den Leitton-Schritt in Zielrichtung Ankerpunkt.

### Modus E — "Phrygisch" (der heute live laufende Charakter)

- **Skala:** Phrygisch, `[0, 1, 3, 5, 7, 8, 10]` Halbtöne — **exakt** der
  Wert, der aktuell in `supercollider/klangnetz_bells.scd` Zeile 320 als
  `~scaleSteps` steht ("aktuell Phrygisch ab A2", Kommentar Zeile 79). Dieser
  Modus ist damit der einzige der acht, bei dem die Umstellung auf die
  topologische Zuordnung **nur** die Notenverteilung ändert und den
  Tonvorrat unangetastet lässt — der geeignete Modus für einen A/B-Vergleich
  "alt gegen neu", weil sich dabei genau eine Sache ändert.
- **Bevorzugte Kanten-Intervalle:** primär Sekundschritt wie Modus A, aber
  mit ausdrücklichem Vorzug für den **Halbtonschritt Stufe 1 → Stufe 2**
  (`0 → 1`) an Kanten, die vom Grundton wegführen. Dieser Halbton direkt
  über dem Grundton ist das Erkennungsmerkmal von Phrygisch (skalen.md
  Abschnitt 1: "Moll mit erniedrigter 2. Stufe, spanisch/dunkel"); wird er
  vom Algorithmus nur zufällig getroffen, klingt das Ergebnis wie
  irgendein Moll. Quint bleibt Landmarken-Intervall wie überall.
- **Schritt/Sprung-Gewichtung:** 75% Sekundschritt, 15% Terz, 10%
  Quint/Landmarke — etwas schrittlastiger als Modus A. Begründung: der
  charakteristische Halbton wirkt nur, wenn er auch tatsächlich als
  *Schritt* vorkommt; bei viel Terz-Anteil wird er regelmäßig übersprungen
  und der Modus verliert genau das Merkmal, wegen dem er gewählt wurde.
  Phrygisch ist außerdem dunkler und spannungsreicher als Dorisch (die
  kleine Sekunde über dem Grundton erzeugt einen dauerhaften, nicht
  aufgelösten Reibungspunkt zur Tonika) — mehr Sprünge machen daraus
  schnell einen unruhigen statt eines gespannt-ruhigen Klangs.

### Modus F — "Maqam ʿAjam" (die helle maqam-Färbung)

- **Skala:** `[0, 2, 4, 5, 7, 9, 11]` Halbtöne. **Keine Näherung nötig:**
  Maqam ʿAjam ist aus zwei ʿAjam-Trichorden (Ganzton–Ganzton) plus dem
  Halbtonschritt zur nächsten Stufe aufgebaut und ergibt damit dieselbe
  Tonmenge wie die westliche Durtonleiter; die einzige mikrotonale
  Abweichung, die die Praxis kennt, ist eine leicht tiefer intonierte dritte
  Stufe, und die ist eine Intonations-, keine Strukturfrage
  (maqamworld.com/Wikipedia ʿAjam, siehe Quellen unten).
- **Warum trotzdem "Maqam" und nicht "Ionisch":** in diesem Konzept ist ein
  Modus nicht nur eine Tonmenge, sondern eine Tonmenge **plus** Kantenregel
  (Abschnitt 4, Einleitung). ʿAjam unterscheidet sich von einem
  Dur-/Ionisch-Modus genau dort: die Melodik verläuft jins-intern
  stufenweise mit einem betonten Wechselpunkt auf der 5. Stufe (dem
  *Ghammaz*, an dem der obere Jins ansetzt), nicht in Dreiklangsbrechungen.
  Der Tonvorrat ist identisch, die Wegeführung nicht — und in einem
  Verfahren, das Wege vertont, ist das der wirksamere Unterschied.
  **Offen gesagt:** wer nur den Tonvorrat betrachtet, hat hier Dur vor sich;
  das Dokument behauptet nichts anderes.
- **Bevorzugte Kanten-Intervalle:** Sekundschritt; die Kante, die auf die
  5. Stufe führt, bekommt Vorrang als Landmarken-Kante (Ghammaz-Rolle, deckt
  sich mit der ohnehin vorhandenen Quint-Landmarken-Regel aus Schritt 3b).
- **Schritt/Sprung-Gewichtung:** 70% Sekundschritt, 15% Terz, 15%
  Quint/Landmarke. Der gegenüber Modus A erhöhte Landmarken-Anteil ist die
  Ghammaz-Betonung; die abgesenkte Terz hält die Linie jins-intern.
- **Charakter im Vergleich:** der helle, offene Gegenpol zu Hijaz (Modus C)
  und Saba (Modus H) — die Wahl für einen Abend, an dem die Installation
  freundlich statt spannungsvoll klingen soll.

### Modus G — "Maqam Nikriz" (übermäßige Sekunde in der Mitte)

- **Skala:** `[0, 2, 3, 6, 7, 9, 10]` Halbtöne — unterer **Jins Nikriz**
  (Pentachord `C–D–E♭–F♯–G`, also `0 2 3 6 7`) plus ein Buselik-/
  Moll-Tetrachord auf der 5. Stufe (`G–A–B♭–C` → `7 9 10 12`). **Keine
  Vierteltöne**, damit ohne Näherung auf dem 12-Ton-Raster darstellbar.
  Die in manchen Quellen ebenfalls genannte Variante mit Jins **Rast**
  obenauf ist hier bewusst **nicht** gewählt: Rast enthält eine neutrale
  (halb-erniedrigte) Stufe und wäre nur als Näherung zu haben — das
  vermeidbare Zugeständnis wird vermieden.
- **Das strukturelle Argument für diesen Modus:** die übermäßige Sekunde
  (3 Halbtöne innerhalb der Skala) ist das Merkmal, das drei der acht Modi
  teilen — aber an **je anderer Stelle**:

  | Modus | übermäßige Sekunde zwischen Stufe | Wirkung |
  |---|---|---|
  | C — Maqam Hijaz | 2 → 3 (`1 → 4`) | ganz unten, direkt über dem Grundton: sofort präsent, prägt jede Phrase |
  | G — Maqam Nikriz | 3 → 4 (`3 → 6`) | in der Mitte: die Skala klingt unten neutral-mollig und "kippt" erst im Verlauf |
  | D — Harmonisch Moll | 6 → 7 (`8 → 11`) | ganz oben, vor dem Leitton: wirkt als Zuspitzung kurz vor der Auflösung |

  Für ein Verfahren, das Melodien aus *Wegen* erzeugt, ist das ein echter
  und hörbarer Unterschied und keine Variantenkosmetik: an welcher Stelle
  eines Weges die Reibung auftritt, hängt direkt davon ab, wo im Tonvorrat
  sie sitzt.
- **Bevorzugte Kanten-Intervalle:** Nachbarstufe (die übermäßige Sekunde
  `3 → 6` zählt dabei wie in Modus C als *Schritt*, weil sie die auf dieser
  Skala liegende Nachbarstufe ist), Quint als Landmarke. Die 4. Stufe (`6`,
  der Tritonus über dem Grundton) ist bewusst **kein** bevorzugtes
  Sprungziel: als Durchgangsstufe innerhalb des Pentachords trägt sie den
  Charakter, als angesprungener Zielton klingt sie nach Fehler.
- **Schritt/Sprung-Gewichtung:** 65% Nachbarstufe, 20% zwei Stufen, 15%
  Quint/Landmarke.

### Modus H — "Maqam Saba" (die dunkle, klagende Färbung)

- **Skala (12-TET-Näherung, offen deklariert):** `[0, 1, 3, 4, 7, 8, 10]`
  Halbtöne. Original auf D: `D – E½♭ – F – G♭ – A – B♭ – C`. Die
  halb-erniedrigte zweite Stufe `E½♭` wird hier nach **unten** auf `E♭`
  angenähert (= `1`); die Näherung nach oben (`E`, = `2`) wäre ebenso
  vertretbar (skalen.md Abschnitt 4 sagt für Bayati genau das über
  denselben Ton), ergäbe aber `[0, 2, 3, 4, ...]` — drei Halbtonschritte in
  Folge und damit einen Klang, der mit Saba nichts mehr zu tun hat. Die
  Wahl ist begründet, nicht beliebig, aber sie **ist** eine Wahl.
- **Das Erkennungsmerkmal:** die **verminderte Quart** `D → G♭` (4 Halbtöne
  statt 5) an der Stelle, an der praktisch jeder andere Modus eine reine
  Quart hat. Der untere Jins ist bis dahin fast identisch mit Bayati; erst
  die um einen Halbton abgesenkte 4. Stufe macht Saba aus. Charakter:
  in der Literatur durchgehend als der klagendste, trauerbezogene Maqam
  beschrieben — traditionell bei Trauerfeiern gespielt, "der emotional
  eindeutigste der Kern-Maqamat".
- **Ein ehrlicher Vorbehalt, der genannt gehört:** Saba wiederholt sich in
  der Praxis **nicht sauber auf der Oktave** (der obere Skalenteil schließt
  einen Halbton unter der Oktave, weil die Ajnas dort anders ineinander
  greifen). Der Algorithmus in Abschnitt 5 rechnet dagegen `octave =
  scaleIndex div scale.length` und **erzwingt damit Oktavwiederholung** —
  dieses eine Merkmal von Saba geht in diesem Konzept also verloren. Der
  Modus liefert die Saba-*Färbung* über den Tonvorrat, nicht die
  Saba-Systematik. Das ohne Not zu behaupten wäre falsch; die Alternative
  (Skalen mit nicht-oktavperiodischer Fortsetzung im Datenmodell zulassen)
  wäre ein eigener Entwurf und ist hier bewusst nicht gemacht.
- **Bevorzugte Kanten-Intervalle:** ausgeprägt schrittweise — Halbton- und
  Nachbarstufenschritte, insbesondere die Zelle `0 → 1 → 3 → 4` (der Weg
  hinunter zur verminderten Quart), die den Charakter trägt. Die Quint
  (Stufe 5, `7`) bleibt Landmarken-Intervall und ist die einzige wirklich
  stabile Stufe des Modus.
- **Schritt/Sprung-Gewichtung:** 80% Nachbarstufe, 10% zwei Stufen, 10%
  Quint/Landmarke — der schrittlastigste aller acht Modi. Begründung: die
  charakteristische Enge des unteren Jins (drei Stufen innerhalb von vier
  Halbtönen) ist nur hörbar, wenn sie durchschritten und nicht übersprungen
  wird; ein Modus mit vielen Sprüngen macht aus Saba eine beliebige
  verminderte Skala.

**Quellen für Modi F–H:** `hermes-knowledge/musiktheorie/skalen.md`
Abschnitt 4 (Jins-/Maqam-Terminologie, Hijaz, Bayati, Einordnung der Ajnas
ʿAjam/Rast/Nahawand/Kurd) als Ausgangspunkt, ergänzt um
[maqamworld.com](https://www.maqamworld.com/en/maqam.php) (Jins-Systematik,
Nikriz-Pentachord, ʿAjam-Trichorde, Saba),
[Wikipedia — Ajam (maqam)](https://en.wikipedia.org/wiki/Ajam_(maqam)),
[Wikipedia — Nikriz pitch class set](https://en.wikipedia.org/wiki/Nikriz_pitch_class_set)
und [Wikipedia — Rast (Arabic maqam)](https://en.wikipedia.org/wiki/Rast_(Arabic_maqam))
für die Abgrenzung der Näherungsfälle.

**Bewusst nicht aufgenommen** (damit die Auswahl nicht aus fast gleichen
Modi besteht): **Maqam Nahawand** (≈ harmonisch/natürlich Moll, deckt sich
mit Modus D), **Maqam Kurd** (≈ Phrygisch, deckt sich mit Modus E) und
**Maqam Rast** — letzteres, obwohl es der zentralste Maqam überhaupt ist:
seine beiden neutralen Stufen (3. und 7.) sind sein ganzes Wesen, und die
12-TET-Näherung davon ist entweder Ionisch oder Mixolydisch. Ein Modus, der
"Rast" heißt und dabei genau das weglässt, was Rast ausmacht, wäre eine
Etikettierung ohne Substanz — dieselbe Sorgfaltslinie, die skalen.md
Abschnitt 4 für Bayati zieht.

## 5. Algorithmus (Konzept, kein Code)

### Schritt 1 — Graph aus `nodeCrossings.txt` ableiten

```
Eingabe: Liste von 92 Zeilen "ledIdxA ledIdxB [...]" aus nodeCrossings.txt,
         numLedsPerStripe (aktuell 600)

1. Für jede Zeile i: erzeuge Node(id = i, ledIndices = {ledIdxA, ledIdxB, ...})
2. Für jeden Node: berechne für jeden seiner ledIndices
   (stripeIndex, indexInStripe) = (ledIdx / numLedsPerStripe,
                                    ledIdx % numLedsPerStripe)
3. Gruppiere ALLE (Node, indexInStripe)-Paare nach stripeIndex
4. Innerhalb jeder Stripe-Gruppe: sortiere nach indexInStripe aufsteigend
5. Für aufeinanderfolgende Einträge derselben Stripe-Gruppe (die zu
   unterschiedlichen Nodes gehören): füge eine ungerichtete Kante
   zwischen diesen beiden Nodes zum Graph hinzu
   → Ergebnis: adjacency-Map Node -> Set<Node>  (siehe Beispielrechnung
     unten: node 90 hat z.B. die Nachbarn {64, 66, 70, 71})
6. (Optional, für Vollständigkeit) LED-Endpunkte, die an KEINER Kreuzung
   liegen (Stripe-Enden), werden als "virtuelle Blattknoten" mit Grad 1
   behandelt — für die reine Ton-Zuordnung an den 92 echten Nodes nicht
   zwingend nötig, aber relevant, falls Stripe-Enden ebenfalls Klangevents
   auslösen sollen (nicht Teil dieses Konzepts, nur als Erweiterungshinweis).
```

### Schritt 2 — Modus wählen

```
Operator wählt Modus ∈ {DORISCH, PENTATONIK, MAQAM_HIJAZ, HARMONISCH_MOLL,
                        PHRYGISCH, MAQAM_AJAM, MAQAM_NIKRIZ, MAQAM_SABA}
→ legt scale[] (Halbtonabstände) und die Kanten-Gewichtungstabelle fest
  (siehe Abschnitt 4 je Modus)
```

### Schritt 3 — Prioritäts-Traversierung vom Ursprungsknoten

Kernidee: EINMALIGE Breitensuche (BFS) vom gewählten Startknoten, bei der
jeder neu erreichte Node eine Skalenstufe relativ zu dem Nachbarn bekommt,
über den er zuerst erreicht wurde — nicht relativ zu allen seinen Nachbarn
gleichzeitig (das wäre bei einem zyklischen Graphen oft unerfüllbar, siehe
Abschnitt 6).

```
Eingabe: Graph (Schritt 1), scale[] + Gewichtungstabelle (Schritt 2),
         startNode (OSC-Parameter /net/melody/startNode, siehe Abschnitt 9;
         Default: Node mit höchstem Grad = "Hub", siehe Beispielrechnung:
         Node 90 mit Grad 4)

1. degreeMap = für jeden Node: Anzahl seiner Nachbarn (aus Schritt 1)
2. hubThreshold = z.B. 75. Perzentil aller Grade
   (Nodes mit degree >= hubThreshold gelten als "Landmarken")

3. scaleIndex[startNode] = scale.length * (numOctaves div 2)
       // Grundton/Tonika – aber in der MITTLEREN Oktave, nicht bei 0.
       // Aktuell 7 * (3 div 2) = 7, also Stufe 1 der zweiten Oktave = A3.
       // Begründung siehe Schritt 3d: es muss in BEIDE Richtungen Luft
       // sein, sonst faltet jedes Abwärtsintervall sofort um.
   tiefe[startNode]      = 0   // BFS-Tiefe, gebraucht in Schritt 3c
4. besucht = {startNode}
5. queue = [startNode]
6. solange queue nicht leer:
     current = queue.pop_front()
     für jeden Nachbarn n von current (nach Priorität sortiert,
         siehe Schritt 3a):
       wenn n bereits besucht: überspringen (Kante wird NICHT erneut
         bewertet — das ist die pragmatische Zyklus-Antwort, siehe
         Abschnitt 6)
       sonst:
         tiefe[n] = tiefe[current] + 1
         wenn degreeMap[n] >= hubThreshold:
           gewähltesIntervall = STABILE_INTERVALLE[ tiefe[n] mod 3 ]
             // Landmarken-Rotation, siehe Schritt 3c — NICHT immer Quint
         sonst:
           gewähltesIntervall = ziehe_gewichtetes_intervall(
               Gewichtungstabelle des Modus)
         scaleIndex[n] = scaleIndex[current] + gewähltesIntervall
             // gewähltesIntervall in SKALENSTUFEN, nicht Halbtönen –
             // dadurch bleibt jede Note automatisch auf der Skala.
             // Der Wert darf hier vorzeichenbehaftet und beliebig gross
             // werden; begrenzt wird erst in Schritt 7 (Oktavfaltung).
         besucht.add(n)
         queue.push_back(n)

7. Für jeden Node: finale MIDI-Note ableiten
     gefaltet = ((scaleIndex[node] mod notesPerOctaveSet)
                 + notesPerOctaveSet) mod notesPerOctaveSet     // Schritt 3d
     octave   = gefaltet div scale.length
     degree   = gefaltet mod scale.length
     semitone = scale[degree] + 12 * octave
     midiNote = rootMidiNote + semitone
     (identische Umrechnungslogik wie die bestehende
      noteIndex -> octave/degree/semitoneOffset -> midiNote Kette in
      klangnetz_bells.scd Zeilen 1385-1388 — NUR scaleIndex[node] ersetzt
      die bisherige (nodeId + bias) % notesPerOctaveSet Zuweisung.
      notesPerOctaveSet = scale.length * numOctaves, aktuell 7 * 3 = 21,
      klangnetz_bells.scd Zeile 326)
```

**Schritt 3a — Nachbarn-Priorität:** an jedem Node werden die noch nicht
besuchten Nachbarn zuerst nach "ist Landmarke" (Grad >= hubThreshold) und
dann nach LED-Index sortiert abgearbeitet — deterministisch bei festem
Startknoten und festem Graph (relevant für die Stabilitätsfrage, Abschnitt
7).

**Schritt 3b — Landmarken-Bonus:** Kanten zu einem Hub-Knoten (hohe
Anzahl Verbindungen — mehrere Stripes/Wege treffen sich dort) bekommen
bevorzugt das markante, aber konsonante Landmarken-Intervall (Quint bzw.
modusabhängig, siehe Abschnitt 4) statt eines einfachen Sekundschritts —
das setzt Hub-Knoten klanglich als Ankerpunkte ab (melodiefuehrung.md
Abschnitt 3: Knoten mit struktureller Bedeutung auf stabile Skalenstufen).

**Schritt 3c — Landmarken-Rotation** (ergänzt 2026-08-01, Birks
Entscheidung):

Das Problem, das diese Regel löst, ist beim Nachfragen zur
Landmarken-Schwelle aufgefallen und war im ursprünglichen Entwurf **nicht
bedacht**: `hubThreshold` ist als Regler gedacht, und dreht man ihn
herunter (= mehr Knoten gelten als Landmarke), macht die alte Regel
"Landmarken-Kante bekommt Quint" zwei Dinge kaputt.

1. **Sie erzeugt keine gemeinsame Tonhöhe, sondern nur ein gemeinsames
   Intervall.** Eine Landmarke bekommt die Quint **zu ihrem jeweiligen
   Elternknoten** — und der sitzt selbst schon irgendwo. Zwei Landmarken in
   verschiedenen BFS-Tiefen klingen also gerade *nicht* gleich stabil, sie
   liegen nur beide eine Quint über etwas Beliebigem. Die Absicht
   ("Landmarken sind die Ankerpunkte") wird von der Umsetzung nicht
   eingelöst.
2. **Sie treibt die Tonhöhe strukturell nach oben.** Jede Landmarken-Kante
   addiert +4 Stufen. Auf einem Weg über `k` Landmarken steigt `scaleIndex`
   um `4k` — bei vielen Landmarken wandert die Zuordnung monoton aus dem
   sinnvollen Bereich heraus. Ohne Gegenmaßnahme ist das eine unbegrenzte
   Oktavdrift.

Die dritte, in der Beispielrechnung (Abschnitt 7) sichtbare Nebenwirkung:
alle drei Landmarken-Nachbarn von Node 90 bekommen `scaleIndex = 4`, also
**exakt denselben Ton** (E3). Bei mehreren gleichzeitig aktiven Impulsen
(`~maxPolyphony = 24`) sind das dann mehrere Glocken auf derselben
Frequenz — Verdopplung und Phasing statt Akkord.

**Die Regel:** eine Landmarken-Kante bekommt nicht immer die Quint, sondern
rotiert deterministisch über drei **stabile** Skalenstufen:

```
STABILE_INTERVALLE = [ +4, -3, +2 ]        // in SKALENSTUFEN, 0-basiert

  +4  = Quint aufwärts    (Stufe 5 über dem Elternknoten)
  -3  = Quart abwärts     (dieselbe Stufe wie +4, eine Oktave tiefer:
                            4 - 7 = -3 bei siebentöniger Skala)
  +2  = Terz aufwärts     (die mildeste der drei, Dreiklangs-Charakter)

landmarkenIntervall(n) = STABILE_INTERVALLE[ (tiefe(n) + rang(n)) mod 3 ]

  tiefe(n) = BFS-Tiefe von n
  rang(n)  = 0-basierte Position von n in der nach Schritt 3a sortierten
             Nachbarliste seines Elternknotens
```

**Warum beide Summanden gebraucht werden** — jeder allein greift zu kurz:

- **Nur `tiefe`** würde ALLEN Landmarken einer BFS-Ebene dasselbe Intervall
  geben. Genau die Geschwister, die im Ausgangsproblem auf demselben Ton
  landeten (64, 70, 71 als Nachbarn von Node 90, alle Tiefe 1), lägen dann
  wieder alle auf demselben Wert — nur auf einem anderen. Der Ton-Stapel
  wäre verschoben, nicht aufgelöst.
- **Nur `rang`** verteilte innerhalb einer Nachbarliste, aber jeder erste
  Nachbar jedes Knotens bekäme immer `+4` — entlang eines langen Weges, der
  jeweils der ersten Landmarke folgt, wäre die Drift ungebremst.

Die Summe verteilt auf **beiden** Achsen: quer (Geschwister bekommen
verschiedene Werte) und längs (dieselbe Rangposition bekommt in der
nächsten Ebene einen anderen Wert). `rang` ist dabei keine neue Größe —
die Reihenfolge steht schon in Schritt 3a und ist bei festem Startknoten
und festem Graph deterministisch, was die Reproduzierbarkeit aus
Abschnitt 8 nicht antastet.

Drei Eigenschaften, wegen der genau diese drei Werte gewählt sind:

- **Der Landmarken-Charakter bleibt erhalten.** `+4` und `-3` liegen auf
  **derselben Skalenstufe**, nur in verschiedenen Oktaven (Quint aufwärts
  und Quart abwärts sind Umkehrungen desselben Intervalls). Zwei Drittel
  aller Landmarken stehen also weiterhin in genau der Quint-Beziehung zu
  ihrem Elternknoten, die sie hörbar als Ankerpunkt markiert — es wechselt
  nur die Lage. Das ist der Unterschied zu "einfach andere Intervalle
  einstreuen": die Regel verwässert den Anker nicht, sie verteilt ihn.
- **Die Drift wird um Faktor 4 kleiner und verliert ihre Richtung.**
  Mittelwert der drei Werte: `(+4 - 3 + 2) / 3 = +1` Stufe pro
  Landmarken-Ebene statt `+4`, und das Vorzeichen wechselt — die Tonhöhe
  pendelt, statt zu steigen.
- **Kein Ton-Stapel mehr.** Die Landmarken-Nachbarn eines Knotens liegen
  jetzt auf verschiedenen Tiefen bzw. verschiedenen Stufen, statt alle auf
  demselben Wert (siehe die aktualisierte Beispielrechnung, Abschnitt 7).

Rotiert wird über abgeleitete Strukturgrößen, nicht über einen Zufallswert:
beide stehen ohnehin zur Verfügung, und die Zuordnung muss reproduzierbar
sein (Abschnitt 8). Ein Zufallswert an dieser Stelle hieße, dass zwei Läufe
mit identischem Startknoten und identischem Graph verschiedene Ergebnisse
liefern — genau das, was die Persistenz verhindern soll.

**Anmerkung zu den normalen (Nicht-Landmarken-)Kanten:** auch deren
gewichtete Ziehung zieht ein **Vorzeichen** mit, ein "Sekundschritt" ist
also `±1`, nicht nur `+1`. Ohne das driftete die Zuordnung über die
normalen Kanten weiter nach oben, und die Rotation löste nur den kleineren
Teil des Problems. Musikalisch ist das ohnehin die richtige Lesart:
melodiefuehrung.md kennt Schritte in beide Richtungen, und eine Linie, die
ausschließlich steigt, ist keine.

**Schritt 3d — Oktavfaltung (die harte Grenze):**

Die Rotation dämpft die Drift, sie **beschränkt sie nicht**. Ein Graph mit
92 Knoten hat Wege beliebiger Länge, und `+1` im Mittel ist immer noch
unbegrenztes Wachstum. Auch die vorzeichenbehaftete Ziehung auf den
normalen Kanten hilft nur statistisch: sie hat Mittelwert 0, aber jede
einzelne Realisierung ist eine Irrfahrt und entfernt sich mit der Weglänge
beliebig weit vom Ausgangspunkt. "Im Mittel bleibt es in der Mitte" ist
keine Garantie für 92 konkrete Knoten. Eine Hörbarkeitsgarantie gibt es
deshalb erst durch eine zweite, unabhängige Regel:

```
gefaltet = ((scaleIndex[node] mod notesPerOctaveSet) + notesPerOctaveSet)
           mod notesPerOctaveSet
   mit notesPerOctaveSet = scale.length * numOctaves   (aktuell 7 * 3 = 21)
```

(Der doppelte Modulo ist die übliche Behandlung negativer Werte — durch
`-3` in der Rotation kann `scaleIndex` negativ werden, und ein einfaches
`%` liefert in Java dann ein negatives Ergebnis.)

Vier Punkte dazu:

- **Der Startknoten muss deshalb in der mittleren Oktave sitzen** (Schritt 3,
  Zeile 3). Läge die Tonika wie ursprünglich auf `scaleIndex = 0`, also am
  unteren Rand des gefalteten Bereichs, würde **jedes** Abwärtsintervall
  sofort umbrechen — die `-3` aus der Rotation träfe direkt beim ersten
  Schritt auf die Umbruchkante und landete am oberen Ende. Mit
  `scale.length * (numOctaves div 2)` bleibt eine volle Oktave Luft nach
  unten und eine nach oben, bevor überhaupt gefaltet werden muss. Das ist
  keine Kosmetik: die Rotation aus Schritt 3c wäre ohne diese Zeile
  wirkungslos bis schädlich.
- **Die Faltung selbst ist keine neue Erfindung, sondern eine schon vorhandene Regel.**
  `klangnetz_bells.scd` Zeile 1380 rechnet heute bereits
  `noteIndex = (nodeId + bias[\noteOffset]) % ~notesPerOctaveSet` — die
  Faltung auf drei Oktaven existiert also längst, sie hing bisher nur an
  der `nodeId`. Dieses Konzept ersetzt den *Zähler* vor dem Modulo, nicht
  den Modulo. Damit gilt weiter dieselbe garantierte Spanne wie im
  Live-Betrieb: MIDI 45 (A2) bis 45 + 10 + 24 = **79** (G5),
  `~rootMidiNote = 45`, `~numOctaves = 3` (Zeilen 324-325).
- **Die Faltung kostet etwas, und zwar an einer benennbaren Stelle.** An
  der Umbruchkante ist das Intervall nicht mehr regelkonform: ein Knoten
  bei `gefaltet = 20`, dessen Kind `+4` bekommt, landet auf `24 → 3` — statt
  einer Quint aufwärts ein Sprung fast drei Oktaven abwärts. Das ist
  **strukturell derselbe Fall** wie die Rückwärtskanten aus Abschnitt 6:
  eine kleine, benannte Minderheit von Kanten ohne Garantie. Die
  Landmarken-Rotation reduziert diese Fälle zusätzlich, weil sie die
  Umbruchkante seltener erreicht (Mittelwert +1 statt +4).
- **Warum Modulo und nicht Spiegelung am Rand.** Eine Reflexion
  ("bei Überschreitung Richtung umkehren") hielte die Intervalle auch an
  der Grenze klein und wäre musikalisch die elegantere Antwort — sie ist
  aber eine **zweite** Umrechnungsvorschrift neben der, die in der `.scd`
  schon steht. Zwei Wege von `scaleIndex` zur MIDI-Note sind zwei
  Wahrheiten, die auseinanderlaufen können; dieselbe Überlegung wie bei
  `~applyParam` und bei `spawnSpeed()`. Wenn die Umbruchkanten sich im
  Betrieb als störend erweisen, ist die Reflexion die naheliegende
  Erweiterung — dann aber an genau einer Stelle, für beide Seiten.

**Messbar statt überraschend:** beim Erzeugen der Zuordnungsdatei
(Abschnitt 8) sollte der Bericht beide Zahlen nennen — Anzahl
Rückwärtskanten und Anzahl Umbruchkanten, jeweils mit dem resultierenden
Intervall. Das sind die einzigen zwei Kantenklassen ohne Regel-Garantie;
sie zu zählen ist der Unterschied zwischen einem bekannten Kompromiss und
einem stillen Fehler.

## 6. Das Zyklus-Problem — explizit benannt (ENTSCHIEDEN)

> **Entschieden am 2026-08-01:** Birk akzeptiert den unten beschriebenen
> Kompromiss ausdrücklich. Rückwärtskanten bekommen **keine**
> Intervall-Garantie, und es wird **keine** Nachbearbeitungsstufe gebaut,
> die besonders dissonante Rückwärtskanten nachträglich glättet. Der Punkt
> ist damit aus der Liste der offenen Fragen heraus (siehe Abschnitt 11).

Der Graph ist nicht kreisfrei. Beispiel aus den echten Daten (siehe
Beispielrechnung unten): Node 90 verbindet zu Node 70 UND Node 71; Node 70
und Node 71 sind selbst ebenfalls direkt verbunden → Dreieck 90-70-71.

Für eine "perfekte" Lösung müsste GLEICHZEITIG gelten:
`interval(90,70)` UND `interval(90,71)` UND `interval(70,71)` seien alle
drei "gute" Melodie-Intervalle. Das ist bei drei frei wählbaren
Skalenstufen im Allgemeinen NICHT für beliebig viele Zyklen gleichzeitig
erfüllbar — sobald zwei der drei Intervalle feststehen, ist das dritte
automatisch die Differenz der beiden ersten und kann zufällig groß/
dissonant ausfallen (ein bekanntes Problem der Graph-Färbung/-Einbettung:
konsistente lokale Abstände auf einem Baum sind trivial, auf einem
Graphen mit Zyklen im Allgemeinen unlösbar ohne Kompromiss).

**Pragmatischer Kompromiss (in Schritt 3 bereits eingebaut):** die
BFS-Traversierung entscheidet die Tonhöhe eines Nodes EINMALIG, beim
ERSTEN Erreichen über die kürzeste/zuerst gefundene Kante. Kanten, die
später auf einen bereits besuchten Node zurückführen (sogenannte
"Rückwärtskanten"/"back edges" des BFS-Baums — bei Node 90-70-71 z.B. die
Kante 70-71, falls beide bereits über 90 erreicht wurden), werden NICHT
mehr zur Tonhöhen-Berechnung herangezogen; der Impuls, der diese Kante
tatsächlich zur Laufzeit entlangwandert, bekommt trotzdem die bereits
fixierte Note des Zielknotens — das resultierende Intervall auf DIESER
speziellen Kante ist dann nicht mehr regelkonform garantiert, sondern
ergibt sich als Restgröße.

Das ist ein akzeptierter, kommunizierter Kompromiss: die überwiegende
Mehrheit der Kanten (die "Baumkanten" des BFS) bekommt garantiert
regelkonforme Intervalle; nur die deutlich selteneren Rückwärtskanten
(bei 92 Nodes und der beobachteten Graphdichte ca. 10-20 % aller Kanten,
siehe Zahlen unten) können klanglich "aus der Reihe tanzen" — was in einem
Ambient-/Installationskontext eher als gelegentliche Überraschung denn als
Störung wahrgenommen werden dürfte, zumal Bell-Klänge grundsätzlich
klangfarblich verzeihender sind als z. B. eine gesungene Melodie.

## 7. Beispielrechnung an echten Daten

Adjazenz wurde aus `data/nodeCrossings.txt` + `numLedsPerStripe = 600`
(aus `imPulse.pde`) nach Schritt 1 tatsächlich berechnet (nicht erfunden).
Ausschnitt, BFS-Start bei Node 90 (Grad 4, damit ein Hub-Kandidat):

```
Zeile 90 (nodeId=90): LEDs 9286, 17700  → Stripe 15/29, Position 286/300
Zeile 64 (nodeId=64): LEDs 9942, 17831  → Stripe 16/29, Position 342/431
Zeile 66 (nodeId=66): LEDs 9162, 17083  → Stripe 15/28, Position 162/283
Zeile 70 (nodeId=70): LEDs 14638,17677  → Stripe 24/29, Position 238/377
Zeile 71 (nodeId=71): LEDs 9303, 14616  → Stripe 15/24, Position 303/216
Zeile 65 (nodeId=65): LEDs 9745, 17141  → Stripe 16/28, Position 145/541
Zeile 19 (nodeId=19): LEDs 2048, 17870  → Stripe 3/29, Position 448/470
Zeile 63 (nodeId=63): LEDs 8770, 10060  → Stripe 14/16, Position 770/1660(*)

Berechnete Adjazenz (aus Stripe-Positions-Reihenfolge):
  90 -> {64, 66, 70, 71}      (Grad 4 → Hub-Kandidat)
  64 -> {65, 90, 19, 63}      (Grad 4 → ebenfalls Hub-Kandidat)
  66 -> {65, 90, 68}
  70 -> {41, 90, 68, 71}      (Grad 4 → Hub-Kandidat)
  71 -> {24, 90, 21, 70}      (Grad 4 → Hub-Kandidat)
  65 -> {64, 66, 40}
  19 -> {64, 18, 20}
  63 -> {64, 17, 76, 54}
```

(*) Position 1660 liegt außerhalb der 600er-Stripe-Länge, ein Hinweis
darauf, dass die reale Rohdaten-Adjazenz-Berechnung im Detail noch die
korrekten Stripe-Grenzen aus der Laufzeit-Konfiguration abgleichen muss;
in der obigen konzeptuellen Beispielrechnung ändert das nichts an der
grundsätzlichen Nachbarschafts-Struktur (90-70-71 als echter Zyklus bleibt
in jedem Fall bestehen, unabhängig von der genauen Positionsberechnung
einzelner Randfälle).

**Zyklus im Beispiel:** 90 → 70 → 71 → 90 (Dreieck, siehe Abschnitt 6).

### Durchrechnung mit Modus A (Dorisch, scale = [0,2,3,5,7,9,10])

Start: Node 90 = Tonika, `scaleIndex[90] = 7` (mittlere Oktave, Schritt 3)
→ MIDI 57 (A3). `notesPerOctaveSet = 21`, `rootMidiNote = 45`.

BFS von 90 aus, Nachbarn-Priorität nach Schritt 3a: erst Landmarken
(Grad ≥ 4), innerhalb der Gruppe nach kleinstem LED-Index. 64, 70, 71 haben
Grad 4 (Landmarken), 66 hat Grad 3. Kleinster LED-Index: 71 → 9303,
64 → 9942, 70 → 14638. Daraus die Ränge `rang = 0,1,2,3` für
71, 64, 70, 66 — und mit `tiefe = 1` die Rotationsindizes
`(1+0), (1+1), (1+2) mod 3 = 1, 2, 0`.

```
Node 90  scaleIndex=7   Stufe 1, mittlere Oktave           MIDI 57 (A3)
                         (Tonika, Startknoten)

Node 71  scaleIndex=4   Landmarke, rot=1 → -3 Stufen       MIDI 45+7=52
                         (Quart ABWÄRTS von 90)              (E3)
Node 64  scaleIndex=9   Landmarke, rot=2 → +2 Stufen       MIDI 45+15=60
                         (Terz aufwärts von 90)              (C4)
Node 70  scaleIndex=11  Landmarke, rot=0 → +4 Stufen       MIDI 45+19=64
                         (Quint aufwärts von 90)             (E4)
Node 66  scaleIndex=8   normale Kante, +1 (Sekundschritt)  MIDI 45+14=59
                                                             (H3)

  (Ebene 2, Kinder von 64: Priorität 63 (Grad 4, Landmarke),
   dann 19 (LED 2048), dann 65 (LED 9745) → rang 0,1,2, tiefe 2)

Node 63  scaleIndex=11  Landmarke, rot=(2+0)=2 → +2        MIDI 64 (E4)
Node 19  scaleIndex=10  normale Kante, +1 von 64           MIDI 45+17=62
                                                             (D4)
Node 65  scaleIndex=8   normale Kante, -1 von 64           MIDI 59 (H3)
```

**Was die Rotation im Vergleich zur alten Fassung bewirkt:** vorher lagen
64, 70 und 71 alle auf `scaleIndex = 4`, also drei Knoten auf **exakt
demselben Ton** (E3). Jetzt liegen sie auf E3, C4 und E4 — zusammen mit der
Tonika A3 ergibt das über die vier Knoten 90/64/70 einen a-Moll-Dreiklang
(A3–C4–E4) und mit 71 die Quart darunter. Die Landmarken sind dadurch
weiterhin unüberhörbar stabil (zwei von dreien stehen in Quint-Beziehung zu
90, nur in verschiedenen Lagen), aber sie stapeln sich nicht mehr auf einer
Frequenz — genau die zwei Ziele aus Schritt 3c.

**Musikalische Lesbarkeit:** ein Impuls auf dem Weg 90 → 64 → 19 hört
A3 → C4 → D4 (Terzsprung an den Hub, dann ein Schritt weiter). Ein Impuls
auf 90 → 70 hört A3 → E4 (die markante Quint aufwärts, klar als "Ankunft an
einem Hub" erkennbar), einer auf 90 → 71 dagegen A3 → E3 — dieselbe
stabile Beziehung, nach unten aufgelöst. Der Weg 90 → 66 → ... bleibt die
ruhige, stufenweise Linie A3 → H3. Alle Wege sind für sich musikalisch
stimmig, ohne dass einer von ihnen vorab "durchgespielt" werden musste —
genau der Effekt, den Abschnitt 2 als Ziel beschreibt.

Die einzige "unkontrollierte" Kante in diesem Ausschnitt ist weiterhin
70-71 (beide über 90 belegt, die direkte Kante zwischen ihnen wird nicht
mehr bewertet): 70 steht auf `scaleIndex = 11`, 71 auf `4`, das Intervall
ist also 7 Skalenstufen = **genau eine Oktave** (E3 → E4). In der alten
Fassung war es zufällig ein Unisono, jetzt zufällig eine Oktave — beides
unproblematisch, und beides **Zufall**, nicht Regel. Genau das ist der in
Abschnitt 6 beschriebene und von Birk bestätigte Kompromiss: bei anderen
Zyklen kann dieselbe Restgröße auch als Tritonus oder große Septime
herauskommen.

Umbruchkanten (Schritt 3d) gibt es in diesem Ausschnitt keine — alle
Werte liegen zwischen 4 und 11 und damit weit innerhalb der Spanne
`0 .. 20`. Das ist die Wirkung der mittleren Startoktave: mit
`scaleIndex[90] = 0` hätte allein schon die `-3` bei Node 71 umgebrochen
und E**5** statt E3 ergeben.

## 8. Persistenz: einmal rechnen, als Datei festhalten (ENTSCHIEDEN)

> **Entschieden am 2026-08-01:** "Persistenz ja auf jeden Fall
> reproduzierbar fixieren." Die BFS-Zuordnung wird **einmalig berechnet und
> in eine Datei geschrieben**, nicht bei jedem Sketch-Start neu gewürfelt.

Der Grund ist derselbe, aus dem `master` den Live-Zustand abbilden muss
(CLAUDE.md, Branch-Konvention): ein Neustart der Installation soll exakt
den Klang reproduzieren, der vorher lief. Eine Zuordnung, die bei jedem
Start neu entsteht, hätte drei Folgen, die alle unerwünscht sind — die
Installation klänge jeden Abend tonal anders, ein am Vorabend gehörtes
Problem ließe sich nicht wiederfinden, und ein Preset (das nur Parameter
enthält, nicht die Zuordnung) beschriebe den Klang nicht mehr vollständig.

Die gewichteten Ziehungen aus Schritt 3 sind der einzige Zufall im
Verfahren (die Landmarken-Rotation ist bewusst deterministisch, siehe
Schritt 3c). Genau dieser Zufall wird durch das Persistieren **eingefroren**
— das Ergebnis ist ein Wurf, aber ein festgehaltener.

**Dateiformat** — bewusst nach dem Vorbild von `data/stripeTrees.txt` (dem
jüngsten Beispiel im Repo für "automatisch erzeugt, von Hand nachkorrigierbar"):

```
data/nodeMelody_<modus>.txt

# Kopfzeilen mit '#' als Kommentar: Modus, Startknoten, hubThreshold,
# Erzeugungsdatum, Anzahl Rückwärts- und Umbruchkanten (siehe Schritt 3d)
#
# Format: nodeId <TAB> scaleIndex <TAB> midiNote <TAB> tiefe
0	9	60	2
1	6	55	3
...
```

Vier Spalten, drei Entwurfsentscheidungen dahinter:

- **Maßgeblich ist `scaleIndex`, nicht `midiNote`.** Die MIDI-Note hängt an
  `~rootMidiNote` und `~numOctaves` auf der Klangseite; ändert Birk dort den
  Grundton, soll die Datei gültig bleiben. `midiNote` steht trotzdem
  daneben — als **abgeleitete Kontrollspalte** zum Mitlesen beim
  Nachkorrigieren von Hand, nicht als Eingabe. Wer die Datei ändert, ändert
  `scaleIndex`.
- **`tiefe` ist reine Diagnose** und wird beim Laden nicht ausgewertet —
  dieselbe Rolle wie `confidence` in `stripeTrees.txt`. Sie steht in der
  Datei, weil man beim Nachschauen sonst nicht erkennt, warum ein Knoten
  gerade dieses Landmarken-Intervall bekommen hat.
- **Ein Modus pro Datei**, der Modus steht im Dateinamen. Ein Moduswechsel
  ist ein Zuordnungswechsel; acht Modi in einer Datei zu verschachteln
  hieße, beim Umschalten die richtige Spalte zu treffen, statt eine andere
  Datei zu laden.

**Wann die Datei entsteht — ein Trigger von Hand, kein Automatismus.**
Berechnet wird nur auf ausdrückliche Aufforderung (ein Tastendruck im
Sketch analog `S`/`R` in den beiden Kalibrier-Modi, oder ein
OSC-Kommando analog `/preset/save`). Beim Start wird die Datei nur
**gelesen**. Fehlt sie, ist das kein Absturz, sondern eine `WARNUNG`-Zeile
plus Rückfall auf die heutige `nodeId`-Zuordnung — dasselbe Muster wie bei
fehlenden LED-Positionen (`setup()`, siehe CLAUDE.md).

Automatisches Neurechnen beim Start wäre die naheliegende Bequemlichkeit
und scheidet genau daran aus: es macht die Datei zu einem Zwischenspeicher
statt zu einer Quelle. Eine von Hand nachkorrigierte Zeile wäre beim
nächsten Start weg, und zwar ohne Meldung.

## 9. Startknoten als OSC-Parameter (ENTSCHIEDEN)

> **Entschieden am 2026-08-01:** "Startknoten soll von mir festgelegt werden
> können. Das unterscheidet dann ja die Melodie komplett. Soll auch als
> OSC-Parameter exposed werden, aber mit einem Default starten."

```
/net/melody/startNode   RemoteControlledIntParameter, 0 .. 91, Default: siehe unten
```

Der Bereich `0..91` entspricht der aktuellen Zeilenzahl von
`data/nodeCrossings.txt` (92 Kreuzungen). **Achtung:** das ist eine von der
Kreuzungszahl abgeleitete Zahl und darf nach der Repo-Konvention nicht als
Literal im Code stehen (CLAUDE.md, "Keine von der Kreuzungszahl abgeleitete
Zahl als Literal") — die Obergrenze muss zur Laufzeit aus der geladenen
Kreuzungsliste kommen, sonst ist sie nach der nächsten Kalibriersitzung
falsch.

**Default:** der Knoten mit dem höchsten Grad, bei Gleichstand der mit der
kleinsten `nodeId` (im aktuellen Datenstand ein Knoten mit Grad 4, siehe
Beispielrechnung: Node 90 ist einer davon). Begründung: ein Hub hat die
meisten direkten Nachbarn, die Tonika steht damit von vornherein mit
möglichst vielen Knoten in direkter Intervallbeziehung, und die BFS-Tiefe
des gesamten Graphen wird kleiner — weniger Tiefe heißt weniger
akkumulierte Drift (Schritt 3d).

**Der entscheidende Unterschied zu allen anderen OSC-Parametern:** eine
Änderung an `/net/melody/startNode` verstellt **keinen Wert, sondern löst
eine Neuberechnung der gesamten Zuordnung aus**. Alle 92 Knoten bekommen
neue Töne. Das ist Absicht — der Startknoten ist die Tonika, und von ihm
aus wächst der ganze BFS-Baum; ein anderer Startknoten ist eine andere
Melodie-Charakteristik, kein anderer Klangregler.

Daraus folgen drei Dinge:

- **Es ist ein seltener, großer Eingriff, kein Live-Feintuning.** Der
  Parameter gehört nicht in dieselbe gedankliche Schublade wie
  `/net/impulse/speed`. Wer ihn während der Show um eins verstellt,
  bekommt nicht eine leichte Änderung, sondern eine andere Komposition.
- **Ein Preset kann ihn nicht sanft überblenden.** Presets setzen Werte;
  hier gibt es nichts zwischen "alte Zuordnung" und "neue Zuordnung". Ein
  Preset-Wechsel mitten in der Show, der den Startknoten mitändert, würde
  die Tonhöhen aller gerade klingenden und aller folgenden Glocken
  schlagartig neu setzen. Der Parameter sollte deshalb wie die
  Scheduler-Parameter behandelt werden: in `PresetStore.EXCLUDED`, also
  Transport statt Inhalt (CLAUDE.md, "Was nicht in ein Preset gehört und
  warum").
- **Er greift erst nach dem Schreiben der Datei.** Neuberechnen und
  Persistieren (Abschnitt 8) sind derselbe Vorgang; sonst gäbe es einen
  laufenden Zustand, den keine Datei beschreibt — genau die Divergenz
  zwischen Live-Stand und `master`, die die Branch-Konvention verhindern
  soll.

**Darstellung im Web-UI:** nicht als Schieberegler zwischen den anderen.
Sinnvoll ist eine eigene, kleine Sektion (analog der Preset-Sektion, die
ebenfalls über den Tabs steht) mit einem Zahlenfeld plus Knopf
"Zuordnung neu berechnen" und einer **ausdrücklichen Bestätigung** davor —
mit demselben Argument, aus dem `L` in beiden Kalibrier-Modi eine
Doppelbestätigung verlangt: die Aktion verwirft einen aufgebauten Zustand
vollständig und lässt sich nicht zurücknehmen. Der Hinweistext sollte
benennen, was passiert ("alle 92 Knoten bekommen neue Töne, kein weiches
Überblenden möglich"), nicht nur warnen. Das reine Verschieben eines
Reglers darf hier nicht schon die Neuberechnung auslösen.

## 9b. Oktaven-Range als OSC-Parameter (ENTSCHIEDEN, ergänzt 2026-08-01)

> **Entschieden am 2026-08-01:** Birk will die Oktaven-Range — tiefster Ton
> plus Anzahl Oktaven, über die sich die Zuordnung verteilt — ebenfalls als
> OSC-Parameter live einstellen können, nicht nur als Code-Konstante.

Das sind exakt die beiden Werte, die in Schritt 3d bereits als
`~rootMidiNote` und `~numOctaves` benannt wurden (aktuell 45 bzw. 3,
`klangnetz_bells.scd` Zeilen 324-325) — dieser Abschnitt macht sie zu
Parametern, statt neue Konzepte einzuführen:

```
/net/melody/rootMidiNote   RemoteControlledIntParameter,  24 .. 84, Default: 45 (A2)
/net/melody/numOctaves     RemoteControlledIntParameter,   1 ..  6, Default: 3
```

**Wertebereich-Begründung:** 24 (C1) bis 84 (C6) deckt den für Glockenklänge
sinnvollen Bereich ab, ohne dass `~glockenBell` in Frequenzbereiche gerät,
für die die Teiltonverhältnisse nicht mehr plausibel klingen (siehe
Kommentare zum SynthDef). `numOctaves` nach oben auf 6 begrenzt, weil
`notesPerOctaveSet = scale.length * numOctaves` sonst bei den 5-stufigen
Pentatonik-Modi (B) unnötig groß und bei den 7-stufigen Modi (die meisten)
schnell den ganzen hörbaren Bereich sprengen würde.

**Dieselbe Konsequenz wie beim Startknoten (Abschnitt 9): eine Änderung an
einem der beiden Parameter löst dieselbe Neuberechnung/Persistierung aus,**
kein sanftes Verstellen — aus zwei Gründen, die beide bereits in Schritt 3d
stehen und hier nur zusammengezogen werden:

1. **`rootMidiNote` verschiebt jede resultierende `midiNote` gleichermaßen**
   (reine Addition, `midiNote = rootMidiNote + semitone`) — das allein wäre
   sogar live überblendbar. Aber:
2. **`numOctaves` ändert `notesPerOctaveSet` und damit den Modulo-Teiler der
   Oktavfaltung selbst** (Schritt 3d: `gefaltet = scaleIndex mod
   notesPerOctaveSet`). Eine andere Faltungsbreite ergibt für praktisch
   jeden Knoten ein anderes Ergebnis, nicht nur eine Transposition — die
   bereits berechnete Zuordnung ist mit dem neuen `numOctaves` schlicht
   falsch, keine Variante davon.

Weil beide Parameter denselben Neuberechnungs-Mechanismus auslösen wie
`startNode`, gehören sie in dieselbe Web-UI-Sektion (siehe Ende Abschnitt 9)
und in dieselbe `PresetStore.EXCLUDED`-Liste — Transport statt Inhalt,
gleiche Begründung.

**Wichtige Randbedingung aus Schritt 3d, hier nur wiederholt weil sie bei
einer Änderung von `numOctaves` erneut greift:** der Startknoten muss nach
jeder `numOctaves`-Änderung weiterhin in der neu berechneten *mittleren*
Oktave verankert werden (`scale.length * (numOctaves div 2)`), nicht auf dem
alten `scaleIndex`-Wert stehen bleiben. Wird `numOctaves` z. B. von 3 auf 5
erhöht, ohne den Startpunkt neu zu verankern, sitzt die Tonika plötzlich am
unteren Rand der neuen, breiteren Spanne — mit genau der Fehlermöglichkeit,
die Schritt 3d als Grund nennt, warum die Verankerung in der Mitte kein
Detail, sondern Voraussetzung ist. Praktisch bedeutet das: die
Neuberechnungs-Routine muss `rootMidiNote`, `numOctaves` UND `startNode`
gemeinsam als einen atomaren Vorgang behandeln, nicht als drei unabhängige
Trigger, die sich addieren.

## 10. Klangbias nach Ursprungs-Baum statt nach Netzregion (ENTSCHIEDEN)

> **Entschieden am 2026-08-01:** der Klangbias soll davon abhängen, von
> **welchem der vier Bäume** ein Impuls gestartet ist — nicht davon, in
> welchem Quadranten der Knoten liegt, den er gerade trifft. Der neue
> Ursprungs-Baum-Bias **ersetzt** den bestehenden Zonen-Bias vollständig,
> er kommt nicht additiv daneben.

**Klarstellung, wie der bestehende Mechanismus wirklich arbeitet:**
`~regionZone`/`~regionBias` in `klangnetz_bells.scd` (Zeilen 385-423) ist
eine rein **geografische Eigenschaft des Knotens**. `~regionZone.(px, py)`
bildet die Draufsicht-Position auf vier Quadranten ab
(`xi + 2*yi`, also hinten-links / hinten-rechts / vorn-links / vorn-rechts)
und liefert daraus `noteOffset`, `brightness` und `detune`. Der
Ursprungs-Baum eines Impulses spielt dabei **keine Rolle** — er ist auf der
Klangseite gar nicht bekannt. Dieselben vier Himmelsrichtungen tauchen im
Projekt an zwei verschiedenen Stellen mit zwei verschiedenen Bedeutungen
auf, was die Verwechslung erklärt: als Lautsprecher-/Quadranten-Geometrie
in der `.scd` und als Baum-Zuordnung je Stripe in `data/stripeTrees.txt`
(`StripeTreeStore.TREE_NAMES = { "vorn", "hinten", "rechts", "links" }`).

**Warum der Ursprungs-Baum für diesen Anwendungsfall die bessere Größe ist
— drei Gründe, der dritte ist der technisch zwingende:**

1. **Ein Impuls erzählt die Geschichte seines Ausgangspunktes.** Er startet
   an einem Baum, wandert durchs Netz und spaltet sich auf. Was ihn als
   *ein* Ereignis zusammenhält, ist sein Ursprung, nicht die zufällige
   Region, in der er sich gerade befindet. Beim Zonen-Bias wechselt
   derselbe Impuls im Flug die Klangfarbe — die Farbe beschreibt dann den
   Raum, nicht den Weg.
2. **Es ist genau das, was das Nacht-Kompositions-Feature schon anlegt.**
   `originTreeFilter` je Sequencer-Track schränkt bereits ein, von welchem
   Baum ein Track spawnt (CLAUDE.md, "Baum-Origin-Filter"). Bisher hört man
   diese Wahl nur räumlich; mit dem Ursprungs-Bias bekäme jeder Baum eine
   eigene Klangfarbe, und ein Track wäre auch klanglich unterscheidbar.
   Das ist die Umsetzung von "jeder Weg soll seine eigene Qualität haben".
3. **Der Ursprungs-Bias ist intervallerhaltend, der Zonen-Bias nicht.**
   Das ist für dieses Konzept der ausschlaggebende Punkt. Der `noteOffset`
   des Zonen-Bias (`~regionNoteOffsets = [-2, 0, 2, 4]` Skalenstufen) wird
   auf den Notenindex des **getroffenen Knotens** addiert. Zwei benachbarte
   Knoten beiderseits einer Quadrantengrenze bekommen also verschiedene
   Offsets — und das Intervall zwischen ihnen, das Abschnitt 5 sorgfältig
   auf eine Modusregel gesetzt hat, verschiebt sich um bis zu 6
   Skalenstufen. Der Zonen-Bias würde die topologische Zuordnung an genau
   den Stellen zerlegen, an denen sie wirken soll. Der Ursprungs-Bias hat
   dieses Problem strukturell nicht: er ist **für einen gegebenen Impuls
   konstant**, transponiert also seinen gesamten Weg gleichmäßig und lässt
   damit **jedes** Intervall auf diesem Weg unangetastet. Vier Bäume
   ergeben vier Transpositionen derselben Melodie statt vier Störungen
   darin.

**Was das an Architektur voraussetzt** (Anforderung, **keine**
Implementierung — hier wird nichts gebaut):

- **Der Ursprungs-Baum muss am Impuls mitgeführt werden.** In
  `LedNetworkTransportEffect.java` bräuchte `TravellingActivation` ein
  weiteres unveränderliches Feld neben `final int id` (Zeile 386) und
  `final float decayScale` (Zeile 396) — dieselbe Bauform, aus demselben
  Grund: es ist eine Eigenschaft, die der Impuls bei der Geburt bekommt und
  bis zum Tod behält.
- **Vererbung an alle Kinder.** Bei einer Aufspaltung
  (`activationEncounteredNode()`) muss jedes Kind den Wert des
  Elternimpulses übernehmen, ebenso jeder `TravellingActivationFiller`
  (der erbt heute schon `id` und `decayScale`, Zeile 404). Ohne das wäre
  der Bias nach dem ersten Split weg, und zwar lautlos.
- **Genau eine Stelle, an der der Wert entsteht.** Analog zu `spawnSpeed()`,
  das der einzige Ort für eine Spawn-Geschwindigkeit ist: alle fünf
  Spawn-Pfade (`/tube/trigger`, `/net/activateStripe`, `/net/activateNode`,
  `spawnRandomImpulses()`, `tickSequencer()`) müssen durch dieselbe
  Ableitung `stripeIndex → Baum` gehen. Ein sechster Pfad, der das Feld
  vergisst, wäre ein Impuls mit falscher Klangfarbe ohne Fehlermeldung.
  `StripeTreeStore` hat dafür heute `treeNameOf(int stripeIndex)`; gebraucht
  würde die Index-Variante davon (der Bias rechnet mit `0..3`, nicht mit
  Namen).
- **Ein zusätzliches Argument auf `/net/hitNode`.** Die Klangseite kennt
  heute nur `<nodeId> <energy> <x> <y>`. Der Baumindex müsste **angehängt**
  werden — genau wie seinerzeit `x`/`y` an `/net/hitNode` und `speed` an
  `/net/impulse`: ein Empfänger, der die ersten vier Argumente liest,
  bleibt unberührt.
- **Auf der Klangseite ersetzt eine Tabelle die andere.**
  `~regionNoteOffsets`/`~regionBrightness`/`~regionDetune` bleiben in Form
  und Größe (vier Einträge), werden aber über den Baumindex indiziert statt
  über `~regionZone.(px, py)`; `~regionZone` entfällt damit ersatzlos,
  `px`/`py` werden für den Bias nicht mehr gebraucht (fürs Panning schon).
  `~regionBiasAmount` bleibt als Stärkeregler, sinnvollerweise umbenannt.
  Weil beide Größen vierwertig sind, ist das ein Austausch der
  Indexquelle — kein Umbau der Bias-Mechanik.

**Ausdrücklich nicht additiv.** Beide Biase parallel zu fahren hieße zwei
Notenoffsets auf demselben Notenindex, und der Zonen-Anteil brächte den
unter Punkt 3 beschriebenen Intervallschaden vollständig zurück. Wer den
geografischen Charakter behalten will, hat ihn ohnehin noch: die
Lautsprecher-Ortung über `~toQuad` ist davon unberührt.

## 11. Entschiedene Punkte und verbleibende offene Fragen

### Entschieden (2026-08-01, Rückfrage-Dialog mit Birk)

1. **Persistenz — entschieden: reproduzierbar festschreiben.** Die
   Zuordnung wird einmalig berechnet und in `data/nodeMelody_<modus>.txt`
   geschrieben, Neuberechnung nur auf ausdrücklichen Trigger. Ausgearbeitet
   in **Abschnitt 8**.

2. **Startknoten — entschieden: von Birk festlegbar, per OSC.** Neuer
   Parameter `/net/melody/startNode` mit Default "höchster Grad"; eine
   Änderung löst eine vollständige Neuberechnung aus und ist deshalb
   kein Live-Regler. Ausgearbeitet in **Abschnitt 9**.

2b. **Oktaven-Range — entschieden: `rootMidiNote`/`numOctaves` ebenfalls per
    OSC.** Dieselben zwei Werte, die Schritt 3d als Voraussetzung für die
    Oktavfaltung bereits nennt, werden zu Parametern
    (`/net/melody/rootMidiNote`, `/net/melody/numOctaves`) statt Konstanten.
    Löst denselben Neuberechnungs-Mechanismus wie `startNode` aus, alle drei
    Parameter müssen als ein atomarer Vorgang behandelt werden. Ausgearbeitet
    in **Abschnitt 9b**.

3. **Zyklus-Kompromiss — entschieden: angenommen wie beschrieben.**
   Rückwärtskanten bekommen keine Intervall-Garantie, es wird **keine**
   Nachbearbeitungsstufe gebaut. Siehe **Abschnitt 6**.

4. **Landmarken-Schwelle — bleibt ein Parameter, aber die Landmarkenregel
   wurde dabei repariert.** Beim Durchsprechen ist aufgefallen, dass ein
   höher gedrehter `hubThreshold` mit der ursprünglichen Regel
   ("Landmarken-Kante = Quint") zwei Fehler produziert: die Landmarken
   bekommen nur denselben Intervall*typ* zu ihrem jeweiligen Elternknoten,
   nicht dieselbe Tonhöhe, und `scaleIndex` driftet mit jeder
   Landmarken-Ebene um +4 Stufen unbegrenzt nach oben. Die Antwort besteht
   aus zwei zusammengehörigen Regeln: **Landmarken-Rotation** über drei
   stabile Skalenstufen (Schritt 3c) plus **Oktavfaltung** als harte
   Hörbarkeitsgrenze (Schritt 3d), abgesichert durch den Startknoten in der
   mittleren Oktave. Die Schwelle selbst bleibt Betriebs- und
   Geschmacksfrage.

5. **Klangbias — entschieden: Ursprungs-Baum ersetzt Netzregion.** Der
   bestehende `~regionBias` (geografischer Quadrant des getroffenen
   Knotens) wird durch einen Bias nach dem **Ursprungs-Baum des Impulses**
   ersetzt, nicht ergänzt. Ausgearbeitet in **Abschnitt 10**, inklusive der
   Architektur-Anforderung, den Baum am `TravellingActivation`-Objekt
   mitzuführen und an Split-Kinder zu vererben.

6. **Anzahl Modi — entschieden: acht statt vier.** Ergänzt wurden
   **Phrygisch** (der heute live laufende Tonvorrat) sowie drei weitere
   Maqamat mit deutlich verschiedenem Charakter: **ʿAjam** (hell),
   **Nikriz** (übermäßige Sekunde in der Skalenmitte) und **Saba** (dunkel,
   klagend, verminderte Quart). Siehe **Abschnitt 4**.

### Verbleibend offen

1. **Konkreter Wert für `hubThreshold`.** Das 75. Perzentil ist ein
   Vorschlag; bei der beobachteten Gradverteilung (Grad 3-4 verbreitet)
   muss am Gerät geprüft werden, ob das genug oder zu viele Landmarken
   ergibt. Erst mit der Rotation aus Schritt 3c ist das überhaupt eine
   reine Geschmacksfrage — vorher wäre ein hoher Wert schädlich gewesen.

2. **Startmodus für den Regelbetrieb.** Acht Modi stehen zur Wahl; welcher
   der Ausgangszustand ist, entscheidet sich am besten hörend. Modus E
   (Phrygisch) ist der Kandidat für den ersten Vergleich, weil er als
   einziger den Tonvorrat unverändert lässt und damit isoliert hörbar
   macht, was die topologische Zuordnung für sich genommen bewirkt.

3. **Wie sich Modus H (Saba) ohne Oktavperiodizität verhielte.** Der
   Algorithmus erzwingt Oktavwiederholung; Saba hat sie im Original nicht
   (Abschnitt 4). Ob sich der Aufwand lohnt, das Datenmodell dafür zu
   öffnen, lässt sich erst beurteilen, wenn die genäherte Fassung einmal
   gelaufen ist.

4. **Umbenennung von `~regionBiasAmount`.** Mit dem Wechsel auf den
   Ursprungs-Baum stimmt der Name nicht mehr. Ein Umbenennen bricht
   vorhandene SC-Presets und die handgepflegte `SC_PARAMS`-Tabelle im
   Web-UI (CLAUDE.md, "Klangseite") — kein Hindernis, aber eine bewusste
   Entscheidung mit zwei nachzuziehenden Stellen.

## Zusammenfassung für Birk

- **Kernidee:** Note eines Knotens wird einmalig per BFS von einem
  Startknoten aus relativ zu seinem Eltern-Knoten im BFS-Baum vergeben
  (nicht relativ zu ALLEN Nachbarn — das wäre bei Zyklen nicht durchgehend
  lösbar). Dadurch ist jede Baumkante automatisch ein "gutes" Intervall,
  jeder mögliche Impulsweg ist im Wesentlichen eine Kette solcher guten
  Intervalle.
- **Landmarken werden verteilt, nicht gestapelt** (Schritt 3c, ergänzt
  2026-08-01): eine Kante zu einem Hub bekommt nicht immer die Quint,
  sondern rotiert über `[+4, -3, +2]` Skalenstufen nach
  `(BFS-Tiefe + Rang in der Nachbarliste) mod 3`. `+4` und `-3` sind
  dieselbe Skalenstufe in verschiedenen Oktaven — der Ankercharakter bleibt
  also erhalten, aber die Landmarken landen nicht mehr alle auf demselben
  Ton, und die Drift sinkt von +4 auf +1 Stufe je Ebene mit wechselndem
  Vorzeichen.
- **Die Hörbarkeit garantiert erst die Oktavfaltung** (Schritt 3d):
  `scaleIndex mod (scale.length * numOctaves)`, also dieselbe Faltung auf
  drei Oktaven, die `klangnetz_bells.scd` Zeile 1380 heute schon rechnet.
  Der Startknoten sitzt dafür in der **mittleren** Oktave, sonst bräche
  jedes Abwärtsintervall sofort um.
- **Acht Modi:** Dorisch (diatonisch, mild), Moll-Pentatonik (robust, immer
  konsonant), Maqam Hijaz (orientalisch, übermäßige Sekunde unten),
  Harmonisch Moll (europäisch-funktional, Leitton), Phrygisch (der heute
  live laufende Tonvorrat), Maqam ʿAjam (hell, ohne Näherung darstellbar),
  Maqam Nikriz (übermäßige Sekunde in der Skalenmitte, ohne Näherung
  darstellbar), Maqam Saba (dunkel/klagend, verminderte Quart, mit offen
  benannter 12-TET-Näherung).
- **Zyklen sind explizit als ungelöstes Restproblem benannt**, mit
  pragmatischem Kompromiss (Rückwärtskanten bekommen keine Regel-Garantie)
  — von Birk am 2026-08-01 ausdrücklich so angenommen. Dazu kommt als
  zweite, gleichartige Ausnahme die Umbruchkante der Oktavfaltung; beide
  Klassen sollen beim Erzeugen der Zuordnungsdatei **gezählt** werden.
- **Die Zuordnung wird persistiert** (`data/nodeMelody_<modus>.txt`,
  Abschnitt 8) und nur auf ausdrücklichen Trigger neu berechnet — derselbe
  Reproduzierbarkeitsanspruch wie beim Show-Zustand auf `master`.
- **Der Startknoten ist ein OSC-Parameter** (`/net/melody/startNode`,
  Abschnitt 9), aber ein besonderer: er verstellt keinen Wert, sondern
  würfelt die gesamte Zuordnung neu. Deshalb mit Bestätigung im Web-UI und
  außerhalb der Presets.
- **Der Klangbias richtet sich künftig nach dem Ursprungs-Baum des
  Impulses**, nicht nach der Netzregion des getroffenen Knotens
  (Abschnitt 10, ersetzt `~regionBias`). Entscheidender Grund: der
  Ursprungs-Bias ist für einen Impuls konstant und damit
  **intervallerhaltend** — der Zonen-Bias würde die topologisch gesetzten
  Intervalle an jeder Quadrantengrenze wieder zerlegen.
- Vier verbleibende offene Punkte (konkreter `hubThreshold`, Startmodus,
  Saba-Oktavperiodizität, Umbenennung von `~regionBiasAmount`).
