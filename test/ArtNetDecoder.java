// Rechnet die Abbildung aus Sicht des Controllers nach: aus SubUni und Net
// wird die Port-Adresse gebildet, daraus ueber das Start-Universum der Port,
// daraus Output und Universum innerhalb des Outputs. Bewusst ein anderer
// Rechenweg als im Sender.
class ArtNetDecoder {

  static int[][] decode(ArtNetOutput.Frame frame, int[] octets,
                        int numLedsPerStripe, int outputsPerController) {
    int channels = numLedsPerStripe * ArtNetOutput.CHANNELS_PER_LED;
    int universesPerOutput = (channels + 511) / 512;
    int numStripes = octets.length * outputsPerController;
    int[][] result = new int[numStripes * numLedsPerStripe][];

    int syncCount = 0;

    for (int p = 0; p < frame.data.length; p++) {
      byte[] buf = frame.data[p];
      int opCode = (buf[8] & 0xFF) | ((buf[9] & 0xFF) << 8);

      if (opCode == 0x5200) {
        syncCount++;
        continue;
      }
      if (opCode != 0x5000) {
        throw new IllegalStateException("Unbekannter OpCode " + opCode + " in Paket " + p);
      }

      int portAddress = (buf[14] & 0xFF) | ((buf[15] & 0x7F) << 8);

      // welcher Controller? ueber das Ziel, nicht ueber die Adresse
      int controller = -1;
      for (int k = 0; k < octets.length; k++) {
        if (frame.targets[p].equals("2.2.2." + octets[k])) { controller = k; break; }
      }
      if (controller < 0) {
        throw new IllegalStateException("Ziel " + frame.targets[p] + " gehoert zu keinem Controller");
      }

      int port = portAddress - octets[controller] * 100;
      if (port < 0 || port >= outputsPerController * universesPerOutput) {
        throw new IllegalStateException("Port " + port + " ausserhalb des Bereichs bei Paket " + p);
      }

      int output = port / universesPerOutput;
      int universeInOutput = port % universesPerOutput;
      int stripe = controller * outputsPerController + output;

      for (int i = 0; i < ArtNetOutput.LEDS_PER_UNIVERSE; i++) {
        int inStripe = universeInOutput * ArtNetOutput.LEDS_PER_UNIVERSE + i;
        if (inStripe >= numLedsPerStripe) {
          continue;   // Reserve-Slots, tragen keine Nutzdaten
        }
        int o = ArtNetOutput.DMX_HEADER_LEN + i * ArtNetOutput.CHANNELS_PER_LED;
        int led = stripe * numLedsPerStripe + inStripe;
        if (result[led] != null) {
          throw new IllegalStateException("LED " + led + " wurde doppelt beschrieben");
        }
        result[led] = new int[] {
            buf[o + ArtNetOutput.R_OFFSET] & 0xFF,
            buf[o + ArtNetOutput.G_OFFSET] & 0xFF,
            buf[o + ArtNetOutput.B_OFFSET] & 0xFF
        };
      }
    }

    if (syncCount != octets.length) {
      throw new IllegalStateException("Erwartet " + octets.length + " Sync-Pakete, gezaehlt " + syncCount);
    }
    return result;
  }
}
