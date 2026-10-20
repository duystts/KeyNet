# KeyNet

Mô phỏng Hệ thống Bộ đệm Phân tán (Distributed Cache System) với Key-Value Store — đồ án cuối kỳ môn **Lập trình mạng máy tính**, Nhóm 05, lớp 23DTHA5.

Nhiều Cache Server nhân bản hoạt động như Key-Value Store; Client dùng hashing (`|hash(key)| mod N`) để chọn server, tự chuyển sang server dự phòng khi server chính gặp sự cố; Registry theo dõi trạng thái các server qua heartbeat.

> Repo này thay cho repo cũ [`duystts/KeyNet-old`](https://github.com/duystts/KeyNet-old) (đã archive). Nhóm làm lại từ đầu trên repo mới để áp dụng đúng quy trình Git của môn học (Issue, nhánh feature, Pull Request có review chéo).

## Tài liệu

- [Đặc tả giao thức](./docs/Dac-ta-giao-thuc-Nhom05.docx) — format message, mã lỗi, công thức hashing, giao thức replication/heartbeat, cấu trúc package. **Đọc trước khi code.**
- [Phân công công việc](./docs/Phan-cong-cong-viec-Nhom05.docx) — 5 module, deliverable, mốc thời gian.
- [Đặc tả triển khai](./docs/DAC_TA_TRIEN_KHAI.md) — tên lớp, hàm public, cổng mạng, định dạng lệnh cho từng Issue. **Đọc phần của mình trước khi làm Issue.**
- [Kịch bản demo đồ án](./docs/KICH_BAN_DEMO.md) — kịch bản từng bước trình diễn trước giảng viên (vấn đáp, failover, recovery, load test) (#19).
- [Báo cáo đồ án](./docs/BAO_CAO_DO_AN.md) — bản báo cáo đồ án hoàn chỉnh môn Lập trình Mạng máy tính (#19).
- [Báo cáo đo tải](./docs/load_test_report.txt) — kết quả đo tải thực tế 2,000 req trên cụm 3 server (#17).

## Cấu trúc package

```
com.nhom05.cache.common        dùng chung: ErrorCode, ProtocolParser, HashUtil, ServerConfig
com.nhom05.cache.server        Module 1 - Core Cache Server / KV engine
com.nhom05.cache.client        Module 2 - Client + Hashing Router + Failover
com.nhom05.cache.replication   Module 3 - Replication & Consistency
com.nhom05.cache.registry      Module 4 - Monitoring / Registry / Health Check
com.nhom05.cache.gui           Module 5 - GUI, dashboard, load test
```

## Yêu cầu & build

- Java 17+, Maven 3.8+

```bash
mvn test       # chạy toàn bộ test JUnit
mvn package    # build target/keynet.jar
```

Cấu hình cluster: [`config/config.properties`](./config/config.properties).

### Chạy cả cụm (Registry + 3 Cache Server)

Mỗi lệnh chạy ở 1 cửa sổ terminal riêng, **bật Registry trước**:

```bash
java -cp target/keynet.jar com.nhom05.cache.registry.RegistryServer      # Registry, port 6000
java -jar target/keynet.jar config/config.properties 0   # server #0, port 5001 (replication 6001)
java -jar target/keynet.jar config/config.properties 1   # server #1, port 5002 (replication 6002)
java -jar target/keynet.jar config/config.properties 2   # server #2, port 5003 (replication 6003)
```

Tham số thứ 2 (serverIndex) ghi đè `cache.serverIndex` trong file, nên 3 server dùng chung 1 file cấu hình.

Khi khởi động, mỗi server: mở port replication (port client + 1000) → lấy lại dữ liệu từ 2 server kề (`recover`) → mở port client → `recover` thêm 1 lần → gửi heartbeat 3 giây/lần tới Registry. Mỗi PUT/DELETE thành công được gửi bất đồng bộ sang server dự phòng `(i+1) mod N`.

Client dòng lệnh (tự failover sang server dự phòng, in kèm server đã trả lời):

```bash
java -cp target/keynet.jar com.nhom05.cache.client.ClientConsole config/config.properties
> PUT username Phạm Anh Duy
OK  (server #0)
```

Trên Windows chạy `chcp 65001` trước để hiển thị tiếng Việt đúng.

Xem trạng thái cụm và thử tắt server:

```bash
printf 'STATS\n' | nc 127.0.0.1 6000      # STATS|0:UP:12:340;1:UP:10:200;2:UP:9:150
printf 'SHUTDOWN\n' | nc 127.0.0.1 6001   # tắt server #0 (chỉ nhận từ localhost)
```

Tắt 1 server: client tự chuyển sang server dự phòng, Registry báo `DOWN` sau ~9 giây. Bật lại server đó bằng đúng lệnh cũ: nó tự lấy lại dữ liệu (kể cả các key bị sửa/xóa lúc nó chết) rồi mới nhận client.

Thử nhanh bằng telnet (`telnet 127.0.0.1 5001`) hoặc netcat:

```bash
printf 'PUT|username|Duy\n' | nc 127.0.0.1 5001   # OK
printf 'GET|username\n' | nc 127.0.0.1 5001        # VALUE|Duy
printf 'DELETE|username\n' | nc 127.0.0.1 5001     # OK
```

## Quy trình làm việc

1. Mỗi việc là một **Issue** (gán đúng 1 người, có label và milestone).
2. Tạo nhánh từ `main`: `feature/<MSSV>-issue-<số>-<tên-ngắn>`.
3. Commit theo Conventional Commits: `feat: ...`, `fix: ...`, `refactor: ...`, `docs: ...`.
4. Mở Pull Request (ghi `Closes #<số>`, mô tả đã làm gì, ảnh hưởng module nào, kèm ảnh chụp chạy thành công).
5. Ít nhất **1 thành viên khác** review và Approve rồi mới merge. Nhánh `main` được khóa, không push trực tiếp.

Thứ tự làm các Issue: xem Issue **📌 Lộ trình** (#27). Label `sẵn sàng` / `chờ issue khác` được GitHub Actions tự cập nhật mỗi khi có Issue đóng (`.github/workflows/roadmap-labels.yml`, test: `node --test ".github/scripts/*.test.js"`).

## Bảng đóng góp

| MSSV | Họ và tên | Module & Công việc đã thực hiện | Pull Requests |
|:---:|:---|:---|:---|
| 2380600371 | **Phạm Anh Duy** *(Nhóm trưởng)* | **Module 1**: Core Cache Server, KeyValueStore, package common, TTL, LRU, tích hợp cụm | #21, #22, #23, #24, #26, #29, #31, #32, #40, #44, #48 |
| 2380600773 | **Ngô Mạnh Hùng** | **Module 2**: CacheClient, HashRouter (`\|hash\| mod N`), Failover tự động, ClientConsole | #33, #34, #38 |
| 2380600542 | **Nguyễn Tiến Đức** | **Module 3**: ReplicationHandler, Last-Write-Wins timestamp, SYNC recovery khi node sống lại | #37, #42, #43, #49, #51 |
| 238060... | **Nguyễn Trần Tuấn Anh** | **Module 4**: RegistryServer, Heartbeat định kỳ 3s, Health check phát hiện node chết sau 9s | #35 |
| 2380601556 | **Nguyễn Văn Nhật** | **Module 5**: Swing GUI Dashboard (#14), Client GUI (#15), Nút tắt/bật node thật (#16), LoadTester (#17), Kịch bản demo & Báo cáo đồ án (#19) | #50, #52, #53, #54 |

