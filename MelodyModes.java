// Die acht waehlbaren Modi der topologiebasierten Melodiekomposition:
// Tonvorrat plus Kanten-Gewichtung. Ein Modus ist hier ausdruecklich BEIDES -
// zwei Modi mit gleicher Tonmenge, aber verschiedener Wegeregel sind zwei
// verschiedene Modi (siehe Ajam gegen Ionisch im Konzeptdokument,
// docs/superpowers/specs/2026-08-01-topologiebasierte-melodiekomposition-konzept.md
// Abschnitt 4).
//
// Bewusst ohne Processing-, oscP5- und netP5-Abhaengigkeit, damit die Logik
// ueber test/run.sh pruefbar bleibt - dasselbe Muster wie SpeedQuantizer,
// SplitVariance und StripeTreeStore.
class MelodyModes {

  // Die drei Intervallklassen, in denen die Gewichte gelesen werden - in
  // SKALENSTUFEN, nicht Halbtoenen. Dadurch bleibt jede Note automatisch auf
  // der Skala, egal welcher Modus laeuft.
  //
  //   1 = Nachbarstufe ("Sekundschritt"; in Hijaz und Nikriz kann das die
  //       uebermaessige Sekunde sein - die ist auf diesen Skalen die
  //       Nachbarstufe und wird deshalb wie ein Schritt behandelt)
  //   2 = zwei Stufen ("Terz")
  //   4 = vier Stufen ("Quint", das Landmarken-Intervall)
  static final int[] CLASS_INTERVALS = { 1, 2, 4 };

  // Reihenfolge A..H wie im Konzept, Abschnitt 4. Der Index ist der Wert von
  // /net/melody/mode und von ~melodyMode auf der Klangseite - die drei
  // Reihenfolgen muessen uebereinstimmen.
  static final MelodyMode[] ALL = {
    // A - Dorisch: mildeste modale Faerbung, weder Dur-froehlich noch
    // Moll-schwer. "Stufen als Rueckgrat, Spruenge als Wuerze".
    new MelodyMode("dorisch", "Dorisch",
        new int[] { 0, 2, 3, 5, 7, 9, 10 },
        new float[] { 70f, 20f, 10f }),

    // B - Moll-Pentatonik: keine benachbarten Halbtonschritte, praktisch
    // jedes Intervall konsonant. Deshalb die entspannteste Gewichtung -
    // der robuste Modus fuer dichten Impulsverkehr.
    new MelodyMode("pentatonik", "Moll-Pentatonik",
        new int[] { 0, 3, 5, 7, 10 },
        new float[] { 50f, 35f, 15f }),

    // C - Maqam Hijaz, 12-TET-Naeherung (offen deklariert, keine
    // Vierteltoene): uebermaessige Sekunde direkt ueber dem Grundton.
    // Weniger terzlastig als Dorisch, weil Maqam-Melodik jins-intern
    // stufenweise verlaeuft.
    new MelodyMode("hijaz", "Maqam Hijaz",
        new int[] { 0, 1, 4, 5, 7, 8, 10 },
        new float[] { 60f, 25f, 15f }),

    // D - Harmonisch Moll: ebenfalls eine uebermaessige Sekunde, aber ganz
    // oben vor dem echten Leitton. Genuin europaeischer Herkunft, bewusst
    // nicht mit der maqam-nahen Seite vermischt.
    new MelodyMode("harmonischmoll", "Harmonisch Moll",
        new int[] { 0, 2, 3, 5, 7, 8, 11 },
        new float[] { 70f, 20f, 10f }),

    // E - Phrygisch: EXAKT der Tonvorrat, der heute in klangnetz_bells.scd
    // als ~scaleSteps steht. Der Modus fuer den A/B-Vergleich "alt gegen
    // neu", weil sich dabei genau eine Sache aendert - die Verteilung, nicht
    // der Vorrat. Schrittlastiger als Dorisch: der charakteristische Halbton
    // ueber dem Grundton wirkt nur, wenn er auch als Schritt vorkommt.
    new MelodyMode("phrygisch", "Phrygisch",
        new int[] { 0, 1, 3, 5, 7, 8, 10 },
        new float[] { 75f, 15f, 10f }),

    // F - Maqam Ajam: keine Naeherung noetig, liegt ohne Vierteltoene auf
    // dem 12-Ton-Raster. Wer nur den Tonvorrat betrachtet, hat hier Dur vor
    // sich - der Unterschied steckt in der Wegeregel: erhoehter
    // Landmarken-Anteil (Ghammaz auf der 5. Stufe), abgesenkte Terz, damit
    // die Linie jins-intern bleibt.
    new MelodyMode("ajam", "Maqam Ajam",
        new int[] { 0, 2, 4, 5, 7, 9, 11 },
        new float[] { 70f, 15f, 15f }),

    // G - Maqam Nikriz: Jins Nikriz unten plus Buselik-Tetrachord auf der
    // 5. Stufe. Die uebermaessige Sekunde sitzt in der MITTE der Skala - die
    // Linie klingt unten neutral-mollig und kippt erst im Verlauf. Keine
    // Vierteltoene (die Rast-Variante obenauf ist deshalb bewusst nicht
    // gewaehlt).
    new MelodyMode("nikriz", "Maqam Nikriz",
        new int[] { 0, 2, 3, 6, 7, 9, 10 },
        new float[] { 65f, 20f, 15f }),

    // H - Maqam Saba, 12-TET-Naeherung: die halb-erniedrigte zweite Stufe
    // wird nach UNTEN angenaehert; nach oben ergaebe sie drei Halbtonschritte
    // in Folge und haette mit Saba nichts mehr zu tun. Der schrittlastigste
    // Modus: die Enge des unteren Jins (drei Stufen in vier Halbtoenen) ist
    // nur hoerbar, wenn sie durchschritten und nicht uebersprungen wird.
    //
    // Der Algorithmus erzwingt Oktavwiederholung, Saba hat sie im Original
    // nicht - dieser Modus liefert die Saba-FAERBUNG ueber den Tonvorrat,
    // nicht die Saba-Systematik. Offen benannt, nicht behauptet.
    new MelodyMode("saba", "Maqam Saba",
        new int[] { 0, 1, 3, 4, 7, 8, 10 },
        new float[] { 80f, 10f, 10f })
  };

