# HP Telecom API

API nội bộ cho tài khoản, sự cố và di dời. Server dùng Python 3.12 và SQLite, không cần cài thư viện Python ngoài. Dữ liệu được lưu trong Docker volume `hptelecom-api-data`.

## Chạy thử

```sh
cp .env.example .env
docker compose -f compose.yaml up -d --build
docker compose -f compose.yaml logs hptelecom-api
```

API chỉ publish ở `127.0.0.1:18080`; kiểm tra trạng thái tại `http://127.0.0.1:18080/api/health`. Không mở cổng `18080` trực tiếp ra Internet.

## Triển khai trên Synology

1. Tạo một Project trong Container Manager từ thư mục `api-server`, gồm `compose.yaml`, `Dockerfile`, `server.py` và `.env`. Sao chép `.env.example` thành `.env`, giữ `PUBLIC_ORIGIN=https://hptelecom.id.vn` và `COOKIE_SECURE=1`.
2. Build và chạy project. Volume `hptelecom-api-data` giữ SQLite và mã khởi tạo qua lần restart/container rebuild.
3. Trong DSM Reverse Proxy, thêm quy tắc HTTPS cho host `hptelecom.id.vn` với đường dẫn nguồn `/api` trỏ đến HTTP `127.0.0.1:18080`, giữ nguyên prefix `/api`. Dùng chứng chỉ HTTPS hợp lệ của domain.
4. Bảo đảm reverse proxy ghi đè `X-Forwarded-For` bằng địa chỉ client thực; chỉ khi đó mới để `TRUST_PROXY=1`. Cổng container đã bind vào loopback để không truy cập thẳng từ Internet.
5. Đọc mã khởi tạo từ log container: `docker logs hptelecom-api`. Mã được in lại sau restart nếu admin chưa tạo, và bị xóa khỏi volume ngay sau khi dùng thành công. Gửi một yêu cầu `POST /api/setup` để tạo tài khoản admin đầu tiên; thao tác này chỉ dùng được một lần.
6. Kiểm tra `https://hptelecom.id.vn/api/health`. Không xóa volume khi cập nhật image; hãy sao lưu volume trước khi nâng cấp hoặc chuyển NAS.

Ví dụ tạo admin (thay mã khởi tạo và thông tin tài khoản):

```sh
curl -i https://hptelecom.id.vn/api/setup \
  -H "Content-Type: application/json" \
  -H "Origin: https://hptelecom.id.vn" \
  --data '{"setupCode":"PASTE_ONE_TIME_CODE","fullName":"Quản trị viên","username":"admin","email":"admin@example.com","password":"CHANGE_TO_A_LONG_UNIQUE_PASSWORD"}'
```

## Endpoint

Tất cả dữ liệu trao đổi là JSON UTF-8. Lỗi có dạng `{"error":{"code":"...","message":"..."}}`. Phiên đăng nhập dùng cookie `HttpOnly`, `Secure` và `SameSite=Strict`.

| Method | Path | Quyền | Tác dụng |
|---|---|---|---|
| GET | `/api/health` | Công khai | Kiểm tra tiến trình và SQLite |
| POST | `/api/setup` | Mã khởi tạo một lần | Tạo admin đầu tiên |
| POST | `/api/auth/register` | Công khai | Đăng ký tài khoản `pending` |
| POST | `/api/auth/login` | Công khai | Đăng nhập tài khoản đã duyệt |
| POST | `/api/auth/logout` | Đã đăng nhập | Hủy phiên |
| GET | `/api/auth/me` | Đã đăng nhập | Xem tài khoản hiện tại |
| GET | `/api/teams` | Đã đăng nhập | Xem các nhóm mình tham gia (admin xem toàn bộ nhóm), kèm slug duy nhất |
| POST | `/api/teams` | Tài khoản đã duyệt | Tạo nhóm cá nhân hoặc tổ chức; người tạo là chủ nhóm/Điều phối viên; tên hiển thị giữ nguyên, slug URL tự thêm số nếu trùng |
| POST | `/api/teams/{id}/members` | Điều phối viên nhóm | Thêm tài khoản đang hoạt động; vai trò mặc định là Kỹ thuật viên |
| PATCH | `/api/teams/{id}/members/{userId}` | Chủ nhóm/admin | Cấp hoặc bỏ quyền Điều phối viên trong nhóm |
| DELETE | `/api/teams/{id}/members/{userId}` | Điều phối viên nhóm, admin hoặc thành viên đó | Xóa thành viên hoặc tự rời nhóm; không thể xóa chủ nhóm |
| DELETE | `/api/teams/{id}` | Chủ nhóm/admin | Xóa nhóm và danh sách thành viên |
| GET | `/api/users?status=pending` | Admin | Liệt kê tài khoản, lọc theo trạng thái |
| PATCH | `/api/users/{id}` | Admin | Duyệt/vô hiệu hóa và đổi vai trò |
| DELETE | `/api/users/{id}` | Admin | Xóa tài khoản, không cho xóa admin cuối |
| GET | `/api/incidents` | Đã đăng nhập | Lọc theo `month`, `team`, `limit`, `offset` |
| POST | `/api/incidents` | Admin/điều phối | Tạo báo cáo sự cố |
| PATCH/DELETE | `/api/incidents/{id}` | Admin/điều phối | Sửa/xóa báo cáo sự cố |
| GET | `/api/relocations` | Đã đăng nhập | Lọc theo `month`, `team`, `limit`, `offset` |
| POST | `/api/relocations` | Admin/điều phối | Tạo báo cáo di dời |
| PATCH/DELETE | `/api/relocations/{id}` | Admin/điều phối | Sửa/xóa báo cáo di dời |

Báo cáo nhận `month` theo `YYYY-MM`, `code`, `team` và object `data`. `team` là tên tự do tối đa 120 ký tự; có thể để trống. Đăng ký yêu cầu họ tên, username, email và mật khẩu tối thiểu 12 ký tự; admin phải chuyển `status` sang `active` thì người dùng mới đăng nhập được. Vai trò hợp lệ: `admin`, `coordinator`, `operator`.

Trang đăng ký và trang Team/Đội dùng API cùng nguồn `/api`; cần triển khai API và cấu hình reverse proxy trên NAS trước khi dùng dữ liệu thật. Nhóm cá nhân/tổ chức do người dùng đã duyệt tự tạo; thành viên được thêm bằng tên đăng nhập hoặc email. Người tạo là Điều phối viên chủ nhóm, thành viên mới là Kỹ thuật viên, và chỉ chủ nhóm mới được cấp hoặc bỏ quyền Điều phối viên cho thành viên khác. Vai trò trong nhóm độc lập với quyền quản trị toàn hệ thống. Team/Đội nhận tên tự do, không phụ thuộc danh sách 34 tỉnh/thành.

Trang `/nhom/<slug>` mở trực tiếp một nhóm. Ví dụ hai nhóm cùng tên `demo` sẽ lần lượt có đường dẫn `/nhom/demo` và `/nhom/demo1`, trong khi tên hiển thị vẫn là `demo`. `nhom/.htaccess` cung cấp rewrite cho Apache Web Station; nếu NAS phục vụ trang bằng Nginx, hãy thêm rewrite tương đương trong cấu hình Web Station để các đường dẫn con trả về `nhom/index.html`.

## Kiểm thử

```sh
python test_server.py
```

Kiểm thử tích hợp chạy server trên loopback và dùng SQLite tạm; không chạm vào dữ liệu triển khai.
