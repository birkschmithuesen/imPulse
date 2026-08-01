import java.util.Arrays;

// Prueft die zwei Entscheidungen einer Aufspaltung: WIEVIELE der moeglichen
// Zweige einen Kind-Impuls bekommen, und WELCHE.
public class SplitFanoutTest {

  // Zufallsquelle mit fester Folge - ohne diese Naht haengt jede Erwartung an
  // einem echten Zufallsgenerator. Dieselbe Hilfe wie in OriginSequencerTest.
  static class FixedRandom implements RandomSource {
    private final double[] values;
    private int pos = 0;
    int calls = 0;

    FixedRandom(double... values_) {
      values = values_;
    }

    public double next() {
      calls++;
      if (values.length == 0) {
        return 0.0;
      }
      double v = values[pos % values.length];
      pos++;
      return v;
    }
  }

  public static void main(String[] args) throws Exception {
    // ---- Der Auslieferungsfall: alles auf "alle" ----
    // Genau dafuer ist der Default da - ein Neustart mit diesem Stand muss
    // sich verhalten wie vor dem Feature, sonst aendert er ungefragt das
    // Klangbild einer laufenden Installation.
    float[] allOnly = { 100f, 0f, 0f };
    for (int candidates = 0; candidates <= 6; candidates++) {
      for (int i = 0; i <= 100; i++) {
        Check.eq("weight/all=100 spawnt immer alle Zweige",
            candidates, SplitFanout.branchCount(allOnly, candidates, i/100.0));
      }
    }

    // ---- Die drei Kategorien bei einem Knoten mit drei Zweigen ----
    Check.eq("drei Kategorien", 3, SplitFanout.CATEGORY_COUNT);
    float[] each = { 50f, 30f, 20f }; // Grenzen bei 0.5 / 0.8 / 1.0
    Check.eq("unten: alle drei", 3, SplitFanout.branchCount(each, 3, 0.0));
    Check.eq("knapp unter der ersten Grenze: alle drei",
        3, SplitFanout.branchCount(each, 3, 0.499));
    Check.eq("ab der ersten Grenze: einer weniger",
        2, SplitFanout.branchCount(each, 3, 0.501));
    Check.eq("knapp unter der zweiten Grenze: einer weniger",
        2, SplitFanout.branchCount(each, 3, 0.799));
    Check.eq("ab der zweiten Grenze: genau einer",
        1, SplitFanout.branchCount(each, 3, 0.801));
    Check.eq("ganz oben: genau einer", 1, SplitFanout.branchCount(each, 3, 1.0));

    // ---- Ein Gewicht von 0 wird nie gezogen ----
    // Dieselbe Zusage wie bei den Speed-Klassen: ein auf 0 gedrehter Regler
    // ist AUS, nicht "selten".
    float[] noSingle = { 60f, 40f, 0f };
    for (int i = 0; i <= 1000; i++) {
      Check.that("Gewicht 0 auf 'genau einer' zieht nie einen einzelnen Zweig",
          SplitFanout.branchCount(noSingle, 3, i/1000.0) != 1);
    }

    // ---- Die Verteilung stimmt ueber viele Ziehungen ----
    // Kein Zufallsgenerator: die Ziehung ist eine reine Funktion von
    // random01, also wird der Bereich gleichmaessig abgetastet.
    float[] mix = { 70f, 20f, 10f };
    int[] seen = new int[4]; // Index = Zweigzahl
    int draws = 100000;
    for (int i = 0; i < draws; i++) {
      seen[SplitFanout.branchCount(mix, 3, (i + 0.5)/draws)]++;
    }
    Check.that("etwa 70 Prozent alle drei",
        Math.abs(seen[3]/(double) draws - 0.70) < 0.01);
    Check.that("etwa 20 Prozent zwei",
        Math.abs(seen[2]/(double) draws - 0.20) < 0.01);
    Check.that("etwa 10 Prozent einer",
        Math.abs(seen[1]/(double) draws - 0.10) < 0.01);

    // ---- Nicht-prozentuale Gewichte werden normalisiert ----
    // Ein Operator dreht einzelne Regler, ohne dass die Summe 100 ergibt.
    float[] raw = { 3f, 1f, 0f };
    Check.eq("Summe 4: unten alle", 3, SplitFanout.branchCount(raw, 3, 0.0));
    Check.eq("Summe 4: knapp unter 0.75 noch alle",
        3, SplitFanout.branchCount(raw, 3, 0.749));
    Check.eq("Summe 4: ab 0.75 einer weniger",
        2, SplitFanout.branchCount(raw, 3, 0.751));

    // ---- Entartete Gewichte fallen auf "alle" zurueck ----
    // Der Ausfallmodus muss das bisherige Verhalten sein, nicht Stille:
    // branchCount 0 hiesse, dass eine Kreuzung ihre Kinder verschluckt.
    float[] allZero = { 0f, 0f, 0f };
    Check.eq("alle Gewichte 0 gibt alle Zweige",
        3, SplitFanout.branchCount(allZero, 3, 0.5));
    float[] negative = { -5f, -1f, -2f };
    Check.eq("negative Gewichte gelten als 0, also alle Zweige",
        3, SplitFanout.branchCount(negative, 3, 0.5));
    float[] nan = { Float.NaN, Float.NaN, Float.NaN };
    Check.eq("NaN-Gewichte geben alle Zweige",
        3, SplitFanout.branchCount(nan, 3, 0.5));
    Check.eq("NaN als Zufallswert gibt alle Zweige",
        3, SplitFanout.branchCount(each, 3, Double.NaN));
    Check.eq("null-Tabelle gibt alle Zweige",
        3, SplitFanout.branchCount(null, 3, 0.9));
    Check.eq("zu kurze Tabelle gibt alle Zweige",
        3, SplitFanout.branchCount(new float[] { 1f, 1f }, 3, 0.9));

    // ---- Knoten mit anderem Grad ----
    // data/nodeCrossings.txt fuehrt heute nur Kreuzungen aus zwei Stripes,
    // die Zahl der moeglichen ZWEIGE haengt aber am Rand des Stripes und an
    // der Richtung des Elternimpulses - sie ist nicht fest 3.
    float[] singleOnly = { 0f, 0f, 100f };
    Check.eq("ein einziger moeglicher Zweig bleibt einer",
        1, SplitFanout.branchCount(singleOnly, 1, 0.5));
    Check.eq("kein moeglicher Zweig bleibt keiner",
        0, SplitFanout.branchCount(singleOnly, 0, 0.5));
    Check.eq("negative Kandidatenzahl gibt 0",
        0, SplitFanout.branchCount(singleOnly, -3, 0.5));
    // Bei zwei Kandidaten fallen "einer weniger" und "genau einer" zusammen -
    // beides ist 1. Das ist gewollt: die Kategorien sind relativ zur Zahl der
    // moeglichen Zweige beschrieben, nicht absolut.
    float[] oneLessOnly = { 0f, 100f, 0f };
    Check.eq("zwei Kandidaten, 'einer weniger' ist einer",
        1, SplitFanout.branchCount(oneLessOnly, 2, 0.5));
    Check.eq("zwei Kandidaten, 'genau einer' ist einer",
        1, SplitFanout.branchCount(singleOnly, 2, 0.5));
    Check.eq("vier Kandidaten, 'einer weniger' sind drei",
        3, SplitFanout.branchCount(oneLessOnly, 4, 0.5));
    Check.eq("vier Kandidaten, 'genau einer' ist einer",
        1, SplitFanout.branchCount(singleOnly, 4, 0.5));
    // Nie 0 bei vorhandenen Kandidaten: ein Impuls, der an einer Kreuzung
    // spurlos verschwindet, waere ein Loch im Netz ohne Fehlermeldung.
    for (int candidates = 1; candidates <= 5; candidates++) {
      for (int i = 0; i <= 200; i++) {
        int n = SplitFanout.branchCount(mix, candidates, i/200.0);
        Check.that("nie 0 Zweige bei vorhandenen Kandidaten", n >= 1);
        Check.that("nie mehr Zweige als Kandidaten", n <= candidates);
      }
    }

    // ---- chooseOrder: welche Zweige, in welcher Reihenfolge ----
    FixedRandom rnd = new FixedRandom(0.0, 0.0, 0.0);
    int[] all3 = SplitFanout.chooseOrder(3, 3, rnd);
    Check.eq("alle drei gewaehlt", 3, all3.length);
    int[] sorted = Arrays.copyOf(all3, all3.length);
    Arrays.sort(sorted);
    Check.eq("Permutation: enthaelt 0", 0, sorted[0]);
    Check.eq("Permutation: enthaelt 1", 1, sorted[1]);
    Check.eq("Permutation: enthaelt 2", 2, sorted[2]);

    // Teilmenge: verschiedene Indizes im Bereich, genau so viele wie verlangt
    for (int seed = 0; seed <= 20; seed++) {
      FixedRandom r = new FixedRandom(seed/21.0, (seed*7 % 21)/21.0, (seed*13 % 21)/21.0);
      int[] two = SplitFanout.chooseOrder(3, 2, r);
      Check.eq("zwei aus drei", 2, two.length);
      Check.that("Index im Bereich", two[0] >= 0 && two[0] < 3);
      Check.that("Index im Bereich", two[1] >= 0 && two[1] < 3);
      Check.that("keine Wiederholung", two[0] != two[1]);
    }

    // Die Reihenfolge ist gemischt, nicht die Kandidatenreihenfolge: sie
    // bestimmt spaeter, welcher Zweig zuerst und welcher verzoegert startet.
    // Ein fester Vorrang waere ein stiller Bias auf immer denselben Stripe.
    boolean sawNonZeroFirst = false;
    for (int seed = 0; seed < 30; seed++) {
      FixedRandom r = new FixedRandom((seed % 3)/3.0 + 0.16, 0.5);
      int[] two = SplitFanout.chooseOrder(3, 2, r);
      if (two[0] != 0) {
        sawNonZeroFirst = true;
      }
    }
    Check.that("nicht immer derselbe Zweig zuerst", sawNonZeroFirst);

    // Entartete Aufrufe
    Check.eq("take ueber der Kandidatenzahl wird geklemmt",
        3, SplitFanout.chooseOrder(3, 9, new FixedRandom(0.5)).length);
    Check.eq("take 0 gibt ein leeres Feld",
        0, SplitFanout.chooseOrder(3, 0, new FixedRandom(0.5)).length);
    Check.eq("keine Kandidaten geben ein leeres Feld",
        0, SplitFanout.chooseOrder(0, 2, new FixedRandom(0.5)).length);
    Check.eq("ohne Zufallsquelle die Kandidatenreihenfolge",
        3, SplitFanout.chooseOrder(3, 3, null).length);

    // Ein Zufallswert ausserhalb 0..1 darf keinen Index ausserhalb erzeugen -
    // ein IndexOutOfBounds mitten in drawMe() nimmt die ganze Show mit.
    int[] wild = SplitFanout.chooseOrder(4, 4, new FixedRandom(-3.0, 7.0, Double.NaN, 1.0));
    for (int i = 0; i < wild.length; i++) {
      Check.that("Index im Bereich trotz unbrauchbarem Zufall",
          wild[i] >= 0 && wild[i] < 4);
    }

    System.exit(Check.report("SplitFanoutTest"));
  }
}
