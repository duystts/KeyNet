# BÁO CÁO ĐỒ ÁN MÔN HỌC: LẬP TRÌNH MẠNG MÁY TÍNH

**ĐỀ TÀI:**  
# XÂY DỰNG HỆ THỐNG BỘ ĐỆM PHÂN TÁN (DISTRIBUTED CACHE SYSTEM) VỚI KEY-VALUE STORE

---

**Thông tin chung:**
* **Học viện / Trường:** Đại học Công nghệ TP.HCM (HUTECH)
* **Khoa:** Công nghệ Thông tin
* **Môn học:** Lập trình Mạng Máy Tính
* **Lớp:** 23DTHA5
* **Nhóm thực hiện:** Nhóm 05
* **Giảng viên hướng dẫn:** Bộ môn Mạng máy tính & Truyền thông

---

## BẢNG DANH SÁCH THÀNH VIÊN VÀ ĐÓNG GÓP

| STT | Họ và Tên | Mã số sinh viên (MSSV) | Module phụ trách | Mức độ đóng góp |
|:---:|:---|:---:|:---|:---:|
| 1 | **Phạm Anh Duy** *(Nhóm trưởng)* | 2380600371 | **Module 1**: Core Cache Server & Common Package | 100% |
| 2 | **Ngô Mạnh Hùng** | 2380600773 | **Module 2**: Client Library, Hashing Router & Failover | 100% |
| 3 | **Nguyễn Tiến Đức** | 2380600542 | **Module 3**: Data Replication, Consistency & Recovery | 100% |
| 4 | **Nguyễn Trần Tuấn Anh** | 2380600068 | **Module 4**: Registry Server & Health Check Monitoring | 100% |
| 5 | **Nguyễn Văn Nhật** | 2380601556 | **Module 5**: Swing GUI, Node Control, Load Tester & Báo cáo đồ án | 100% |

---

