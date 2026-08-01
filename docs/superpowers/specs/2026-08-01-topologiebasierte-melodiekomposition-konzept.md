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
Abschnitt 5, das BFS-Beispiel enthält einen echten Dreieck-Zyklus:
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
  Algorithmus, Abschnitt 4, Schritt 1).
- Ein Node kann an MEHREREN Stripes beteiligt sein (wenn mehr als 2
  LED-Indizes an einer Kreuzung zusammentreffen) — aktuell sind alle 92
  Zeilen genau 2-elementig, das Konzept sollte aber allgemein für Nodes mit
  Grad > 2 funktionieren (LedNetworkNode.ledIndices ist ein TreeSet, nicht
  auf 2 Elemente begrenzt).

## 4. Vier wählbare Modi

Alle vier Modi teilen sich denselben Grundalgorithmus (Abschnitt 5) und
unterscheiden sich nur in: (a) der verwendeten Skala, (b) den bevorzugten
Kanten-Intervallen zwischen direkt benachbarten Nodes, (c) der Gewichtung
Schritt vs. Sprung. Der Operator wählt EINEN Modus für einen kompletten
Zuordnungslauf (kein Mischbetrieb pro Node).

### Modus A — "Dorisch" (diatonischer Grundmodus)

- **Skala:** Dorisch, `[0, 2, 3, 5, 7, 9, 10]` Halbtöne (siehe
  `hermes-knowledge/musiktheorie/skalen.md`). Bewusst Dorisch statt Ionisch/
  Äolisch gewählt: mildeste, unverfänglichste modale Färbung, klingt weder
  "Dur-fröhlich" noch "Moll-schwer", schwebender Grundcharakter, passt zur
  bisherigen Sound-Ästhetik der Installation (Note in `klangnetz_bells.scd`
  Zeile 79: aktuell schon Phrygisch ab A2 im Einsatz — Dorisch ist die
  näher an Dur liegende Alternative, wenn ein weniger "dunkler" Grundmodus
  gewünscht ist; falls Phrygisch beibehalten werden soll, ist es 1:1 als
  fünfter Modus mit denselben Regeln nachrüstbar).
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
Operator wählt Modus ∈ {DORISCH, PENTATONIK, MAQAM_HIJAZ, HARMONISCH_MOLL}
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
         startNode (Operator-Wahl, oder Default: Node mit höchstem Grad
         = "Hub", siehe Beispielrechnung: Node 90 mit Grad 4)

1. degreeMap = für jeden Node: Anzahl seiner Nachbarn (aus Schritt 1)
2. hubThreshold = z.B. 75. Perzentil aller Grade
   (Nodes mit degree >= hubThreshold gelten als "Landmarken")

3. scaleIndex[startNode] = 0   // Grundton/Tonika-Position
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
         gewähltesIntervall = ziehe_gewichtetes_intervall(
             Gewichtungstabelle des Modus,
             istZielLandmarke = (degreeMap[n] >= hubThreshold))
         scaleIndex[n] = scaleIndex[current] + gewähltesIntervall
             // gewähltesIntervall in SKALENSTUFEN, nicht Halbtönen –
             // dadurch bleibt jede Note automatisch auf der Skala
         besucht.add(n)
         queue.push_back(n)

7. Für jeden Node: finale MIDI-Note ableiten
     octave  = scaleIndex[node] div scale.length
     degree  = scaleIndex[node] mod scale.length
     semitone = scale[degree] + 12 * octave
     midiNote = rootMidiNote + semitone
     (identische Umrechnungslogik wie die bestehende
      noteIndex -> octave/degree/semitoneOffset -> midiNote Kette in
      klangnetz_bells.scd Zeilen 1385-1388 — NUR scaleIndex[node] ersetzt
      die bisherige (nodeId + bias) % notesPerOctaveSet Zuweisung)
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

## 6. Das Zyklus-Problem — explizit benannt

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

Start: Node 90 = Tonika, `scaleIndex[90] = 0` → MIDI 45 (A2, wie aktuell
`~rootMidiNote`).

