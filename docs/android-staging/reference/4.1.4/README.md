# Tham chiếu Tuần tuyến Android 4.1.4

Nguồn: HP Telecom Android native 4.1.4, versionCode 136, đối chiếu ngày 12/10/2026. Các file dưới đây giữ nội dung mã tại thời điểm bàn giao, phục vụ thiết kế lại Tuần tuyến staging.

**Đây là mã tham chiếu, chưa phải dự án Android độc lập có thể build.** Nhiều file gọi lớp nghiệp vụ, thư viện Android, tài nguyên hoặc thành phần ứng dụng chưa nằm trong bộ này. Khi triển khai phải nhập đầy đủ nguồn native vào thư mục ứng dụng, không dùng thư mục tài liệu làm module build.

Đọc trước [đặc tả](../../TUAN-TUYEN-4.1.4.md), [API](../../TUAN-TUYEN-DATA-API.md), [nghiệm thu](../../TUAN-TUYEN-CHECKLIST.md). Không sửa bản tham chiếu để che khác biệt; triển khai thay đổi ở nguồn ứng dụng và ghi rõ điểm khác bản nền.

## Bản đồ đọc mã

| Nhóm | Tệp |
| --- | --- |
| Danh sách tuyến, camera result, chỉnh vị trí và menu | [MainActivity](patrol/MainActivity.kt) |
| Lưu, bản nháp, chèn STT, chuyển/xóa ảnh, hoàn thành, xuất | [WorkModel](patrol/WorkModel.kt) |
| Chụp ảnh, thông tin vị trí, OK/NOK và GPS | [CaptureActivity](patrol/CaptureActivity.kt) |
| Chi tiết sửa chữa và ảnh trước/sau | [CableRepairActivity](patrol/CableRepairActivity.kt) |
| Dữ liệu và kiểm tra nhập | [Data](patrol/Data.kt), [Draft](patrol/Draft.kt), [Rules](patrol/Rules.kt) |
| Số thứ tự và tháng | [RouteSequence](patrol/RouteSequence.kt), [RouteMoveNumbers](patrol/RouteMoveNumbers.kt), [RouteMonth](patrol/RouteMonth.kt) |
| Chuyển ảnh và tên tuyến | [RoutePhotoMove](patrol/RoutePhotoMove.kt), [RoutePhotoTitles](patrol/RoutePhotoTitles.kt) |
| Chữ trên ảnh và EXIF tiếng Việt | [PhotoRenderer](patrol/PhotoRenderer.kt), [PhotoTextEncoding](patrol/PhotoTextEncoding.kt) |
| Metadata và xuất báo cáo | [CablePoints](patrol/CablePoints.kt), [Exports](patrol/Exports.kt), [CableHeader](patrol/CableHeader.kt) |
| Điều kiện đồng bộ và gỡ local | [CableSyncCheck](patrol/CableSyncCheck.kt), [CableArchiveCheck](patrol/CableArchiveCheck.kt) |
| Đoạn lớp đồng bộ | [snapshot](excerpts/ServerSync.snapshot.kt.txt), [uploadLocked](excerpts/ServerSync.uploadLocked.kt.txt), [receiveCableTasks và archive](excerpts/ServerSync.receiveCableTasks.kt.txt), [canReceiveReport](excerpts/WorkApp.canReceiveReport.kt.txt) |
| Test nghiệp vụ nền | [Rules](tests/RulesTest.kt), [STT](tests/RouteSequenceTest.kt), [STT tuyến đích](tests/RouteMoveNumbersTest.kt), [tháng](tests/RouteMonthTest.kt), [xuất](tests/ExportsTest.kt), [đồng bộ](tests/CableSyncCheckTest.kt), [lưu trữ](tests/CableArchiveCheckTest.kt) |

Các tệp `.kt.txt` là đoạn trích, có vị trí dòng gốc trong [manifest](manifest.json). Manifest ghi SHA256 của file nguồn đầy đủ, kể cả khi chỉ trích một đoạn. Các file `.kt` là toàn bộ file nguồn tương ứng, nên có thể chứa phần dùng chung hoặc màn hình ngoài Tuần tuyến.

Trong mã cũ còn helper hoặc trường không được luồng UI gọi. Chỉ sự tồn tại của hàm không chứng minh có chức năng trên màn hình. Đặc tả ghi rõ trường hợp ảnh xa/repair và các mẫu Excel để tránh lấy changelog cũ thay cho hành vi 4.1.4.

Không kèm khóa ký, mật khẩu, database hoặc ảnh người dùng. [Fixture](../../fixtures/patrol-example.json) chỉ có dữ liệu giả để dựng mẫu. Không tự chạy fixture với endpoint thật.
