// Zeiger auf einen Eintrag der Kreuzungsliste, damit sich beim Kalibrieren
// eine einzelne falsch gesetzte Kreuzung heraussuchen und loeschen laesst.
// -1 bedeutet "nichts gewaehlt". Bewusst ohne Processing- und Netzabhaengigkeit,
// damit das Klemmen bei schrumpfender Liste pruefbar ist (siehe
// test/NodeSelectionTest.java) - ein Griff daneben wuerde beim Zeichnen mitten
// in der Aufnahme am Netz eine Exception werfen.
class NodeSelection {

  private int index = -1;

  int index() { return index; }

  boolean hasSelection() { return index >= 0; }

  void clear() { index = -1; }

  // Nach vorn. Am Ende der Liste bleibt die Auswahl stehen - ein Umlauf wuerde
  // beim schnellen Durchblaettern unbemerkt wieder von vorn anfangen.
  void next(int size) {
    if (size <= 0) { index = -1; return; }
    if (index < 0) { index = 0; return; }
    if (index < size - 1) index++;
  }

  // Nach hinten. Aus dem Nichts heraus greift prev() den letzten Eintrag - das
  // ist der gerade aufgenommene. Vom ersten Eintrag aus hebt prev() die
  // Auswahl auf, damit man sie ohne Umweg wieder loswird.
  void prev(int size) {
    if (size <= 0) { index = -1; return; }
    if (index < 0) { index = size - 1; return; }
    index--;
  }

  // Nach jeder Aenderung der Liste aufzurufen: haelt die Auswahl im gueltigen
  // Bereich, ohne sie unnoetig zu verschieben.
  void clampTo(int size) {
    if (size <= 0) { index = -1; return; }
    if (index >= size) index = size - 1;
  }
}
