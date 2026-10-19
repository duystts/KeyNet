package bai08.bt01;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.nio.charset.StandardCharsets;

/**
 * BAI08_BT01 - May gui (MulticastSender).
 * Gui mot thong diep van ban den nhom multicast 224.1.1.1:4444.
 *
 * Cach chay:  java bai08.bt01.MulticastSender ["noi dung"] [diaChiNhom] [cong]
 * Gui chuoi "exit" de bao cac MulticastReceiver roi nhom va ket thuc.
 */
public class MulticastSender {

    public static final String GROUP = "224.1.1.1";
    public static final int PORT = 4444;

    public static void main(String[] args) {
        String message = args.length > 0 ? args[0] : "Xin chào!";
        String groupAddr = args.length > 1 ? args[1] : GROUP;
        int port = args.length > 2 ? Integer.parseInt(args[2]) : PORT;

        // try-with-resources: socket tu dong dong khi ket thuc
        try (MulticastSocket socket = new MulticastSocket()) {
            InetAddress group = InetAddress.getByName(groupAddr);
            if (!group.isMulticastAddress()) {
                System.err.println(groupAddr + " khong phai dia chi multicast (224.0.0.0 - 239.255.255.255)");
                return;
            }
            // TTL = 1: goi tin chi di trong mang noi bo, khong vuot qua router
            socket.setTimeToLive(1);

            // Dung UTF-8 de gui dung tieng Viet co dau
            byte[] buffer = message.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, group, port);
            socket.send(packet);

            System.out.println("Da gui den " + groupAddr + ":" + port + " -> " + message);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
