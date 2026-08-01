import java.util.ArrayList;
import java.util.List;

public class OriginSequencerTest {

  // Zufallsquelle mit fest vorgegebener Folge - laeuft die Folge aus, beginnt
  // sie von vorn. Ohne das haengt jede Erwartung an Math.random().
  static class FixedRandom implements RandomSource {
    private final double[] values;
    private int pos = 0;
    FixedRandom(double... values) { this.values = values; }
    public double next() {
      double v = values[pos % values.length];
      pos++;
      return v;
    }
  }

  static TrackConfig off() {
    TrackConfig c = new TrackConfig();
    c.enabled = false;
    c.noteValue = 4;
    c.repeatCount = 3;
    c.energy = 0.6f;
    c.swingJitter = 0f;
    c.originStripeOverride = -1;
    return c;
  }

  static TrackConfig on(int noteValue) {
    TrackConfig c = off();
    c.enabled = true;
    c.noteValue = noteValue;
    return c;
  }

  // Nur Track 0 belegt, alle anderen aus
  static TrackConfig[] single(TrackConfig first) {
    TrackConfig[] cfg = new TrackConfig[OriginSequencer.TRACK_COUNT];
    cfg[0] = first;
    for (int i = 1; i < cfg.length; i++) {
      cfg[i] = off();
    }
    return cfg;
  }

  static boolean fired(int[] tracks, int track) {
    for (int t : tracks) {
      if (t == track) {
        return true;
      }
    }
    return false;
  }

