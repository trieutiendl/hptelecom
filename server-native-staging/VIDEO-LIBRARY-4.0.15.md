# Thư viện video 4.0.15

Android gửi video MP4 tối đa 64 MiB, mỗi phần tối đa 1 MiB. Video nằm trong thư mục dữ liệu `video-library/<id-tài-khoản>/<sha256>.mp4`; SQLite chỉ giữ thông tin và tiến độ tải. File `.part` chưa hoàn thành không xuất hiện trong thư viện.

## API (cần Bearer token)

- `POST /api/mobile/videos/start`: `upload_id` (UUID chữ thường có dấu gạch), `name`, `captured_at` (mili giây Unix), `size`, `sha256` (64 ký tự hex thường), `duration_ms`, `details` (object). Trả `upload_id`, `offset`, `chunk_size:1048576`, `complete`; nếu đã có còn trả `id` và `offset:size`.
- `POST /api/mobile/videos/chunk`: `upload_id`, `offset`, `base64`. Trả `upload_id`, `offset` tiếp theo. Gửi lại đúng phần đã nhận an toàn. Khi nhận 409, gọi `start` bằng cùng thông tin để lấy vị trí hiện tại.
- `POST /api/mobile/videos/finish`: `upload_id`. Chỉ xác nhận sau khi đủ dữ liệu, đúng SHA-256 và cấu trúc MP4. Trả thông tin video, `id`, `complete:true`, `offset:size`. Gọi lại an toàn. Cùng nội dung của cùng tài khoản chỉ có một video.
- `GET /api/mobile/videos`: danh sách `videos` tối đa 500 video mới nhất trong quyền xem.
- `GET /api/mobile/videos/<id>`: MP4, hỗ trợ HTTP Range (206/416), đọc từng phần 64 KiB.
- `POST /api/mobile/videos/<id>/stream`: tạo URL phát/tải cho trình duyệt. Ticket chỉ đọc một video, hết hạn sau tối đa 10 phút, gắn với phiên hiện tại. Đăng xuất, khóa tài khoản, hết phiên hoặc đổi quyền/đội được kiểm tra lại mỗi lượt đọc.

Quản trị xem tất cả; Hỗ trợ xem của mình và thành viên cùng đội; người dùng khác chỉ xem của mình. Vai trò chỉ xem không được tải video lên. Không nhận `owner` từ client. API ảnh và bộ chọn ảnh trong báo cáo giữ nguyên.

Khi gọi lại `start`/`finish`, server kiểm tra tệp MP4 thực tế trước khi xác nhận đã sao lưu. Nếu file mất, bị cắt hoặc sai SHA-256, `finish` trả 409; `start` cho tải lại từ đầu với cùng ID thư viện. Việc này không thay thế sao lưu đủ thư mục video: điện thoại cần còn bản gốc và thực hiện gửi lại mới khôi phục được file bị mất.

Trang `/thuvien` gộp ảnh và video, lọc người chụp/quay và tháng. Chỉ tải nội dung khi mở xem/tải; video phát trực tiếp bằng HTTP Range, không nạp cả tệp vào bộ nhớ trình duyệt.

## Xóa và khôi phục ảnh

`photo-library-trash` trong capabilities cho biết server hỗ trợ Thùng rác ảnh. Người sở hữu và quản trị được chuyển ảnh vào Thùng rác hoặc khôi phục. Hỗ trợ chỉ quản lý ảnh của chính mình, không xóa ảnh của thành viên khác; vai trò chỉ xem không được sửa. Web yêu cầu xác nhận trước khi chuyển ảnh vào Thùng rác.

- `GET /api/mobile/library/trash`: `{photos:[...]}` cùng metadata thư viện và `deleted_at` (giây Unix). Quản trị thấy tất cả, người khác chỉ thấy của mình.
- `POST /api/mobile/library/<id>/trash` và `/restore`: trả `{id,trashed,deleted_at}`; có thể gọi lại an toàn. Mỗi lần thay đổi trạng thái được ghi nhật ký.
- Danh sách ảnh thường bỏ qua ảnh đã xóa; tải trực tiếp ảnh đã xóa trả 404 kể cả với quản trị. Khôi phục để xem lại.
- Gửi lại bản trùng của ảnh trong Thùng rác trả 409 với `code:PHOTO_IN_TRASH`, không tự khôi phục và không xác nhận đã sao lưu vào thư viện đang hoạt động.

Đây là xóa có thể khôi phục: giữ nguyên BLOB và metadata, không tự dọn sau 30 ngày, không giải phóng dung lượng. Không xóa bản sao trong báo cáo, trên điện thoại, hoặc video. Schema chỉ thêm cột `photo_library.deleted_at` và index. Nếu quay lại phần mềm cũ chưa hỗ trợ Thùng rác, ảnh đã xóa có thể xuất hiện lại trong danh sách; dữ liệu vẫn được giữ.

## Phiên đăng nhập web

Web lưu token và thời hạn trong `sessionStorage` của tab, xác minh lại tài khoản qua `/api/me` khi tải lại trang hoặc đi qua các đường dẫn báo cáo/thư viện. Không lưu mật khẩu hoặc dùng `localStorage`. Đăng xuất xóa phiên trong tab kể cả khi mạng lỗi; token hết hạn hoặc bị server thu hồi phải đăng nhập lại. Đóng tab thông thường sẽ kết thúc kho phiên của tab đó; việc khôi phục tab cũ do trình duyệt quản lý.

## Cập nhật và sao lưu NAS

Đưa thêm `video_library.py` cùng bản cập nhật `server.py`, `mobile.py`, `static/app.js`, `static/style.css`. Khởi động server sẽ tự thêm bảng; không xóa hay chuyển đổi dữ liệu ảnh cũ. `/api/status` trả `version:4.0.15` và capability `video-library`.

**Sao lưu phục hồi đầy đủ phải gồm cả SQLite và thư mục DATA/video-library.** Tùy chọn `server.py --backup` hiện chỉ sao lưu SQLite, không sao chép MP4. Dừng tác vụ ghi hoặc dùng snapshot NAS nhất quán khi sao lưu cả hai. Để quay lại phần mềm cũ, giữ nguyên thư mục dữ liệu và cơ sở dữ liệu; phần mềm cũ không hiển thị video nhưng dữ liệu vẫn còn.

Kiểm thử local: `python -m unittest test_video_library test_photo_trash -v`, `node test_video_library_ui.cjs` và `node test_web_session.cjs`. Test chỉ dùng thư mục tạm và localhost, không kết nối NAS.
