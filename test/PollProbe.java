// Test 1d - fragt die Controller nach ihrem eigenen Befinden.
//
// Auf OpPoll antwortet die Firmware mit einem ArtPollReply, in dessen
// NodeReport ein Statusstring der Form
//   numOuts;2;numUniPOut;5;temp;41.2;fps;39.8;uUniPF;10.0;
// steht. fps ist die vom Controller gemessene Rate eintreffender Sync-Pakete,
// uUniPF die geglaettete Anzahl empfangener Universen je Frame. Erwartet
// werden rund 40 und 10.0, waehrend der Sketch oder TimingProbe laeuft.
public class PollProbe {
  static final int[] OCTETS = { 2, 4, 6, 7, 8, 10, 12, 13, 14, 16, 17, 18, 19, 20, 21 };
  static final int ARTNET_PORT = 6454;

  public static void main(String[] args) throws Exception {
    java.net.DatagramSocket socket = new java.net.DatagramSocket();
    socket.setSoTimeout(1500);

    byte[] poll = new byte[14];
    poll[0] = 'A'; poll[1] = 'r'; poll[2] = 't'; poll[3] = '-';
    poll[4] = 'N'; poll[5] = 'e'; poll[6] = 't'; poll[7] = 0;
    poll[8] = 0x00; poll[9] = 0x20;   // OpPoll 0x2000, little-endian
    poll[10] = 0; poll[11] = 14;      // ProtVer
    poll[12] = 0x02;                  // TalkToMe: antworte bei Aenderung
    poll[13] = 0;                     // Priority

    for (int octet : OCTETS) {
      String ip = "2.2.2." + octet;
      java.net.InetAddress addr = java.net.InetAddress.getByName(ip);
      socket.send(new java.net.DatagramPacket(poll, poll.length, addr, ARTNET_PORT));

      byte[] in = new byte[600];
      java.net.DatagramPacket reply = new java.net.DatagramPacket(in, in.length);
      try {
        socket.receive(reply);
      } catch (java.net.SocketTimeoutException e) {
        System.out.printf("%-9s keine Antwort%n", ip);
        Check.that(ip + " antwortet", false);
        continue;
      }

      String text = new String(in, 0, reply.getLength(), "ISO-8859-1");
      int start = text.indexOf("numOuts;");
      if (start < 0) {
        System.out.printf("%-9s Antwort ohne Statusbericht%n", ip);
        Check.that(ip + " liefert Statusbericht", false);
        continue;
      }
      int end = text.indexOf('\0', start);
      String report = end > start ? text.substring(start, end) : text.substring(start);

      double fps = field(report, "fps");
      double uni = field(report, "uUniPF");
      System.out.printf("%-9s %s%n", ip, report);

      Check.that(ip + " meldet 38-42 fps", fps >= 38.0 && fps <= 42.0);
      Check.that(ip + " meldet 9.5-10.5 Universen je Frame", uni >= 9.5 && uni <= 10.5);
    }

    socket.close();
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
