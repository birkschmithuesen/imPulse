public class WeightedChoiceTest {

  public static void main(String[] args) throws Exception {
    // ---- Kumulierte Verteilung, Grenzen exakt ----
    // 85/10/4/1 auf die Indizes 1..4, Index 0 aus. Summe 100, die Grenzen
    // liegen also bei 0.85 / 0.95 / 0.99 / 1.0.
    float[] w = { 0f, 85f, 10f, 4f, 1f };
    Check.eq("ganz unten die erste Klasse mit Gewicht",
        1, WeightedChoice.pick(w, 5, 0.0, 1));
    Check.eq("knapp unter der ersten Grenze noch Index 1",
        1, WeightedChoice.pick(w, 5, 0.849, 1));
    Check.eq("ab der ersten Grenze Index 2",
        2, WeightedChoice.pick(w, 5, 0.851, 1));
    Check.eq("ab der zweiten Grenze Index 3",
        3, WeightedChoice.pick(w, 5, 0.951, 1));
    Check.eq("ganz oben die letzte Klasse mit Gewicht",
        4, WeightedChoice.pick(w, 5, 1.0, 1));

    // ---- Ein Gewicht von 0 wird NIE gezogen ----
    // Auch nicht bei einem Zufallswert genau auf seiner Grenze: ein
    // ausgeschalteter Uebergang darf nicht doch gelegentlich vorkommen.
    for (int i = 0; i <= 1000; i++) {
      Check.that("Gewicht 0 wird nie gezogen",
          WeightedChoice.pick(w, 5, i/1000.0, 1) != 0);
    }

    // ---- Verteilung ueber 100 000 Ziehungen ----
    int[] counts = new int[5];
    int n = 100000;
    for (int i = 0; i < n; i++) {
      counts[WeightedChoice.pick(w, 5, (i + 0.5)/n, 1)]++;
    }
    Check.eq("Index 0 kommt nicht vor", 0, counts[0]);
    Check.near("Index 1 trifft die 85 Prozent", 0.85, counts[1]/(double) n, 0.002);
    Check.near("Index 2 trifft die 10 Prozent", 0.10, counts[2]/(double) n, 0.002);
    Check.near("Index 3 trifft die 4 Prozent", 0.04, counts[3]/(double) n, 0.002);
    Check.near("Index 4 trifft das 1 Prozent", 0.01, counts[4]/(double) n, 0.002);

    // ---- Gewichte muessen sich nicht zu 100 summieren ----
    // Ein Operator dreht einen Regler, ohne die anderen nachzurechnen.
    float[] w2 = { 3f, 1f };
    int erste = 0;
    for (int i = 0; i < 40000; i++) {
      if (WeightedChoice.pick(w2, 2, (i + 0.5)/40000.0, 0) == 0) {
        erste++;
      }
    }
    Check.near("Gewichte werden normalisiert, nicht als Prozent gelesen",
        0.75, erste/40000.0, 0.002);

    // ---- Entartete Eingaben geben den Rueckfall, nicht einen Absturz ----
    Check.eq("alle Gewichte 0 gibt den Rueckfall",
        2, WeightedChoice.pick(new float[] { 0f, 0f, 0f, 0f }, 4, 0.5, 2));
    Check.eq("null-Array gibt den Rueckfall",
        2, WeightedChoice.pick(null, 4, 0.5, 2));
    Check.eq("count groesser als das Array gibt den Rueckfall",
        2, WeightedChoice.pick(new float[] { 1f, 1f }, 4, 0.5, 2));
    Check.eq("count 0 gibt den Rueckfall",
        2, WeightedChoice.pick(new float[] { 1f, 1f }, 0, 0.5, 2));
    Check.eq("negatives Gewicht wird nicht gezogen",
        1, WeightedChoice.pick(new float[] { -5f, 1f }, 2, 0.5, 0));
    Check.eq("NaN-Gewicht gilt als 0, nicht als Verfaelschung der Summe",
        1, WeightedChoice.pick(new float[] { Float.NaN, 1f }, 2, 0.5, 0));
    Check.eq("NaN-Zufall gibt den Rueckfall",
        2, WeightedChoice.pick(w, 5, Double.NaN, 2));

    // Ein Array, das laenger ist als count, ist erlaubt: die ueberzaehligen
    // Gewichte werden nicht mitgezaehlt. Sonst koennte eine Matrixzeile mit
    // Reserveplaetzen die Verteilung still verschieben.
    float[] laenger = { 1f, 1f, 999f };
    int zweiter = 0;
    for (int i = 0; i < 10000; i++) {
      if (WeightedChoice.pick(laenger, 2, (i + 0.5)/10000.0, 0) == 1) {
        zweiter++;
      }
    }
    Check.near("Gewichte hinter count zaehlen nicht mit",
        0.5, zweiter/10000.0, 0.005);

    // Zufall ausserhalb 0..1 wird geklemmt statt zu ueberlaufen
    Check.eq("Zufall unter 0 wird geklemmt", 1, WeightedChoice.pick(w, 5, -1.0, 1));
    Check.eq("Zufall ueber 1 wird geklemmt", 4, WeightedChoice.pick(w, 5, 2.0, 1));

    // Genau ein Gewicht: es gewinnt immer
    float[] nurEins = { 0f, 0f, 1f, 0f };
    for (int i = 0; i <= 100; i++) {
      Check.eq("einziges gewichtetes Element gewinnt immer",
          2, WeightedChoice.pick(nurEins, 4, i/100.0, 0));
    }

    System.exit(Check.report("WeightedChoiceTest"));
  }
}
