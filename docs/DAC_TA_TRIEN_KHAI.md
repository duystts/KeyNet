# ĐẶC TẢ TRIỂN KHAI CÁC ISSUE — KeyNet (Nhóm 05)

File này chốt **tên lớp, chữ ký hàm, cổng mạng, định dạng lệnh** cho từng Issue để 5 module ráp được với nhau ở #13 (tích hợp). Đọc kèm [đặc tả giao thức](./Dac-ta-giao-thuc-Nhom05.docx) và nội dung từng Issue trên GitHub.

- Muốn đổi bất kỳ thứ gì ở đây (tên hàm, định dạng lệnh, port) → **hỏi nhóm trưởng trước**, vì module khác đang dựa vào.
- Nếu dùng AI hỗ trợ: đưa cho AI file này + nội dung Issue, nhưng **phải tự đọc hiểu từng dòng trước khi commit** — vấn đáp không giải thích được code mình commit là **hủy điểm đề tài**.

---

## 0. Quy ước chung (mọi Issue)

| Mục | Quy ước |
|---|---|
| Ngôn ngữ / build | Java 17, Maven, **không thêm thư viện ngoài** (GUI dùng Swing). Cần thêm dependency → hỏi nhóm trưởng |
| Package | Chỉ code trong package của module mình (mục 7 đặc tả). Sửa `common` hoặc `server` → ghi rõ trong PR, nhóm trưởng review |
| Socket | UTF-8 tường minh, mỗi message kết thúc `\n`, connection-per-request (trừ Registry, xem #11) |
| Timeout | Client đặt `connect` và `setSoTimeout` = **2000 ms** |
| Log | `java.util.logging.Logger`, không `System.out.println` trong code server/client (GUI/demo console thì được) |
| Comment trong code | Tiếng Việt không dấu (giống code hiện có) |
| Test | JUnit 5, mỗi Issue phải có test. Test bật server thật dùng **port 0** + `localPort()`, **không dùng cổng cố định** 5001–5003, 6000–6003 (trùng với cụm đang chạy trên máy) |
| Hoàn thành | `mvn test` BUILD SUCCESS trên máy mình trước khi mở PR |

### Cổng mạng (dùng chung toàn hệ thống)

| Thành phần | Cổng |
|---|---|
| Cache Server #0 / #1 / #2 (client kết nối) | 5001 / 5002 / 5003 (`config.properties`) |
| Kênh replication của server #i | **cổng client + 1000** → 6001 / 6002 / 6003 |
| Registry | 6000 (`registry.port`) |

### Những gì đã có trên `main` (dùng, không viết lại)

```java
// common
ErrorCode.E001..E006, code(), defaultMessage(), shouldFailover()   // E004, E006 -> true
ProtocolParser.parseRequest(line) -> Request(type, key, value)     // type: PUT/GET/DELETE/UNKNOWN
ProtocolParser.isKeyValid(k), isValueValid(v)
ProtocolParser.put(k,v), get(k), delete(k)                          // tạo dòng request (chưa có \n)
ProtocolParser.ok(), value(v), notFound(), error(code), error(code, msg)
ProtocolParser.errorCodeOf(response) -> ErrorCode hoặc null
HashUtil.computeServerIndex(key, n), HashUtil.replicaIndex(i, n)
ServerConfig.load(path), servers(), serverCount(), serverIndex(), self(),
             ttlSeconds(), maxEntries(), registry(), withServerIndex(i)
ServerConfig.ServerAddress(host, port)

// server (#3, #4)
new KeyValueStore(ttlSeconds, maxEntries): put, get, delete, size, close
new CacheServer(port, ttlSeconds, maxEntries): start() (chặn), shutdown(), store(),
             awaitListening(ms), localPort()       // port 0 -> hệ điều hành tự chọn
```

Mẫu bật server thật trong test (module nào cũng dùng được):

```java
CacheServer server = new CacheServer(0, 60, 1000);
Thread t = new Thread(() -> { try { server.start(); } catch (IOException ignored) { } });
t.setDaemon(true);
t.start();
assertTrue(server.awaitListening(3000));
int port = server.localPort();
// ... test ...
server.shutdown();   // trong @AfterEach
```

---

## 1. Module 2 — Ngô Mạnh Hùng (package `com.nhom05.cache.client`)

### #5 HashRouter

```java
public final class HashRouter {
    public HashRouter(List<ServerAddress> servers)      // rỗng/null -> IllegalArgumentException
    public int serverCount()
    public List<ServerAddress> servers()                // bất biến (List.copyOf)
    public ServerAddress server(int index)
    public int primaryIndex(String key)                 // = HashUtil.computeServerIndex(key, N)
    public int replicaIndex(int primaryIndex)           // = HashUtil.replicaIndex(...); index sai -> IndexOutOfBoundsException
}
```

- **Không tự viết lại công thức hash** — gọi `HashUtil` (để client và replication tính giống hệt nhau).
- Test: key cố định ra đúng index, replica quay vòng (server cuối → 0), key `"polygenelubricants"` không ra số âm, danh sách rỗng báo lỗi.

### #6 CacheClient

```java
public class CacheClient {
    public static final int TIMEOUT_MS = 2000;

    public CacheClient(List<ServerAddress> servers)
    public CacheClient(List<ServerAddress> servers, int timeoutMs)
    public static CacheClient fromConfig(String path) throws IOException

    public HashRouter router()

    /** Kết quả 1 lần gửi, GUI dùng để hiển thị server nào đã trả lời. */
    public record Response(String line, int servedBy, boolean failedOver) { }

    public Response send(String key, String requestLine)   // #6: chỉ gửi server chính; #7: thêm failover
    public String put(String key, String value)            // = send(...).line()
    public String get(String key)
    public String delete(String key)
}
```

- `put/get/delete` **kiểm tra key/value phía client trước** (`isKeyValid` → `ERROR|E002...`, `isValueValid` → `ERROR|E003...`), không gửi request sai lên mạng.
- Mỗi request: `new Socket()` → `connect(addr, timeout)` → `setSoTimeout(timeout)` → ghi `line + "\n"` UTF-8 → đọc 1 dòng → đóng.
- Trả về **nguyên dòng response** của server (`OK`, `VALUE|...`, `NOTFOUND`, `ERROR|...`), không tự đổi định dạng.
- Test: bật `CacheServer` thật (mẫu ở mục 0) → PUT/GET/DELETE, tiếng Việt, key quá dài bị chặn phía client.

### #7 Failover + demo console

Failover trong `send(...)`:

1. Gửi server chính `primaryIndex(key)`.
2. **Chuyển sang server dự phòng** `replicaIndex(primary)` khi: không kết nối được, quá 2 giây không trả lời, server đóng kết nối không trả gì (`readLine()` = null), hoặc trả `ERROR|E004` / `ERROR|E006` (dùng `ProtocolParser.errorCodeOf(...).shouldFailover()`).
3. **Chỉ thử dự phòng 1 lần** (mục 8 đặc tả). Cả 2 đều lỗi → trả `ERROR|E004|...` (tự build bằng `ProtocolParser.error`).
4. Các lỗi khác (E001, E002, E003, E005) là lỗi dữ liệu → **trả luôn, không failover**.
5. Ghi log mức WARNING khi failover.

- **Không** hỏi Registry trước khi gửi (đã thử ở repo cũ: dễ ghi nhầm sang server dự phòng đúng lúc server chính vừa bật lại).
- Demo console: lớp `ClientConsole` có `main`. Lệnh: `PUT <key> <value có dấu cách>`, `GET <key>`, `DELETE <key>`, `INFO <key>` (in server chính/dự phòng), `SERVERS`, `HELP`, `EXIT`; chấp nhận cả cú pháp thô `PUT|k|v`.
- Test failover: bật 2 server thật, tắt server chính (`shutdown()`), request vẫn thành công qua dự phòng và `failedOver() == true`; tắt cả 2 → `ERROR|E004`.

---

## 2. Module 3 — Nguyễn Tiến Đức (package `com.nhom05.cache.replication`)

Module này **phải sửa thêm** `KeyValueStore` (package `server`) và `ProtocolParser`, `ServerConfig` (package `common`) → được phép, ghi rõ trong PR, nhóm trưởng review.

### #8 Gửi REPLICATE + listener

Thêm vào `common`:

```java
// ServerConfig
public int replicationPort(int index)      // = servers.get(index).port() + 1000
public int backupIndex()                   // = (serverIndex + 1) % serverCount()

// ProtocolParser
public record Replicate(boolean isPut, String key, String value, long timestamp) { }
public static Replicate parseReplicate(String line)   // sai cú pháp / timestamp không phải số -> null
public static String replicatePut(String key, String value, long timestamp)   // tạo dòng REPLICATE|PUT|...
public static String replicateDelete(String key, long timestamp)              // tạo dòng REPLICATE|DELETE|...
```

Định dạng (mục 5 đặc tả): `REPLICATE|PUT|key|value|timestamp` và `REPLICATE|DELETE|key|timestamp`, server dự phòng trả `OK` (hoặc `ERROR|E001|...` nếu sai).

```java
public final class ReplicationHandler implements AutoCloseable {
    public ReplicationHandler(KeyValueStore store)
    public void setBackup(String host, int replicationPort)          // host null / port <= 0 -> không gửi
    public void replicatePut(String key, String value, long timestamp)   // BẤT ĐỒNG BỘ
    public void replicateDelete(String key, long timestamp)              // BẤT ĐỒNG BỘ
    public void startListener(int replicationPort) throws IOException    // port 0 được, xem listenerPort()
    public int listenerPort()
    String apply(String line)        // package-private: áp 1 dòng REPLICATE vào store, trả OK / ERROR|E001
                                     // (key/value sai luật ProtocolParser.isKeyValid/isValueValid cũng E001)
    public void close()
}
```

- Gửi bằng thread pool riêng (`ExecutorService`), **không** gửi trong thread đang trả lời client. Gửi lỗi (server dự phòng chết) → chỉ log WARNING, **không retry vô hạn**, không ném exception ra ngoài.
- Bên gửi đọc dòng `OK` trả về rồi mới đóng socket.
- Listener ghi vào kho **qua `KeyValueStore`** (hàm ở #9), không tự giữ map riêng — để TTL/LRU vẫn áp dụng cho bản sao.
- `timestamp` do nơi gọi truyền vào (timestamp lần ghi trên server chính), **không** tự lấy giờ lúc gửi — #9 so sánh bằng giá trị này.
- Trạng thái: đã merge qua PR #37 + #41. #8 hiện ghi bằng `store.put/delete` (bỏ qua timestamp); #9 đổi sang `putReplicated/deleteReplicated`.
- Test: `apply(...)` với dòng đúng/sai; tích hợp 2 `ReplicationHandler` + 2 `KeyValueStore`: `replicatePut` bên A → đọc được bên B; B chưa chạy → A không lỗi.

### #9 Xung đột timestamp + tombstone

Sửa `KeyValueStore` (giữ nguyên hành vi cũ, thêm):

```java
public long nextTimestamp()                                   // tăng nghiêm ngặt: max(now, last + 1)
public long put(String key, String value)                     // đổi void -> trả timestamp của lần ghi
public boolean delete(String key, long timestamp)             // xóa + để lại tombstone
public boolean putReplicated(String key, String value, long timestamp)   // true nếu được ghi
public boolean deleteReplicated(String key, long timestamp)
```

- Last-write-wins: bản ghi có timestamp **lớn hơn** thắng; `putReplicated` cũ hơn (hoặc bằng) bản đang có → bỏ qua.
- **Tombstone**: khi xóa, nhớ `key -> timestamp xóa`; `putReplicated` có timestamp ≤ tombstone → bỏ qua (PUT cũ đến trễ không làm key "sống lại"). Tombstone tự dọn sau 1 chu kỳ TTL.
- Khi nhận timestamp từ server khác, kéo bộ đếm lên: `last = max(last, timestamp)` (lần ghi sau của mình luôn mới hơn, kể cả khi đồng hồ 2 máy lệch).
- Dùng `ConcurrentHashMap.compute(...)` cho thao tác "so sánh rồi ghi" (mục 8 đặc tả: check-then-act phải nguyên tử).
- Test: PUT đến sai thứ tự (3000 trước 2000), DELETE rồi PUT cũ đến trễ, DELETE cũ hơn bản đang có bị bỏ qua.

### #10 Phục hồi khi server khởi động lại (SYNC)

Server chết rồi bật lại → kho RAM trống → phải lấy lại dữ liệu, nếu không client đọc key của nó sẽ ra NOTFOUND (repo cũ mất 110/320 key ở kịch bản này).

Mở rộng giao thức kênh replication:

```
Gửi:    SYNC\n
Nhận:   REPLICATE|PUT|key|value|timestamp\n   (mỗi key còn hạn 1 dòng)
        ...
        END\n
```

```java
// KeyValueStore
public record Snapshot(String key, String value, long timestamp) { }
public List<Snapshot> snapshot()                       // các key chưa hết hạn

// ReplicationHandler
public int syncFrom(String host, int replicationPort, Predicate<String> keyFilter)  // -1 nếu không kết nối được
public int recover(ServerConfig config)                // tổng số bản ghi lấy về
```

- `recover`: server #i hỏi **server kề sau** (i+1, đang giữ bản sao của i và các lần ghi client đã failover sang) và **server kề trước** (i−1, vì #i là dự phòng của nó); chỉ giữ key có server chính là `i` hoặc `i−1`; ghi bằng `putReplicated` (timestamp lớn hơn thắng). N = 2 thì 2 server kề là một → hỏi 1 lần.
- Server kề chưa chạy → bỏ qua, không lỗi (lúc cả cụm mới khởi động).
- Test bắt buộc (deliverable trong bảng phân công): server chết → sửa / xóa / thêm key lúc nó chết (qua server dự phòng) → bật lại + `recover` → đọc đúng 100%.

---

## 3. Module 4 — Nguyễn Trần Tuấn Anh (package `com.nhom05.cache.registry`)

### #11 RegistryServer + RegistryClient

Lệnh (mục 6 đặc tả), mỗi dòng 1 lệnh, trả 1 dòng:

| Lệnh | Trả về |
|---|---|
| `HEARTBEAT\|index\|host\|port` hoặc `HEARTBEAT\|index\|host\|port\|soKey\|soRequest` | `ACK` |
| `STATUS\|index` | `UP` hoặc `DOWN` (server chưa từng heartbeat → `DOWN`) |
| `STATS` | `STATS\|0:UP:12:340;1:DOWN:10:200` (sắp theo index); chưa có server → `STATS\|` |
| sai cú pháp | `ERROR\|E001\|...` |
| lỗi nội bộ khi xử lý lệnh | `ERROR\|E005\|...` |
| quá tải (pool 20 luồng + hàng đợi 100 đều đầy) | `ERROR\|E004\|Server overloaded` rồi đóng kết nối, Registry vẫn nhận kết nối mới |

```java
public final class RegistryServer {
    public static final int DEFAULT_PORT = 6000;
    public static final long TIMEOUT_MS = 9000;          // 3 chu kỳ heartbeat
    public RegistryServer(int port)
    public RegistryServer(int port, long timeoutMs)      // test dùng timeout ngắn
    RegistryServer(int port, long timeoutMs, int poolSize, int queueCapacity)   // test giả lập quá tải
    public void start() throws IOException               // mở port rồi TRẢ VỀ NGAY (accept chạy thread nền)
    public int localPort()
    public void shutdown()
    String handleCommand(String line)                    // package-private, test không cần socket
    public static void main(String[] args)               // tham số: port (mặc định 6000)
}

public final class RegistryClient {
    public RegistryClient(String host, int port)
    public boolean sendHeartbeat(int index, String host, int port, int keyCount, long requestCount)
    public String status(int index)                       // "UP" / "DOWN", hoặc null nếu KHÔNG kết nối được Registry
    public record NodeStat(int index, boolean up, int keyCount, long requestCount) { }
    public List<NodeStat> stats()                         // Registry không chạy -> danh sách rỗng
}
```

- Lưu trạng thái bằng `ConcurrentHashMap<Integer, ...>`.
- Registry cho phép giữ kết nối và gửi nhiều dòng (tiện telnet), nhưng **đặt timeout 60 giây** cho kết nối im lặng.
- `status()` phải **phân biệt** "server DOWN" với "không liên lạc được Registry" (null) — GUI cần hiển thị khác nhau.

### #12 Health check + HeartbeatSender

- RegistryServer: thread quét **mỗi 1 giây**; quá `timeoutMs` không có heartbeat → `DOWN`, log WARNING `[DEAD SERVER DETECTED] Server #i ...`; heartbeat lại → `UP`, log `[SERVER RECOVERED]`.
- Heartbeat có kèm số liệu thì **luôn cập nhật, kể cả khi bằng 0** (server khởi động lại phải hiện 0, không giữ số cũ).

```java
public final class HeartbeatSender {
    public HeartbeatSender(String registryHost, int registryPort, int serverIndex,
                           String selfHost, int selfPort, long intervalSeconds,
                           IntSupplier keyCount, LongSupplier requestCount)
    public HeartbeatSender(String registryHost, int registryPort, int serverIndex,
                           String selfHost, int selfPort,
                           IntSupplier keyCount, LongSupplier requestCount)   // interval mặc định 3 giây
    public void start()        // thread nền (daemon), gửi ngay lần đầu rồi mỗi intervalSeconds (mặc định 3)
    public void stop()         // = close() (implements AutoCloseable)
}
```

- Lỗi bất ngờ khi lấy số liệu / gửi (exception trong `keyCount`, `requestCount`) phải bắt lại, không làm dừng lịch gửi.

- Registry tắt → chỉ log **1 lần** khi mất kết nối và 1 lần khi kết nối lại, không log mỗi 3 giây.
- Trạng thái: #11 và #12 đã merge qua PR #35, gồm luôn phần nối `HeartbeatSender` vào `CacheServer.main` (`CacheServer.keyCount()` = `store.size()`, `requestCount()` = số dòng lệnh đã xử lý). #13 chỉ sắp lại thứ tự khởi động (xem mục 5).

### #18 Log minh chứng

Chạy **cụm thật** (Registry + 3 server, sau khi #13 merge), tắt 1 server, ghi lại UP → DOWN sau ~9 giây và DOWN → UP khi bật lại. Lưu `docs/log_minh_chung_server_chet.txt` (UTF-8). Không dùng heartbeat giả.

---

## 4. Module 5 — Nguyễn Văn Nhật (package `com.nhom05.cache.gui`)

Swing (không cần thư viện ngoài). Mọi thao tác mạng chạy **thread nền** (`SwingWorker` / `ExecutorService`), chỉ cập nhật giao diện trên EDT (`SwingUtilities.invokeLater`) — không gọi mạng trong `actionPerformed` (đơ giao diện).

Tuần 1–2 làm giao diện với **dữ liệu giả** (interface + lớp mock), tuần 3 thay bằng `RegistryClient` / `CacheClient` thật.

### #14 Dashboard

- Lấy `RegistryClient.stats()` mỗi 1–2 giây.
- Luôn hiện **đủ N server theo `config.properties`**, kể cả server chưa có trong STATS (hiện "DOWN / chưa đăng ký").
- `status()` = null / Registry không chạy → hiện "Mất kết nối Registry", không treo.
- Tiêu đề khung (`TitledBorder`) trên nền tối: đặt màu nền cho cả `JScrollPane` (repo cũ bị chữ trắng trên nền sáng, không đọc được).

### #15 GUI Client

- Nút PUT / GET / DELETE / INFO gọi `CacheClient` (#6, #7); hiển thị `Response.line()`, server đã trả lời (`servedBy`) và có failover không (`failedOver`).
- INFO: `router().primaryIndex(key)` / `replicaIndex(...)`, không gửi mạng.
- **Không làm ô nhập TTL**: giao thức không có TTL theo từng key, server dùng TTL chung trong config.

### #16 Nút tắt / bật server thật

Nút "Sập node" phải **tắt tiến trình server thật** — repo cũ chỉ đổi chữ trên giao diện nên sau "phục hồi" đọc ra dữ liệu cũ.

- Tắt: gửi `SHUTDOWN\n` tới **port replication** của server đó → server trả `OK` rồi dừng ngay. Phía server (nhận lệnh, chỉ chấp nhận từ localhost) do nhóm trưởng làm trong #13.
- Bật lại: chạy server ở **tiến trình riêng** bằng `ProcessBuilder` (`java -cp <classpath của GUI> com.nhom05.cache.server.CacheServer config/config.properties <index>`), ghi log ra `logs/server-<index>.log`. Server mới tự `SYNC` lấy lại dữ liệu (#10).
- Kịch bản kiểm tra: ghi key → tắt → sửa/xóa key → bật lại → đọc ra giá trị mới, key đã xóa trả NOTFOUND.

### #17 Load tester

- N luồng × M request (PUT/GET), đo throughput, độ trễ trung bình / min / max, tỷ lệ thành công.
- **Thành công chỉ khi** server trả đúng giao thức: PUT → `OK`; GET → `VALUE|...` hoặc `NOTFOUND`. `ERROR|...` hoặc mất kết nối = thất bại (repo cũ đếm GET luôn thành công → số liệu sai).
- Báo cáo `docs/load_test_report.txt` **ghi đè** (không nối thêm), chạy từ cụm 3 server thật.
- Lưu ý: số key đang dùng vượt `cache.maxEntries` thì LRU loại bớt → GET có thể NOTFOUND (đúng hành vi cache, không phải lỗi).

### #19 Kịch bản demo + báo cáo

- Key dùng để demo failover phải **thật sự thuộc server bị tắt** — kiểm tra bằng INFO. Ví dụ với 3 server: `username`, `ho_ten` → chính #0, dự phòng #1 (repo cũ dùng `student_name` thuộc #2 nên tắt #0 không chứng minh được gì).

---

## 5. Nhóm trưởng (#13) sẽ nối các phần lại

Để các bạn biết code của mình được dùng thế nào:

- `CacheServer` gọi `replicatePut/replicateDelete` sau mỗi PUT/DELETE thành công (bằng timestamp `store.put(...)` trả về).
- `CacheServer.main`: bật listener replication (`config.replicationPort(i)`) + `setBackup(host của backupIndex(), replicationPort(backupIndex()))` → `recover(config)` **trước** khi mở cổng client → mở cổng client → `recover` thêm 1 lần → bật `HeartbeatSender`. Hiện `HeartbeatSender` (từ PR #35) đang bật **trước** khi mở cổng client — #13 chuyển xuống sau `recover` để Registry không báo UP lúc server còn đang lấy lại dữ liệu.
- Nhận `SHUTDOWN` trên kênh replication (chỉ từ localhost) cho nút "Sập node" của GUI.
