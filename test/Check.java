// Minimale Prüfhilfen. Kein Testframework installiert, deshalb von Hand.
class Check {
  static int checks = 0;
  static int failures = 0;

  static void eq(String what, long expected, long actual) {
    checks++;
    if (expected != actual) {
      failures++;
      if (failures <= 20) {
        System.out.println("  FEHLER " + what + ": erwartet " + expected + ", war " + actual);
      }
    }
  }

  static void eq(String what, String expected, String actual) {
    checks++;
    if (!expected.equals(actual)) {
      failures++;
      if (failures <= 20) {
        System.out.println("  FEHLER " + what + ": erwartet \"" + expected + "\", war \"" + actual + "\"");
      }
    }
  }

  static void that(String what, boolean condition) {
    checks++;
    if (!condition) {
      failures++;
      if (failures <= 20) {
        System.out.println("  FEHLER " + what);
      }
    }
  }

  static int report(String suite) {
    if (failures == 0) {
      System.out.println(suite + ": " + checks + " Pruefungen, alle bestanden");
      return 0;
    }
    System.out.println(suite + ": " + checks + " Pruefungen, " + failures + " Fehler");
    return 1;
  }
}
