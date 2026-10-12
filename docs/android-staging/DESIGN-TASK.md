# Yêu cầu thiết kế lại giao diện Android staging

## Công việc giao cho GitHub Copilot hoặc người triển khai

Thiết kế lại giao diện **HP Telecom Staging** từ cấu trúc trong [README](README.md), [SCREENS](SCREENS.md) và [INTEGRATION](INTEGRATION.md). Giữ chức năng native của HP Telecom Android 4.1.4 (136); tổ chức lại màn hình để dễ thao tác ngoài hiện trường, đọc rõ chữ, ít cuộn và phân biệt trạng thái lưu/đồng bộ.

### Thực hiện

1. Đọc ba tài liệu trước khi đề xuất thiết kế. Lập danh sách màn hình đã có và màn hình Team/Đội cần bổ sung.
2. Thiết kế một bộ thành phần thống nhất: màu sáng/tối, chữ, khoảng cách, biểu mẫu, thẻ báo cáo, chọn tháng/nhóm, nhóm ảnh thu gọn, thanh hành động và trạng thái.
3. Dùng Trang chủ / Công việc / Thư viện / Tài khoản làm phương án điều hướng đầu tiên. Camera luôn dễ truy cập; trình bày bản nháp và việc chưa đồng bộ rõ ràng.
4. Ưu tiên mẫu Đăng nhập → Trang chủ → Danh sách Sự cố → Báo cáo nhiều bước → Chọn/chụp ảnh MX → Lưu/Đồng bộ. Sau đó hoàn thiện Tuần tuyến, Di dời, Video và Team/Đội.
5. Tên nhóm giữ nguyên tiếng Việt có dấu. Nhóm và thành viên thu gọn bằng mũi tên bên phải. Bộ lọc công việc dùng tháng và nhóm đã tạo/tham gia.
6. Tạo trạng thái tải, trống, lỗi, offline, chờ duyệt, không đủ quyền, GPS yếu, đang đọc ảnh và chưa gửi xong. Không hiển thị dữ liệu mẫu như dữ liệu server.
7. Prototype dùng dữ liệu giả ghi rõ Minh họa. Không dùng thông tin đăng nhập, ảnh báo cáo hay dữ liệu thật.
8. Khi triển khai native, bắt đầu từ đúng mã 4.1.4. Nếu nhánh chưa có nguồn native, hoàn thiện bộ thiết kế và liệt kê phần nhập mã nguồn còn thiếu; không tạo một WebView rồi coi như đã thay thế APK native.
9. Thay UI theo từng màn hình, giữ lớp dữ liệu, OCR, đồng bộ, quyền và tính toán. Kết nối staging là hạng mục riêng có kiểm thử.
10. Đưa kết quả vào pull request có ảnh hoặc prototype xem được, danh sách màn hình đã hoàn thiện và phần còn thiếu.

### Tiêu chí hoàn thành thiết kế

- Có đủ các màn hình và trạng thái trong SCREENS.md; không mất đường vào chức năng cũ.
- Chữ rõ trên cả nền sáng và tối; dùng được khi tăng cỡ chữ hoặc mở bàn phím.
- Tên dài và danh sách nhiều thành viên/ảnh không làm vỡ bố cục.
- Cả chủ nhóm, điều phối viên, kỹ thuật viên và quản trị có trạng thái quyền rõ ràng.
- Ảnh MX có trạng thái đọc tọa độ và hành động thử lại; không tự điền tọa độ giả.
- Nhãn Staging và trạng thái chưa kết nối API thể hiện trung thực.
- Mẫu thiết kế không phát sinh request ghi vào server chính.
- Phân biệt hoàn thành thiết kế với hoàn thành tích hợp và kiểm thử APK.

Bộ yêu cầu này không tự khởi chạy GitHub Copilot. Có thể dùng nguyên nội dung phần công việc để tạo tác vụ thiết kế trên nhánh chứa tài liệu.
