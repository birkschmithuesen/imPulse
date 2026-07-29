// Test 1a - Adressrechnung. Der Paketbau kommt in Task 2 dazu.
public class ArtNetOutputTest {
  static final int[] OCTETS = { 2, 4, 6, 7, 8, 10, 12, 13, 14, 16, 17, 18, 19, 20, 21 };
  static final int LEDS_PER_STRIPE = 600;

  public static void main(String[] args) {
    ArtNetOutput out = new ArtNetOutput(OCTETS, LEDS_PER_STRIPE);

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

    System.exit(Check.report("ArtNetOutputTest"));
  }
}
