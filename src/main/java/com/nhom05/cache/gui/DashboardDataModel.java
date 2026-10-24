package com.nhom05.cache.gui;

import com.nhom05.cache.common.ServerConfig;
import com.nhom05.cache.registry.RegistryClient;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Xu ly logic tong hop va dinh dang du lieu Dashboard tu ServerConfig va RegistryClient.
 * Tach rieng logic khoi giao dien Swing de de dang kiem thu Unit Test doc lap.
 */
public final class DashboardDataModel {

    public static final String STATUS_UP = "UP";
    public static final String STATUS_DOWN = "DOWN";
    public static final String STATUS_NOT_REGISTERED = "CHƯA ĐĂNG KÝ";
    public static final String STATUS_DISCONNECTED = "MẤT KẾT NỐI REGISTRY";

    private final boolean registryConnected;
    private final String registryAddress;
    private final List<NodeDisplayInfo> nodes;
    private final int totalNodes;
    private final int upNodes;
    private final int totalKeys;
    private final long totalRequests;
    private final String lastUpdatedTime;

    public DashboardDataModel(boolean registryConnected, String registryAddress,
                              List<NodeDisplayInfo> nodes, int totalNodes, int upNodes,
                              int totalKeys, long totalRequests, String lastUpdatedTime) {
        this.registryConnected = registryConnected;
        this.registryAddress = registryAddress != null ? registryAddress : "";
        this.nodes = nodes != null ? List.copyOf(nodes) : List.of();
        this.totalNodes = totalNodes;
        this.upNodes = upNodes;
        this.totalKeys = totalKeys;
        this.totalRequests = totalRequests;
        this.lastUpdatedTime = lastUpdatedTime != null ? lastUpdatedTime : "--:--:--";
    }

    /**
     * Thu thap du lieu truc tiep tu RegistryClient dua tren danh sach server trong ServerConfig.
     */
    public static DashboardDataModel fetch(ServerConfig config, RegistryClient registryClient) {
        String regAddr = (config != null && config.registry() != null)
                ? config.registry().host() + ":" + config.registry().port()
                : "127.0.0.1:6000";

        List<RegistryClient.NodeStat> stats = List.of();
        boolean connected = false;

        if (registryClient != null) {
            try {
                // Thu status(0) de phan biet Registry song hay chet
                String ping = registryClient.status(0);
                if (ping != null) {
                    connected = true;
                    stats = registryClient.stats();
                } else {
                    stats = registryClient.stats();
                    if (!stats.isEmpty()) {
                        connected = true;
                    }
                }
            } catch (Exception e) {
                connected = false;
            }
        }

        String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        return build(config, stats, connected, regAddr, time);
    }

    /**
     * Ham thuan (pure function) de gop danh sach server voi so lieu Registry, phuc vu Unit Test.
     */
    public static DashboardDataModel build(ServerConfig config, List<RegistryClient.NodeStat> stats,
                                           boolean registryConnected, String registryAddress, String timestamp) {
        Map<Integer, RegistryClient.NodeStat> statMap = new HashMap<>();
        if (stats != null) {
            for (RegistryClient.NodeStat st : stats) {
                statMap.put(st.index(), st);
            }
        }

        List<ServerConfig.ServerAddress> serverAddrs = (config != null) ? config.servers() : List.of();
        int n = serverAddrs.size();

        List<NodeDisplayInfo> nodes = new ArrayList<>(n);
        int upCount = 0;
        int sumKeys = 0;
        long sumRequests = 0;

        for (int i = 0; i < n; i++) {
            ServerConfig.ServerAddress addr = serverAddrs.get(i);
            int replPort = addr.port() + ServerConfig.REPLICATION_PORT_OFFSET;
            int backupFor = (n > 1) ? (i - 1 + n) % n : 0;

            RegistryClient.NodeStat st = statMap.get(i);

            String statusStr;
            boolean isUp = false;
            int keyCount = 0;
            long requestCount = 0;

            if (!registryConnected) {
                statusStr = STATUS_DISCONNECTED;
            } else if (st == null) {
                statusStr = STATUS_NOT_REGISTERED;
            } else {
                isUp = st.up();
                statusStr = isUp ? STATUS_UP : STATUS_DOWN;
                keyCount = Math.max(0, st.keyCount());
                requestCount = Math.max(0, st.requestCount());
            }

            if (isUp) {
                upCount++;
                sumKeys += keyCount;
                sumRequests += requestCount;
            }

            nodes.add(new NodeDisplayInfo(i, addr.host(), addr.port(), replPort,
                    statusStr, isUp, keyCount, requestCount, backupFor));
        }

        return new DashboardDataModel(
                registryConnected,
                registryAddress,
                nodes,
                n,
                upCount,
                sumKeys,
                sumRequests,
                timestamp != null ? timestamp : "--:--:--"
        );
    }

    /**
     * Hien cac node vua bi tat tu GUI la DOWN du Registry van bao UP (Registry chi bao DOWN sau
     * 9 giay khong nhan heartbeat, #61); tinh lai so node UP, tong key va tong request.
     */
    public DashboardDataModel withNodesDown(Set<Integer> downIndexes) {
        if (downIndexes == null || downIndexes.isEmpty()) {
            return this;
        }
        List<NodeDisplayInfo> updated = new ArrayList<>(nodes.size());
        int upCount = 0;
        int sumKeys = 0;
        long sumRequests = 0;
        for (NodeDisplayInfo n : nodes) {
            if (n.isUp() && downIndexes.contains(n.index())) {
                n = new NodeDisplayInfo(n.index(), n.host(), n.clientPort(), n.replicationPort(),
                        STATUS_DOWN, false, n.keyCount(), n.requestCount(), n.backupForIndex());
            }
            if (n.isUp()) {
                upCount++;
                sumKeys += n.keyCount();
                sumRequests += n.requestCount();
            }
            updated.add(n);
        }
        return new DashboardDataModel(registryConnected, registryAddress, updated, totalNodes,
                upCount, sumKeys, sumRequests, lastUpdatedTime);
    }

    public boolean isRegistryConnected() {
        return registryConnected;
    }

    public String registryAddress() {
        return registryAddress;
    }

    public List<NodeDisplayInfo> nodes() {
        return nodes;
    }

    public int totalNodes() {
        return totalNodes;
    }

    public int upNodes() {
        return upNodes;
    }

    public int totalKeys() {
        return totalKeys;
    }

    public long totalRequests() {
        return totalRequests;
    }

    public String lastUpdatedTime() {
        return lastUpdatedTime;
    }
}
