# Dữ liệu và API Tuần tuyến Android 4.1.4

Hợp đồng dưới đây được đọc từ client Android 4.1.4 (136). API staging phải tương thích hoặc có adapter đã kiểm thử. Đây không phải xác nhận rằng các endpoint mobile đã chạy trên staging.

## Các lớp dữ liệu

Nguồn: [Data.kt](reference/4.1.4/patrol/Data.kt), [Draft.kt](reference/4.1.4/patrol/Draft.kt).

| Đối tượng | Trường chính và ý nghĩa |
| --- | --- |
| Place | id local; kind = Tuyến cáp; name tên tuyến. Không dùng tên làm khóa đồng bộ. |
| Job | id; placeId; name; completed. Có dữ liệu bộ ảnh cũ, nhưng snapshot đồng bộ yêu cầu tối đa một Job mỗi Place. |
| Photo | id, jobId, seq, locationName, lat, lon, accuracy, note, path ảnh có chữ, originalPath ảnh gốc, capturedAt, showDate, textColor, photographer. |
| IncidentRecord | placeId + chuỗi JSON. Với Tuần tuyến lưu cableWork, workType, routeName, lengthKm, team, points. |
| Draft | jobId, photoId (0 nếu mới), rawPath, seq/name/note đang nhập, lat/lon nullable, accuracy, fixTime, capturedAt, capturePending, showDate, textColor, address/proposal/assessment. |
| Liên kết server | remote folder ID, revision, chữ ký đã gửi, pending, baseline, yêu cầu tải lại, dấu đã lưu trữ, tháng giao tuyến và cursor nhận thay đổi. |

`Photo.capturedAt` và `Draft.fixTime` là mili giây; `task.created` là giây Unix. `RouteMonth.key` dùng Asia/Ho_Chi_Minh. Nhầm đơn vị sẽ làm sai tháng.

### Metadata points

`report.points` là object, khóa là STT chuyển thành chuỗi, không phải id của ảnh.

| Trường tại points["STT"] | Ý nghĩa |
| --- | --- |
| address | Địa chỉ từ GPS hoặc nội dung người dùng sửa. |
| description | Mô tả; có thể khởi tạo từ ghi chú ảnh. |
| proposal | Phương án / kiến nghị. |
| assessment | OK hoặc NOK; dữ liệu cũ có thể rỗng. |
| before, after | Object chứa file và photographer nếu có; là ảnh trước/sau sửa. |
| far, repair | Khóa dữ liệu cũ mà helper còn biết; không suy ra nút ảnh xa hoặc điều kiện bắt buộc đang có trong giao diện hiện tại. |

Đổi STT cần chuyển khóa points. Chuyển tuyến cần chuyển points theo ảnh. Xóa vị trí phải bỏ points và xử lý tệp được tham chiếu. Không tái tạo points từ giao diện rồi vô tình xóa before/after hoặc trường không hiển thị.

## Nhận tuyến được giao

Nguồn nguyên đoạn: [ServerSync.receiveCableTasks](reference/4.1.4/excerpts/ServerSync.receiveCableTasks.kt.txt).

| Yêu cầu | Dữ liệu client đọc |
| --- | --- |
| GET mobile/cable-tasks | tasks array; sync_cursor nếu server có. |
| GET mobile/cable-tasks/since/{cursor} | Dùng khi đã có cursor; chỉ fallback về endpoint đầy đủ khi HTTP 404 hoặc 405. |
| Mỗi task | name, work_type, length_km, team, note, created, members. |
| Mỗi member | user, active, folder, completed, revision, points. |

Chỉ nhận member có `user == user.id` hiện tại và `active == true`. `folder` là remote ID dùng tải báo cáo; `points` ở member là số điểm, khác object `report.points`. Giữ cùng folder khi nhận lại để tránh nhân đôi tuyến.

Metadata task cập nhật workType/routeName/lengthKm/team trong report; ghi chú và thời điểm giao lưu riêng. Chỉ ghi cursor mới sau khi vòng xử lý thành công và tài khoản chưa đổi.

## Gửi và nhận báo cáo

Nguồn: [snapshot](reference/4.1.4/excerpts/ServerSync.snapshot.kt.txt), [uploadLocked](reference/4.1.4/excerpts/ServerSync.uploadLocked.kt.txt).