## MỤC LỤC
1. [LỜI NÓI ĐẦU & TỔNG QUAN ĐỀ TÀI](#1-lời-nói-đầu--tổng-quan-đề-tài)
2. [PHÂN TÍCH YÊU CẦU & KIẾN TRÚC HỆ THỐNG](#2-phân-tích-yêu-cầu--kiến-trúc-hệ-thống)
3. [ĐẶC TẢ GIAO THỨC TẦNG ỨNG DỤNG (APPLICATION PROTOCOL)](#3-đặc-tả-giao-thức-tầng-ứng-dụng-application-protocol)
4. [HIỆN THỰC CHI TIẾT CÁC PHÂN HỆ (MODULES)](#4-hiện-thực-chi-tiết-các-phân-hệ-modules)
5. [KẾT QUẢ THỰC NGHIỆM, KIỂM THỬ VÀ ĐO TẢI](#5-kết-quả-thực-nghiệm-kiểm-thử-và-đo-tải)
6. [KẾT LUẬN & HƯỚNG PHÁT TRIỂN](#6-kết-luận--hướng-phát-triển)

---

## 1. LỜI NÓI ĐẦU & TỔNG QUAN ĐỀ TÀI

### 1.1. Đặt vấn đề
Trong các hệ thống phần mềm quy mô lớn (Large-Scale Web Applications), việc truy vấn trực tiếp cơ sở dữ liệu quan hệ (RDBMS) thường xuyên dẫn tới tình trạng nghẽn cổ chai (I/O Bottleneck), làm tăng thời gian phản hồi (Latency) và giảm thông lượng (Throughput). Nhằm giải quyết bài toán này, các hệ thống **Bộ đệm phân tán (Distributed In-Memory Cache)** như Redis hay Memcached đã trở thành thành phần cốt lõi trong kiến trúc hiện đại.

Đồ án **"KeyNet - Distributed Cache System"** được xây dựng nhằm mô phỏng và hiện thực hóa một hệ thống lưu trữ Key-Value phân tán hoàn chỉnh từ tầng Socket mạng TCP/IP bằng ngôn ngữ Java SE 17, tích hợp các cơ chế chịu lỗi (Fault Tolerance), phân hoạch dữ liệu (Partitioning / Hashing Router), nhân bản dữ liệu (Data Replication) và giám sát nhịp tim (Heartbeat Monitoring).

### 1.2. Mục tiêu đề tài
- **Mục tiêu lý thuyết:** Vận dụng sâu sắc kiến trúc Client-Server, mô hình Socket TCP Blocking I/O kết hợp Thread Pool (mỗi request một kết nối Socket độc lập), giải thuật phân hoạch băm, giải thuật nhất quán dữ liệu (Data Consistency), cơ chế Tombstone và giải quyết xung đột bằng Timestamp (Last-Write-Wins).
- **Mục tiêu thực hành:** Xây dựng hệ thống hoàn chỉnh từ con số không (From scratch), không sử dụng thư viện ngoài; hỗ trợ cụm $N$ node độc lập, có Registry điều phối, thư viện Client thông minh tự động failover, giao diện đồ họa giám sát Swing và bộ công cụ đo tải (Benchmarking).

---

## 2. PHÂN TÍCH YÊU CẦU & KIẾN TRÚC HỆ THỐNG

### 2.1. Mô hình kiến trúc 3 tầng (3-Tier Architecture)

Hệ thống KeyNet bao gồm 3 phân tầng chính giao tiếp với nhau hoàn toàn qua các kênh Socket TCP độc lập:

```
                          ┌───────────────────────────┐
                          │   REGISTRY SERVER (6000)  │
                          │   - Health Check (1s)     │
                          │   - Theo dõi UP/DOWN (9s) │
                          └─────────────▲─────────────┘
                                        │
                         Heartbeat (3s) │ STATS / STATUS
                                        │
           ┌────────────────────────────┼───────────────────────────┐
           │                            │                           │
┌──────────▼──────────┐      ┌──────────▼──────────┐     ┌──────────▼──────────┐
│   CACHE SERVER #0   │      │   CACHE SERVER #1   │     │   CACHE SERVER #2   │
│ Client Port: 5001   │◄────►│ Client Port: 5002   │◄───►│ Client Port: 5003   │
│ Replic Port: 6001   │      │ Replic Port: 6002   │     │ Replic Port: 6003   │
└──────────▲──────────┘      └──────────▲──────────┘     └──────────▲──────────┘
           │                            │                           │
           │  PUT / GET / DELETE        │ Failover                  │
           └────────────────────────────┼───────────────────────────┘
                                        │
                          ┌─────────────┴─────────────┐
                          │     CLIENT APPLICATION    │
                          │ - HashRouter: |hash| % N  │
                          │ - Auto-Failover in send() │
                          │ - Swing GUI / LoadTester  │
                          └───────────────────────────┘
```

1. **Tầng Giám Sát (Registry Server - Port 6000):** Đóng vai trò là Service Discovery & Health Monitoring. Thu thập nhịp tim (Heartbeat) định kỳ từ tất cả các Cache Node, theo dõi số lượng key và request, đồng thời phát hiện node chết khi mất liên lạc quá 9 giây (`TIMEOUT_MS = 9000`).
2. **Tầng Lưu Trữ Phân Tán (Cache Cluster - Port 5001..5003):** Gồm $N$ máy chủ chạy độc lập. Mỗi node sở hữu 2 cổng TCP:
   - **Client Port ($5000 + i$):** Lắng nghe và xử lý các yêu cầu CRUD từ Client qua Socket TCP blocking.
   - **Replication Port ($6000 + i$):** Dành riêng cho giao tiếp nội bộ giữa các server (Replication, Sync dữ liệu và nhận lệnh Shutdown từ quản trị viên).
3. **Tầng Ứng Dụng Khách (Client Tier):** Thư viện `CacheClient` đóng gói thuật toán băm phân tán `HashRouter`. Client tính toán vị trí server chính và server dự phòng, hàm `CacheClient.send()` tự động thực hiện Failover trong suốt khi server chính gặp sự cố (kết nối thất bại hoặc nhận lỗi E004/E006).

### 2.2. Giải thuật Phân hoạch Dữ liệu (Hash Partitioning)
Nhóm áp dụng giải thuật phân hoạch theo mã băm chuẩn của Java `String.hashCode()` kết hợp với phép toán Modulo an toàn:
$$\text{serverIndex} = |\text{key.hashCode}() \pmod N|$$
$$\text{replicaIndex} = (\text{serverIndex} + 1) \pmod N$$

* **Xử lý lỗi tràn số âm:** Trong Java, `Integer.MIN_VALUE = -2147483648`. Nếu tính `Math.abs(hash) % N` thì `Math.abs` không thể chuyển đổi số âm nhỏ nhất này sang dương và vẫn trả về số âm. Nhóm giải quyết triệt để bằng cách lấy modulo trước: `Math.abs(key.hashCode() % N)` trong lớp `HashUtil.java`.

---

## 3. ĐẶC TẢ GIAO THỨC TẦNG ỨNG DỤNG (APPLICATION PROTOCOL)

Hệ thống sử dụng giao thức văn bản (Text-based Protocol) định dạng phân cách bởi ký tự `|` (Pipe-delimited), kết thúc mỗi thông điệp bằng ký tự xuống dòng `\n`, mã hóa UTF-8.

### 3.1. Giao thức Client - Cache Server (Client Port 5001..5003)

*(Lưu ý: Lệnh `INFO` được xử lý hoàn toàn cục bộ tại Client thông qua HashRouter để tra cứu server chính/dự phòng, không gửi gói tin qua mạng).*

| Thao tác | Cú pháp gửi từ Client | Phản hồi từ Server khi thành công | Phản hồi khi thất bại / không tìm thấy |
|:---|:---|:---|:---|
| **Ghi dữ liệu (PUT)** | `PUT|<key>|<value>` | `OK` | `ERROR|<ErrorCode>|<Message>` |
| **Đọc dữ liệu (GET)** | `GET|<key>` | `VALUE|<value>` | `NOTFOUND` |
| **Xóa dữ liệu (DELETE)** | `DELETE|<key>` | `OK` | `NOTFOUND` |

### 3.2. Bảng mã lỗi chuẩn hóa (Standard Error Codes - ErrorCode.java)

| Mã lỗi | Tên định danh | Ý nghĩa theo đặc tả | Hành vi Failover của Client |
|:---:|:---|:---|:---:|
| `E001` | `INVALID_COMMAND` | Cú pháp câu lệnh không hợp lệ (*Invalid command syntax*) | Không failover (lỗi dữ liệu) |
| `E002` | `KEY_EXCEEDS_MAX_LENGTH` | Độ dài khóa vượt quá giới hạn cấu hình (*Key exceeds max length*) | Không failover (lỗi dữ liệu) |
| `E003` | `VALUE_EXCEEDS_MAX_LENGTH`| Độ dài giá trị vượt quá giới hạn cấu hình (*Value exceeds max length*) | Không failover (lỗi dữ liệu) |
| `E004` | `SERVER_OVERLOADED` | Máy chủ quá tải (*Server overloaded*) hoặc Client không kết nối được server | **Kích hoạt Failover** sang dự phòng |
| `E005` | `INTERNAL_SERVER_ERROR` | Lỗi ngoại lệ nội bộ không mong muốn tại máy chủ (*Internal server error*) | Không failover |
| `E006` | `SERVER_SYNCING` | Máy chủ đang trong quá trình đồng bộ phục hồi (*Server syncing, retry later*) | **Kích hoạt Failover** sang dự phòng |

### 3.3. Giao thức Nhân bản & Phục hồi (Replication Port 6001..6003)

- **Nhân bản ghi (`REPLICATE|PUT`):**  
  `REPLICATE|PUT|<key>|<value>|<timestamp>` $\rightarrow$ Backup trả về `OK` (hoặc `ERROR|E001|...` nếu sai format).
- **Nhân bản xóa (`REPLICATE|DELETE`):**  
  `REPLICATE|DELETE|<key>|<timestamp>` $\rightarrow$ Backup trả về `OK`.
- **Đồng bộ khi khởi động lại (`SYNC`):**  
  Server mới gửi: `SYNC\n`.  
  Server dự phòng trả về danh sách các dòng dữ liệu hợp lệ và bản ghi tombstone:  
  `REPLICATE|PUT|<key>|<value>|<timestamp>`  
  `REPLICATE|DELETE|<key>|<timestamp>`  
  ...  
  Kết thúc toàn bộ tiến trình đồng bộ bằng dòng: `END\n`. *(Không có trường expiry trong bản tin replication; TTL được xử lý độc lập).*
- **Dừng máy chủ khẩn cấp (`SHUTDOWN`):**  
  `SHUTDOWN\n` (Chỉ chấp nhận từ `127.0.0.1` trên cổng replication) $\rightarrow$ Server trả lời `OK` rồi gọi `Runtime.halt(0)` để dừng tiến trình ngay lập tức.

### 3.4. Giao thức Giám sát Nhịp tim (Registry Port 6000)

- **Bản tin nhịp tim:**  
  `HEARTBEAT|<serverIndex>|<host>|<port>|<keyCount>|<requestCount>` $\rightarrow$ Registry trả về **`ACK`**.
- **Truy vấn trạng thái 1 server:**  
  `STATUS|<serverIndex>` $\rightarrow$ Trả về `UP` hoặc `DOWN`.
- **Truy vấn toàn bộ cụm:**  
  `STATS\n` $\rightarrow$ Trả về chuỗi: `STATS|0:UP:12:340;1:UP:10:200;2:DOWN:0:0`.

---

## 4. HIỆN THỰC CHI TIẾT CÁC PHÂN HỆ (MODULES)

### 4.1. Module 1: Core Cache Server & KV Engine (`com.nhom05.cache.server`)
* **Chịu trách nhiệm:** Phạm Anh Duy (MSSV: 2380600371).
* **Lớp trung tâm:** `CacheServer.java`, `KeyValueStore.java`, `CacheEntry.java`.
* **Kỹ thuật then chốt:**
  - `ConcurrentHashMap<String, CacheEntry>` làm cấu trúc lưu trữ thread-safe không khóa chặn toàn cục.
  - Cấu trúc `CacheEntry` lưu trữ 4 trường:
    1. `value`: Giá trị chuỗi của key.
    2. `timestamp`: Thời điểm ghi nhận (phục vụ Last-Write-Wins).
    3. `expireAtMillis`: Thời điểm hết hạn tuyệt đối (`now + ttlMillis`).
    4. `lastAccess`: Bộ đếm nguyên tử `accessCounter.incrementAndGet()` ghi nhận thứ tự truy cập gần nhất (phục vụ giải thuật LRU).
  - **Thuật toán LRU Eviction:** Khi số lượng phần tử vượt ngưỡng cấu hình `cache.maxEntries`, hệ thống tìm và xóa phần tử có `lastAccess` nhỏ nhất (lâu không được truy cập nhất).
  - **Cơ chế Sweeper dọn dẹp TTL:** Khởi chạy một `ScheduledExecutorService` định kỳ **mỗi 1 giây** quét toàn bộ kho dữ liệu `map`, tự động xóa sạch các entry có `nowMillis >= expireAtMillis` để giải phóng bộ nhớ RAM. Đồng thời khi `GET`, hệ thống kiểm tra Lazy Expiration để trả `NOTFOUND` ngay nếu key vừa hết hạn.

### 4.2. Module 2: Client Library & Hashing Router (`com.nhom05.cache.client`)
* **Chịu trách nhiệm:** Ngô Mạnh Hùng (MSSV: 2380600773).
* **Lớp trung tâm:** `CacheClient.java`, `HashRouter.java`, `ClientConsole.java`.
* **Kỹ thuật then chốt:**
  - `HashRouter` chịu trách nhiệm thuần túy tính toán vị trí: `primaryIndex(key)` và `replicaIndex(primary)`.
  - **Cơ chế Failover đặt trong `CacheClient.send()`:**
    1. Gửi request đến server chính qua Socket TCP blocking.
    2. Nếu server chính trả lỗi cần failover (mã `E004`, `E006`, hoặc kết nối mạng bị `IOException` / `ConnectException`), Client tự động thử lại đúng 1 lần tới server dự phòng `replicaAddr`.
    3. Nếu cả server chính và server dự phòng đều không kết nối được, Client trả về `ERROR|E004|Ca server chinh va du phong deu khong kha dung`.
  - Trả về đối tượng `Response` chứa chuỗi phản hồi, chỉ số server đã phục vụ (`servedBy`) và cờ đánh dấu `failedOver` (`true`/`false`).

### 4.3. Module 3: Replication & Data Consistency (`com.nhom05.cache.replication`)
* **Chịu trách nhiệm:** Nguyễn Tiến Đức (MSSV: 2380600542).
* **Lớp trung tâm:** `ReplicationHandler.java`.
* **Kỹ thuật then chốt:**
  - **Asynchronous Replication qua ThreadPool:** Sau khi ghi thành công vào RAM tại node chính, bản tin replication được đẩy vào hàng đợi `senderPool` để gửi sang node dự phòng. Hàng đợi có dung lượng tối đa 10,000 bản tin nhằm tránh tràn bộ nhớ khi node dự phòng chết lâu.
  - **Cơ chế Tombstone khi xóa:** Khi xóa key, hệ thống lưu bản ghi `Tombstone(timestamp, expireAtMillis)` tồn tại trong 1 chu kỳ TTL. Nếu bản tin `REPLICATE|PUT` cũ bị trễ trên mạng đến sau lệnh xóa, node sẽ so sánh timestamp và từ chối ghi đè, ngăn hiện tượng key "sống lại".
  - **Hỗ trợ ghi qua Failover (Issue #45 / PR #49):** Khi server chính chết, Client gửi PUT/DELETE trực tiếp lên server dự phòng. Server dự phòng lưu giữ các bản ghi này; khi server chính phục hồi và gọi lệnh `SYNC`, server dự phòng cung cấp toàn bộ các bản ghi mới (kèm tombstone) để server chính tự động cập nhật lại đúng giá trị mới nhất.
  - **Nguyên lý Last-Write-Wins (LWW):** Dữ liệu đồng bộ luôn đi kèm `original_write_timestamp`. Bản ghi nào có timestamp lớn hơn sẽ giành chiến thắng trong mọi tình huống xung đột.

### 4.4. Module 4: Registry Server & Health Check Monitoring (`com.nhom05.cache.registry`)
* **Chịu trách nhiệm:** Nguyễn Trần Tuấn Anh.
* **Lớp trung tâm:** `RegistryServer.java`, `RegistryClient.java`, `HeartbeatSender.java`.
* **Kỹ thuật then chốt:**
  - Quản lý danh sách node bằng `ConcurrentHashMap<Integer, NodeInfo>`, trong đó `NodeInfo` lưu: `index`, `host`, `port`, `keyCount`, `requestCount`, `lastHeartbeat`, `up`.
  - `HeartbeatSender` chạy như một daemon thread tại mỗi Cache Server, định kỳ mỗi 3 giây gửi thông số tải về Registry và nhận phản hồi `ACK`.
  - **Health Checker định kỳ 1 giây:** Thread nền tại Registry kiểm tra `now - node.lastHeartbeat > TIMEOUT_MS` (với hằng số `TIMEOUT_MS = 9000`). Nếu một node im lặng quá 9 giây (tương đương 3 nhịp heartbeat), node lập tức bị chuyển thành `DOWN` và ghi log:  
    `WARNING: [DEAD SERVER DETECTED] Server #i (host:port) khong gui heartbeat qua 9000ms`.

### 4.5. Module 5: Swing GUI, Node Process Control & Load Tester (`com.nhom05.cache.gui`)
* **Chịu trách nhiệm:** Nguyễn Văn Nhật (MSSV: 2380601556).
* **Lớp trung tâm:** `MainAppFrame.java`, `DashboardPanel.java`, `ClientPanel.java`, `NodeProcessManager.java`, `LoadTester.java`, `LoadTesterPanel.java`.
* **Kỹ thuật then chốt:**
  - **MainAppFrame:** Ứng dụng tích hợp quản trị trung tâm dạng tab:
    - Tab 1: `Giám Sát Cụm (Dashboard #14)`
    - Tab 2: `Thao Tác Cache & Key Explorer (#15)`
    - Tab 3: `Kiểm Thử Tải (Load Tester #17)`
  - **Non-blocking Event Dispatch Thread:** Toàn bộ tác vụ I/O mạng chạy trên `SwingWorker` / `ExecutorService`, chỉ cập nhật giao diện trên EDT thông qua `SwingUtilities.invokeLater()`, đảm bảo giao diện luôn mượt mà.
  - **Quản lý tiến trình thật (NodeProcessManager):**
    - "Sập Node Được Chọn": Gửi lệnh `SHUTDOWN` qua TCP đến cổng replication của node để tắt tiến trình thật.
    - "Bật Lại Node Được Chọn": Dùng `ProcessBuilder` khởi chạy tiến trình Java độc lập, kiểm tra trạng thái sống `process.isAlive()`, ghi log ra `logs/server-<index>.log`, và giữ nguyên vị trí hàng chọn trên JTable.
  - **Công cụ đo tải LoadTester:**
    - Sinh tải đa luồng qua Socket TCP blocking song song với $N$ luồng $\times M$ request.
    - Đo chính xác Throughput, Latency (Avg, Min, Max, p50, p95, p99) và tỷ lệ thành công.
    - Kiểm định chặt chẽ: PUT thành công khi trả lời đúng `OK` (`equals("OK")`), GET thành công khi trả lời `VALUE|...` (Cache Hit) hoặc `NOTFOUND` (Cache Miss).
    - Trộn ngẫu nhiên (Interleaved Random Workload) giữa PUT và GET để mô phỏng tải thực tế. Báo cáo tự động ghi đè vào `docs/load_test_report.txt`.

---

## 5. KẾT QUẢ THỰC NGHIỆM, KIỂM THỬ VÀ ĐO TẢI

### 5.1. Kết quả kiểm thử tự động (Unit & Integration Tests)
Hệ thống được kiểm thử tự động toàn diện qua JUnit 5 với **193 test cases** (trong đó phân hệ quản lý tiến trình `NodeProcessManagerTest` gồm 5 test cases) bao phủ toàn bộ các tầng chức năng:
```
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running com.nhom05.cache.server.KeyValueStoreTest
[INFO] Tests run: 28, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.nhom05.cache.server.ClusterIntegrationTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.nhom05.cache.replication.ReplicationHandlerTest
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.nhom05.cache.registry.RegistryServerTest
[INFO] Tests run: 14, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.nhom05.cache.gui.LoadTesterTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.nhom05.cache.gui.LoadTesterPanelTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.nhom05.cache.gui.NodeProcessManagerTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
...
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] Tests run: 193, Failures: 0, Errors: 0, Skipped: 0
[INFO] Total time: 32.876 s
[INFO] ------------------------------------------------------------------------
```

### 5.2. Kết quả kiểm thử khả năng chịu lỗi (Failover & Self-Healing Test)
- **Thực nghiệm:** Thiết lập cụm 3 server, ghi key `username` (Server chính #0, dự phòng #1).
- **Hành vi:**
  1. Tắt Server #0 bằng nút "Sập Node Được Chọn" $\rightarrow$ Client đọc `GET username` tự động chuyển sang Server #1 thành công và hiển thị nhãn `[DỰ PHÒNG - FAILOVER]`.
  2. Ghi đè `PUT username DuLieuMoi` lên Server #1 trong lúc Server #0 đang chết.
  3. Bật lại Server #0 $\rightarrow$ Server #0 khởi động, chạy `SYNC` từ Server #1, cập nhật đúng `DuLieuMoi` theo LWW timestamp.
  4. Client đọc lại `GET username` $\rightarrow$ Server #0 trả về đúng `DuLieuMoi` (không failover). Toàn bộ dữ liệu nhất quán 100%.

### 5.3. Kết quả đo tải hiệu năng trên cụm 3 server thật (Benchmarking)
Đo tải thực tế với cấu hình **10 luồng đồng thời $\times$ 200 request/luồng = 2,000 requests** (tỷ lệ 50% PUT / 50% GET, 20 keys):

*Trích xuất từ file báo cáo chuẩn [docs/load_test_report.txt](load_test_report.txt):*

| Hạng mục đo lường | Giá trị thực nghiệm đạt được | Đánh giá kỹ thuật |
|:---|:---:|:---|
| **Tổng số requests xử lý** | **2,000 requests** | Khối lượng tải lớn chứng minh năng lực chịu tải |
| **Tổng thời gian hoàn thành (Duration)**| **702 ms (0.70 giây)** | Thời gian xử lý tức thời |
| **Throughput (Thông lượng)** | **2,849.00 req/s** | Năng lực phục vụ xấp xỉ 3,000 yêu cầu / giây |
| **Tỷ lệ thành công (Success Rate)** | **100.00 %** | 2,000 / 2,000 req thành công, 0 lỗi socket |
| **Độ trễ trung bình (Avg Latency)** | **3.00 ms** | Độ trễ cực thấp đối với giao tiếp Socket qua mạng |
| **Độ trễ tối thiểu (Min Latency)** | **0.66 ms** | Tốc độ đáp ứng bộ nhớ RAM |
| **Độ trễ tối đa (Max Latency)** | **130.19 ms** | Đột biến chỉ xuất hiện ở nhịp kết nối đầu tiên |
| **Phân vị 50th (p50 Latency)** | **1.85 ms** | 50% các yêu cầu hoàn tất dưới 1.85 ms |
| **Phân vị 95th (p95 Latency)** | **7.39 ms** | 95% các yêu cầu hoàn tất dưới 7.39 ms |
| **Phân vị 99th (p99 Latency)** | **24.71 ms** | 99% các yêu cầu hoàn tất dưới 24.71 ms |

**Chi tiết phân bổ tác vụ:**
- **Thao tác PUT (1,019 requests):** 1,019 thành công (100.00% phản hồi `OK`), 0 thất bại.
- **Thao tác GET (981 requests):** 981 thành công (100.00% phản hồi `VALUE` hoặc `NOTFOUND`), gồm:
  - **961 Cache Hits** (chiếm 97.96% số lượt đọc - trả về `VALUE|...`).
  - **20 Cache Misses** (chiếm 2.04% số lượt đọc - trả về `NOTFOUND`).
- *Nhận xét:* Sự xuất hiện của 20 lượt Cache Miss minh chứng giải thuật sinh dữ liệu ngẫu nhiên hoạt động hoàn hảo, phản ánh sát thực tế khi truy vấn các khóa chưa từng tồn tại trong bộ nhớ cache.

---

## 6. KẾT LUẬN & HƯỚNG PHÁT TRIỂN

### 6.1. Kết quả đạt được
1. Hiện thực hoàn chỉnh hệ thống Distributed In-Memory Cache phân tán từ tầng mạng Socket TCP thuần túy trên Java 17, tuân thủ nghiêm ngặt mô hình Client-Server.
2. Hoàn thành đầy đủ 5 module chức năng với mã nguồn chuẩn hóa, cấu trúc tường minh, 193 unit/integration tests đạt 100% pass.
3. Giải quyết triệt để các bài toán khó trong hệ thống phân tán:
   - Phân hoạch băm Modulo an toàn (`|hash(key)| mod N`).
   - Tự động chuyển hướng sự cố (Seamless Transparent Failover).
   - Cơ chế nhất quán dữ liệu Last-Write-Wins, Tombstone và tự phục hồi (Self-Healing Recovery).
   - Giám sát nhịp tim thời gian thực (Heartbeat Monitoring).
   - Giao diện người dùng Swing trực quan, hỗ trợ tắt/bật node thật và công cụ đo tải đa luồng chính xác.

### 6.2. Hướng phát triển mở rộng
- Hỗ trợ giải thuật băm nhất quán **Consistent Hashing với Virtual Nodes** để việc bổ sung hoặc giảm bớt máy chủ không làm xáo trộn toàn bộ bảng băm.
- Mở rộng giao thức hỗ trợ cấu trúc dữ liệu phong phú hơn ngoài Key-Value chuỗi (như Hash, List, Set tương tự Redis).
- Triển khai nhân bản đa bản sao (Multi-Replica Quorum: $R + W > N$) để đảm bảo tính sẵn sàng cao ngay cả khi có 2 node sập đồng thời.

---
*Báo cáo được hoàn thành bởi Nhóm 05 — Lớp 23DTHA5 — Đồ án môn học Lập trình Mạng Máy Tính.*
