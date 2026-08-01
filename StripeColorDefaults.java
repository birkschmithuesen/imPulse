// Die acht Auslieferungsfarben des Modus "Stripe-Farben".
//
// Bis 2026-08-01 stand diese Tabelle als Literal-Array `stripeColorMapping`
// mitten in LedNetworkTransportEffect und war weder per OSC noch im Web-UI
// erreichbar. Jetzt registriert der Effekt daraus 24
// RemoteControlledFloatParameter (/net/impulse/stripeColor/<0..7>/{r,g,b});
// diese Klasse ist nur noch die Quelle ihrer Startwerte.
//
// Sie steht bewusst ALLEIN in einer eigenen Datei, ohne oscP5- und
// netP5-Abhaengigkeit: LedNetworkTransportEffect laesst sich von test/run.sh
// nicht uebersetzen, diese Klasse schon. Genau darum geht es -- die Zahlen von
// Hand aus "68/255f" in eine Konstantentabelle zu uebertragen ist die Sorte
// Aenderung, bei der ein vertauschter Kanal niemandem auffaellt, bis die
// Installation laeuft. StripeColorDefaultsTest haelt sie fest.
//
// Warum es KEINE data/stripeColors.txt gibt: Stripe-Farben stehen damit an
// genau zwei Orten (Code-Default und Preset) statt an dreien. Eine Datei
// daneben wuerde einem geladenen Preset widersprechen, ohne dass das
// auffaellt -- und Preset-Persistenz, Live-Aenderung und der Eintrag in
// remoteSettings.txt kommen als normale Parameter ohnehin geschenkt.
class StripeColorDefaults {

  // Anzahl der Slots. Der Effekt bildet Stripe -> Slot per Modulo ab: bei 30
  // Stripes wiederholt sich das Muster also alle acht.
  static final int COUNT = 8;

  // Kanalwerte in 0..255, genau wie sie bis 2026-08-01 in
  // LedNetworkTransportEffect standen. Die Umrechnung nach 0..1 passiert in
  // rgb() -- an einer Stelle, statt 24-mal "/255f" im Quelltext.
  private static final int[][] RGB255 = {
    {  68,   0,  62 },
    { 189, 103,   0 },
    { 236, 204,   0 },
    { 221,  65,   8 },
    { 187, 213,  67 },
    { 126, 201, 232 },
    { 210,  39,  45 },
    { 234, 147,  44 },
  };

  /** Kanal (0=r, 1=g, 2=b) des Slots als 0..1. */
  static float rgb(int slot, int channel) {
    return RGB255[slot][channel] / 255f;
  }

  /** Alle drei Kanaele eines Slots als 0..1, in der Reihenfolge r, g, b. */
  static float[] rgb(int slot) {
    return new float[] { rgb(slot, 0), rgb(slot, 1), rgb(slot, 2) };
  }

  /** Der Kanalwert in 0..255, wie er in der Tabelle steht. */
  static int rgb255(int slot, int channel) {
    return RGB255[slot][channel];
  }

  private StripeColorDefaults() {
  }
}
