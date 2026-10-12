# HP Telecom — giao diện staging

Đồng bộ toàn bộ 17 trang giao diện staging tại https://hptelecom.id.vn/staging/ ngày 12/10/2026 (bản live-20261012-r6).

- Đường dẫn và API của giao diện đều nằm trong /staging/.
- Quản trị đăng nhập vào /staging/nguoidung/; vai trò khác vào /staging/tonghop/tongquan/. Nginx kiểm tra quyền quản trị trước khi phục vụ trang Người dùng.
- Menu Tổng hợp/Báo cáo mở khi rê chuột và thu lại khi rời khỏi, kể cả sau khi chọn mục con.
- Cả 9 trang có thanh bên đều có cùng 9 liên kết, gồm Team/Đội của tôi; mục Người dùng chỉ dành cho quản trị.
- Tổng quan lấy các báo cáo sự cố/di dời đã lưu trong API staging, có phân trang, lọc theo nhóm và tháng. Tháng trống hiển thị 0; lỗi tải hiển thị thông báo, không thay bằng dữ liệu mẫu. Chưa có dữ liệu tuần tuyến trong API nên không tính chỉ số tuần tuyến.
- Ô Team/Đội trên 5 trang Tổng hợp/Báo cáo có gợi ý nhóm người dùng đã tạo hoặc tham gia, tìm không dấu và chọn bằng bàn phím. Tên trùng được phân biệt bằng đường dẫn nhóm. Các bảng báo cáo mẫu hiện có vẫn lọc theo tên nhóm; cần dữ liệu có mã nhóm để phân biệt số liệu giữa các nhóm trùng tên.
- API và volume dữ liệu staging chạy riêng. Mã nguồn không chứa tài khoản, mật khẩu, cơ sở dữ liệu hay cấu hình đăng nhập của NAS.

Mã giao diện nằm ngay trong các thư mục dangnhap, dangky, nguoidung, nhom, baocao, tonghop... Xem [DEPLOY-STAGING.md](DEPLOY-STAGING.md) để chạy trên NAS; không mở bằng file:// để kiểm tra đăng nhập hoặc API.

Thư mục trieutiendl-verbose-disco và file RAR là bản lưu cũ được giữ từ lịch sử kho. Dockerfile.staging chỉ đóng gói các trang hiện tại, không dùng bản lưu đó.
