package com.nhom05.cache.client;

import com.nhom05.cache.common.HashUtil;
import com.nhom05.cache.common.ServerConfig.ServerAddress;

import java.util.List;
import java.util.Objects;

/**
 * Module 2 - Hashing Router.
 *
 * Tinh toán Server chính va Server dự phòng chịu trách nhiệm cho 1 key
 * dựa theo danh sách Cache Server va công thức hashing:
 *     serverIndex = |hash(key)| mod N
 *     replicaIndex = (serverIndex + 1) mod N
 */
public final class HashRouter {

    private final List<ServerAddress> servers;

    public HashRouter(List<ServerAddress> servers) {
        Objects.requireNonNull(servers, "Danh sach server khong duoc null");
        if (servers.isEmpty()) {
            throw new IllegalArgumentException("Danh sach server khong duoc rong");
        }
        this.servers = List.copyOf(servers);
    }

    /** Tong so Cache Server trong cluster. */
    public int getServerCount() {
        return servers.size();
    }

    /** Lay danh sach tat ca server. */
    public List<ServerAddress> getServers() {
        return servers;
    }

    /** Tinh serverIndex chinh phu trach key nay. */
    public int getPrimaryServerIndex(String key) {
        Objects.requireNonNull(key, "Key khong duoc null");
        return HashUtil.computeServerIndex(key, servers.size());
    }

    /** Lay dia chi Server chinh phu trach key nay. */
    public ServerAddress getPrimaryServer(String key) {
        int index = getPrimaryServerIndex(key);
        return servers.get(index);
    }

    /** Tinh replicaIndex (server du phong) cua mot primaryIndex. */
    public int getReplicaServerIndex(int primaryIndex) {
        if (primaryIndex < 0 || primaryIndex >= servers.size()) {
            throw new IndexOutOfBoundsException("Primary index khong hop le: " + primaryIndex);
        }
        return HashUtil.replicaIndex(primaryIndex, servers.size());
    }

    /** Lay dia chi Server du phong cho mot primaryIndex. */
    public ServerAddress getReplicaServer(int primaryIndex) {
        int replicaIndex = getReplicaServerIndex(primaryIndex);
        return servers.get(replicaIndex);
    }

    /** Lay dia chi Server du phong phu trach key nay. */
    public ServerAddress getReplicaServerForKey(String key) {
        int primaryIndex = getPrimaryServerIndex(key);
        return getReplicaServer(primaryIndex);
    }
}
