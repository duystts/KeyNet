# KỊCH BẢN DEMO CHI TIẾT BÁO CÁO ĐỒ ÁN KEYNET
## Môn: Lập trình mạng máy tính — Nhóm 05 (Lớp 23DTHA5)
### Thành viên thực hiện demo: Nguyễn Văn Nhật & Nhóm 05

---

### 📋 MỤC TIÊU BÀI DEMO
Minh chứng thực tế cho Giảng viên thấy 4 yếu tố cốt lõi của hệ thống:
1. **Khả năng Giám sát Cluster tập trung (Registry & Health Check).**
2. **Thuật toán Hashing Router định tuyến dữ liệu tự động.**
3. **Cơ chế Chịu lỗi (Failover) & Tự phục hồi dữ liệu khi Server sập.**
4. **Giao diện Quản trị GUI & Công cụ Load Test chịu tải.**

---

### 🎬 CÁC BƯỚC THỰC HIỆN DEMO CHI TIẾT

#### **BƯỚC 1: Khởi động hệ thống (Registry + 3 Cache Server)**

1. **Khởi động Registry Server (Port 6000):**
   - Chạy lệnh: `java -cp target/keynet.jar com.nhom05.cache.registry.RegistryServer 6000`
   - *Giải thích với Giảng viên:* Tiến trình Registry tập trung lắng nghe ở port 6000 để tiếp nhận Heartbeat và quản lý trạng thái của cả cụm.

2. **Khởi động 3 Cache Server (Server 0, Server 1, Server 2):**
   - Terminal 2: `java -jar target/keynet.jar config/config.properties 0`
   - Terminal 3: `java -jar target/keynet.jar config/config.properties 1`
   - Terminal 4: `java -jar target/keynet.jar config/config.properties 2`
   - *Giải thích với Giảng viên:* Cụm gồm 3 server chạy trên 3 port client (5001, 5002, 5003) và 3 port replication (6001, 6002, 6003).

---

#### **BƯỚC 2: Thuyết minh Giao diện GUI Dashboard (`KeyNetGuiApp`)**

1. **Mở ứng dụng GUI:**
   - Chạy lệnh: `java -cp target/keynet.jar com.nhom05.cache.gui.KeyNetGuiApp`
2. **Thuyết minh Thẻ 1 (Cluster Dashboard):**
   - Chỉ vào bảng Dashboard hiển thị 3 Server với trạng thái màu xanh **UP**.
   - Chỉ số KeyCount = 0, RequestCount tăng dần mỗi khi có tương tác.

3. **Thuyết minh Thẻ 2 (Interactive Client):**
   - Nhập Key: `student_name`, Value: `Nguyen Van Nhat`.
   - Bấm nút **INFO**: Giao diện hiển thị Primary Server được chọn là `Server #0`, Replica Server là `Server #1`.
   - Bấm nút **PUT**: Ghi dữ liệu vào cache. Console hiển thị `OK`.
   - Bấm nút **GET**: Đọc dữ liệu ra. Console hiển thị `VALUE|Nguyen Van Nhat`.

---

#### **BƯỚC 3: Demonstration Failover & Recovery (⭐ Phần ghi điểm cao nhất)**

1. **Giả lập Server sập:**
   - Sang Terminal Server 0, nhấn `Ctrl + C` để ngắt kết nối Server 0.
   - Nhìn sang màn hình GUI Dashboard: Sau 9 giây, ô trạng thái Server 0 tự động đổi sang màu đỏ **DOWN**.

2. **Thử nghiệm Failover trên Client:**
   - Quay lại tab Client trên GUI, bấm nút **GET** cho key `student_name`.
   - *Kết quả:* Client tự động chuyển hướng sang đọc từ Replica `Server #1` ➔ Vẫn lấy lại đúng giá trị `Nguyen Van Nhat`!
   - *Kết luận:* Hệ thống đảm bảo tính sẵn sàng cao (High Availability), người dùng không bị mất dữ liệu khi 1 server bị nổ/sập.

3. **Giả lập Server sống lại (Recovery):**
   - Bật lại Server 0 ở Terminal 2.
   - Log Server 0 báo gửi lệnh `SYNC` để kéo lại các key mới từ Server 1.
   - Trên GUI Dashboard, ô trạng thái Server 0 lập tức đổi lại thành màu xanh **UP**.

---

#### **BƯỚC 4: Demonstration Load Testing chịu tải đa luồng**

1. **Chạy công cụ kiểm thử hiệu năng:**
   - Chạy lệnh: `java -cp target/keynet.jar com.nhom05.cache.gui.LoadTester 20 2000`
2. **Giải thích kết quả Benchmark cho Giảng viên:**
   - **Throughput:** Đạt ~4,000 ops/sec.
   - **Tỷ lệ thành công:** 100%.
   - **Báo cáo log:** Mở file `docs/load_test_report.txt` để minh chứng dữ liệu kiểm thử.

---
*Kịch bản được chuẩn bị chi tiết bởi Nguyễn Văn Nhật — Nhóm 05.*
