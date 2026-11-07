# Nghiệm thu Di dời theo Android 4.1.4

Các ca sau cần chạy khi triển khai bản Android staging. Chưa đánh dấu đạt; việc bàn giao tài liệu không phải kiểm thử APK.

| Mã | Tình huống | Kết quả cần giữ |
| --- | --- | --- |
| DD01 | Vào Di dời bằng tài khoản được cấp module | Mở đúng danh sách; không sang Tuần tuyến hoặc server chính. |
| DD02 | Tạo tên trống hoặc tên tiếng Việt | Chặn trống; giữ nguyên dấu/tên hợp lệ. |
| DD03 | Lọc tháng và Đang làm/Hoàn thành | Lấy tháng ngày thực hiện; thiếu ngày ở Chưa rõ tháng. |
| DD04 | Mở báo cáo của người lập | Đúng 3 bước, kiểm tra/xuất ở cuối bước Ảnh. |
| DD05 | Sửa tên, sao chép, xóa/chọn nhiều | Đúng quyền, xác nhận xóa; báo ảnh cũ không thể sửa chữ. |
| DD06 | Tên dài, chữ lớn, bàn phím, sáng/tối | Nội dung đọc được; không mất nút lưu/tiếp tục. |
| DD07 | Tự nhập địa điểm rồi lấy GPS | Giữ chế độ, địa chỉ/tọa độ đúng và có hủy/thử lại. |
| DD08 | Người dùng gõ trong lúc chờ địa chỉ GPS | Không bị kết quả đến muộn ghi đè. |
| DD09 | Từ chối quyền, GPS tắt hoặc timeout | Thông báo đúng; giữ bản nháp. |
| DD10 | Nguyên nhân có sẵn và Khác | Giữ lựa chọn/nội dung; không hiện trường giờ khôi phục Sự cố. |
| DD11 | Chỉ thu hồi cáp | Giữ số mét thu hồi và vật tư; không tự tính là cáp mới. |
| DD12 | Thu hồi 50 m + đầu số 3067/2917 | Cáp mới 150 m, hai phần độc lập. |
| DD13 | Đảo hai đầu số | Vẫn 150 m; không ra số âm. |
| DD14 | Thiếu đầu số, số âm, ký tự lạ | Chưa có số mét hợp lệ; cảnh báo. |
| DD15 | Nhập dấu phẩy/chấm thập phân | Tính đúng, hiển thị số phù hợp tiếng Việt. |
| DD16 | Tắt rồi bật phần cáp mới | Không làm mất số mét thu hồi. |
| DD17 | Mở report cũ dùng relocationLength | Chuyển đúng, mở lại nhiều lần không mất mét cũ. |
| DD18 | Chọn FO và khoảng vượt | FO dùng chung; KV100/200/300/500 được lưu. |
| DD19 | Thêm Măng xông/Vật tư khác/D12/Đai Inox | Đúng trường loại/tên và đơn vị gợi ý; lỗi thiếu dữ liệu đúng dòng. |
| DD20 | Bỏ vật tư hoặc nhiều dòng trùng tên | Có xác nhận; không tự gộp mất ghi chú. |
| DD21 | Mở Di dời mới của người lập | Có đủ 7 nhóm sau prepare, giữ thứ tự theo mã. |
| DD22 | Thu gọn/mở rộng nhóm nhiều ảnh | Mũi tên/trạng thái đúng, không mất ảnh/mô tả. |
| DD23 | Thêm ảnh từ camera, máy, thư viện | Đúng nhóm; bản sao báo cáo không xóa nguồn thư viện. |
| DD24 | Chọn nhiều ảnh, lỗi tại ảnh giữa | Nêu số đã thêm; dữ liệu ảnh thành công còn nguyên. |
| DD25 | Ảnh lẻ trong hàng hoặc nhiều cặp | Caption từng ảnh và mô tả hàng khớp cả UI lẫn Excel. |
| DD26 | Ảnh MX có EXIF/GPS đúng ảnh | Điền đúng một lần; không lấy tọa độ của ảnh khác. |
| DD27 | Ảnh MX không có EXIF | Dùng OCR ảnh gốc; có trạng thái chưa đọc rõ/lỗi và thử lại. |
| DD28 | Đọc lại MX có ghi chú thủ công | Giữ ghi chú; không lặp tọa độ hoặc điền closureLocation của Sự cố. |
| DD29 | Server trả GPS muộn cùng revision | Nhận tọa độ lúc an toàn; không đè biểu mẫu đang mở. |
| DD30 | Ảnh chờ GPS, tắt quyền/đóng trang | Giữ pending; tiếp tục/hủy đúng file, không hoàn thành khi còn pending. |
| DD31 | Xóa một ảnh làm thay đổi cặp | Mô tả theo ảnh còn lại, không mất ghi chú hoặc GPS của ảnh khác. |
| DD32 | Xóa toàn bộ ảnh | Có xác nhận; ghi chú thủ công và thư viện nguồn còn nguyên. |
| DD33 | Xóa ảnh nhưng lưu lỗi | Khôi phục nhóm ảnh và completed trước thao tác. |
| DD34 | Người lập thêm/bỏ người cùng làm | Dùng candidates/revision từ server; cập nhật quyền và nhận lại. |
| DD35 | Thành viên mở báo cáo | Giao diện ảnh; không sửa cáp/thông tin hoặc tự hoàn thành. |
| DD36 | Thành viên sửa caption/xóa ảnh | Chỉ thực hiện khi quyền/trạng thái đồng bộ cho phép; gửi delta. |
| DD37 | Thu hồi quyền hoặc server đã hoàn thành | Chặn gửi phù hợp; giữ ảnh chưa gửi. |
| DD38 | Mất mạng/đổi tài khoản trong lúc gửi ảnh | Không nhân đôi operation hoặc ghi vào tài khoản khác. |
| DD39 | Sửa report đã hoàn thành | Người lập sửa thì mở lại; UI danh sách phản ánh đúng. |
| DD40 | Cảnh báo thiếu dữ liệu | Hiện đầy đủ; Vẫn xuất vẫn có; không tự coi warnings là chặn hoàn thành toàn bộ. |
| DD41 | Xuất Excel có thu hồi + mới + vật tư | Đúng số mét mới, nội dung thu hồi và vật tư; không có trường giờ Sự cố. |
| DD42 | Excel có nhiều nhóm, ảnh JPEG/PNG và ảnh lẻ | Ảnh nhúng mở được; mô tả đúng; Kết quả đo kiểm cuối cùng. |
| DD43 | Xuất nhiều tên trùng hoặc ảnh thiếu | ZIP không đè file; ảnh thiếu báo lỗi, không báo thành công giả. |
| DD44 | Di dời hoàn thành tháng hiện tại | Không gỡ local theo quy tắc gỡ ngay của Tuần tuyến. |
| DD45 | Di dời tháng trước đã đồng bộ | Chỉ gỡ khi owner/payload/assets/revision/receipt an toàn; report server còn. |
| DD46 | Gửi báo cáo/ảnh mới từ APK staging | Xác minh chỉ ghi staging, không gọi /api/ của server chính. |