  static int count() {
    return ALL.length;
  }

  // Modus nach Index, geklemmt statt geworfen: der Index kommt aus einem
  // OSC-Parameter, und ein Wurf mitten in draw() waere das schlechtere Ende.
  static MelodyMode at(int index) {
    if (index < 0) {
      return ALL[0];
    }
    if (index >= ALL.length) {
      return ALL[ALL.length - 1];
    }
    return ALL[index];
  }

  static MelodyMode byKey(String key) {
    int i = indexOfKey(key);
    return (i < 0) ? null : ALL[i];
  }

  // -1, wenn der Schluessel unbekannt ist. Der Aufrufer entscheidet, ob das
  // ein Fehler ist - beim Laden einer von Hand umbenannten Datei etwa nicht.
  static int indexOfKey(String key) {
    if (key == null) {
      return -1;
    }
    String trimmed = key.trim();
    for (int i = 0; i < ALL.length; i++) {
      if (ALL[i].key.equals(trimmed)) {
        return i;
      }
    }
    return -1;
  }

  // Ein Intervall fuer eine NORMALE (Nicht-Landmarken-)Kante, in
  // Skalenstufen und VORZEICHENBEHAFTET.
  //
  // Das Vorzeichen ist kein Beiwerk: ohne es driftete die Zuordnung ueber die
  // normalen Kanten monoton nach oben, und die Landmarken-Rotation loeste nur
  // den kleineren Teil des Problems. Musikalisch ist es ohnehin die richtige
  // Lesart - eine Linie, die ausschliesslich steigt, ist keine.
  //
  // Zwei getrennte Zufallswerte, weil Klasse und Richtung unabhaengig sind:
  // ein einziger Wert koppelte "grosser Sprung" an "aufwaerts".
  static int drawInterval(MelodyMode mode, double rClass, double rSign) {
    int cls = pickClass(mode.classWeights, rClass);
    int step = CLASS_INTERVALS[cls];
    return (rSign < 0.5) ? -step : step;
  }

  // Gewichtete Auswahl, gleiche Bauform wie SpeedQuantizer.pick(): die
  // Gewichte muessen sich NICHT zu 100 summieren, normalisiert wird hier.
  static int pickClass(float[] weights, double r) {
    float total = 0f;
    for (int i = 0; i < weights.length; i++) {
      float w = weights[i];
      if (w > 0f && !Float.isNaN(w)) {
        total += w;
      }
    }
    if (total <= 0f) {
      // Alle Gewichte 0, negativ oder NaN: der Sekundschritt ist der
      // unverfaenglichste Rueckfall - er ist in jedem der acht Modi das
      // hoechstgewichtete Intervall.
      return 0;
    }
    double target = r * total;
    if (target < 0) {
      target = 0;
    }
    float acc = 0f;
    for (int i = 0; i < weights.length; i++) {
      float w = weights[i];
      if (w > 0f && !Float.isNaN(w)) {
        acc += w;
        if (target < acc) {
          return i;
        }
      }
    }
    // Nur bei r >= 1 oder Rundungsrest erreichbar: die letzte Klasse mit
    // positivem Gewicht.
    for (int i = weights.length - 1; i >= 0; i--) {
      if (weights[i] > 0f && !Float.isNaN(weights[i])) {
        return i;
      }
    }
    return 0;
  }
}

// Ein Modus: Tonvorrat plus Kanten-Gewichtung. Unveraenderlich - die acht
// Instanzen in MelodyModes.ALL sind statisch und werden von mehreren Stellen
// gelesen.
class MelodyMode {

  // Dateinamenstauglich und zugleich der Schluessel im Kopf der
  // Melodie-Datei: nur a-z und Ziffern, damit
  // data/nodeMelody_<key>.txt derselben Regel folgt wie ein Preset-Name
  // (PresetStore.isValidName).
  final String key;

  // Klartext fuer Konsole und Web-UI.
  final String name;

  // Halbtonabstaende vom Grundton, aufsteigend, alle < 12.
  final int[] scale;

  // Gewichte ueber MelodyModes.CLASS_INTERVALS, in Prozent notiert, aber
  // nicht darauf angewiesen - normalisiert wird beim Ziehen.
  final float[] classWeights;

  MelodyMode(String key_, String name_, int[] scale_, float[] classWeights_) {
    key = key_;
    name = name_;
    scale = scale_;
    classWeights = classWeights_;
  }

  int length() {
    return scale.length;
  }

  // Die Anzahl der Stufen, ueber die gefaltet wird - der Modulo-Teiler der
  // Oktavfaltung (Konzept, Schritt 3d).
  int notesPerOctaveSet(int numOctaves) {
    int n = (numOctaves < 1) ? 1 : numOctaves;
    return scale.length * n;
  }

  // Halbtonabstand eines bereits GEFALTETEN Skalenindex zum Grundton.
  int semitoneOf(int foldedScaleIndex) {
    int octave = foldedScaleIndex / scale.length;
    int degree = foldedScaleIndex % scale.length;
    return scale[degree] + 12 * octave;
  }
}
