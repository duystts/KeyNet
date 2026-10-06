# KeyNet

Mô phỏng Hệ thống Bộ đệm Phân tán (Distributed Cache System) với Key-Value Store — đồ án cuối kỳ môn **Lập trình mạng máy tính**, Nhóm 05, lớp 23DTHA5.

Nhiều Cache Server nhân bản hoạt động như Key-Value Store; Client dùng hashing đơn giản (`hash(key) mod N`) để quyết định kết nối server nào.

## Tài liệu

- [Đặc tả giao thức](./docs/Dac-ta-giao-thuc-Nhom05.docx) — format message, mã lỗi, công thức hashing, giao thức replication/heartbeat, cấu trúc package. **Đọc trước khi code bất kỳ module nào.**
- [Phân công công việc](./docs/Phan-cong-cong-viec-Nhom05.docx) — 5 module, deliverable, mốc thời gian.

## Cấu trúc package

```
com.nhom05.cache.common       dùng chung: ErrorCode, ProtocolParser, HashUtil, ServerConfig
com.nhom05.cache.server        Module 1 - Core Cache Server / KV engine
com.nhom05.cache.client        Module 2 - Client + Hashing Router + Failover
com.nhom05.cache.replication   Module 3 - Replication & Consistency
com.nhom05.cache.registry      Module 4 - Monitoring / Registry / Health Check
com.nhom05.cache.gui           Module 5 - GUI, dashboard
```

Mỗi thành viên chỉ code trong package của module mình. Thay đổi trong `common` cần nhóm trưởng review trước khi merge.

## Module 1 — Core Cache Server (đã hoàn thành)

- `KeyValueStore`: `ConcurrentHashMap` lưu key-value trong RAM, TTL tự động hết hạn (sweeper mỗi 1s), LRU eviction khi vượt `maxEntries`.
- `CacheServer`: lắng nghe TCP, thread pool cố định (50 thread) xử lý nhiều client đồng thời.
- `ClientHandler`: parse request, trả response đúng định dạng trong đặc tả giao thức (`OK`, `VALUE|...`, `NOTFOUND`, `ERROR|<mã>|<mô tả>`).

### Build & chạy

```bash
mvn test              # chạy 18 unit test (KeyValueStore + ClientHandler)
mvn package            # build target/keynet.jar
java -jar target/keynet.jar config/config.properties
```

Mặc định đọc `config/config.properties` nếu không truyền đường dẫn. Sửa `cache.serverIndex` trong file config để chạy nhiều instance trên cùng máy (mỗi instance port khác nhau theo `server.<index>.port`).

### Test nhanh bằng telnet

```bash
telnet 127.0.0.1 5001
PUT|username|Duy
GET|username
DELETE|username
```

## Module 3 — Replication & Consistency

- `ReplicationHandler` (package `replication`): sau mỗi PUT/DELETE thành công, server chính gửi **bất đồng bộ** `REPLICATE|PUT|key|value|timestamp` / `REPLICATE|DELETE|key|timestamp` tới server dự phòng `(serverIndex+1) mod N` trên **port replication riêng = port client + 1000** (vd server 5001 ↔ 6001). Gửi lỗi chỉ ghi log, không ảnh hưởng client.
- Xung đột: bản ghi có `timestamp` lớn hơn thắng (last-write-wins). `KeyValueStore` giữ "tombstone" cho key đã xóa để lệnh PUT cũ đến trễ không làm sống lại key.
- Bản sao nhận TTL mặc định tính từ lúc nhận (message đồng bộ không mang TTL).
- Server chỉ bật replication khi cluster có từ 2 server trở lên.

## Module 4 — Monitoring / Registry + Health Check (Nguyễn Trần Tuấn Anh)

- `RegistryServer`: Tiến trình Registry độc lập lắng nghe trên port cố định `6000`, sử dụng `ConcurrentHashMap` lưu trữ thông tin và trạng thái (`UP`/`DOWN`) của các Cache Server trong cluster.
- `Health Check Thread`: Kiểm tra định kỳ (mỗi 1 giây), phát hiện và đánh dấu `DOWN` khi server không gửi heartbeat quá **9 giây** (3 chu kỳ), đồng thời ghi log cảnh báo phát hiện server chết.
- Phục vụ truy vấn:
  - `HEARTBEAT|<serverIndex>|<host>|<port>` -> phản hồi `ACK` (hỗ trợ cả thống kê mở rộng).
  - `STATUS|<serverIndex>` -> phản hồi `UP` hoặc `DOWN`.
  - `STATS` -> phản hồi danh sách trạng thái dạng `STATS|<index>:<status>:<keyCount>:<requestCount>;...` phục vụ cho GUI Dashboard (Module 5).
- `RegistryClient` & `HeartbeatSender`: Client tiện ích cho Client/GUI truy vấn trạng thái và CacheServer gửi heartbeat định kỳ mỗi 3 giây.
- `FailureDetectionDemo`: Kịch bản mô phỏng kiểm thử tự động, phát hiện server ngắt kết nối và ghi lại log minh chứng tại `docs/log_minh_chung_server_chet.txt`.

### Chạy RegistryServer & Test

```bash
# Chạy RegistryServer độc lập (port mặc định: 6000)
java -cp target/classes com.nhom05.cache.registry.RegistryServer 6000

# Chạy Demo kịch bản phát hiện server chết & xuất file log minh chứng
java -cp target/classes com.nhom05.cache.registry.FailureDetectionDemo
```

### Test nhanh Registry qua telnet

```bash
telnet 127.0.0.1 6000
HEARTBEAT|0|127.0.0.1|5001
STATUS|0
STATS
```

## Yêu cầu

- Java 17+
- Maven 3.8+
