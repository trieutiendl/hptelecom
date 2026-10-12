# Hạ NAS HP Telecom Server về 4.0.15

Gói này cài lại mã chạy 4.0.15 trên đúng NAS HP Telecom. Có thể dùng khi NAS đang ở 4.0.16; script chỉ chấp nhận đúng server ID đã đóng trong gói và phiên bản hiện tại 4.0.13, 4.0.14, 4.0.16 hoặc 4.0.15 (để kiểm tra chạy lại).

Trước khi dừng dịch vụ, script sao lưu toàn bộ thư mục chương trình và sao lưu SQLite bằng API backup, sau đó kiểm tra `integrity_check`. Gói, manifest và từng tệp được xác minh bằng SHA-256 trước khi sửa mã. Cập nhật thành công phải trả về phiên bản 4.0.15, xác nhận các capability `video-library` và `photo-library-trash`, và khớp hash tất cả tệp đã cài.

Thao tác này chỉ thay mã chương trình. Cơ sở dữ liệu đang chạy, ảnh, video, thư viện và thư mục DATA được giữ nguyên tại chỗ; bản SQLite trong thư mục backup chỉ dùng để phục hồi khẩn cấp và không bị chép đè lên cơ sở dữ liệu đang chạy. Các tính năng chỉ có ở 4.0.16 sẽ không còn xuất hiện khi chạy mã 4.0.15, nhưng dữ liệu hiện có được giữ nguyên.

Chạy bằng DSM Task Scheduler dưới người dùng `nasvietlinhtelecom`, hoặc dùng script PowerShell rollback đi kèm để tải gói lên NAS và chạy launcher đã kiểm tra hash. Mật khẩu NAS chỉ nhập tại dấu nhắc SSH trong terminal; không lưu mật khẩu trong script. Kết quả: `hptelecom/logs/update-4.0.15-result.json`; bản sao lưu ở `hptelecom/backups/`.

Nếu khởi động phiên bản mới thất bại, script phục hồi mã cũ tự động mà không xóa hoặc ghi đè DATA, ảnh, video hay dữ liệu mới.
