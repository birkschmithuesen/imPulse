import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

// Die eigentliche Zuordnung Knoten -> Skalenstufe: eine EINMALIGE
// Breitensuche vom Startknoten aus, bei der jeder neu erreichte Knoten seine
// Stufe RELATIV zu dem Nachbarn bekommt, ueber den er zuerst erreicht wurde.
//
// Nicht relativ zu allen seinen Nachbarn gleichzeitig - das waere auf einem
// Graphen mit Zyklen im Allgemeinen unerfuellbar (Konzept, Abschnitt 6:
// sobald zwei Intervalle eines Dreiecks feststehen, ist das dritte die
// Differenz und faellt aus jeder Regel heraus). Der Kompromiss ist bewusst
// angenommen: Rueckwaertskanten bekommen KEINE Intervall-Garantie, und es
// gibt KEINE Nachbearbeitungsstufe, die sie nachtraeglich glaettet. Gezaehlt
// werden sie trotzdem - das ist der Unterschied zwischen einem bekannten
// Kompromiss und einem stillen Fehler.
//
// Bewusst ohne Processing-, oscP5- und netP5-Abhaengigkeit, damit die Logik
// ueber test/run.sh pruefbar bleibt. Der Zufall wird als RandomSource
// hereingereicht (dasselbe Muster wie OriginSequencer), sonst waere die
// Zuweisung nicht reproduzierbar pruefbar.
class MelodyAssigner {

  // Die drei STABILEN Skalenstufen, ueber die eine Landmarken-Kante rotiert
  // (Konzept, Schritt 3c). In Skalenstufen, 0-basiert:
  //
  //   +4 = Quint aufwaerts   (Stufe 5 ueber dem Elternknoten)
  //   -3 = Quart abwaerts    (dieselbe Stufe wie +4, eine Oktave tiefer:
  //                           4 - 7 = -3 bei siebentoeniger Skala)
  //   +2 = Terz aufwaerts    (die mildeste der drei)
  //
  // Warum nicht immer die Quint: sie erzeugt kein gemeinsames Tonhoehen-
  // Niveau, sondern nur ein gemeinsames Intervall zu jeweils verschiedenen
  // Elternknoten - und sie treibt die Tonhoehe mit jeder Landmarken-Ebene um
  // +4 Stufen unbegrenzt nach oben. Zwei Drittel der Rotation (+4 und -3)
  // liegen auf DERSELBEN Skalenstufe in verschiedenen Oktaven, der
  // Ankercharakter bleibt also erhalten; der Mittelwert sinkt von +4 auf +1
  // und wechselt das Vorzeichen.
  static final int[] STABLE_INTERVALS = { 4, -3, 2 };

  // Rotiert wird ueber (Tiefe + Rang), nicht ueber einen Zufallswert: die
  // Zuordnung muss reproduzierbar sein (Konzept, Abschnitt 8), und beide
  // Groessen stehen ohnehin zur Verfuegung.
  //
  // Beide Summanden werden gebraucht, jeder allein greift zu kurz:
  //   nur tiefe -> alle Landmarken EINER Ebene bekaemen dasselbe Intervall,
  //                der Ton-Stapel waere verschoben statt aufgeloest
  //   nur rang  -> jeder erste Nachbar jedes Knotens bekaeme immer +4, die
  //                Drift entlang eines langen Weges waere ungebremst
  static int landmarkInterval(int depth, int rank) {
    int i = (depth + rank) % STABLE_INTERVALS.length;
    if (i < 0) {
      i += STABLE_INTERVALS.length;
    }
    return STABLE_INTERVALS[i];
  }

  // Die Oktavfaltung (Konzept, Schritt 3d) - die HARTE Hoerbarkeitsgrenze.
  //
  // Die Rotation daempft die Drift, sie beschraenkt sie nicht: auch die
  // vorzeichenbehaftete Ziehung auf den normalen Kanten hat zwar Mittelwert
  // 0, jede einzelne Realisierung ist aber eine Irrfahrt und entfernt sich
  // mit der Weglaenge beliebig weit. "Im Mittel bleibt es in der Mitte" ist
  // keine Garantie fuer 92 konkrete Knoten.
  //
  // Der doppelte Modulo ist die uebliche Behandlung negativer Werte: durch
  // die -3 der Rotation kann der Rohwert negativ werden, und Javas % liefert
  // dann ein negatives Ergebnis.
  static int fold(int rawScaleIndex, int notesPerOctaveSet) {
    int n = (notesPerOctaveSet < 1) ? 1 : notesPerOctaveSet;
    return ((rawScaleIndex % n) + n) % n;
  }

