// Die acht Auslieferungsfarben des Modus "Stripe-Farben".
//
// Der Punkt dieser Suite ist eng, aber real: die Tabelle wurde am 2026-08-01
// aus einem Literal-Array in LedNetworkTransportEffect herausgeloest, damit
// 24 OSC-Parameter ihre Startwerte daraus beziehen koennen. Beim Uebertragen
// von Hand ist ein vertauschter oder verrutschter Kanal genau die Sorte
// Fehler, die erst an der Installation auffaellt -- und dann als "die Farben
// stimmen irgendwie nicht mehr", ohne Fehlermeldung.
//
// Deshalb stehen die Zahlen hier NOCH EINMAL, unabhaengig abgetippt aus der
// Fassung vor der Aenderung (git show 8aba549:LedNetworkTransportEffect.java,
// Zeile 27f). Eine Pruefung, die dieselbe Tabelle gegen sich selbst haelt,
// waere keine.
public class StripeColorDefaultsTest {

  public static void main(String[] args) throws Exception {
    // ---- Acht Slots, nicht mehr und nicht weniger ----
    // Der Effekt bildet Stripe -> Slot per Modulo ab; eine andere Zahl waere
    // ein anderes Muster ueber die 30 Stripes.
    Check.eq("acht Slots", 8, StripeColorDefaults.COUNT);

    // ---- Die historischen Werte, Kanal fuer Kanal ----
    int[][] erwartet = {
      {  68,   0,  62 },
      { 189, 103,   0 },
      { 236, 204,   0 },
      { 221,  65,   8 },
      { 187, 213,  67 },
      { 126, 201, 232 },
      { 210,  39,  45 },
      { 234, 147,  44 },
    };
    for (int slot = 0; slot < StripeColorDefaults.COUNT; slot++) {
      for (int c = 0; c < 3; c++) {
        Check.eq("Slot " + slot + " Kanal " + c + " in 0..255",
            erwartet[slot][c], StripeColorDefaults.rgb255(slot, c));
      }
    }

    // ---- Umrechnung nach 0..1 ----
    // Die Farbwerte des Projekts laufen durchgehend in 0..1 (siehe LedColor);
    // die Tabelle notiert sie aber in 0..255, weil sie so gemessen wurden.
    for (int slot = 0; slot < StripeColorDefaults.COUNT; slot++) {
      float[] rgb = StripeColorDefaults.rgb(slot);
      Check.eq("rgb(slot) hat drei Kanaele", 3, rgb.length);
      for (int c = 0; c < 3; c++) {
        Check.near("Slot " + slot + " Kanal " + c + " als 0..1",
            erwartet[slot][c] / 255.0, rgb[c], 1e-6);
        Check.near("rgb(slot) und rgb(slot, c) sind dasselbe",
            rgb[c], StripeColorDefaults.rgb(slot, c), 1e-9);
        Check.that("Kanal liegt in 0..1",
            rgb[c] >= 0f && rgb[c] <= 1f);
      }
    }

    // ---- Kein Slot ist schwarz ----
    // Ein schwarzer Slot waere im Modus "Stripe-Farben" ein Stripe, auf dem
    // Impulse unsichtbar sind -- ein Loch im Netz ohne Fehlermeldung. In der
    // Auslieferungstabelle gibt es keinen; wer einen einfuegt, soll hier
    // stolpern und es bewusst tun.
    for (int slot = 0; slot < StripeColorDefaults.COUNT; slot++) {
      float[] rgb = StripeColorDefaults.rgb(slot);
      Check.that("Slot " + slot + " ist nicht schwarz",
          rgb[0] + rgb[1] + rgb[2] > 0f);
    }

    System.exit(Check.report("StripeColorDefaultsTest"));
  }
}