| Endpoint tương đối với base API | Mục đích |
| --- | --- |
| GET mobile/folders/{folderId} | Lấy revision và payload hiện hành để tải hoặc đối chiếu xung đột. |
| POST mobile/assets | Gửi JSON module, base64, folderId khi gắn vào báo cáo; nhận id tệp. Client kiểm tra tệp thuộc tài khoản và tối đa 10 MB. |
| GET mobile/assets/{assetId} | Tải ảnh của báo cáo theo quyền. |
| PUT mobile/folders/{folderId} | Gửi operation UUID, revision nền, payload; phản hồi có revision được chấp nhận. |

Payload có version=1, module=cable, name, completed, photos, report. Mỗi ảnh wire có seq, name, lat, lon, accuracy, note, file, originalFile, date, capturedAt, showDate, textColor, photographer. `textColor` là chuỗi #RRGGBB khi gửi, còn local là Int Android.

Trước gửi, thay đường dẫn local trong file/originalFile thành asset ID. Không gửi đường dẫn máy như thể server truy cập được. Cả ảnh gốc, ảnh đã viết chữ và ảnh trước/sau trong metadata phải còn liên kết đúng.

`wireReport` dùng bộ chuyển đổi dùng chung với báo cáo khác nên có thể thêm trường phụ ngoài bảng này. Adapter cần giữ các trường chưa biết; fixture chỉ minh họa phần tối thiểu Tuần tuyến, không phải schema server đầy đủ.

### Đồng bộ và xung đột

- Lưu request pending trước khi gửi để thử lại cùng operation, tránh báo cáo trùng.
- Receipt/chữ ký cache chưa đủ: khi server có cùng revision nhưng khác số điểm hoặc trạng thái hoàn thành thì đánh dấu gửi lại.
- Revision khác: không coi là khớp. HTTP 409 cần đối chiếu baseline/local/remote; không tự tăng số rồi ghi đè.
- Server cũ hơn hoặc báo cáo không còn: giữ bản local và báo lỗi cụ thể.
- Bản nháp/màn hình đang sửa phải được bảo vệ; đoạn [canReceiveReport](reference/4.1.4/excerpts/WorkApp.canReceiveReport.kt.txt) cho biết khi nào được nhận thay thế.
- Tự gỡ tuyến đã hoàn thành cần toàn bộ điều kiện trong [CableArchiveCheck](reference/4.1.4/patrol/CableArchiveCheck.kt). Không gỡ chỉ vì người dùng bấm Hoàn thành.

## Fixture minh họa

Dùng [patrol-example.json](fixtures/patrol-example.json) cho prototype. Các tên, ID và tọa độ đều là dữ liệu giả, có nhãn Minh họa. Không gửi fixture lên server thật.

Fixture gồm trạng thái người dùng giả, phản hồi giao tuyến, payload báo cáo và các tình huống kỳ vọng. Hai ảnh chính thể hiện OK/NOK; ảnh trước/sau dùng ID giả. Không có file ảnh thật. Khi demo hiển thị ảnh, dùng ô giữ chỗ có nhãn.

## Tích hợp staging

- Base dự kiến: https://hptelecom.id.vn/staging/api/. Tất cả endpoint và chuyển hướng phải ở staging; không bỏ tiền tố để gọi API server chính.
- Phiên, server ID, dữ liệu local, ảnh và hàng chờ tách khỏi ứng dụng chính. Package staging riêng.
- Client chính hiện dùng token, capability và hợp đồng mobile; staging đã đối chiếu trước đó dùng auth cookie và API khác. Chỉ đổi URL không đủ.
- Thêm nhóm: cần team ID ổn định, tên hiển thị nguyên dấu, danh sách nhóm thuộc tài khoản và quyền giao/nhận tuyến do server kiểm soát. Trường team chuỗi của bản chính không thay được định danh nhóm staging.
- Ghi rõ tính năng chưa kết nối trong UI prototype. Chưa được báo “Đã đồng bộ” trước khi server staging nhận và trả xác nhận.

## Các giới hạn cần thể hiện đúng

Ảnh thư viện thiếu gốc riêng có thể xem, lưu và xuất được nhưng bị chặn ở thao tác vẽ lại. Địa chỉ GPS thiếu vẫn có thể lưu tọa độ. OK/NOK, completed, acknowledged là ba trạng thái khác nhau. Xuất file được không chứng minh đồng bộ đã thành công.