  static MelodyAssignment assign(MelodyGraph graph, MelodyMode mode, int startNode,
      int rootMidiNote, int numOctaves, int hubThreshold, RandomSource random) {
    int n = graph.nodeCount();
    int octaves = (numOctaves < 1) ? 1 : numOctaves;
    int notesPerOctaveSet = mode.notesPerOctaveSet(octaves);

    MelodyAssignment result = new MelodyAssignment(n, mode, rootMidiNote, octaves,
        notesPerOctaveSet, startNode, hubThreshold);
    if (n == 0) {
      return result;
    }
    int start = startNode;
    if (start < 0 || start >= n) {
      start = graph.defaultStartNode();
      result.startNodeAdjusted = true;
    }
    if (start < 0) {
      return result;
    }
    result.startNode = start;

    int[] raw = new int[n];
    int[] parent = new int[n];
    Arrays.fill(parent, -1);

    // Der Startknoten sitzt in der MITTLEREN Oktave, nicht bei 0. Laege die
    // Tonika am unteren Rand des gefalteten Bereichs, wuerde jedes
    // Abwaertsintervall sofort umbrechen - die -3 der Rotation traefe schon
    // beim ersten Schritt auf die Umbruchkante und landete am oberen Ende.
    // Ohne diese Zeile waere die Rotation wirkungslos bis schaedlich.
    raw[start] = mode.length() * (octaves / 2);
    result.depth[start] = 0;
    result.reached[start] = true;

    Deque<Integer> queue = new ArrayDeque<Integer>();
    queue.addLast(Integer.valueOf(start));

    while (!queue.isEmpty()) {
      int current = queue.removeFirst().intValue();
      int[] ordered = prioritizedNeighbors(graph, current, hubThreshold);
      for (int rank = 0; rank < ordered.length; rank++) {
        int nb = ordered[rank];
        if (result.reached[nb]) {
          // Rueckwaertskante. Wird NICHT erneut bewertet - das ist die
          // pragmatische Zyklus-Antwort. Gezaehlt wird sie unten, einmal je
          // ungerichteter Kante.
          continue;
        }
        int depth = result.depth[current] + 1;
        int interval;
        if (graph.degree(nb) >= hubThreshold) {
          interval = landmarkInterval(depth, rank);
        } else {
          interval = MelodyModes.drawInterval(mode, random.next(), random.next());
        }
        raw[nb] = raw[current] + interval;
        result.depth[nb] = depth;
        result.reached[nb] = true;
        result.interval[nb] = interval;
        result.landmark[nb] = graph.degree(nb) >= hubThreshold;
        parent[nb] = current;
        queue.addLast(Integer.valueOf(nb));
      }
    }

    // Nicht erreichte Knoten liegen in einer anderen Zusammenhangskomponente
    // (etwa waehrend einer laufenden Kalibrierung, wenn eine Kreuzung noch
    // fehlt). Das Konzept sagt dazu nichts.
    //
    // Sie bekommen den Wert des Startknotens, also die Tonika, und werden
    // GEZAEHLT. Ein unbestimmter Wert waere ein stiller Ausfall, ein Abbruch
    // haenge die Show an einer Kalibrierluecke auf, und die Tonika ist die
    // neutralste Wahl - sie klingt nach etwas, nur nach nichts Besonderem.
    for (int i = 0; i < n; i++) {
      if (!result.reached[i]) {
        raw[i] = raw[start];
        result.depth[i] = -1;
        result.unreachedCount++;
      }
    }

    // Faltung und MIDI-Note
    for (int i = 0; i < n; i++) {
      result.scaleIndex[i] = fold(raw[i], notesPerOctaveSet);
      result.midiNote[i] = rootMidiNote + mode.semitoneOf(result.scaleIndex[i]);
    }

    // Die zwei Kantenklassen ohne Regel-Garantie zaehlen (Konzept, Schritt 3d,
    // "messbar statt ueberraschend").
    for (int u = 0; u < n; u++) {
      int[] nb = graph.neighbors(u);
      for (int k = 0; k < nb.length; k++) {
        int v = nb[k];
        if (v <= u) {
          continue; // jede ungerichtete Kante genau einmal betrachten
        }
        boolean treeEdge = (parent[v] == u) || (parent[u] == v);
        if (!treeEdge) {
          result.backEdges++;
        }
      }
    }
    for (int v = 0; v < n; v++) {
      int p = parent[v];
      if (p < 0) {
        continue;
      }
      // Eine Umbruchkante ist eine BAUMkante, bei der die Faltung das
      // gewaehlte Intervall zerrissen hat: aus einer Quint aufwaerts wird
      // dann ein Sprung fast drei Oktaven abwaerts. Strukturell derselbe Fall
      // wie eine Rueckwaertskante - eine kleine, benannte Minderheit ohne
      // Garantie.
      if (result.scaleIndex[v] - result.scaleIndex[p] != result.interval[v]) {
        result.wrapEdges++;
      }
    }
    return result;
  }

