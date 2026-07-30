import java.util.Arrays;
import java.util.Comparator;

// Entscheidet, wann ein Sendetakt fuer /net/impulse faellig ist und welche
// Impulse hineinkommen.
//
// Warum eine eigene Klasse: LedNetworkTransportEffect haengt an oscP5 und
// laesst sich von test/run.sh nicht uebersetzen. Diese Entscheidung soll aber
// geprueft sein - deshalb liegt sie hier, ohne Abhaengigkeit auf processing,
// oscP5 oder netP5. Genau das Muster, aus dem in diesem Projekt die Logik aus
// den Effekten herausgezogen ist.
class ImpulseOscThrottle {

  // NEGATIVE_INFINITY, damit der erste Aufruf faellig ist - ein Nullwert
  // waere bei einer Zeitbasis aus System.currentTimeMillis()/1000 kein
  // brauchbarer Startpunkt.
  private double lastSend = Double.NEGATIVE_INFINITY;

  // Toleriert nur den Fehlbetrag von wenigen Femtosekunden, den ein
  // dezimales Intervall-Literal in einem Test durch die Bitdarstellung von
  // double bekommt (z.B. 100.10 - 100.0 wird als 0.09999999999999432
  // dargestellt, nicht 0.1). Bei den Zeitbasen, mit denen due() im Betrieb
  // tatsaechlich aufgerufen wird - System.currentTimeMillis()/1000, also
  // Groessenordnung 1.75e9, wo ein ULP schon 2.384e-7s betraegt, 238x groesser
  // als EPSILON - kann dieser Wert keine Sendeentscheidung verschieben; er
  // ist bewusst so klein gewaehlt, dass er das nie kann. Kein Wert zum
  // Hochdrehen, falls irgendwo im Betrieb ein Takt "zu frueh" wirkt - das
  // Problem liegt dann woanders.
  private static final double EPSILON = 1e-9;

  // true, wenn seit dem letzten Takt mindestens 1/rateHz Sekunden vergangen
  // sind.
  //
  // rateHz <= 0 schaltet den Strom ab und setzt zurueck, damit beim
  // Wiedereinschalten sofort ein Takt kommt statt erst nach einem Intervall.
  // Die Bedingung ist bewusst als !(rateHz > 0f) formuliert, nicht als
  // rateHz <= 0f: bei NaN ist jeder direkte Vergleich mit <= false, das
  // wuerde NaN durchlassen, aus interval wuerde NaN und jeder Aufruf meldete
  // faellig - ein ungedrosselter Strom. !(rateHz > 0f) faengt NaN mit ab.
  //
  // Holt bewusst NICHT nach: nach einer langen Pause - Fenster im
  // Hintergrund, Rechner beschaeftigt - gibt es EINEN Takt, nicht einen
  // Schwall aufgesparter Nachrichten.
  boolean due(double nowSeconds, float rateHz) {
    if (!(rateHz > 0f)) {
      lastSend = Double.NEGATIVE_INFINITY;
      return false;
    }
    double interval = 1.0 / rateHz;
    if (nowSeconds - lastSend < interval - EPSILON) {
      return false;
    }
    lastSend = nowSeconds;
    return true;
  }

  // Indizes der energiereichsten Impulse, absteigend nach Energie. Bei
  // gleicher Energie gewinnt der kleinere Index - ohne diese Regel waere die
  // Auswahl von der Sortierreihenfolge abhaengig und die Drohnen wuerden bei
  // gleich starken Impulsen von Takt zu Takt flackern.
  int[] select(float[] energies, int maxCount) {
    if (energies == null || maxCount <= 0 || energies.length == 0) {
      return new int[0];
    }
    int n = energies.length;
    int take = maxCount < n ? maxCount : n;
    Integer[] order = new Integer[n];
    for (int i = 0; i < n; i++) {
      order[i] = Integer.valueOf(i);
    }
    final float[] e = energies;
    Arrays.sort(order, new Comparator<Integer>() {
      public int compare(Integer a, Integer b) {
        float ea = e[a.intValue()];
        float eb = e[b.intValue()];
        if (ea > eb) {
          return -1;
        }
        if (ea < eb) {
          return 1;
        }
        return a.intValue() - b.intValue();
      }
    });
    int[] result = new int[take];
    for (int i = 0; i < take; i++) {
      result[i] = order[i].intValue();
    }
    return result;
  }
}