  public static void main(String[] args) throws Exception {
    // ---- Rasterung der Notenwerte ----
    // RemoteControlledIntParameter kann keine Aufzaehlung, die Range ist
    // 1..16. Ein Regler, der auf 5 stehen bleibt, soll sich wie 4 verhalten
    // statt ein krummes Intervall zu erzeugen.
    Check.eq("1 bleibt 1", 1, OriginSequencer.quantizeNoteValue(1));
    Check.eq("2 bleibt 2", 2, OriginSequencer.quantizeNoteValue(2));
    Check.eq("4 bleibt 4", 4, OriginSequencer.quantizeNoteValue(4));
    Check.eq("8 bleibt 8", 8, OriginSequencer.quantizeNoteValue(8));
    Check.eq("16 bleibt 16", 16, OriginSequencer.quantizeNoteValue(16));
    Check.eq("3 rastet auf 2", 2, OriginSequencer.quantizeNoteValue(3));
    Check.eq("5 rastet auf 4", 4, OriginSequencer.quantizeNoteValue(5));
    Check.eq("7 rastet auf 4", 4, OriginSequencer.quantizeNoteValue(7));
    Check.eq("15 rastet auf 8", 8, OriginSequencer.quantizeNoteValue(15));
    Check.eq("0 rastet auf 1", 1, OriginSequencer.quantizeNoteValue(0));
    Check.eq("negativ rastet auf 1", 1, OriginSequencer.quantizeNoteValue(-4));
    Check.eq("ueber 16 rastet auf 16", 16, OriginSequencer.quantizeNoteValue(99));

    // ---- Ein ausgeschalteter Track feuert nie ----
    OriginSequencer s0 = new OriginSequencer(8);
    TrackConfig[] allOff = single(off());
    for (int i = 0; i < 100; i++) {
      Check.eq("ausgeschaltet feuert nichts",
          0, s0.update(i*0.5, allOff, new FixedRandom(0.5)).length);
    }

    // ---- Grundtakt: Viertel bei 4 Beats = vier Treffer ----
    OriginSequencer s1 = new OriginSequencer(8);
    TrackConfig[] viertel = single(on(4));
    // Der allererste update() setzt nur den Nullpunkt und feuert nicht -
    // sonst kaeme beim Einschalten des Sequencers ein Schlag aus dem Nichts,
    // bevor der Operator die Traktparameter gesetzt hat.
    Check.eq("erster Aufruf setzt nur den Nullpunkt",
        0, s1.update(0.0, viertel, new FixedRandom(0.5)).length);
    Check.eq("nach einem halben Beat noch nicht",
        0, s1.update(0.5, viertel, new FixedRandom(0.5)).length);
    int[] beiBeatEins = s1.update(1.0, viertel, new FixedRandom(0.5));
    Check.eq("nach einem Beat feuert genau einer", 1, beiBeatEins.length);
    Check.eq("und zwar Track 0", 0, beiBeatEins[0]);
    Check.eq("derselbe Zeitpunkt feuert nicht noch einmal",
        0, s1.update(1.0, viertel, new FixedRandom(0.5)).length);
    Check.eq("nach zwei Beats wieder",
        1, s1.update(2.0, viertel, new FixedRandom(0.5)).length);
    Check.eq("nach 2.5 nicht", 0, s1.update(2.5, viertel, new FixedRandom(0.5)).length);
    Check.eq("nach drei wieder", 1, s1.update(3.0, viertel, new FixedRandom(0.5)).length);

    // ---- Achtel feuern doppelt so oft wie Viertel ----
    OriginSequencer s2 = new OriginSequencer(8);
    TrackConfig[] achtel = single(on(8));
    s2.update(0.0, achtel, new FixedRandom(0.5));
    int treffer = 0;
    // in kleinen Schritten ueber vier Beats laufen
    for (int i = 1; i <= 400; i++) {
      treffer += s2.update(i*0.01, achtel, new FixedRandom(0.5)).length;
    }
    Check.eq("Achtel feuern acht mal in vier Beats", 8, treffer);

    OriginSequencer s3 = new OriginSequencer(8);
    TrackConfig[] ganze = single(on(1));
    s3.update(0.0, ganze, new FixedRandom(0.5));
    treffer = 0;
    for (int i = 1; i <= 800; i++) {
      treffer += s3.update(i*0.01, ganze, new FixedRandom(0.5)).length;
    }
    Check.eq("Ganze feuern zwei mal in acht Beats", 2, treffer);

    // ---- repeatCount haelt den Ursprung fest ----
    // Der Kern des Features: von demselben Ursprung wiederholt spawnen soll
    // (fast) dieselbe Melodie erzeugen. Ohne das waere jeder Spawn ein
    // Einzelereignis ohne Wiedererkennbarkeit.
    OriginSequencer s4 = new OriginSequencer(8);
    TrackConfig[] rep = single(on(4));
    rep[0].repeatCount = 3;
    // Zufallsfolge: 0.0 -> Stripe 0, 0.5 -> Stripe 4, 0.99 -> Stripe 7
    FixedRandom rnd = new FixedRandom(0.0, 0.5, 0.99);
    s4.update(0.0, rep, rnd);
    List<Integer> ursprungsfolge = new ArrayList<Integer>();
    for (int i = 1; i <= 900; i++) {
      if (s4.update(i*0.01, rep, rnd).length > 0) {
        ursprungsfolge.add(Integer.valueOf(s4.originOf(0)));
      }
    }
    Check.eq("neun Beats bei Vierteln geben neun Treffer", 9, ursprungsfolge.size());
    Check.eq("Treffer 1 zieht den ersten Ursprung", 0, ursprungsfolge.get(0).intValue());
    Check.eq("Treffer 2 bleibt darauf", 0, ursprungsfolge.get(1).intValue());
    Check.eq("Treffer 3 bleibt darauf", 0, ursprungsfolge.get(2).intValue());
    Check.eq("Treffer 4 zieht neu", 4, ursprungsfolge.get(3).intValue());
    Check.eq("Treffer 5 bleibt darauf", 4, ursprungsfolge.get(4).intValue());
    Check.eq("Treffer 6 bleibt darauf", 4, ursprungsfolge.get(5).intValue());
    Check.eq("Treffer 7 zieht neu", 7, ursprungsfolge.get(6).intValue());
    Check.eq("Treffer 8 bleibt darauf", 7, ursprungsfolge.get(7).intValue());
    Check.eq("Treffer 9 bleibt darauf", 7, ursprungsfolge.get(8).intValue());

    // repeatCount 1 zieht bei jedem Treffer neu
    OriginSequencer s5 = new OriginSequencer(8);
    TrackConfig[] rep1 = single(on(4));
    rep1[0].repeatCount = 1;
    FixedRandom rnd1 = new FixedRandom(0.0, 0.5);
    s5.update(0.0, rep1, rnd1);
    List<Integer> folge1 = new ArrayList<Integer>();
    for (int i = 1; i <= 400; i++) {
      if (s5.update(i*0.01, rep1, rnd1).length > 0) {
        folge1.add(Integer.valueOf(s5.originOf(0)));
      }
    }
    Check.eq("vier Treffer", 4, folge1.size());
    Check.eq("Treffer 1", 0, folge1.get(0).intValue());
    Check.eq("Treffer 2 zieht sofort neu", 4, folge1.get(1).intValue());
    Check.eq("Treffer 3 wieder", 0, folge1.get(2).intValue());
    Check.eq("Treffer 4 wieder", 4, folge1.get(3).intValue());

    // ---- originStripeOverride gewinnt immer ----
    OriginSequencer s6 = new OriginSequencer(8);
    TrackConfig[] fix = single(on(4));
    fix[0].originStripeOverride = 5;
    s6.update(0.0, fix, new FixedRandom(0.0, 0.5, 0.99));
    for (int i = 1; i <= 600; i++) {
      if (s6.update(i*0.01, fix, new FixedRandom(0.0, 0.5, 0.99)).length > 0) {
        Check.eq("Override haelt den Ursprung ueber repeatCount hinweg",
            5, s6.originOf(0));
      }
    }
    // Ein Override ausserhalb der Stripe-Zahl darf keinen Index-Fehler geben
    OriginSequencer s7 = new OriginSequencer(8);
    TrackConfig[] zuGross = single(on(4));
    zuGross[0].originStripeOverride = 999;
    s7.update(0.0, zuGross, new FixedRandom(0.5));
    s7.update(1.0, zuGross, new FixedRandom(0.5));
    Check.that("zu grosser Override wird auf die letzte Stripe geklemmt",
        s7.originOf(0) >= 0 && s7.originOf(0) < 8);

    // ---- Wiedereinschalten feuert nicht sofort ----
    // Dieselbe Regel wie PresetScheduler.isDue() und ImpulseOscThrottle.due():
    // nach einer langen Aus-Phase waere sonst sofort ein Intervall verstrichen
    // und es kaeme ein Schlag mitten in die laufende Szene.
    OriginSequencer s8 = new OriginSequencer(8);
    TrackConfig[] an = single(on(4));
    TrackConfig[] aus = single(off());
    s8.update(0.0, an, new FixedRandom(0.5));
    s8.update(1.0, an, new FixedRandom(0.5)); // feuert
    for (int i = 2; i <= 100; i++) { // lange aus
      s8.update(i, aus, new FixedRandom(0.5));
    }
    Check.eq("direkt nach dem Wiedereinschalten feuert nichts",
        0, s8.update(100.5, an, new FixedRandom(0.5)).length);
    Check.eq("erst ein Intervall spaeter",
        1, s8.update(101.5, an, new FixedRandom(0.5)).length);

    // ---- Kein Nachholen nach einem Haenger ----
    // Ein im Hintergrund geparktes Fenster darf beim Zurueckkommen keinen
    // Schwall ausloesen - dieselbe Regel, die ImpulseOscThrottle durchsetzt.
    OriginSequencer s9 = new OriginSequencer(8);
    TrackConfig[] hae = single(on(16));
    s9.update(0.0, hae, new FixedRandom(0.5));
    Check.eq("nach einem Sprung ueber 500 Beats genau ein Treffer, kein Schwall",
        1, s9.update(500.0, hae, new FixedRandom(0.5)).length);
    Check.eq("und danach wieder im Takt",
        0, s9.update(500.1, hae, new FixedRandom(0.5)).length);
    Check.eq("ein Sechzehntel spaeter wieder",
        1, s9.update(500.25, hae, new FixedRandom(0.5)).length);

    // ---- Mehrere Tracks gleichzeitig ----
    OriginSequencer s10 = new OriginSequencer(8);
    TrackConfig[] zwei = single(on(4));
    zwei[1] = on(2); // Halbe
    s10.update(0.0, zwei, new FixedRandom(0.5));
    int[] beiEins = s10.update(1.0, zwei, new FixedRandom(0.5));
    Check.that("bei Beat 1 feuert nur die Viertel", fired(beiEins, 0));
    Check.that("die Halbe noch nicht", !fired(beiEins, 1));
    int[] beiZwei = s10.update(2.0, zwei, new FixedRandom(0.5));
    Check.that("bei Beat 2 feuert die Viertel", fired(beiZwei, 0));
    Check.that("und die Halbe auch", fired(beiZwei, 1));

    // Die zwei Tracks haben unabhaengige Urspruenge
    OriginSequencer s11 = new OriginSequencer(8);
    TrackConfig[] unab = single(on(4));
    unab[1] = on(4);
    s11.update(0.0, unab, new FixedRandom(0.0, 0.99));
    s11.update(1.0, unab, new FixedRandom(0.0, 0.99));
    Check.that("Track 0 und Track 1 ziehen getrennte Urspruenge",
        s11.originOf(0) != s11.originOf(1));

    // ---- swingJitter 0 ist exakt periodisch ----
    OriginSequencer s12 = new OriginSequencer(8);
    TrackConfig[] exakt = single(on(4));
    exakt[0].swingJitter = 0f;
    s12.update(0.0, exakt, new FixedRandom(0.0)); // Zufall am Rand
    Check.eq("bei Jitter 0 aendert der Zufall nichts",
        0, s12.update(0.99, exakt, new FixedRandom(0.0)).length);
    Check.eq("und der Takt sitzt exakt",
        1, s12.update(1.0, exakt, new FixedRandom(0.0)).length);

    // ---- swingJitter verschiebt, ohne das Intervall auf 0 fallen zu lassen ----
    OriginSequencer s13 = new OriginSequencer(8);
    TrackConfig[] swing = single(on(16));
    swing[0].swingJitter = 1f;
    s13.update(0.0, swing, new FixedRandom(0.0)); // Faktor 0 -> Intervall 0
    int treffer13 = 0;
    for (int i = 1; i <= 1000; i++) {
      treffer13 += s13.update(i*0.001, swing, new FixedRandom(0.0)).length;
    }
    // Ohne die Untergrenze waere das Intervall 0 und JEDER der 1000 Aufrufe
    // wuerde feuern - das Netz waere in Sekunden geflutet. MIN_INTERVAL_BEATS
    // deckelt das auf hoechstens einen Treffer je 0.05 Beats.
    Check.that("volles Swing laesst das Intervall nicht auf 0 fallen",
        treffer13 <= (int)(1.0/OriginSequencer.MIN_INTERVAL_BEATS) + 1);
    Check.that("es feuert aber ueberhaupt", treffer13 > 0);

    // ---- Ein Stripe ist immer im gueltigen Bereich ----
    OriginSequencer s14 = new OriginSequencer(3);
    TrackConfig[] eng = single(on(16));
    s14.update(0.0, eng, new FixedRandom(0.0, 0.333, 0.667, 0.999));
    for (int i = 1; i <= 200; i++) {
      if (s14.update(i*0.05, eng, new FixedRandom(0.0, 0.333, 0.667, 0.999)).length > 0) {
        int o = s14.originOf(0);
        Check.that("Ursprung liegt im Bereich 0..nStripes-1", o >= 0 && o < 3);
      }
    }

    // ---- originPool: die Ziehung bleibt im Pool ----
    // Der Baum-Filter (Feature 7) reicht den erlaubten Stripe-Vorrat als
    // Array herein. Der Sequencer kennt keine Baeume - er zieht nur aus dem,
    // was er bekommt.
    OriginSequencer s15 = new OriginSequencer(30);
    TrackConfig[] pool = single(on(16));
    pool[0].repeatCount = 1;               // bei jedem Treffer neu wuerfeln
    pool[0].originPool = new int[] { 3, 7, 11 };
    s15.update(0.0, pool, new FixedRandom(0.0, 0.2, 0.5, 0.8, 0.99));
    int treffer15 = 0;
    for (int i = 1; i <= 400; i++) {
      if (s15.update(i*0.05, pool, new FixedRandom(0.0, 0.2, 0.5, 0.8, 0.99)).length > 0) {
        int o = s15.originOf(0);
        Check.that("gezogener Stripe liegt im Pool",
            o == 3 || o == 7 || o == 11);
        treffer15++;
      }
    }
    Check.that("es wurde ueberhaupt gefeuert", treffer15 > 0);

    // Alle Pool-Eintraege sind erreichbar, nicht nur der erste
    OriginSequencer s16 = new OriginSequencer(30);
    TrackConfig[] alle = single(on(16));
    alle[0].repeatCount = 1;
    alle[0].originPool = new int[] { 3, 7, 11 };
    boolean sah3 = false, sah7 = false, sah11 = false;
    FixedRandom r16 = new FixedRandom(0.0, 0.5, 0.99);
    s16.update(0.0, alle, r16);
    for (int i = 1; i <= 300; i++) {
      if (s16.update(i*0.05, alle, r16).length > 0) {
        int o = s16.originOf(0);
        sah3 |= (o == 3);
        sah7 |= (o == 7);
        sah11 |= (o == 11);
      }
    }
    Check.that("der erste Pool-Eintrag kommt vor", sah3);
    Check.that("der mittlere auch", sah7);
    Check.that("und der letzte auch", sah11);

    // Ein Pool mit genau einem Stripe liefert immer denselben
    OriginSequencer s17 = new OriginSequencer(30);
    TrackConfig[] einer = single(on(8));
    einer[0].repeatCount = 1;
    einer[0].originPool = new int[] { 23 };
    s17.update(0.0, einer, new FixedRandom(0.0, 0.5, 0.99));
    for (int i = 1; i <= 200; i++) {
      if (s17.update(i*0.05, einer, new FixedRandom(0.0, 0.5, 0.99)).length > 0) {
        Check.eq("einelementiger Pool liefert immer denselben Stripe",
            23, s17.originOf(0));
      }
    }

    // Ein leerer Pool zaehlt wie kein Pool - sonst schwiege der Track still.
    // StripeTreeStore liefert dafuer zwar null, aber der Sequencer soll das
    // auch abfangen, wenn ihn jemand anders fuellt.
    OriginSequencer s18 = new OriginSequencer(8);
    TrackConfig[] leer = single(on(4));
    leer[0].originPool = new int[0];
    s18.update(0.0, leer, new FixedRandom(0.5));
    Check.eq("leerer Pool feuert trotzdem",
        1, s18.update(1.0, leer, new FixedRandom(0.5)).length);
    Check.that("und liefert einen gueltigen Stripe",
        s18.originOf(0) >= 0 && s18.originOf(0) < 8);

    // null-Pool = kein Filter, exakt das bisherige Verhalten
    OriginSequencer s19 = new OriginSequencer(8);
    TrackConfig[] ohne = single(on(4));
    ohne[0].originPool = null;
    ohne[0].repeatCount = 1;
    s19.update(0.0, ohne, new FixedRandom(0.0));
    s19.update(1.0, ohne, new FixedRandom(0.0));
    Check.eq("ohne Pool zieht Zufall 0.0 den Stripe 0", 0, s19.originOf(0));

    // originStripeOverride schlaegt den Pool -- die Vorrangregel aus dem Brief
    OriginSequencer s20 = new OriginSequencer(30);
    TrackConfig[] beides = single(on(4));
    beides[0].originPool = new int[] { 3, 7, 11 };
    beides[0].originStripeOverride = 25;
    s20.update(0.0, beides, new FixedRandom(0.5));
    for (int i = 1; i <= 200; i++) {
      if (s20.update(i*0.05, beides, new FixedRandom(0.5)).length > 0) {
        Check.eq("expliziter Stripe schlaegt den Baum-Pool",
            25, s20.originOf(0));
      }
    }

    // Der Pool gilt AUCH beim Nachwuerfeln nach Ablauf von repeatCount
    OriginSequencer s21 = new OriginSequencer(30);
    TrackConfig[] wdh = single(on(8));
    wdh[0].repeatCount = 2;
    wdh[0].originPool = new int[] { 5, 9 };
    FixedRandom r21 = new FixedRandom(0.0, 0.99);
    s21.update(0.0, wdh, r21);
    List<Integer> folge21 = new ArrayList<Integer>();
    for (int i = 1; i <= 400; i++) {
      if (s21.update(i*0.05, wdh, r21).length > 0) {
        folge21.add(Integer.valueOf(s21.originOf(0)));
      }
    }
    Check.that("mehrere Treffer", folge21.size() >= 6);
    for (int i = 0; i < folge21.size(); i++) {
      int o = folge21.get(i).intValue();
      Check.that("auch nach dem Nachwuerfeln bleibt es im Pool",
          o == 5 || o == 9);
    }
    Check.eq("Treffer 1 und 2 teilen den Ursprung",
        folge21.get(0).intValue(), folge21.get(1).intValue());
    Check.that("Treffer 3 hat neu gewuerfelt",
        folge21.get(2).intValue() != folge21.get(1).intValue()
        || folge21.size() < 3);

    System.exit(Check.report("OriginSequencerTest"));
  }
}
