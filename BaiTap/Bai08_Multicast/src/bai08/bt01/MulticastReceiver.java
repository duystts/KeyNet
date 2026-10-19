package bai08.bt01;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;

/**
 * BAI08_BT01 - May nhan (MulticastReceiver).
 * Tham gia nhom multicast 224.1.1.1:4444, in cac thong diep nhan duoc ra man hinh.
 * Nhan duoc "exit" thi roi nhom (leaveGroup) va dong socket.
 *
 * Cach chay:  java bai08.bt01.MulticastReceiver [diaChiNhom] [cong]
 * Chay nhieu cua so (hoac nhieu may) MulticastReceiver TRUOC, sau do moi chay MulticastSender.
 */
public class MulticastReceiver {

    public static void main(String[] args) {
        String groupAddr = args.length > 0 ? args[0] : MulticastSender.GROUP;
        int port = args.length > 1 ? Integer.parseInt(args[1]) : MulticastSender.PORT;

        // MulticastSocket mac dinh bat SO_REUSEADDR nen nhieu receiver tren cung
        // mot may van bind duoc cung mot cong 4444
        try (MulticastSocket socket = new MulticastSocket(port)) {
            InetAddress group = InetAddress.getByName(groupAddr);
            InetSocketAddress groupSocketAddr = new InetSocketAddress(group, port);
            // null = dung card mang mac dinh cua he dieu hanh
            NetworkInterface netIf = null;

            socket.joinGroup(groupSocketAddr, netIf);
            System.out.println("Da tham gia nhom " + groupAddr + ":" + port + ". Dang cho thong diep...");

            byte[] buffer = new byte[1024];
            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet); // block cho den khi co goi tin

                String received = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                System.out.println("Nhan tu " + packet.getAddress().getHostAddress() + ":" + packet.getPort()
                        + " -> " + received);

                if (received.trim().equalsIgnoreCase("exit")) {
                    break;
                }
            }

            socket.leaveGroup(groupSocketAddr, netIf); // roi khoi nhom
            System.out.println("Da roi nhom " + groupAddr + ". Ket thuc.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
