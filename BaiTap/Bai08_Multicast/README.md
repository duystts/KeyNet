# Bài 08 – Lập trình UDP Multicast

Sinh viên: Phạm Anh Duy – MSSV 2380600371 – Lớp 23DTHA5

Báo cáo: [`BAI08_2380600371_PhamAnhDuy.docx`](./BAI08_2380600371_PhamAnhDuy.docx) (lý thuyết LT01–LT05, bài tập BT01–BT03).

## Biên dịch (Java 17+)

```bash
cd BaiTap/Bai08_Multicast
javac -encoding UTF-8 -d out $(find src -name "*.java")
```

Nhóm mặc định `224.1.1.1`, cổng `4444`. Luôn chạy **máy nhận trước** máy gửi.

## BT01 – Gửi / nhận thông điệp

```bash
java -cp out bai08.bt01.MulticastReceiver          # mở nhiều cửa sổ
java -cp out bai08.bt01.MulticastSender            # gửi "Xin chào!"
java -cp out bai08.bt01.MulticastSender "Nội dung khác"
java -cp out bai08.bt01.MulticastSender exit       # các receiver rời nhóm và thoát
```

## BT02 – Chat nhóm (Swing)

```bash
java -cp out bai08.bt02.MulticastChat              # mở nhiều cửa sổ, nhập Username, bấm "Tham gia"
```

## BT03 – Phân phối file

```bash
java -cp out bai08.bt03.FileReceiver nhan1          # [thuMucLuu] [tiLeMatGoiGiaLap] [nhom] [cong]
java -cp out bai08.bt03.FileReceiver nhan2 0.3      # giả lập mất 30% gói để thử cơ chế NACK
java -cp out bai08.bt03.FileSender duong/dan/file 2 # [soMayNhan] [nhom] [cong]
```

Máy gửi nhận NACK/DONE qua UDP unicast cổng `4445` – cần mở cổng này trên tường lửa khi chạy trên nhiều máy.
Nếu máy có nhiều card mạng (VPN, VirtualBox…) mà không nhận được gói, thêm `-Djava.net.preferIPv4Stack=true` hoặc tắt card ảo.
