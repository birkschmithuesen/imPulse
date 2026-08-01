import java.util.HashSet;
import java.util.Set;

public class MelodyModesTest {

  public static void main(String[] args) throws Exception {
    // ---- Die acht Modi ----
    Check.eq("acht Modi", 8, MelodyModes.count());
    Check.eq("acht Eintraege in ALL", 8, MelodyModes.ALL.length);

    Set<String> keys = new HashSet<String>();
    Set<String> names = new HashSet<String>();
    for (int i = 0; i < MelodyModes.ALL.length; i++) {
      MelodyMode m = MelodyModes.ALL[i];
      Check.that("Schluessel eindeutig: " + m.key, keys.add(m.key));
      Check.that("Name eindeutig: " + m.name, names.add(m.name));

      // Der Schluessel wird zum Dateinamen (data/nodeMelody_<key>.txt) und
      // steht im Kopf der Datei - er muss demselben Zeichenvorrat folgen wie
      // ein Preset-Name, sonst laesst sich die Datei nicht per OSC benennen.
      Check.that("Schluessel nicht leer: " + m.key, m.key.length() > 0);
      for (int c = 0; c < m.key.length(); c++) {
        char ch = m.key.charAt(c);
        Check.that("Schluessel nur a-z/0-9/_/-: " + m.key,
            (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')
            || ch == '_' || ch == '-');
      }

      // Skala: aufsteigend, ab 0, alles unter der Oktave. Waere ein Wert
      // >= 12 dabei, ueberlappten sich zwei Oktaven der Faltung.
      Check.that("Skala nicht leer: " + m.key, m.scale.length >= 2);
      Check.eq("Skala beginnt auf dem Grundton: " + m.key, 0, m.scale[0]);
      for (int s = 1; s < m.scale.length; s++) {
        Check.that("Skala aufsteigend: " + m.key, m.scale[s] > m.scale[s - 1]);
      }
      Check.that("Skala unter der Oktave: " + m.key,
          m.scale[m.scale.length - 1] < 12);

      // Gewichte: drei Klassen, Summe positiv.
      Check.eq("drei Gewichtsklassen: " + m.key,
          MelodyModes.CLASS_INTERVALS.length, m.classWeights.length);
      float sum = 0f;
      for (int w = 0; w < m.classWeights.length; w++) {
        Check.that("Gewicht nicht negativ: " + m.key, m.classWeights[w] >= 0f);
        sum += m.classWeights[w];
      }
      Check.that("Gewichtssumme positiv: " + m.key, sum > 0f);

      // Der Sekundschritt ist in JEDEM der acht Modi das hoechstgewichtete
      // Intervall - "Stufen als Rueckgrat, Spruenge als Wuerze". Faellt das
      // in einem Modus, ist entweder die Tabelle vertippt oder die
      // Grundregel des Verfahrens aufgegeben.
      Check.that("Sekundschritt hat das hoechste Gewicht: " + m.key,
          m.classWeights[0] > m.classWeights[1]
          && m.classWeights[0] > m.classWeights[2]);
    }

    // ---- Konkrete Werte aus Abschnitt 4 des Konzepts ----
    Check.eq("Modus 4 ist Phrygisch", "phrygisch", MelodyModes.at(4).key);
    // EXAKT der Wert, der in klangnetz_bells.scd als ~scaleSteps steht -
    // Modus E ist der einzige, der den Tonvorrat unveraendert laesst.
    int[] phrygisch = { 0, 1, 3, 5, 7, 8, 10 };
    for (int i = 0; i < phrygisch.length; i++) {
      Check.eq("Phrygisch Stufe " + i, phrygisch[i], MelodyModes.at(4).scale[i]);
    }
    Check.eq("Pentatonik hat fuenf Stufen", 5, MelodyModes.at(1).length());
    // Die drei Modi mit uebermaessiger Sekunde, jeweils an anderer Stelle
    Check.eq("Hijaz: uebermaessige Sekunde zwischen Stufe 2 und 3",
        3, MelodyModes.at(2).scale[2] - MelodyModes.at(2).scale[1]);
    Check.eq("Nikriz: uebermaessige Sekunde zwischen Stufe 3 und 4",
        3, MelodyModes.at(6).scale[3] - MelodyModes.at(6).scale[2]);
    Check.eq("Harmonisch Moll: uebermaessige Sekunde zwischen Stufe 6 und 7",
        3, MelodyModes.at(3).scale[6] - MelodyModes.at(3).scale[5]);
    // Saba: verminderte Quart (4 Halbtoene) statt reiner Quart
    Check.eq("Saba: verminderte Quart", 4, MelodyModes.at(7).scale[3]);

    // ---- Zugriff ----
    Check.eq("at klemmt nach unten", "dorisch", MelodyModes.at(-5).key);
    Check.eq("at klemmt nach oben", "saba", MelodyModes.at(99).key);
    Check.eq("indexOfKey findet", 6, MelodyModes.indexOfKey("nikriz"));
    Check.eq("indexOfKey trimmt", 0, MelodyModes.indexOfKey("  dorisch "));
    Check.eq("indexOfKey unbekannt", -1, MelodyModes.indexOfKey("ionisch"));
    Check.eq("indexOfKey null", -1, MelodyModes.indexOfKey(null));
    Check.that("byKey unbekannt gibt null", MelodyModes.byKey("gibtsnicht") == null);
    Check.that("byKey findet", MelodyModes.byKey("saba") != null);

    // ---- notesPerOctaveSet ----
    Check.eq("Phrygisch ueber drei Oktaven", 21, MelodyModes.at(4).notesPerOctaveSet(3));
    Check.eq("Pentatonik ueber drei Oktaven", 15, MelodyModes.at(1).notesPerOctaveSet(3));
    // Entartete Oktavzahl darf nicht 0 ergeben - ein Modulo durch 0 waere ein
    // Absturz mitten in der Zuweisung.
    Check.eq("numOctaves 0 zaehlt wie 1", 7, MelodyModes.at(4).notesPerOctaveSet(0));
    Check.eq("numOctaves negativ zaehlt wie 1", 7, MelodyModes.at(4).notesPerOctaveSet(-3));

    // ---- semitoneOf ----
    MelodyMode phr = MelodyModes.at(4);
    Check.eq("Stufe 0 ist der Grundton", 0, phr.semitoneOf(0));
    Check.eq("Stufe 1 ist die kleine Sekunde", 1, phr.semitoneOf(1));
    Check.eq("Stufe 7 ist die Oktave", 12, phr.semitoneOf(7));
    Check.eq("Stufe 8 ist Oktave plus kleine Sekunde", 13, phr.semitoneOf(8));
    Check.eq("Stufe 20 ist der hoechste Ton der dritten Oktave",
        10 + 24, phr.semitoneOf(20));

    // ---- pickClass: gewichtete Auswahl ----
    // 70/20/10, Summe 100 -> Grenzen bei 0.70 und 0.90
    float[] w = { 70f, 20f, 10f };
    Check.eq("ganz unten Klasse 0", 0, MelodyModes.pickClass(w, 0.0));
    Check.eq("knapp unter der ersten Grenze noch 0", 0, MelodyModes.pickClass(w, 0.699));
    Check.eq("ab der ersten Grenze Klasse 1", 1, MelodyModes.pickClass(w, 0.701));
    Check.eq("knapp unter der zweiten Grenze noch 1", 1, MelodyModes.pickClass(w, 0.899));
    Check.eq("ab der zweiten Grenze Klasse 2", 2, MelodyModes.pickClass(w, 0.901));
    Check.eq("ganz oben die letzte Klasse", 2, MelodyModes.pickClass(w, 0.9999));

    // Ein Gewicht von 0 wird nie gezogen
    float[] noThird = { 90f, 0f, 10f };
    for (int i = 0; i <= 1000; i++) {
      Check.that("Gewicht 0 wird nie gezogen",
          MelodyModes.pickClass(noThird, i / 1000.0) != 1);
    }

    // Entartete Gewichte: kein Absturz, kein NaN-Index
    float[] allZero = { 0f, 0f, 0f };
    Check.eq("alle Gewichte 0 faellt auf den Sekundschritt zurueck",
        0, MelodyModes.pickClass(allZero, 0.5));
    float[] negative = { -5f, -1f, -2f };
    Check.eq("negative Gewichte fallen auf den Sekundschritt zurueck",
        0, MelodyModes.pickClass(negative, 0.5));
    float[] withNan = { Float.NaN, 50f, 50f };
    Check.that("NaN-Gewicht wird uebersprungen",
        MelodyModes.pickClass(withNan, 0.1) != 0);

    // Nicht-prozentuale Gewichte werden normalisiert (Summe 4, Grenzen 0.5/0.75)
    float[] raw = { 2f, 1f, 1f };
    Check.eq("normalisiert: unter der Haelfte Klasse 0", 0, MelodyModes.pickClass(raw, 0.49));
    Check.eq("normalisiert: ueber der Haelfte Klasse 1", 1, MelodyModes.pickClass(raw, 0.51));
    Check.eq("normalisiert: ueber drei Vierteln Klasse 2", 2, MelodyModes.pickClass(raw, 0.76));

    // ---- Verteilung ueber viele Ziehungen ----
    int n = 100000;
    int[] hits = new int[3];
    for (int i = 0; i < n; i++) {
      hits[MelodyModes.pickClass(w, (i + 0.5) / n)]++;
    }
    Check.near("Klasse 0 trifft 70%", 0.70, hits[0] / (double) n, 0.01);
    Check.near("Klasse 1 trifft 20%", 0.20, hits[1] / (double) n, 0.01);
    Check.near("Klasse 2 trifft 10%", 0.10, hits[2] / (double) n, 0.01);

    // ---- drawInterval: Vorzeichen und Betrag ----
    MelodyMode dorisch = MelodyModes.at(0);
    Check.eq("Sekundschritt aufwaerts", 1, MelodyModes.drawInterval(dorisch, 0.1, 0.9));
    Check.eq("Sekundschritt abwaerts", -1, MelodyModes.drawInterval(dorisch, 0.1, 0.1));
    Check.eq("Terz aufwaerts", 2, MelodyModes.drawInterval(dorisch, 0.8, 0.9));
    Check.eq("Quint abwaerts", -4, MelodyModes.drawInterval(dorisch, 0.95, 0.1));

    // Nur Werte aus {+-1, +-2, +-4}, und beide Vorzeichen kommen vor - ohne
    // das driftete die Zuordnung ueber die normalen Kanten monoton nach oben.
    boolean sawUp = false, sawDown = false;
    for (int i = 0; i < 2000; i++) {
      double rc = (i * 7919 % 2000) / 2000.0;
      double rs = (i * 6151 % 2000) / 2000.0;
      int v = MelodyModes.drawInterval(dorisch, rc, rs);
      int a = Math.abs(v);
      Check.that("Intervall aus der Klassentabelle", a == 1 || a == 2 || a == 4);
      if (v > 0) { sawUp = true; }
      if (v < 0) { sawDown = true; }
    }
    Check.that("es gibt aufwaerts gerichtete Schritte", sawUp);
    Check.that("es gibt abwaerts gerichtete Schritte", sawDown);

    System.exit(Check.report("MelodyModesTest"));
  }
}
