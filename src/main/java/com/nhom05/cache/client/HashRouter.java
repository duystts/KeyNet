package com.nhom05.cache.client;

import com.nhom05.cache.common.HashUtil;
import com.nhom05.cache.common.ServerConfig.ServerAddress;

import java.util.List;

/**
 * Dinh tuyen key toi Cache Server chinh va server du phong (Issue #5, Module 2).
 *
 * Cong thuc chon server lay tu HashUtil (muc 4 dac ta) de dam bao Client va
 * Replication tinh ra cung 1 ket qua.
 *
 *   primaryIndex(key)          = HashUtil.computeServerIndex(key, N)
 *   replicaIndex(primaryIndex) = HashUtil.replicaIndex(primaryIndex, N)
 */
public final class HashRouter {

    private final List<ServerAddress> servers;

    /**
     * Tao router tu danh sach server trong file cau hinh.
     *
     * @param servers danh sach dia chi cac Cache Server (lay tu ServerConfig.servers())
     * @throws IllegalArgumentException neu danh sach null hoac rong
     */
    public HashRouter(List<ServerAddress> servers) {
        if (servers == null || servers.isEmpty()) {
            throw new IllegalArgumentException(
                    "Danh sach server khong duoc null hoac rong");
        }
        this.servers = List.copyOf(servers);
    }

    /** So luong server trong cluster. */
    public int serverCount() {
        return servers.size();
    }

    /** Danh sach dia chi server, bat bien. */
    public List<ServerAddress> servers() {
        return servers;
    }

    /**
     * Lay dia chi server theo chi so.
     *
     * @throws IndexOutOfBoundsException neu index nam ngoai [0, serverCount())
     */
    public ServerAddress server(int index) {
        if (index < 0 || index >= servers.size()) {
            throw new IndexOutOfBoundsException(
                    "index=" + index + " nam ngoai pham vi [0, " + servers.size() + ")");
        }
        return servers.get(index);
    }

    /**
     * Chi so server chinh phu trach key nay.
     * Goi thang HashUtil, khong tu viet lai cong thuc.
     */
    public int primaryIndex(String key) {
        return HashUtil.computeServerIndex(key, servers.size());
    }

    /**
     * Chi so server du phong cua server chinh: server ke tiep, quay vong ve 0.
     *
     * @throws IndexOutOfBoundsException neu primaryIndex nam ngoai [0, serverCount())
     */
    public int replicaIndex(int primaryIndex) {
        if (primaryIndex < 0 || primaryIndex >= servers.size()) {
            throw new IndexOutOfBoundsException(
                    "primaryIndex=" + primaryIndex + " nam ngoai pham vi [0, " + servers.size() + ")");
        }
        return HashUtil.replicaIndex(primaryIndex, servers.size());
    }
}