BFS von 90 aus, Nachbarn-Priorität: Landmarken (Grad ≥ 4) zuerst.
Alle vier Nachbarn von 90 (64, 66, 70, 71) haben Grad ≥ 3; 64, 70, 71
haben Grad 4 (Landmarken), 66 hat Grad 3.

```
Node 90  scaleIndex=0   Stufe 1 (Grundton, A2)             MIDI 45
Node 64  scaleIndex=4   Stufe 5 (Quint, über 90 als        MIDI 45+7=52
                         Landmarken-Kante: +4 Stufen        (E3, reine Quint
                         = Quint in Dorisch)                 zu 90 ✓ markant,
                                                              konsonant, gut
                                                              als Eröffnung)
Node 70  scaleIndex=4   Stufe 5 (Quint, ebenfalls           MIDI 52 (E3)
                         Landmarken-Kante von 90)
Node 71  scaleIndex=4   Stufe 5 (Quint, Landmarken-         MIDI 52 (E3)
                         Kante von 90; ACHTUNG: 71 wird
                         später über die 70-71-Kante NICHT
                         erneut bewertet – siehe Zyklus-
                         Kompromiss Abschnitt 6)
Node 66  scaleIndex=1   Stufe 2 (Sekundschritt von 90,      MIDI 45+2=47
                         normale Kante, kein Hub)             (H2, Ganzton
                                                               über Tonika)
Node 65  scaleIndex=2   Stufe 3 (Sekundschritt von 66,       MIDI 45+3=48
                         Terz-Charakter zur Tonika insgesamt)  (C3)
Node 19  scaleIndex=6   Stufe 5+2=Oktave-1 (Sekundschritt    MIDI 45+10=55
                         von 64, das selbst schon Stufe 5      (G3)
                         war)
Node 63  scaleIndex=5   Stufe 6 (Sekundschritt von 64)       MIDI 45+9=54
                                                              (Fis3)
```

**Musikalische Lesbarkeit:** ein Impuls, der z.B. den Weg 90 → 64 → 19
nimmt, hört: A2 (Tonika) → E3 (Quint, markanter Sprung, klar als
"Ankunft an einem Hub" erkennbar) → G3 (Sekundschritt weiter). Ein anderer
Impuls auf dem Weg 90 → 66 → 65 hört: A2 → H2 (sanfter Ganztonschritt) →
C3 (weiterer Sekundschritt) — eine ruhige, stufenweise Linie. Beide Wege
sind für sich musikalisch stimmig, obwohl sie an völlig unterschiedlichen
Punkten im Graph starten und keiner der beiden Wege vorab "durchgespielt"
werden musste — genau der Effekt, den Abschnitt 2 als Ziel beschreibt.

Die einzige "unkontrollierte" Kante in diesem Ausschnitt ist 70-71 (beide
über 90 mit Stufe 5 belegt, Intervall zwischen ihnen also 0 = Unisono,
zufällig aus dem Kompromiss entstanden statt aus einer Modusregel) — in
diesem konkreten Fall zufällig sogar unproblematisch (Unisono ist neutral,
kein Sprung), aber genau das ist der in Abschnitt 6 beschriebene
Kompromiss: bei anderen Zyklen kann die resultierende Rückwärtskante auch
zufällig größer/dissonanter ausfallen.

## 8. Offene Fragen für Birk

1. **Neuberechnung bei jedem Sketch-Start vs. stabile Zuordnung:**
   Soll die BFS-Traversierung bei jedem Start neu laufen (ggf. mit
   zufälligem Startknoten → jeder Abend klingt tonal anders, aber nicht
   reproduzierbar) oder soll die Zuordnung EINMAL berechnet und als
   Datei (analog `nodeCrossings.txt`, z.B. `data/nodeMelody_<modus>.txt`)
   persistiert werden, damit sie über Neustarts hinweg stabil bleibt
   (reproduzierbarer, wiedererkennbarer Klangcharakter der Installation,
   analog zum Prinzip "master = reproduziert exakt den Show-Zustand" aus
   CLAUDE.md)? Empfehlung des Konzepts: **persistieren**, aus demselben
   Grund wie beim Show-Zustand — aber das ist Birks Entscheidung.