  // Nachbarn-Prioritaet (Konzept, Schritt 3a): erst Landmarken, innerhalb der
  // Gruppe nach kleinstem LED-Index. Deterministisch bei festem Startknoten
  // und festem Graph - der Rang in DIESER Liste geht in die
  // Landmarken-Rotation ein, die Sortierung ist also nicht Kosmetik.
  //
  // Sortiert wird die ganze Nachbarliste, auch die schon besuchten Knoten:
  // haenge der Rang davon ab, welche Nachbarn zufaellig schon dran waren,
  // waere er von der BFS-Reihenfolge abhaengig statt von der Geometrie.
  static int[] prioritizedNeighbors(final MelodyGraph graph, int node,
      final int hubThreshold) {
    int[] src = graph.neighbors(node);
    Integer[] boxed = new Integer[src.length];
    for (int i = 0; i < src.length; i++) {
      boxed[i] = Integer.valueOf(src[i]);
    }
    Arrays.sort(boxed, new java.util.Comparator<Integer>() {
      public int compare(Integer a, Integer b) {
        boolean la = graph.degree(a.intValue()) >= hubThreshold;
        boolean lb = graph.degree(b.intValue()) >= hubThreshold;
        if (la != lb) {
          return la ? -1 : 1;
        }
        int ma = graph.minLedIndex(a.intValue());
        int mb = graph.minLedIndex(b.intValue());
        if (ma != mb) {
          return (ma < mb) ? -1 : 1;
        }
        return a.intValue() - b.intValue();
      }
    });
    int[] out = new int[boxed.length];
    for (int i = 0; i < boxed.length; i++) {
      out[i] = boxed[i].intValue();
    }
    return out;
  }
}

// Das Ergebnis eines Zuordnungslaufs. scaleIndex ist bereits GEFALTET
// (0..notesPerOctaveSet-1) - das ist der Wert, der in die Datei geht und den
// eine Handkorrektur aendert.
class MelodyAssignment {

  final int nodeCount;
  final MelodyMode mode;
  final int rootMidiNote;
  final int numOctaves;
  final int notesPerOctaveSet;
  final int hubThreshold;

  int startNode;
  // true, wenn der uebergebene Startknoten ausserhalb lag und durch den
  // Vorschlag ersetzt wurde. Gemeldet statt still korrigiert.
  boolean startNodeAdjusted = false;

  final int[] scaleIndex;
  final int[] midiNote;
  // BFS-Tiefe, reine Diagnose. -1 = nicht erreicht.
  final int[] depth;
  final boolean[] reached;
  // Das gewaehlte Intervall der Baumkante zum Elternknoten, in Skalenstufen.
  // Diagnose und Grundlage der Umbruchkanten-Zaehlung.
  final int[] interval;
  final boolean[] landmark;

  int backEdges = 0;
  int wrapEdges = 0;
  int unreachedCount = 0;

  MelodyAssignment(int nodeCount_, MelodyMode mode_, int rootMidiNote_,
      int numOctaves_, int notesPerOctaveSet_, int startNode_, int hubThreshold_) {
    nodeCount = nodeCount_;
    mode = mode_;
    rootMidiNote = rootMidiNote_;
    numOctaves = numOctaves_;
    notesPerOctaveSet = notesPerOctaveSet_;
    startNode = startNode_;
    hubThreshold = hubThreshold_;
    scaleIndex = new int[nodeCount_];
    midiNote = new int[nodeCount_];
    depth = new int[nodeCount_];
    reached = new boolean[nodeCount_];
    interval = new int[nodeCount_];
    landmark = new boolean[nodeCount_];
  }

  int landmarkCount() {
    int c = 0;
    for (int i = 0; i < landmark.length; i++) {
      if (landmark[i]) {
        c++;
      }
    }
    return c;
  }

  int maxDepth() {
    int d = 0;
    for (int i = 0; i < depth.length; i++) {
      if (depth[i] > d) {
        d = depth[i];
      }
    }
    return d;
  }

  // Einzeiler fuer die Konsole. Nennt beide Kantenklassen ohne Garantie -
  // sie zu zaehlen ist der Unterschied zwischen einem bekannten Kompromiss
  // und einem stillen Fehler.
  String report() {
    StringBuilder sb = new StringBuilder();
    sb.append("Melodie ").append(mode.name).append(": ").append(nodeCount)
      .append(" Knoten ab Startknoten ").append(startNode)
      .append(", Grundton MIDI ").append(rootMidiNote)
      .append(", ").append(numOctaves).append(" Oktaven (")
      .append(notesPerOctaveSet).append(" Stufen), Hub-Schwelle ")
      .append(hubThreshold).append(", ").append(landmarkCount())
      .append(" Landmarken, Tiefe bis ").append(maxDepth())
      .append(" | ").append(backEdges).append(" Rueckwaertskanten, ")
      .append(wrapEdges).append(" Umbruchkanten (beide ohne Intervall-Garantie)");
    if (unreachedCount > 0) {
      sb.append(" | WARNUNG: ").append(unreachedCount)
        .append(" Knoten vom Startknoten aus nicht erreichbar, sie bekommen die Tonika");
    }
    if (startNodeAdjusted) {
      sb.append(" | Startknoten lag ausserhalb, ersetzt durch den Vorschlag");
    }
    return sb.toString();
  }
}
