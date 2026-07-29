// Test 1b - der Decoder setzt den LED-Puffer aus den Paketen wieder zusammen.
public class ArtNetDecoderTest {
  static final int[] OCTETS = { 2, 4, 6, 7, 8, 10, 12, 13, 14, 16, 17, 18, 19, 20, 21 };
  static final int LEDS_PER_STRIPE = 600;

  public static void main(String[] args) {
    ArtNetOutput out = new ArtNetOutput(OCTETS, LEDS_PER_STRIPE);
    out.setMasterLevel(1f);

    int numLeds = out.numStripes() * LEDS_PER_STRIPE;
    LedColor[] colors = LedColor.createColorArray(numLeds);
    for (int i = 0; i < numLeds; i++) {
      colors[i].x = ((i) % 251) / 255f;
      colors[i].y = ((i / 251) % 241) / 255f;
      colors[i].z = ((i / 71) % 233) / 255f;
    }

    ArtNetOutput.Frame frame = out.newFrame();
    out.buildFrame(colors, frame);

    int[][] decoded = ArtNetDecoder.decode(frame, OCTETS, LEDS_PER_STRIPE, 2);

    Check.eq("Anzahl rekonstruierter LEDs", numLeds, decoded.length);
    for (int i = 0; i < numLeds; i++) {
      if (decoded[i] == null) {
        Check.that("LED " + i + " hat Daten bekommen", false);
        continue;
      }
      Check.eq("LED " + i + " R", Math.round(colors[i].x * 255f), decoded[i][0]);
      Check.eq("LED " + i + " G", Math.round(colors[i].y * 255f), decoded[i][1]);
      Check.eq("LED " + i + " B", Math.round(colors[i].z * 255f), decoded[i][2]);
    }

    System.exit(Check.report("ArtNetDecoderTest"));
  }
}
