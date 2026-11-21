# Cập nhật 0.2.0

Server đã hỗ trợ hai bản ứng dụng HP Telecom Server riêng. Xem **LIEN-KET-DIEN-THOAI.md** để kết nối. CHAY-SERVER.cmd hiện mở server trong mạng LAN; ứng dụng Android/iOS cũ vẫn giữ nguyên. Phần mô tả 0.1.0 dưới đây là tài liệu bản đầu.

# HP Telecom Server — Bản thử độc lập 0.1.0

## Chạy trên máy Windows hiện tại
1. Giải nén toàn bộ thư mục (nếu tải ZIP).
2. Nhấp đúp **CHAY-SERVER.cmd**, giữ cửa sổ chạy mở.
3. Mở **http://127.0.0.1:8787** bằng trình duyệt.
4. Lần đầu, tự đặt tên đăng nhập và mật khẩu quản trị (ít nhất 10 ký tự).
5. Trong Người dùng, tạo nhân viên và chọn quyền Sự cố / Tuyến cáp / Di dời.
6. Đăng xuất quản trị, đăng nhập nhân viên để kiểm tra quyền. Tải lại trang cần đăng nhập lại.

Không có tài khoản hoặc mật khẩu mặc định. Không cần gửi mật khẩu qua hội thoại.
Trên máy Windows khác, cần Python 3.10 trở lên và thêm Python vào PATH. Không cần cài thêm thư viện Python.
Nếu báo cổng đang sử dụng, mở địa chỉ trên để kiểm tra server đã chạy; không mở thêm cửa sổ chạy trùng.

## Chức năng có trong bản này
- Quản trị tạo, đổi mật khẩu, khóa/mở tài khoản và cấp quyền riêng ba mục.
- Nhân viên chỉ truy cập hồ sơ/ảnh của mình trong mục được cấp quyền; quản trị xem tất cả.
- Hồ sơ thử gồm tên, địa điểm, ghi chú, nhiều ảnh JPG/PNG (tối đa 10 MB/ảnh), xem lớn và xóa.
- Xóa hồ sơ xóa luôn ảnh thuộc hồ sơ. Có kiểm tra tránh ghi đè bản vừa thay đổi.
- Khóa tài khoản hoặc sửa quyền sẽ thu hồi phiên đăng nhập của tài khoản đó.
- Nhật ký 100 thao tác gần nhất. Danh sách hồ sơ hiển thị tối đa 500 hồ sơ gần nhất.
- Mật khẩu được băm PBKDF2; phiên đăng nhập hết hạn sau 12 giờ.

## Phạm vi
Đây là server và trang quản trị độc lập. **Không sửa, không kết nối và không lấy dữ liệu từ ứng dụng Android/iOS hiện có.**
Mẫu hồ sơ web dùng để thử tài khoản và quyền, chưa có mẫu Excel/KMZ, camera GPS và đồng bộ điện thoại của ứng dụng hiện tại.
Server mặc định chỉ mở trên máy Windows này. Đây là bản thử HTTP nội bộ; trước khi dùng qua Internet cần triển khai HTTPS, dịch vụ vận hành và cơ chế sao lưu phù hợp. Chưa cần thuê server để thử bản này.

## Dữ liệu và sao lưu
Tài khoản, hồ sơ và ảnh nằm trong **data/hp-telecom.sqlite3** bên cạnh server.py. Không xóa thư mục data khi nâng cấp.
Sao lưu đơn giản: dừng server bằng Ctrl+C, sao chép toàn bộ thư mục data sang nơi an toàn.
Hoặc sao lưu SQLite trực tiếp bằng Python: `python server.py --backup backups/ban-sao.sqlite3` (tên file đích chưa tồn tại). Từ bản 4.0.15, video MP4 nằm ngoài SQLite trong `data/video-library`; lệnh này không sao chép video. Muốn sao lưu đầy đủ, dừng server rồi sao chép toàn bộ `data`, hoặc dùng snapshot NAS nhất quán gồm cả SQLite và thư mục video.
Khôi phục: dừng server, giữ riêng bản data hiện tại, tạo thư mục data mới rồi đặt file sao lưu vào đó với tên hp-telecom.sqlite3, kèm thư mục `video-library` từ cùng bản sao lưu nếu có video. Không ghép file dữ liệu mới với các file -wal/-shm cũ. Chỉ khôi phục SQLite sẽ không khôi phục được các tệp video.
Bản ZIP cung cấp không chứa tài khoản, mật khẩu hay dữ liệu người dùng.

