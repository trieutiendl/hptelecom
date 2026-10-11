# Dựng staging HP Telecom trên NAS

Bản staging chạy độc lập với production: giao diện phục vụ trên cổng `18082`, API chỉ truy cập trong mạng Docker, và SQLite được lưu ở volume `hptelecom-staging-api-data`. Không dùng lại thư mục dự án, container hay volume production.

## Bước 1 — chuẩn bị thư mục staging

1. Trong File Station, tạo thư mục riêng, ví dụ `docker/hptelecom-staging`.
2. Tải gói staging lên thư mục đó rồi giải nén. Cần giữ nguyên cấu trúc thư mục trong gói.
3. Sao chép `.env.staging.example` thành `.env`. Trước khi chạy trên NAS, đặt `STAGING_PUBLIC_ORIGIN` thành địa chỉ HTTPS riêng dành cho staging, ví dụ `https://staging.hptelecom.id.vn`. Không dùng địa chỉ production.

## Bước 2 — chạy project staging

Trong DSM Container Manager, tạo Project từ thư mục `hptelecom-staging` và chọn tệp `compose.staging.yaml`. Build và khởi chạy Project. Project tạo hai container `hptelecom-staging-web` và `hptelecom-staging-api`; dữ liệu API mới được lưu riêng trong volume `hptelecom-staging-api-data`.

Có thể kiểm tra trạng thái từ terminal của NAS:

```sh
cd /volume1/docker/hptelecom-staging
docker compose --env-file .env -f compose.staging.yaml up -d --build
docker compose --env-file .env -f compose.staging.yaml ps
```

Để dừng riêng staging:

```sh
docker compose --env-file .env -f compose.staging.yaml down
```

Không thêm `-v` khi dừng; tùy chọn đó xóa volume dữ liệu staging.

## Bước 3 — mở địa chỉ staging

Tạo DNS cho hostname staging trỏ về NAS, thêm chứng chỉ HTTPS cho hostname đó, rồi cấu hình DSM Reverse Proxy chuyển hostname staging tới `http://127.0.0.1:18082`. Dùng đúng hostname HTTPS đó trong `STAGING_PUBLIC_ORIGIN` và khởi động lại Project sau khi sửa `.env`.

Khi cấu hình Reverse Proxy, bật chuyển tiếp `X-Forwarded-For` và `X-Forwarded-Proto`. Không mở cổng API ra Internet. Khi hoàn tất, kiểm tra `https://staging.hptelecom.id.vn/api/health` và các trang giao diện trên hostname staging.

Lần đầu staging có database trống. Tạo tài khoản quản trị staging bằng quy trình khởi tạo một lần của API; mã khởi tạo xuất hiện trong log container `hptelecom-staging-api`. Không dùng tài khoản hoặc volume production để khởi tạo staging.

## Giới hạn

- Giao diện được đóng gói vào image khi build. Sau khi tải phiên bản mới lên, chạy lại lệnh `up -d --build` để cập nhật giao diện/API staging.
- `STAGING_WEB_PORT` mặc định là `18082`. Nếu cổng đã được dùng, đổi sang cổng loopback khác rồi cập nhật đích Reverse Proxy cho khớp.
- Đường dẫn `/nhom/<slug>` được Nginx staging chuyển về trang Team/Đội để có thể mở trực tiếp.
- Staging không tự sao chép dữ liệu production; đây là cơ sở dữ liệu mới và tách biệt.
