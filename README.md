# KeyNet

Mô phỏng Hệ thống Bộ đệm Phân tán (Distributed Cache System) với Key-Value Store — đồ án cuối kỳ môn **Lập trình mạng máy tính**, Nhóm 05, lớp 23DTHA5.

Nhiều Cache Server nhân bản hoạt động như Key-Value Store; Client dùng hashing (`|hash(key)| mod N`) để chọn server, tự chuyển sang server dự phòng khi server chính gặp sự cố; Registry theo dõi trạng thái các server qua heartbeat.

> Repo này thay cho repo cũ [`duystts/KeyNet-old`](https://github.com/duystts/KeyNet-old) (đã archive). Nhóm làm lại từ đầu trên repo mới để áp dụng đúng quy trình Git của môn học (Issue, nhánh feature, Pull Request có review chéo).

## Tài liệu

- [Đặc tả giao thức](./docs/Dac-ta-giao-thuc-Nhom05.docx) — format message, mã lỗi, công thức hashing, giao thức replication/heartbeat, cấu trúc package. **Đọc trước khi code.**
- [Phân công công việc](./docs/Phan-cong-cong-viec-Nhom05.docx) — 5 module, deliverable, mốc thời gian.

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

## Quy trình làm việc

1. Mỗi việc là một **Issue** (gán đúng 1 người, có label và milestone).
2. Tạo nhánh từ `master`: `feature/<MSSV>-issue-<số>-<tên-ngắn>`.
3. Commit theo Conventional Commits: `feat: ...`, `fix: ...`, `refactor: ...`, `docs: ...`.
4. Mở Pull Request (ghi `Closes #<số>`, mô tả đã làm gì, ảnh hưởng module nào, kèm ảnh chụp chạy thành công).
5. Ít nhất **1 thành viên khác** review và Approve rồi mới merge. Nhánh `master` được khóa, không push trực tiếp.

## Bảng đóng góp

| MSSV | Họ và tên | Công việc đã thực hiện | Pull Requests |
|---|---|---|---|
| | Phạm Anh Duy (nhóm trưởng) | | |
| | Ngô Mạnh Hùng | | |
| | Nguyễn Tiến Đức | | |
| | Nguyễn Trần Tuấn Anh | | |
| | Nguyễn Văn Nhật | | |