## Test nền

Các test [RelocationTest](reference/4.1.4/relocation/tests/RelocationTest.kt), [IncidentReportTest](reference/4.1.4/relocation/tests/IncidentReportTest.kt), [MxGroupsTest](reference/4.1.4/relocation/tests/MxGroupsTest.kt), [ReportPhotoRemovalTest](reference/4.1.4/relocation/tests/ReportPhotoRemovalTest.kt), [PhotoMutationDeltaTest](reference/4.1.4/relocation/tests/PhotoMutationDeltaTest.kt) và các test GPS/retention trong [bộ tham chiếu](reference/4.1.4/relocation/README.md) dùng làm nền.

Một số test gọi constructor trực tiếp trước prepare nên chỉ có 5 nhóm. Luồng UI người lập gọi prepare và có thêm Đầu số cáp/Kết quả đo kiểm. Khi triển khai, kiểm thử cả luồng UI thực tế thay vì chỉ sao chép kỳ vọng constructor.

## Giao việc cho GitHub Copilot

Thiết kế lại Di dời native theo [DI-DOI-4.1.4](DI-DOI-4.1.4.md) và [DI-DOI-DATA-API](DI-DOI-DATA-API.md), giữ ba bước và mọi quy tắc cáp/ảnh/MX/quyền/đồng bộ. Dùng mã tham chiếu để đối chiếu; dùng fixture giả cho prototype. Bổ sung Team/Đội staging theo API đã xác định. Bàn giao PR có hình màn hình, kết quả từng ca, phần còn thiếu API và APK kiểm thử khi có dự án native đầy đủ. Không ghi đã hoàn thành tích hợp khi chỉ có màn hình mẫu.
