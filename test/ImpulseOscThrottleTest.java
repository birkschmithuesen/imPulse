public class ImpulseOscThrottleTest {

  public static void main(String[] args) throws Exception {
    // ---- Sendetakt ----
    ImpulseOscThrottle t = new ImpulseOscThrottle();

    // Rate 0 schaltet den Strom ab - der Notausgang waehrend der Show
    Check.that("bei Rate 0 ist nie ein Takt faellig", !t.due(100.0, 0f));
    Check.that("auch spaeter nicht", !t.due(200.0, 0f));
    Check.that("negative Rate ebenfalls nicht", !t.due(300.0, -5f));

    // NaN-Rate darf nicht durchrutschen und jeden Aufruf faellig melden
    ImpulseOscThrottle tn = new ImpulseOscThrottle();
    Check.that("NaN-Rate sendet nicht", !tn.due(100.0, Float.NaN));
    Check.that("und auch beim naechsten Aufruf nicht", !tn.due(200.0, Float.NaN));

    // Der erste Aufruf mit einer Rate ist faellig
    ImpulseOscThrottle t2 = new ImpulseOscThrottle();
    Check.that("erster Aufruf ist faellig", t2.due(100.0, 10f));
    Check.that("unmittelbar danach nicht", !t2.due(100.01, 10f));
    Check.that("kurz vor dem Intervall nicht", !t2.due(100.09, 10f));
    Check.that("genau auf dem Intervall wieder", t2.due(100.10, 10f));
    Check.that("und danach wieder nicht", !t2.due(100.15, 10f));

    // Nach einer langen Pause gibt es EINEN Takt, keinen Schwall
    ImpulseOscThrottle t3 = new ImpulseOscThrottle();
    Check.that("erster Takt", t3.due(0.0, 10f));
    Check.that("nach zehn Sekunden Pause ein Takt", t3.due(10.0, 10f));
    Check.that("aber nicht sofort noch einer", !t3.due(10.01, 10f));

    // Abschalten und wieder einschalten: der naechste Takt kommt sofort
    ImpulseOscThrottle t4 = new ImpulseOscThrottle();
    Check.that("erster Takt", t4.due(0.0, 10f));
    Check.that("abgeschaltet", !t4.due(0.5, 0f));
    Check.that("wieder eingeschaltet ist sofort ein Takt faellig", t4.due(0.51, 10f));

    // Hoehere Rate, kuerzeres Intervall
    ImpulseOscThrottle t5 = new ImpulseOscThrottle();
    Check.that("erster Takt bei 40 Hz", t5.due(0.0, 40f));
    Check.that("nach 20 ms noch nicht", !t5.due(0.020, 40f));
    Check.that("nach 25 ms schon", t5.due(0.025, 40f));

    // ---- Auswahl der energiereichsten ----
    ImpulseOscThrottle sel = new ImpulseOscThrottle();

    Check.eq("maxCount 0 waehlt nichts", 0, sel.select(new float[] { 1f, 2f }, 0).length);
    Check.eq("negatives maxCount waehlt nichts",
        0, sel.select(new float[] { 1f, 2f }, -3).length);
    Check.eq("null waehlt nichts", 0, sel.select(null, 5).length);
    Check.eq("leere Liste waehlt nichts", 0, sel.select(new float[0], 5).length);

    float[] e = { 0.1f, 0.9f, 0.5f, 0.3f, 0.7f };
    int[] top2 = sel.select(e, 2);
    Check.eq("zwei ausgewaehlt", 2, top2.length);
    Check.eq("der energiereichste zuerst", 1, top2[0]);
    Check.eq("dann der zweitstaerkste", 4, top2[1]);

    int[] top3 = sel.select(e, 3);
    Check.eq("drei ausgewaehlt", 3, top3.length);
    Check.eq("dritter ist Index 2", 2, top3[2]);

    // maxCount groesser als die Liste liefert alle, absteigend sortiert
    int[] all = sel.select(e, 99);
    Check.eq("alle ausgewaehlt", 5, all.length);
    Check.eq("Reihenfolge absteigend, Platz 1", 1, all[0]);
    Check.eq("Platz 2", 4, all[1]);
    Check.eq("Platz 3", 2, all[2]);
    Check.eq("Platz 4", 3, all[3]);
    Check.eq("Platz 5", 0, all[4]);

    // Gleichstand: der kleinere Index gewinnt, damit die Auswahl
    // reproduzierbar ist und nicht bei jedem Frame flackert
    float[] tie = { 0.5f, 0.9f, 0.9f, 0.2f };
    int[] tieTop = sel.select(tie, 2);
    Check.eq("bei Gleichstand gewinnt der kleinere Index", 1, tieTop[0]);
    Check.eq("dann der naechste", 2, tieTop[1]);

    // Alle gleich: die Reihenfolge ist die der Indizes
    float[] same = { 1f, 1f, 1f };
    int[] sameSel = sel.select(same, 3);
    Check.eq("alle gleich, erster Index", 0, sameSel[0]);
    Check.eq("alle gleich, zweiter Index", 1, sameSel[1]);
    Check.eq("alle gleich, dritter Index", 2, sameSel[2]);

    // Negative Energien kommen zuletzt, stuerzen aber nicht ab
    float[] neg = { -1f, 0.5f };
    int[] negSel = sel.select(neg, 2);
    Check.eq("positive Energie zuerst", 1, negSel[0]);
    Check.eq("negative danach", 0, negSel[1]);

    // select() liest nur - das Feld des Aufrufers bleibt unveraendert
    float[] untouched = { 0.4f, 0.1f, 0.9f, 0.2f };
    float[] untouchedCopy = untouched.clone();
    sel.select(untouched, 2);
    for (int i = 0; i < untouched.length; i++) {
      Check.near("select() veraendert das Eingabearray nicht an Index " + i,
          untouchedCopy[i], untouched[i], 0.0);
    }

    System.exit(Check.report("ImpulseOscThrottleTest"));
  }
}
