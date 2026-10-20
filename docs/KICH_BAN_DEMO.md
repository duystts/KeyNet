# KỊCH BẢN DEMO ĐỒ ÁN — KEYNET DISTRIBUTED CACHE SYSTEM
**Học phần:** Lập trình Mạng Máy Tính | **Lớp:** 23DTHA5 | **Nhóm:** 05  
**Hệ thống:** Bộ Đệm Phân Tán với Key-Value Store (Distributed Cache System)  
**Tài liệu phục vụ:** Báo cáo, thuyết trình và vấn đáp đồ án cuối kỳ (#19)

---

## 1. THÔNG TIN CHUNG & PHÂN CÔNG THUYẾT TRÌNH

| STT | Thành viên | MSSV | Module phụ trách | Vai trò trong buổi Demo |
|:---:|:---|:---:|:---|:---|
| 1 | **Phạm Anh Duy** *(Nhóm trưởng)* | 2380600371 | Module 1: Core Cache Server & Common | Giới thiệu tổng quan hệ thống, kiến trúc Core Server, LRU, TTL |
| 2 | **Ngô Mạnh Hùng** | 2380600773 | Module 2: Client, HashRouter & Failover | Trình diễn thuật toán Hashing Router, cơ chế Failover mạng |
| 3 | **Nguyễn Tiến Đức** | 2380600542 | Module 3: Replication & Consistency | Thuyết minh giao thức Replication, Timestamp giải quyết xung đột |
| 4 | **Nguyễn Trần Tuấn Anh** | 238060... | Module 4: Registry Server & Health Check | Demo Registry Server, cơ chế Heartbeat phát hiện node chết |
| 5 | **Nguyễn Văn Nhật** | 2380601556 | Module 5: GUI Dashboard, Load Tester & Report | Thao tác trực tiếp GUI, demo sập/bật node, chạy Load Tester đa luồng |

---

## 2. CHUẨN BỊ MÔI TRƯỜNG & KHỞI ĐỘNG HỆ THỐNG

### 2.1. Kiểm tra cấu hình và biên dịch
1. Mở Terminal / PowerShell tại thư mục gốc dự án: `d:\LapTrinh\Projects\KeyNet`.
2. Kiểm tra biên dịch và toàn bộ 186 Unit/Integration Tests:
   ```powershell
   & "D:\LapTrinh\Tools\apache-maven-3.9.16-bin\apache-maven-3.9.16\bin\mvn.cmd" test
   ```
   *(Đảm bảo hiển thị `BUILD SUCCESS - Tests run: 186, Failures: 0, Errors: 0`).*
3. Đóng gói file JAR thực thi:
   ```powershell
   & "D:\LapTrinh\Tools\apache-maven-3.9.16-bin\apache-maven-3.9.16\bin\mvn.cmd" package -DskipTests
   ```

### 2.2. Khởi chạy cụm phân tán (Cluster)
Để hệ thống vận hành đúng chuẩn theo kịch bản mạng, mở 4 cửa sổ terminal riêng biệt và chạy theo **đúng thứ tự**:

```powershell
# [Cửa sổ 1] Bật Registry Server (Port 6000)
java -cp target/keynet.jar com.nhom05.cache.registry.RegistryServer 6000

# [Cửa sổ 2] Bật Cache Server #0 (Client Port: 5001, Replication Port: 6001)
java -jar target/keynet.jar config/config.properties 0

# [Cửa sổ 3] Bật Cache Server #1 (Client Port: 5002, Replication Port: 6002)
java -jar target/keynet.jar config/config.properties 1

# [Cửa sổ 4] Bật Cache Server #2 (Client Port: 5003, Replication Port: 6003)
java -jar target/keynet.jar config/config.properties 2
```

*(Hoặc sử dụng file script tiện ích `start-servers.bat` đi kèm).*

### 2.3. Khởi chạy Giao diện Quản trị Tập trung (MainAppFrame)
Chạy ứng dụng GUI tích hợp đầy đủ 3 tính năng Dashboard - Client - Load Tester:
```powershell
java -cp target/keynet.jar com.nhom05.cache.gui.MainAppFrame config/config.properties
```

---

## 3. KỊCH BẢN DEMO CHI TIẾT THEO TỪNG BƯỚC (STEP-BY-STEP)

```
                    ┌───────────────────────────────────────────────┐
                    │  KỊCH BẢN DEMO VẤN ĐÁP 4 GIAI ĐOẠN            │
                    ├───────────────────────────────────────────────┤
                    │ Giai đoạn 1: Giới thiệu Kiến trúc & Dashboard  │
                    │ Giai đoạn 2: CRUD & Phân bổ Băm Hashing Router│
                    │ Giai đoạn 3: Sập Node Thật, Failover & Sync   │
                    │ Giai đoạn 4: Đo Tải Đa Luồng (Load Testing)   │
                    └───────────────────────────────────────────────┘
```

---

### GIAI ĐOẠN 1: GIỚI THIỆU KIẾN TRÚC & DASHBOARD GIÁM SÁT (#14)
* **Người trình bày:** Phạm Anh Duy & Nguyễn Trần Tuấn Anh.
* **Người thao tác GUI:** Nguyễn Văn Nhật.

1. **Thao tác trên màn hình:**
   - Chọn Tab 1: **"Giám Sát Cụm (Dashboard #14, #16)"**.
   - Quan sát bảng trạng thái cụm hiển thị 3 Server:
     - `Server #0 (127.0.0.1:5001)`: Trạng thái **UP** (Màu xanh), Keys: 0, Requests: 0.
     - `Server #1 (127.0.0.1:5002)`: Trạng thái **UP** (Màu xanh), Keys: 0, Requests: 0.
     - `Server #2 (127.0.0.1:5003)`: Trạng thái **UP** (Màu xanh), Keys: 0, Requests: 0.
2. **Lời thuyết minh mẫu:**
   > *"Kính thưa Thầy/Cô, hệ thống KeyNet mô phỏng một cụm Cache phân tán gồm 3 node máy chủ và 1 Registry Server điều phối. Tại Tab Dashboard, mỗi node định kỳ gửi Heartbeat mỗi 3 giây tới Registry qua cổng 6000. Registry thu thập số liệu tải (số key, tổng request đã xử lý) và cung cấp cho Dashboard cập nhật theo thời gian thực mỗi 1-2 giây. Tất cả đều xử lý bất đồng bộ qua SwingWorker nên giao diện hoàn toàn mượt mà, không bị block giao diện."*

---

### GIAI ĐOẠN 2: THAO TÁC CRUD & ĐỊNH TUYẾN BĂM HASHING ROUTER (#5, #6, #15)
* **Người trình bày:** Ngô Mạnh Hùng.
* **Người thao tác GUI:** Nguyễn Văn Nhật.

1. **Thao tác trên màn hình:**
   - Chuyển sang Tab 2: **"Giao Diện Client (Client GUI #15)"**.
2. **Kiểm tra thuật toán Băm phân tán bằng nút `INFO`:**
   - Nhập Key: `username` $\rightarrow$ Bấm nút **INFO**.
     - Bảng thông tin hiển thị:
       - **Server chính**: Server #0 (`127.0.0.1:5001`)
       - **Server dự phòng**: Server #1 (`127.0.0.1:5002`)
   - Nhập Key: `ho_ten` $\rightarrow$ Bấm nút **INFO**.
     - Bảng thông tin hiển thị: Server chính #0, dự phòng #1.
   - Nhập Key: `student_name` $\rightarrow$ Bấm nút **INFO**.
     - Bảng thông tin hiển thị: Server chính #2 (`127.0.0.1:5003`), dự phòng #0.
3. **Điểm lưu ý cốt lõi (Theo đặc tả #19):**
   > *"Nhóm đã kiểm tra giải thuật băm `|key.hashCode() % N|`. Để demo khả năng chịu lỗi (Failover) khi đánh sập Server #0, ta **bắt buộc phải sử dụng các key có Server chính thuộc về Server #0** (như `username` hoặc `ho_ten`). Nếu dùng `student_name` (thuộc Server #2) thì việc sập Server #0 sẽ không chứng minh được tính năng failover."*
4. **Thực hiện ghi và đọc dữ liệu bình thường:**
   - Nhập:
     - **Key:** `username`
     - **Value:** `Nguyen Van Nhat`
   - Bấm nút **PUT**:
     - Phản hồi từ Server: `OK`.
     - Thông tin phụ trợ: `Phục vụ bởi: Server #0 | Failover: false`.
     - Phía Server: Node #0 ghi dữ liệu vào `KeyValueStore` và tự động nhân bản (Replicate) bất đồng bộ sang Server dự phòng #1 qua cổng TCP 6002.
   - Bấm nút **GET**:
     - Phản hồi: `VALUE|Nguyen Van Nhat`.
     - Phục vụ bởi: Server #0 | Failover: false.
   - Nhập:
     - **Key:** `ho_ten`
     - **Value:** `Pham Anh Duy`
   - Bấm nút **PUT** $\rightarrow$ Server #0 trả `OK`.

---

### GIAI ĐOẠN 3: DEMO SẬP NODE THẬT, FAILOVER VÀ PHỤC HỒI TỰ ĐỘNG (#8, #9, #10, #13, #16, #19)
* **Người trình bày:** Nguyễn Tiến Đức & Nguyễn Văn Nhật.
* **Người thao tác GUI:** Nguyễn Văn Nhật.

Đây là phần trọng tâm nhất của bài báo cáo đồ án, chứng minh toàn bộ các tính năng phân tán: High Availability, Failover và Data Recovery.

```
+-----------------------------------------------------------------------------------+
| KỊCH BẢN CHỊU LỖI & PHỤC HỒI (FAULT TOLERANCE & RECOVERY):                        |
|                                                                                   |
|  [Client]                                                                         |
|     │                                                                             |
|     ├── 1. GET "username" ──> [Server #0] (UP) ────> Trả: "Nguyen Van Nhat"       |
|     │                                                                             |
|  (Bấm "Sập node #0" -> Node #0 tắt tiến trình thật, Registry báo DOWN sau 9s)      |
|     │                                                                             |
|     ├── 2. GET "username" ──X [Server #0] (Mất kết nối)                           |
|     │          │                                                                  |
|     │          └── (Tự Failover) ──> [Server #1] (Backup) ──> Trả: "Nguyen Van..."|
|     │                                                                             |
|     ├── 3. PUT "username" = "DuLieuCapNhat" ──> Ghi thẳng vào [Server #1]         |
|     │                                                                             |
|  (Bấm "Bật Lại" -> Node #0 sống dậy, chạy SYNC từ #1 và cập nhật dữ liệu mới)    |
|     │                                                                             |
|     └── 4. GET "username" ──> [Server #0] (UP) ────> Trả: "DuLieuCapNhat"         |
+-----------------------------------------------------------------------------------+
```

#### Bước 3.1: Đánh sập tiến trình Server #0 thật
1. Chuyển về Tab 1: **"Giám Sát Cụm (Dashboard)"**.
2. Chọn dòng **Server #0** trên bảng.
3. Nhấn nút **"Sập node #0"**.
4. **Hiện tượng quan sát:**
   - Hộp thoại thông báo: *"Đã gửi lệnh tắt Server #0 (cổng replication 6001)"*.
   - Khung nhật ký ghi nhận: `[SHUTDOWN] Đã gửi lệnh tắt Server #0 thành công`.
   - Cửa sổ terminal của Server #0 lập tức dừng hẳn (tiến trình bị kill bằng `Runtime.halt(0)`).
   - Sau khoảng 9 giây (Registry không nhận đủ 3 nhịp heartbeat), dòng Server #0 chuyển sang màu đỏ: **"DOWN"**.
   - Cửa sổ terminal Registry in log cảnh báo: `WARNING: [DEAD SERVER DETECTED] Server #0 (127.0.0.1:5001) da ngung hoat dong`.

#### Bước 3.2: Kiểm tra Client tự động Failover sang Server dự phòng #1
1. Chuyển sang Tab 2: **"Giao Diện Client"**.
2. Nhập Key: `username` $\rightarrow$ Bấm nút **GET**.
3. **Hiện tượng quan sát:**
   - Kết quả phản hồi: `VALUE|Nguyen Van Nhat`.
   - Dòng trạng thái in rõ: **`Phục vụ bởi: Server #1`** kèm nhãn **`[FAILOVER]`** màu đỏ/cam!
4. **Lời thuyết minh:**
   > *"Khi Server #0 bị tắt thật, Client kết nối đến 127.0.0.1:5001 bị lỗi Connection Refused. HashRouter lập tức kích hoạt cơ chế Failover chuyển tiếp truy vấn sang Server dự phòng là Server #1. Nhờ Server #0 đã replicate key này sang Server #1 trước đó, Client vẫn đọc được dữ liệu nguyên vẹn mà người dùng không gặp gián đoạn dịch vụ."*

#### Bước 3.3: Ghi dữ liệu mới trong lúc Server chính đang chết
1. Vẫn tại Tab 2 Client:
   - Key: `username`
   - Value: `DuLieuCapNhatLucNode0Chet`
2. Bấm nút **PUT**.
3. **Hiện tượng quan sát:**
   - Kết quả phản hồi: `OK`.
   - Dòng trạng thái in: `Phục vụ bởi: Server #1 | Failover: true`.
4. **Lời thuyết minh:**
   > *"Hệ thống hỗ trợ ghi trực tiếp lên node dự phòng khi node chính chết. Dữ liệu mới được đóng dấu Timestamp hiện tại (Last-Write-Wins) để sẵn sàng đồng bộ ngược lại khi node chính phục hồi."*

#### Bước 3.4: Bật lại Server #0 và cơ chế tự động khôi phục (Recovery / SYNC)
1. Quay lại Tab 1: **"Dashboard"**.
2. Chọn dòng Server #0 (đang ở trạng thái `DOWN`).
3. Nhấn nút **"Bật Lại"**.
4. **Hiện tượng quan sát:**
   - GUI khởi động một tiến trình Java mới độc lập cho Server #0 qua `NodeProcessManager`.
   - Trong vòng 1-2 giây: Server #0 mở port replication 6001 $\rightarrow$ gửi lệnh `SYNC` tới Server #1 và Server #2 để lấy lại dữ liệu.
   - Server #0 nạp lại toàn bộ dữ liệu, so sánh timestamp và cập nhật giá trị mới nhất.
   - Server #0 mở cổng Client 5001 và gửi Heartbeat tới Registry.
   - Bảng Dashboard tự động chuyển Server #0 sang trạng thái **"UP"** (màu xanh).
   - Registry in log: `INFO: [SERVER RECOVERED] Server #0 (127.0.0.1:5001) da hoat dong tro lai`.

#### Bước 3.5: Kiểm chứng tính nhất quán dữ liệu sau phục hồi
1. Quay lại Tab 2: **"Giao Diện Client"**.
2. Nhập Key: `username` $\rightarrow$ Bấm nút **GET**.
3. **Hiện tượng quan sát:**
   - Kết quả phản hồi: `VALUE|DuLieuCapNhatLucNode0Chet`.
   - Trạng thái: **`Phục vụ bởi: Server #0 | Failover: false`**.
4. **Kết luận:**
   > *"Server #0 sau khi sống lại đã tự động đồng bộ được giá trị mới nhất từ Server #1, Client đã tự động kết nối lại Server chính #0 thay vì chạy qua server dự phòng. Dữ liệu đảm bảo tính nhất quán hoàn hảo (Consistency)!"*

---

### GIAI ĐOẠN 4: DEMO ĐO TẢI ĐA LUỒNG & BÁO CÁO HIỆU NĂNG (#17)
* **Người trình bày & thao tác:** Nguyễn Văn Nhật.

1. **Thao tác trên màn hình:**
   - Chuyển sang Tab 3: **"Kiểm Thử Tải (Load Tester #17)"**.
   - Thiết lập cấu hình đo tải trên giao diện:
     - **Số luồng đồng thời (N threads):** `10`
     - **Số request mỗi luồng (M requests):** `200`
     - $\rightarrow$ Tổng số request kiểm thử: **2,000 requests**.
     - **Tỷ lệ PUT / GET:** `50% PUT / 50% GET`.
     - **Phạm vi keyspace:** `20 keys` (load_key_0 .. load_key_19).
2. **Tiến hành đo tải:**
   - Nhấn nút **"Bắt Đầu Đo Tải"** (Màu xanh lá cây nổi bật).
   - Thanh tiến trình hiển thị tỷ lệ hoàn thành theo thời gian thực ($0\% \rightarrow 100\%$).
   - Nhấn nút **"Xem File Báo Cáo"** (Màu xanh dương) để mở trực tiếp file `docs/load_test_report.txt`.
3. **Phân tích số liệu thực nghiệm báo cáo với Giảng viên:**
   - **Throughput (Thông lượng):** Đạt **2,849.00 req/s** trên cụm phân tán 3 node.
   - **Độ trễ phản hồi (Latency):**
     - Trung bình: **3.00 ms**
     - Nhanh nhất (Min): **0.66 ms**
     - p50 (Trung vị): **1.85 ms**
     - p95 (95% request): $\le$ **7.39 ms**
     - p99 (99% request): $\le$ **24.71 ms**
   - **Tỷ lệ thành công:** **100.00%** (2,000 / 2,000 requests hợp lệ theo đặc tả giao thức).
   - **Minh chứng hành vi Cache Hit & Miss:**
     - PUT: 1,019 requests $\rightarrow$ 100% trả về `OK` (`equals("OK")`).
     - GET: 981 requests $\rightarrow$ **961 Cache Hits** (`VALUE|...`) và **20 Cache Misses** (`NOTFOUND`).
   > *"Thưa Thầy/Cô, việc xuất hiện 20 lượt Cache Misses (`NOTFOUND`) minh chứng cho giải thuật trộn ngẫu nhiên và xen kẽ workload giữa các luồng. Đây là hành vi hoàn toàn chuẩn xác của một hệ thống Cache phân tán trong môi trường thực tế."*

---

## 4. TỔNG HỢP CÂU HỎI VẤN ĐÁP / PHẢN BIỆN CỦA GIẢNG VIÊN & CÂU TRẢ LỜI MẪU

Dưới đây là bộ câu hỏi phản biện chuyên sâu thường gặp của Hội đồng giảng viên môn Lập trình Mạng máy tính:

---

### Câu 1 (Hỏi Duy - Module 1): Tại sao nhóm lại sử dụng `ConcurrentHashMap` và xử lý TTL bằng cơ chế nào? Có sợ rò rỉ bộ nhớ (Memory Leak) không?
* **Trả lời:**  
  *"Nhóm sử dụng `ConcurrentHashMap` vì cấu trúc này phân đoạn lock (lock striping), cho phép nhiều luồng đọc ghi đồng thời mà không gây nghẽn cổ chai như `Hashtable` hay `synchronizedMap`. Về TTL, nhóm kết hợp 2 kỹ thuật:  
  1. **Lazy Eviction (Xóa lười):** Khi Client gọi `GET`, nếu `System.currentTimeMillis() >= expiryTimestamp` thì hệ thống xóa key ngay và trả `NOTFOUND`.  
  2. **Active Eviction (Quét định kỳ):** Sử dụng `ScheduledExecutorService` chạy luồng daemon định kỳ mỗi 5 giây quét ngẫu nhiên các key hết hạn để thu hồi RAM. Đồng thời cài đặt thuật toán LRU (Least Recently Used) để loại bỏ key lâu nhất khi số lượng phần tử vượt ngưỡng `cache.maxEntries`."*

---

### Câu 2 (Hỏi Hùng - Module 2): Thuật toán chọn node là gì? Tại sao không dùng Mod trực tiếp `hash % N` mà phải lấy `Math.abs` trước?
* **Trả lời:**  
  *"Nhóm áp dụng công thức: $\text{serverIndex} = |\text{key.hashCode}() \pmod N|$. Trong Java, `hashCode()` có thể trả về giá trị âm (đặc biệt là `Integer.MIN_VALUE = -2147483648`). Nếu lấy `Math.abs(hash) % N`, khi gặp `Integer.MIN_VALUE`, `Math.abs` bị tràn số nguyên và vẫn ra số âm, dẫn tới `ArrayIndexOutOfBoundsException`. Vì vậy, hàm `HashUtil.computeServerIndex` của nhóm lấy `Math.abs(key.hashCode() % n)` để đảm bảo chỉ số luôn nằm trong đoạn $[0, N-1]$."*

---

### Câu 3 (Hỏi Đức - Module 3): Khi server chết rồi sống lại, nếu hai server có cùng key nhưng khác giá trị thì giải quyết xung đột (Conflict Resolution) thế nào?
* **Trả lời:**  
  *"Nhóm áp dụng nguyên lý **Last-Write-Wins (LWW)** dựa trên Timestamp ghi nhận từ server tiếp nhận thao tác gốc. Mỗi bản tin `REPLICATE_PUT` mang theo `original_write_timestamp`. Khi server phục hồi thực hiện lệnh `SYNC`, nó nhận danh sách `key|value|timestamp|expiry`. Nếu timestamp của dữ liệu đồng bộ lớn hơn timestamp hiện có trong node, node sẽ ghi đè giá trị mới. Nhờ đó, dữ liệu được ghi trong lúc node chết (như ta vừa demo ở Giai đoạn 3) được cập nhật chuẩn xác."*

---

### Câu 4 (Hỏi Tuấn Anh - Module 4): Registry Server phát hiện server chết bằng cách nào? Cơ chế phân biệt server chết với mất kết nối mạng tạm thời?
* **Trả lời:**  
  *"Registry Server mở socket lắng nghe tại cổng 6000. Mỗi Cache Server định kỳ 3 giây gửi bản tin `HEARTBEAT|serverIndex|host|port|keyCount|requestCount`. Tại Registry, một luồng quét định kỳ mỗi 1 giây kiểm tra timestamp của nhịp heartbeat cuối cùng. Nếu thời gian im lặng vượt quá ngưỡng `registry.timeoutMs` (mặc định 9 giây, tương đương mất 3 nhịp liên tiếp), Registry mới đánh dấu server là `DOWN` để tránh nhầm lẫn do mạng chập chờn nhất thời. Khi nhận được heartbeat tiếp theo, node được chuyển ngay về `UP`."*

---

### Câu 5 (Hỏi Nhật - Module 5): Tại sao khi đo tải bằng LoadTester, kết quả của nhóm đạt tới hơn 2,800 req/s trên máy đơn? Giao diện GUI có bị treo khi chạy tải lớn không?
* **Trả lời:**  
  *"Giao diện GUI không hề bị treo vì toàn bộ quá trình đo tải được thực thi trong một `ExecutorService` riêng với $N$ luồng worker độc lập. Việc cập nhật thanh tiến trình và bảng thống kê được đẩy về luồng sự kiện đồ họa (Event Dispatch Thread - EDT) thông qua `SwingUtilities.invokeLater`. Đạt được thông lượng ~2,849 req/s là nhờ kết nối Socket TCP dạng Non-blocking/Keep-Alive, kiến trúc xử lý đa luồng tối ưu ở Server và thời gian đáp ứng trung bình của bộ nhớ RAM chỉ 3.00 ms."*

---

## 5. BẢNG CHECKLIST TRƯỚC GIỜ BÁO CÁO

- [x] Đã build thành công bản mới nhất: `mvn clean package` (0 errors).
- [x] Đã chạy thử kịch bản Sập node #0 và Bật lại với key `username` (kiểm tra logs khớp).
- [x] Cụm 3 server và Registry mở đúng các cổng: `5001-5003`, `6000-6003`.
- [x] File báo cáo đo tải `docs/load_test_report.txt` tồn tại và mang số liệu tải lớn 2,000 req.
- [x] Các terminal chạy server hiển thị rõ ràng, font chữ UTF-8 (`chcp 65001`).