## API để kết nối sau này
JSON qua /api; đăng nhập trả token, gửi `Authorization: Bearer <token>` cho yêu cầu bảo vệ.
- POST /api/setup: tạo quản trị đầu tiên từ máy server.
- POST /api/login, POST /api/logout; GET /api/me; POST /api/password.
- GET/POST /api/users; PATCH /api/users/{id}: quản trị.
- GET/POST /api/reports; GET/PUT/DELETE /api/reports/{id}.
- POST /api/reports/{id}/images: name và base64; GET/DELETE /api/images/{id}.
- GET /api/audit: quản trị.
Module: incident, cable, relocation. Khi sửa/xóa báo cáo cần revision từ bản GET mới nhất.
Chủ sở hữu luôn lấy từ phiên đăng nhập, không nhận từ dữ liệu do người dùng tự gửi.

## Kiểm tra
Chạy `python test_server.py`: kiểm tra cách ly dữ liệu, phân quyền ảnh, khóa tài khoản, thu hồi phiên, xung đột chỉnh sửa, giới hạn đăng nhập, kiểm tra nguồn truy cập và sao lưu.

## Quản trị sửa và xóa dữ liệu điện thoại — Server 0.4.0
1. Đăng nhập bằng tài khoản quản trị, chọn Dữ liệu điện thoại.
2. Mở báo cáo nhân viên đã sao lưu, chọn Sửa báo cáo.
3. Sửa thông tin, cáp/vật tư, mô tả hoặc bỏ ảnh rồi bấm Lưu thay đổi.
4. Muốn xóa toàn bộ bản trên server, chọn Xóa báo cáo và xác nhận.
Người lập và chủ sở hữu vẫn là nhân viên ban đầu. Hoạt động sửa/xóa có trong Nhật ký.
Bản trên điện thoại vẫn giữ nguyên. Nhân viên tải bản server về thành bản sao để đối chiếu; bản cũ không tự ghi đè thay đổi mới. Ảnh còn được báo cáo khác sử dụng sẽ được giữ.

Quyền xóa báo cáo trên server: chỉ quản trị được xóa. Nhân viên không có nút xóa bản server; yêu cầu xóa từ ứng dụng cũ cũng bị từ chối. Xóa bản lưu riêng trên điện thoại không xóa bản server. Server 0.4.1, Android 3.41-server.5, iOS 1.0.75.

## Server 0.5.0 — dữ liệu điện thoại và xuất file
Dữ liệu điện thoại chia thành Sự cố, Tuyến cáp, Di dời, kèm số báo cáo mỗi mục. Mở báo cáo rồi chọn Xuất Excel hoặc Xuất KMZ. Excel dùng mẫu của ứng dụng và giữ người lập. KMZ cần tọa độ đã lưu; không suy đoán tọa độ từ chữ in trong ảnh. Nếu báo cáo còn thiếu thông tin, người dùng có thể chọn vẫn xuất Excel.

Server 0.5.1: Sự cố và Di dời xếp theo thời gian nhận mới nhất trước. Thiếu thời gian nhận dùng ngày báo cáo/ngày thực hiện, chưa có ngày xếp cuối. Sửa hoặc gửi lại không đổi thứ tự nếu thời gian nhận không đổi.

Server 0.5.2: Excel Di dời dùng cùng khung cột và bố cục thông tin/ảnh với Sự cố. Giữ tiêu đề Di dời, nguyên nhân và nội dung di dời; không thêm thời gian chưa có dữ liệu.

Server 0.5.5: riêng Di dời xếp theo Ngày thực hiện mới nhất trước, không dùng thời gian nhận hoặc ngày lập báo cáo. Chưa có ngày thực hiện xếp cuối. Sự cố vẫn xếp theo thời gian nhận.
