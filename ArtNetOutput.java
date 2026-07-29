// Sendet die LED-Farben als Art-Net an die Pixel2LED-Controller.
//
// Die Firmware adressiert jede LED mit vier Bytes, ein Universum traegt also
// 128 LEDs. Jeder Output beginnt bei DMX-Adresse 1 eines neuen Universums;
// bei 600 LEDs je Output sind das fuenf Universen, von denen das letzte nur
// 88 echte LEDs traegt. Die uebrigen 40 Slots muessen genullt bleiben, sonst
// schreibt die Firmware sie in die ersten LEDs des naechsten Outputs.
//
// Diese Klasse haengt bewusst nur an LedColor und der Java-Standardbibliothek,
// damit der Paketbau ohne Processing-Laufzeit geprueft werden kann.
class ArtNetOutput {

  static final int ARTNET_PORT = 6454;
  static final int CHANNELS_PER_LED = 4;
  static final int LEDS_PER_UNIVERSE = 512 / CHANNELS_PER_LED;   // 128
  static final int OUTPUTS_PER_CONTROLLER = 2;
  static final int DMX_HEADER_LEN = 18;
  static final int DMX_PACKET_LEN = DMX_HEADER_LEN + 512;        // 530
  static final int SYNC_PACKET_LEN = 14;

  // Kanalreihenfolge im DMX-Paket. Das vierte Byte je LED bleibt immer 0.
  // Kommen die Farben vertauscht an, hier drehen: R=2, G=1, B=0.
  static final int R_OFFSET = 0;
  static final int G_OFFSET = 1;
  static final int B_OFFSET = 2;

  final int[] octets;
  final int numLedsPerStripe;
  final int universesPerOutput;

  ArtNetOutput(int[] octets, int numLedsPerStripe) {
    this.octets = octets;
    this.numLedsPerStripe = numLedsPerStripe;
    int channels = numLedsPerStripe * CHANNELS_PER_LED;
    this.universesPerOutput = (channels + 511) / 512;   // aufgerundet
  }

  int numStripes() {
    return octets.length * OUTPUTS_PER_CONTROLLER;
  }

  int universesPerOutput() {
    return universesPerOutput;
  }

  int packetsPerFrame() {
    // je Controller alle Universen plus ein Sync-Paket
    return octets.length * (OUTPUTS_PER_CONTROLLER * universesPerOutput + 1);
  }

  int portAddress(int controllerIndex, int output, int universeInOutput) {
    return octets[controllerIndex] * 100 + output * universesPerOutput + universeInOutput;
  }

  String targetIp(int controllerIndex) {
    return "2.2.2." + octets[controllerIndex];
  }

  // Zuordnungstabelle fuer die Konsole. Gegen das Web-Interface der Controller
  // pruefbar, bevor irgendetwas angeschlossen ist.
  String describeMapping() {
    StringBuilder sb = new StringBuilder();
    sb.append("Art-Net Zuordnung: ").append(octets.length).append(" Controller, ")
      .append(numStripes()).append(" Stripes, ")
      .append(numStripes() * numLedsPerStripe).append(" LEDs, ")
      .append(packetsPerFrame()).append(" Pakete je Frame\n");
    for (int k = 0; k < octets.length; k++) {
      for (int j = 0; j < OUTPUTS_PER_CONTROLLER; j++) {
        int stripe = k * OUTPUTS_PER_CONTROLLER + j;
        int firstLed = stripe * numLedsPerStripe;
        sb.append(String.format(
            "  %-9s Output %d  Universen %d-%d  Stripe %-3d LED %d-%d%n",
            targetIp(k), j,
            portAddress(k, j, 0), portAddress(k, j, universesPerOutput - 1),
            stripe, firstLed, firstLed + numLedsPerStripe - 1));
      }
    }
    return sb.toString();
  }
}
