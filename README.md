# HP Telecom — giao diện staging

Đồng bộ toàn bộ 17 trang giao diện đang chạy tại https://hptelecom.id.vn/staging/ ngày 12/10/2026 (bản filters-20261012-r4).

- Đường dẫn và API của giao diện đều nằm trong /staging/.
- Quản trị đăng nhập vào /staging/nguoidung/; vai trò khác vào /staging/tonghop/tongquan/. Nginx kiểm tra quyền quản trị trước khi phục vụ trang Người dùng.
- Menu Tổng hợp/Báo cáo mở khi rê chuột và thu lại khi rời khỏi, kể cả sau khi chọn mục con.
- Tổng quan chọn Team/Đội từ các nhóm tài khoản được phép xem và chọn thời gian theo tháng. Các biểu đồ/số liệu Tổng quan vẫn là minh họa, chưa lọc dữ liệu báo cáo thật.
- API và volume dữ liệu staging chạy riêng. Mã nguồn không chứa tài khoản, mật khẩu, cơ sở dữ liệu hay cấu hình đăng nhập của NAS.

Mã giao diện nằm ngay trong các thư mục dangnhap, dangky, nguoidung, nhom, baocao, tonghop... Xem [DEPLOY-STAGING.md](DEPLOY-STAGING.md) để chạy trên NAS; không mở bằng file:// để kiểm tra đăng nhập hoặc API.

Thư mục trieutiendl-verbose-disco và file RAR là bản lưu cũ được giữ từ lịch sử kho. Dockerfile.staging chỉ đóng gói các trang hiện tại, không dùng bản lưu đó.