2. **Startknoten-Wahl:** automatisch (höchster Grad = Hub) oder von Birk
   manuell festgelegt (z.B. der Node, der geografisch/dramaturgisch als
   "Zentrum" der Installation gilt)? Der Startknoten bestimmt de facto den
   Grundton/die Tonika-Position der gesamten Zuordnung.

3. **Umgang mit dem Zyklus-Kompromiss (Abschnitt 6):** reicht der
   beschriebene "erste Kante gewinnt, Rückwärtskanten sind Restgröße"-
   Ansatz, oder soll es eine Nachbearbeitung geben, die Rückwärtskanten mit
   besonders dissonantem Resultat (z.B. Tritonus, große Septime) gezielt
   nachträglich glättet (z.B. durch Oktavversetzung eines der beiden
   beteiligten Nodes)? Das wäre ein zusätzlicher Optimierungsschritt nach
   der BFS, im Konzept nicht ausgearbeitet, aber als Erweiterung denkbar.

4. **Landmarken-Schwelle (`hubThreshold`):** aktuell als "75. Perzentil
   aller Knotengrade" vorgeschlagen — bei 92 Nodes und der beobachteten
   Gradverteilung (Grad 3-4 verbreitet, siehe Beispielrechnung) sollte
   geprüft werden, ob das genug/zu viele Landmarken ergibt. Feinjustierung
   ist Betriebs-/Geschmacksfrage, keine musiktheoretische.

5. **Interaktion mit bestehendem Klangbias (`~regionBias`):** die
   aktuelle Logik addiert bereits einen Bias auf `noteIndex` je nach
   Netzregion (px/py). Falls dieses Konzept später implementiert wird,
   muss geklärt werden, ob der Regionsbias weiterhin ADDITIV auf die neue,
   topologisch fundierte `scaleIndex[node]`-Basis wirken soll (würde die
   sorgfältig gesetzten Intervalle zwischen Nachbarn leicht verschieben)
   oder ob er für diesen neuen Modus deaktiviert/anders gedacht werden
   müsste. Nicht Teil dieses Konzepts, nur als Schnittstellenfrage
   vermerkt.

6. **Vier Modi oder mehr:** das Konzept schlägt vier Startmodi vor
   (Dorisch, Pentatonik, Maqam Hijaz, Harmonisch Moll). Phrygisch (aktuell
   in der Live-SC-Datei aktiv) ließe sich als fünfter Modus 1:1 nach
   demselben Schema ergänzen, falls der bisherige Klangcharakter erhalten
   bleiben soll — hier bewusst nicht mit aufgenommen, um die vier
   geforderten musiktheoretisch klar unterschiedlichen Ideen (diatonisch /
   pentatonisch / maqam-artig / harmonisch) nicht zu verwässern.

## Zusammenfassung für Birk

- **Kernidee:** Note eines Knotens wird einmalig per BFS von einem
  Startknoten aus relativ zu seinem Eltern-Knoten im BFS-Baum vergeben
  (nicht relativ zu ALLEN Nachbarn — das wäre bei Zyklen nicht durchgehend
  lösbar). Dadurch ist jede Baumkante automatisch ein "gutes" Intervall,
  jeder mögliche Impulsweg ist im Wesentlichen eine Kette solcher guten
  Intervalle.
- **Vier Modi:** Dorisch (diatonisch, mild), Moll-Pentatonik (robust,
  immer konsonant), Maqam Hijaz (12-TET-Näherung, orientalische Färbung),
  Harmonisch Moll (europäisch-funktional, mit Leitton-Charakter).
- **Zyklen sind explizit als ungelöstes Restproblem benannt**, mit
  pragmatischem Kompromiss (Rückwärtskanten bekommen keine Regel-Garantie).
- Fünf offene Entscheidungsfragen für Birk (Persistenz, Startknoten,
  Zyklus-Nachbearbeitung, Landmarken-Schwelle, Bias-Interaktion).
