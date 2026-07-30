// Test 1a - Adressrechnung. Der Paketbau kommt in Task 2 dazu.
public class ArtNetOutputTest {
  static final int[] OCTETS = { 2, 4, 6, 7, 8, 10, 12, 13, 14, 16, 17, 18, 19, 20, 21 };
  static final int LEDS_PER_STRIPE = 600;

  public static void main(String[] args) {
    ArtNetOutput out = new ArtNetOutput(OCTETS, LEDS_PER_STRIPE);

    // ---- Auslieferungspegel: Working-State-Default (Birk, 2026-07-30) ----
    // Master-Pegel startet mit vollem Pegel (Working State fuer den Live-
    // Betrieb) - setMasterLevel klemmt weiterhin niemals einen Wert
    // ausserhalb 0..1 durch.
    Check.that("Auslieferungspegel ist 1.0", Math.abs(out.getMasterLevel() - 1.0f) < 0.0001f);
    out.setMasterLevel(-5f);
    Check.that("setMasterLevel klemmt negative Werte auf 0", out.getMasterLevel() == 0f);
    out.setMasterLevel(5f);
    Check.that("setMasterLevel klemmt Werte ueber 1 auf 1", out.getMasterLevel() == 1f);
    out.setMasterLevel(1.0f); // zurueck auf den Auslieferungswert fuer die folgenden Pruefungen

    Check.eq("Anzahl Stripes", 30, out.numStripes());
    Check.eq("Universen je Output", 5, out.universesPerOutput());
    // 15 Controller * (2 Outputs * 5 Universen) + 15 Sync
    Check.eq("Pakete je Frame", 165, out.packetsPerFrame());

    // Konvention: Start-Universum = Oktett * 100
    Check.eq("Controller 0, Output 0, Universum 0", 200, out.portAddress(0, 0, 0));
    Check.eq("Controller 0, Output 0, Universum 4", 204, out.portAddress(0, 0, 4));
    Check.eq("Controller 0, Output 1, Universum 0", 205, out.portAddress(0, 1, 0));
    Check.eq("Controller 0, Output 1, Universum 4", 209, out.portAddress(0, 1, 4));
    // Oktett 10 -> 1000, der Fall den artnet4j nicht adressieren konnte
    Check.eq("Controller 5 (Oktett 10)", 1000, out.portAddress(5, 0, 0));
    Check.eq("Controller 14 (Oktett 21)", 2109, out.portAddress(14, 1, 4));

    Check.eq("Ziel-IP Controller 0", "2.2.2.2", out.targetIp(0));
    Check.eq("Ziel-IP Controller 5", "2.2.2.10", out.targetIp(5));

    // Die Zuordnungstabelle nennt jeden Stripe genau einmal
    String table = out.describeMapping();
    for (int s = 0; s < 30; s++) {
      Check.that("Tabelle nennt Stripe " + s, table.contains("Stripe " + s + " "));
    }

    // ---- Test 1a: Paketbau byte-genau ----
    // Master-Pegel auf 1, sonst wird alles mit 0.1 skaliert und die
    // erwarteten Bytes stimmen nicht.
    out.setMasterLevel(1f);

    int numLeds = out.numStripes() * LEDS_PER_STRIPE;
    LedColor[] colors = LedColor.createColorArray(numLeds);
    // Muster: jede LED traegt ihren globalen Index, aufgeteilt auf drei Kanaele.
    for (int i = 0; i < numLeds; i++) {
      colors[i].x = ((i) % 251) / 255f;
      colors[i].y = ((i / 251) % 241) / 255f;
      colors[i].z = ((i / 71) % 233) / 255f;
    }

    ArtNetOutput.Frame frame = out.newFrame();
    out.buildFrame(colors, frame);

    Check.eq("Paketanzahl im Frame", 165, frame.data.length);

    int p = 0;
    for (int k = 0; k < OCTETS.length; k++) {
      for (int j = 0; j < 2; j++) {
        int stripe = k * 2 + j;
        for (int u = 0; u < 5; u++) {
          byte[] buf = frame.data[p];
          String where = "Controller " + k + " Output " + j + " Universum " + u;

          Check.eq(where + " Laenge", 530, frame.lengths[p]);
          Check.eq(where + " Ziel", out.targetIp(k), frame.targets[p]);

          Check.that(where + " Kennung", buf[0] == 'A' && buf[1] == 'r' && buf[2] == 't'
              && buf[3] == '-' && buf[4] == 'N' && buf[5] == 'e' && buf[6] == 't' && buf[7] == 0);
          Check.eq(where + " OpCode lo", 0x00, buf[8] & 0xFF);
          Check.eq(where + " OpCode hi", 0x50, buf[9] & 0xFF);
          Check.eq(where + " ProtVer hi", 0, buf[10] & 0xFF);
          Check.eq(where + " ProtVer lo", 14, buf[11] & 0xFF);
          Check.eq(where + " Physical", 0, buf[13] & 0xFF);

          int addr = out.portAddress(k, j, u);
          Check.eq(where + " SubUni", addr & 0xFF, buf[14] & 0xFF);
          Check.eq(where + " Net", (addr >> 8) & 0x7F, buf[15] & 0xFF);
          Check.eq(where + " Laenge hi", 0x02, buf[16] & 0xFF);
          Check.eq(where + " Laenge lo", 0x00, buf[17] & 0xFF);

          for (int i = 0; i < 128; i++) {
            int inStripe = u * 128 + i;
            int o = 18 + i * 4;
            Check.eq(where + " Byte 4 bei LED " + i, 0, buf[o + 3] & 0xFF);
            if (inStripe < LEDS_PER_STRIPE) {
              LedColor c = colors[stripe * LEDS_PER_STRIPE + inStripe];
              Check.eq(where + " R bei LED " + i, Math.round(c.x * 255f), buf[o + ArtNetOutput.R_OFFSET] & 0xFF);
              Check.eq(where + " G bei LED " + i, Math.round(c.y * 255f), buf[o + ArtNetOutput.G_OFFSET] & 0xFF);
              Check.eq(where + " B bei LED " + i, Math.round(c.z * 255f), buf[o + ArtNetOutput.B_OFFSET] & 0xFF);
            } else {
              // die 40 Reserve-Slots des letzten Universums je Output
              Check.eq(where + " Reserve R bei LED " + i, 0, buf[o + 0] & 0xFF);
              Check.eq(where + " Reserve G bei LED " + i, 0, buf[o + 1] & 0xFF);
              Check.eq(where + " Reserve B bei LED " + i, 0, buf[o + 2] & 0xFF);
            }
          }
          p++;
        }
      }
      // nach den zehn Universen genau ein Sync-Paket an denselben Controller
      byte[] sync = frame.data[p];
      Check.eq("Sync Laenge Controller " + k, 14, frame.lengths[p]);
      Check.eq("Sync Ziel Controller " + k, out.targetIp(k), frame.targets[p]);
      Check.eq("Sync OpCode lo", 0x00, sync[8] & 0xFF);
      Check.eq("Sync OpCode hi", 0x52, sync[9] & 0xFF);
      Check.eq("Sync ProtVer lo", 14, sync[11] & 0xFF);
      p++;
    }
    Check.eq("alle Pakete geprueft", 165, p);

    // Master-Pegel wirkt
    out.setMasterLevel(0.5f);
    LedColor[] white = LedColor.createColorArray(numLeds);
    for (int i = 0; i < numLeds; i++) { white[i].x = 1f; white[i].y = 1f; white[i].z = 1f; }
    out.buildFrame(white, frame);
    Check.eq("Master-Pegel 0.5 auf Weiss", 128, frame.data[0][18] & 0xFF);
    out.setMasterLevel(1f);

    System.exit(Check.report("ArtNetOutputTest"));
  }
}
