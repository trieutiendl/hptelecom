# Nghiệm thu giao diện Tuần tuyến theo 4.1.4

Bảng dùng khi triển khai Android staging. **Chưa đánh dấu đạt**: đây là kịch bản cần chạy với bản mới. Các test Kotlin kèm theo là test tham chiếu của mã nền, không thay cho kiểm thử APK trên điện thoại.

## Màn hình và thao tác

| Mã | Thực hiện | Kết quả cần kiểm tra |
| --- | --- | --- |
| UI01 | Mở Tuần tuyến bằng tài khoản có module cable | Vào đúng danh sách; không mở web Sự cố. |
| UI02 | Đổi Kiểm tra/Bảo dưỡng/Chưa phân loại | Chỉ hiển thị đúng loại; tháng giao mới trước, thiếu ngày vào Chưa rõ tháng. |
| UI03 | Nhận lại tuyến được giao nhiều lần | Không nhân đôi; chỉ nhận thành viên active của tài khoản. |
| UI04 | Mở tuyến có ghi chú giao việc và ảnh cũ | Đủ ghi chú, trạng thái, STT, GPS, ảnh; có đường vào bộ ảnh cũ khi tồn tại. |
| UI05 | Tài khoản không được cấp module | Báo không đủ quyền; không tự mở rộng quyền từ tên nhóm. |
| UI06 | Dữ liệu rỗng/lỗi mạng | Trạng thái rõ ràng; không đưa tuyến mẫu vào dữ liệu thật. |
| UI07 | Tên dài, chữ tiếng Việt, chữ lớn, bàn phím mở | Không mất nội dung/nút lưu; chữ rõ ở giao diện sáng và tối. |

## Camera bản nháp và sửa chữa

| Mã | Thực hiện | Kết quả cần kiểm tra |
| --- | --- | --- |
| CP01 | Thêm ảnh mới | Tạo bản nháp trước; camera có thông tin tuyến, STT, vị trí, ghi chú, OK/NOK. |
| CP02 | Thiếu STT/tên/đánh giá | Camera yêu cầu bổ sung; thiếu ghi chú/GPS/file gốc thì lưu báo lỗi đúng trường. |
| CP03 | Từ chối quyền/GPS tắt/hết thời gian | Giữ bản nháp, có cách cấp quyền/thử lại; không tự điền GPS giả. |
| CP04 | GPS cũ hoặc sai số >50 m | Yêu cầu GPS mới hoặc hỏi chờ/vẫn chụp theo luồng 4.1.4. |
| CP05 | Không tìm được địa chỉ | GPS và ảnh vẫn lưu được; địa chỉ không bị bịa. |
| CP06 | Chụp OK rồi lưu | Ảnh có chữ và EXIF đúng; giữ ảnh gốc; về danh sách. |
| CP07 | Chụp NOK rồi lưu | Tự mở thông tin sửa chữa của đúng STT. |
| CP08 | Đổi OK/NOK bằng ảnh cũ | Không bắt chụp lại; giữ ảnh trước/sau. |
| CP09 | Thêm/thay ảnh trước/sau | Đúng vị trí, đánh giá NOK theo mã nền; không xóa ảnh nguồn thư viện. |
| CP10 | Đóng/mở lại ứng dụng hoặc xoay máy khi nhập | Khôi phục bản nháp hợp lệ; không nhân đôi ảnh. |
| CP11 | Xóa bản nháp đang sửa ảnh | Ảnh đã lưu trước đó còn nguyên. |
| CP12 | Đổi tài khoản khi chọn/chụp ảnh | Không ghi ảnh vào tài khoản khác. |
| CP13 | Lưu báo cáo thành công nhưng album/server library lỗi | Nói rõ ảnh đã lưu, phần sao lưu nào lỗi; không báo hoàn toàn thất bại. |

## STT chuyển ảnh và ngày

| Mã | Thực hiện | Kết quả cần kiểm tra |
| --- | --- | --- |
| PT01 | Thêm vào STT 2 của dãy 1,2,3 | Ảnh cũ dịch thành 1,3,4; ảnh mới 2; metadata khớp. |
| PT02 | Đổi 7→2 và 2→7 | Dịch đúng các số trong khoảng; chữ trên ảnh và points cùng đổi. |
| PT03 | Xóa 2 trong 1,2,3 | Còn 1,3; gợi ý số 2 khi thêm, không tự nén số lúc xóa. |
| PT04 | Thiếu ảnh gốc trong một ảnh cần đổi số | Hủy toàn bộ thay đổi, không trùng STT. |
| PT05 | Chuyển 2 ảnh tới tuyến có STT lớn nhất 9 | Ảnh mới mang số 10,11 theo thứ tự cũ; chuyển cả before/after; hai tuyến mở lại. |
| PT06 | Nguồn hoặc đích có bản nháp | Chặn chuyển, hướng dẫn lưu/hủy bản nháp. |
| PT07 | Chuyển thất bại ở ảnh thứ hai | Nguồn/đích không bị chuyển một phần. |
| PT08 | Hiện/ẩn/đổi ngày toàn tuyến | Đúng mọi bộ ảnh liên quan; đổi capturedAt khi đổi ngày; giữ GPS và ghi chú. |
| PT09 | Một ảnh thiếu gốc khi đổi ngày | Không đổi ảnh nào; giải thích ảnh lỗi. |
| PT10 | Thêm ảnh thư viện có GPS | Dùng bản sao, giữ người chụp và GPS của ảnh. |
| PT11 | Ảnh thư viện GPS 0/0 hoặc file ngoài tài khoản | Từ chối với lý do. |

