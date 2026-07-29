// Test 1d - fragt die Controller nach ihrem eigenen Befinden.
//
// Auf OpPoll antwortet die Firmware mit einem ArtPollReply, in dessen
// NodeReport ein Statusstring der Form
//   numOuts;2;numUniPOut;5;temp;41.2;fps;39.8;uUniPF;10.0;
// steht. fps ist die vom Controller gemessene Rate eintreffender Sync-Pakete,
// uUniPF die geglaettete Anzahl empfangener Universen je Frame. Erwartet
// werden rund 40 und 10.0, waehrend der Sketch oder TimingProbe laeuft.
//
// Die Firmware schickt das ArtPollReply nicht an den Absenderport der
// Anfrage, sondern - wie von der Art-Net-Spezifikation vorgeschrieben -
// immer als Broadcast an Port 6454. Die Sonde muss deshalb selbst auf
// diesem Port lauschen, sonst kommt nie eine Antwort an, unabhaengig davon,
// ob das Netz in Ordnung ist.
//
// Weil alle Antworten als Broadcast an alle Teilnehmer gehen, koennen sie in
// beliebiger Reihenfolge eintreffen. Deshalb werden zuerst alle 15 Anfragen
// verschickt und danach die Antworten gesammelt und ueber die Absenderadresse
// den Controllern zugeordnet - das naechste eintreffende Datagramm blind dem
// zuletzt gefragten Controller zuzuschreiben, wuerde Werte dem falschen
// Oktett anhaengen.
public class PollProbe {
  static final int[] OCTETS = { 2, 4, 6, 7, 8, 10, 12, 13, 14, 16, 17, 18, 19, 20, 21 };
  static final int ARTNET_PORT = 6454;
  static final long COLLECT_IDLE_MILLIS = 3000;   // Frist ohne neue Antwort, bis das Sammeln endet

  public static void main(String[] args) throws Exception {
    java.net.DatagramSocket socket;
    try {
      socket = new java.net.DatagramSocket(null);
      socket.setReuseAddress(true);
      socket.bind(new java.net.InetSocketAddress(ARTNET_PORT));
    } catch (java.io.IOException e) {
      System.out.println("Port " + ARTNET_PORT + " liess sich nicht belegen - "
          + "vermutlich haelt ein anderes Art-Net-Programm auf diesem Rechner "
          + "den Port besetzt (" + e.getMessage() + ").");
      System.exit(1);
      return;
    }
    socket.setSoTimeout(500);

    byte[] poll = new byte[14];
    poll[0] = 'A'; poll[1] = 'r'; poll[2] = 't'; poll[3] = '-';
    poll[4] = 'N'; poll[5] = 'e'; poll[6] = 't'; poll[7] = 0;
    poll[8] = 0x00; poll[9] = 0x20;   // OpPoll 0x2000, little-endian
    poll[10] = 0; poll[11] = 14;      // ProtVer
    poll[12] = 0x02;                  // TalkToMe: antworte bei Aenderung
    poll[13] = 0;                     // Priority

    // Schritt 1: erst alle Anfragen verschicken.
    java.util.Map<String, java.net.InetAddress> targets = new java.util.LinkedHashMap<>();
    for (int octet : OCTETS) {
      String ip = "2.2.2." + octet;
      targets.put(ip, java.net.InetAddress.getByName(ip));
    }
    for (java.net.InetAddress addr : targets.values()) {
      socket.send(new java.net.DatagramPacket(poll, poll.length, addr, ARTNET_PORT));
    }

    // Schritt 2: danach Antworten sammeln, bis eine Weile lang keine neue
    // mehr eintrifft, und ueber die Absenderadresse zuordnen.
    java.util.Map<String, String> reports = new java.util.LinkedHashMap<>();
    java.util.Set<String> repliedWithoutReport = new java.util.LinkedHashSet<>();
    byte[] in = new byte[600];
    long lastReceiveTime = System.currentTimeMillis();
    while (System.currentTimeMillis() - lastReceiveTime < COLLECT_IDLE_MILLIS) {
      java.net.DatagramPacket reply = new java.net.DatagramPacket(in, in.length);
      try {
        socket.receive(reply);
      } catch (java.net.SocketTimeoutException e) {
        continue;
      }

      int len = reply.getLength();
      boolean isArtPollReply = len >= 12
          && in[0] == 'A' && in[1] == 'r' && in[2] == 't' && in[3] == '-'
          && in[4] == 'N' && in[5] == 'e' && in[6] == 't' && in[7] == 0
          && in[8] == 0x00 && in[9] == 0x21;   // OpPollReply 0x2100, little-endian
      if (!isArtPollReply) {
        continue;   // kein gueltiges ArtPollReply - verwerfen
      }

      String sender = reply.getAddress().getHostAddress();
      if (!targets.containsKey(sender)) {
        System.out.printf("%-9s Antwort von Fremdgeraet ignoriert (nicht zugeordnet)%n", sender);
        continue;
      }

      lastReceiveTime = System.currentTimeMillis();
      String text = new String(in, 0, len, "ISO-8859-1");
      int start = text.indexOf("numOuts;");
      if (start < 0) {
        repliedWithoutReport.add(sender);
        continue;
      }
      int end = text.indexOf('\0', start);
      String report = end > start ? text.substring(start, end) : text.substring(start);
      reports.put(sender, report);   // je Controller zaehlt der zuletzt empfangene Bericht
    }

    socket.close();

    // Schritt 3: erst jetzt auswerten.
    for (String ip : targets.keySet()) {
      String report = reports.get(ip);
      if (report == null) {
        if (repliedWithoutReport.contains(ip)) {
          System.out.printf("%-9s Antwort ohne Statusbericht%n", ip);
          Check.that(ip + " liefert Statusbericht", false);
        } else {
          System.out.printf("%-9s keine Antwort%n", ip);
          Check.that(ip + " antwortet", false);
        }
        continue;
      }

      double fps = field(report, "fps");
      double uni = field(report, "uUniPF");
      System.out.printf("%-9s %s%n", ip, report);

      Check.that(ip + " meldet 38-42 fps", fps >= 38.0 && fps <= 42.0);
      Check.that(ip + " meldet 9.5-10.5 Universen je Frame", uni >= 9.5 && uni <= 10.5);
    }

    System.exit(Check.report("PollProbe"));
  }

  static double field(String report, String name) {
    int i = report.indexOf(name + ";");
    if (i < 0) return -1;
    int from = i + name.length() + 1;
    int to = report.indexOf(';', from);
    if (to < 0) to = report.length();
    try {
      return Double.parseDouble(report.substring(from, to));
    } catch (NumberFormatException e) {
      return -1;
    }
  }
}
