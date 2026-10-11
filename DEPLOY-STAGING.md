# Chạy giao diện staging trên NAS

Bản này phục vụ đường dẫn https://hptelecom.id.vn/staging/. Cổng web là 127.0.0.1:18083, API chỉ dùng trong mạng Docker. Cấu hình không định tuyến các trang của server chính.

## Chuẩn bị

Tải đúng nhánh staging-ui-20261012 và giải nén toàn bộ. Chọn thư mục chứa compose.staging.yaml, Dockerfile.staging, api-server và các thư mục trang; không chọn thư mục chỉ chứa một bản vá.

Trong Container Manager, dùng project hptelecom-staging-independent và cấu hình compose.staging.yaml. Không bật Web Station portal. Reverse proxy/tunnel phải giữ nguyên đường dẫn /staging/ và chuyển đến HTTP 127.0.0.1:18083. HTTPS kết thúc tại proxy; chỉ tin các header chuyển tiếp do proxy kiểm soát.

Các mặc định đã dùng đúng tên miền, cổng và tên volume của staging hiện tại. Nếu cần thay giá trị, sao chép .env.staging.example thành .env trong cùng thư mục hoặc truyền các biến STAGING_* vào Compose.

## Cập nhật staging đã có

Giữ nguyên project và volume hptelecom-staging-independent_hptelecom-staging-independent-api-data. Không tạo project trùng container đang chạy, không xóa/reset volume. Sao lưu dữ liệu trước khi thay cấu hình triển khai.

Sau khi đưa mã vào thư mục project và cập nhật cấu hình để build từ các đường dẫn tương đối trong gói, có thể chỉ build lại web:

~~~sh
docker compose -f compose.staging.yaml up -d --build --no-deps hptelecom-staging-independent-web
~~~

Lệnh này dùng API/volume đang chạy. Không cần khởi tạo lại tài khoản quản trị. Dockerfile của API đã sửa quyền đọc server.py để lần build lại sau không gặp Permission denied.

## Cài mới

~~~sh
docker compose -f compose.staging.yaml up -d --build
docker compose -f compose.staging.yaml ps
~~~

Chỉ khi volume hoàn toàn mới: đọc mã khởi tạo một lần từ log API và dùng POST /staging/api/setup để tạo quản trị. Xem định dạng yêu cầu ở api-server/README.md, thay URL /api/ thành /staging/api/. Không đưa mã khởi tạo hoặc thông tin tài khoản lên GitHub.

## Xác nhận

- /staging/api/health trả về trạng thái khỏe.
- /staging/dangnhap/ mở được. Quản trị vào Người dùng; vai trò khác vào Tổng hợp/Tổng quan.
- Tài khoản không có quyền quản trị không xem được trang Người dùng.
- Menu thu lại sau khi rê chuột ra ngoài. Team/Đội tải từ /staging/api/teams; ô bên cạnh chọn tháng.
- Liên kết nội bộ giữ /staging/; /staging/tongquan/ chuyển đến /staging/tonghop/tongquan/.

33 tệp giao diện đã đối chiếu byte với bản đóng gói được triển khai. Kiểm tra cú pháp 24 đoạn JavaScript và 150 liên kết nội bộ không có lỗi. Chức năng menu và bộ chọn nhóm/tháng đã được kiểm tra trên staging. Bố cục Dockerfile ở kho này dùng các thư mục gốc thay cho thư mục web của gói NAS; chưa build lại bố cục kho này bằng Docker trên máy Windows.

Số liệu Tổng quan vẫn là dữ liệu minh họa; thay nhóm/tháng chưa lọc các biểu đồ theo báo cáo thật.