## Hoàn thành và đồng bộ

| Mã | Thực hiện | Kết quả cần kiểm tra |
| --- | --- | --- |
| SY01 | Hoàn thành tuyến không có ảnh/còn bản nháp | Chặn; hướng dẫn bổ sung hoặc xử lý bản nháp. |
| SY02 | Hoàn thành khi offline | Lưu trạng thái local/chờ gửi, không gỡ dữ liệu. |
| SY03 | Gửi thành công, cả hai phía hoàn thành và receipt khớp | Chỉ gỡ local khi không đang sửa và đủ tất cả điều kiện an toàn. |
| SY04 | Server cùng revision nhưng thiếu ảnh/trạng thái khác | Gửi lại; không tin mỗi chữ ký cache. |
| SY05 | Server revision mới hơn/cũ hơn | Không ghi đè tự động; giữ dữ liệu máy và thể hiện xung đột. |
| SY06 | Server cập nhật khi tuyến đang mở/bản nháp còn | Không thay dữ liệu đang sửa; nhận ở thời điểm an toàn. |
| SY07 | Tuyến đã gỡ, server vẫn hoàn thành | Không tải lại vô hạn. |
| SY08 | Server giao lại tuyến chưa hoàn thành | Bỏ dấu đã lưu trữ và nhận lại. |
| SY09 | Gửi thử staging | Xác minh request, token và dữ liệu chỉ thuộc staging, không ghi API server chính. |
| SY10 | Đổi tên tuyến từ server | Danh sách, bộ ảnh và chữ trên ảnh cập nhật; ảnh thiếu gốc có thông báo. |

## Xuất báo cáo

| Mã | Thực hiện | Kết quả cần kiểm tra |
| --- | --- | --- |
| EX01 | Excel tuyến có OK và NOK | Sheet chính đủ mọi vị trí; sheet Chưa đạt - Sửa chữa có ảnh trước/sau đúng hàng. |
| EX02 | Excel có metadata cableWork | Đủ tiêu đề, team, người thực hiện, ngày, chiều dài và sáu cột của mẫu mới. |
| EX03 | Xuất dữ liệu không có cableWork | Giữ mẫu nền sáu cột tọa độ, không làm file hỏng. |
| EX04 | KMZ với STT nhập lộn thứ tự | Đường nối theo STT, longitude trước latitude; marker đầu/cuối/MX/DP đúng. |
| EX05 | KMZ một vị trí | Một marker, không nhân đôi và không tạo đường giả. |
| EX06 | Nhiều tuyến trùng tên | ZIP không đè file; mỗi tuyến có đúng Excel/KMZ theo lựa chọn. |
| EX07 | Tuyến trống/file ảnh thiếu | Báo lỗi/cảnh báo; không báo xuất thành công với file hỏng. |
| EX08 | Lưu file/chia sẻ trên điện thoại | Mở được tệp, ảnh nhúng còn đủ, tên tiếng Việt không hỏng. |

## Kiểm thử nền kèm theo

[RulesTest](reference/4.1.4/tests/RulesTest.kt), [RouteSequenceTest](reference/4.1.4/tests/RouteSequenceTest.kt), [RouteMoveNumbersTest](reference/4.1.4/tests/RouteMoveNumbersTest.kt), [RouteMonthTest](reference/4.1.4/tests/RouteMonthTest.kt), [ExportsTest](reference/4.1.4/tests/ExportsTest.kt), [CableArchiveCheckTest](reference/4.1.4/tests/CableArchiveCheckTest.kt), [CableSyncCheckTest](reference/4.1.4/tests/CableSyncCheckTest.kt).

Đưa các test thích hợp vào dự án native đầy đủ, chạy lại sau khi đổi UI/nghiệp vụ. Bảng trên còn cần kiểm thử thực tế với camera, GPS, mất mạng, máy cấu hình thấp và server staging; không suy ra đạt từ việc sao chép test.

## Yêu cầu giao việc cho GitHub Copilot

Thiết kế và triển khai lại phần Tuần tuyến theo [đặc tả 4.1.4](TUAN-TUYEN-4.1.4.md) và [hợp đồng dữ liệu](TUAN-TUYEN-DATA-API.md). Đọc mã tham chiếu trước khi thay logic. Giữ đầy đủ màn hình, nút, STT, GPS, bản nháp, ảnh trước/sau, xuất file và đồng bộ. Thêm giao diện staging rõ ràng và các trạng thái lỗi; phần Team/Đội mới phải có hợp đồng API. Bàn giao PR có ảnh các màn hình, kết quả từng kịch bản, phần chưa nối API và APK kiểm thử khi đã có nguồn native đầy đủ. Không đánh dấu hoàn thành chỉ vì có prototype HTML.
