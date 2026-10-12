# Các màn hình Android staging

“Có nền” nghĩa là chức năng đã có trong mã APK native 4.1.4; vẫn phải tích hợp với staging. “Bổ sung” là màn hình hoặc cách tổ chức mới cần thiết kế.

## Đăng nhập và tài khoản

| Mã | Màn hình | Thành phần và thao tác | Nền |
| --- | --- | --- | --- |
| AUTH_LOGIN | Đăng nhập | Logo, nhãn Staging, tên đăng nhập, mật khẩu, nhớ đăng nhập, nút Đăng nhập và Tạo tài khoản. Dùng offline chỉ khi có phiên hợp lệ đã lưu. | Có nền |
| AUTH_REGISTER | Đăng ký | Họ tên, tên đăng nhập, mật khẩu, email, điện thoại; lỗi tại trường; nút Gửi đăng ký. Staging dùng nhóm riêng sau đăng ký; không bắt chọn tỉnh hoặc đội cố định. | Có nền, cần đổi hợp đồng đăng ký |
| AUTH_PENDING | Chờ duyệt | Thông báo đã gửi, yêu cầu quản trị duyệt, nút quay lại đăng nhập. Phân biệt đang chờ, bị từ chối và tài khoản bị khóa. | Có nền |
| ACCOUNT | Tài khoản và đồng bộ | Họ tên, vai trò, môi trường Staging, phiên bản, trạng thái đồng bộ, Đồng bộ ngay, Đăng xuất. Giữ dữ liệu riêng theo tài khoản. | Có nền |

Tài khoản tự tạo chưa được dùng ngay: giữ yêu cầu quản trị phê duyệt của staging. Không coi người đăng ký là quản trị hoặc chủ nhóm chỉ vì tên nhóm trùng.

## Trang chủ và danh sách công việc

| Mã | Màn hình | Thành phần và thao tác | Nền |
| --- | --- | --- | --- |
| HOME | Trang chủ | Chào người dùng, nhãn Staging, các lối vào Sự cố / Tuần tuyến / Di dời / Camera, trạng thái đồng bộ, tiếp tục bản nháp. Thêm lối vào Team/Đội của tôi. | Có nền + lối vào nhóm mới |
| WORK_LIST | Công việc | Chọn loại công việc; bộ lọc nhóm, tháng, trạng thái; thẻ tên tuyến, người lập, trạng thái cùng làm. Thêm Sự cố/Di dời; chọn nhiều để xuất hoặc xóa theo quyền. | Có nền, bộ lọc nhóm mới |
| PATROL_LIST | Tuần tuyến | Tuyến được giao, nhóm tháng, bộ lọc Kiểm tra/Bảo dưỡng, Nhận tuyến được giao, trạng thái thực hiện. | Có nền |

Từ danh sách, mở lại đúng bộ lọc và vị trí cuộn khi quay về. Không thay các danh sách rỗng bằng báo cáo mẫu. Tác vụ chọn nhiều có thanh hành động riêng và cách thoát chọn rõ ràng.

## Sự cố và Di dời

Sự cố có bốn bước: **Thông tin → Cáp/vật tư → Ảnh/mô tả → Kiểm tra/xuất**. Di dời có ba bước; phần kiểm tra và hoàn thành nằm cuối bước Ảnh/mô tả. Có thể dùng tab hoặc thanh bước nhưng phải giữ trường dữ liệu và bản nháp.

| Mã | Nội dung | Thành phần và thao tác |
| --- | --- | --- |
| INCIDENT_INFO | Thông tin sự cố | Tuyến/tên báo cáo, địa điểm tự nhập hoặc lấy GPS, khoảng cách đến trạm, người lập, ngày báo cáo, giờ bắt đầu/khôi phục khi chế độ báo cáo cho phép, nguyên nhân hoặc Khác, phương án xử lý, Người cùng làm. |
| RELOCATION_INFO | Thông tin di dời | Tuyến/tên báo cáo, địa điểm tự nhập hoặc GPS, người lập, ngày thực hiện, nguyên nhân hoặc Khác, nội dung di dời, Người cùng làm. |
| MATERIALS | Cáp và vật tư | Thu hồi/kéo lại, số mét, chủng loại, khoảng vượt; cáp mới với hai đầu số và số mét tính ra; danh sách vật tư, số lượng, đơn vị, ghi chú; Thêm/Bỏ vật tư. |
| REPORT_PHOTOS | Ảnh và mô tả | Nhóm ảnh có tiêu đề, số ảnh, mũi tên thu gọn bên phải; ảnh theo hàng, mô tả từng ảnh và mô tả hàng ảnh; Chụp mới / Chọn ảnh / Thư viện; sửa, xóa theo quyền. |
| REPORT_REVIEW | Kiểm tra và xuất | Tóm tắt thông tin, vật tư, cảnh báo trường thiếu, Xuất Excel, Hoàn thành và xác nhận. |
| COLLABORATORS | Người cùng làm | Danh sách được phép chọn, đánh dấu nhiều người, Lưu/Hủy. Hiển thị người lập và tình trạng bị thu hồi quyền trên báo cáo cùng làm. |

Các thao tác quan trọng phải được giữ:

- Lưu bản nháp, quay lại, tiếp tục và xuất báo cáo; không mất dữ liệu khi đổi bước hoặc mở bàn phím.
- Ảnh MX: đọc tọa độ từ ảnh gốc; hiển thị đang đọc, đọc được, chưa đọc được hoặc lỗi. Có hành động đọc lại khi phù hợp.
- Tọa độ MX điền vào mô tả hàng ảnh theo quy tắc hiện có; giữ ghi chú người dùng đã sửa.
- Ảnh chờ GPS có lựa chọn tiếp tục hoặc hủy. GPS sai số lớn phải hiện thông tin để người dùng quyết định.
- Xóa ảnh hoặc toàn bộ ảnh có xác nhận phạm vi. Xóa ảnh khỏi báo cáo và xóa ảnh thư viện là hai hành động riêng.
- Người cùng làm chỉ có thao tác đúng quyền thực nhận; trạng thái hoàn thành hoặc thu hồi quyền phải được phản ánh.
- Đồng bộ không thay thế báo cáo đang mở sửa; nhận bản cập nhật sau khi đóng màn hình.

## Tuần tuyến và vị trí

| Mã | Màn hình | Thành phần và thao tác |
| --- | --- | --- |
| PATROL_POINTS | Vị trí trong tuyến | Tên tuyến, ghi chú giao việc, danh sách STT/vị trí/ảnh/GPS/ghi chú, Thêm ảnh, chọn từ thư viện, tiếp tục bản nháp, hoàn thành/mở lại theo quyền. |
| POINT_EDIT | Ảnh và thông tin vị trí | STT, tên vị trí, ghi chú, đánh giá OK/NOK, phương án, ngày ảnh, GPS và sai số; Lấy GPS, mở cài đặt vị trí, Lưu, Giữ bản nháp. |
| POINT_REPAIR | Thông tin sửa chữa | Ảnh vị trí, địa chỉ GPS, mô tả, kiến nghị/phương án, đánh giá, ảnh trước/sau sửa, chụp/chọn ảnh, Lưu. |
| EXPORT | Xuất và chia sẻ | Excel có ảnh, KMZ, lựa chọn nhiều tuyến khi hỗ trợ; tiến trình tạo file, lưu vào máy hoặc chia sẻ. |

Giữ chọn nhiều ảnh, chuyển ảnh sang tuyến khác theo quyền, chỉnh hiện/ẩn/đổi ngày toàn tuyến và thứ tự vị trí. Giới hạn các tác vụ bằng quyền và trạng thái báo cáo, không chỉ bằng màu nút.

## Camera và thư viện

| Mã | Màn hình | Thành phần và thao tác |
| --- | --- | --- |
| CAMERA | Chụp ảnh | Khung hình lớn, chụp, đổi camera/đèn/zoom khi thiết bị hỗ trợ, lấy nét, GPS, thông tin vị trí, ngày/màu chữ, ảnh gần nhất, mở Thư viện, chuyển Video. |
| VIDEO | Quay video | Bắt đầu/dừng, bộ đếm, trạng thái lưu, đèn/camera theo khả năng thiết bị, trở về chế độ Ảnh. Giữ giới hạn hiện tại: 2 phút hoặc 64 MB. |
| LIBRARY | Thư viện | Phân biệt tệp trên máy với tệp đã đồng bộ; ảnh/video, tháng, tiến trình gửi, tải lại, xem chi tiết, chọn nhiều và Thùng rác theo khả năng nguồn dữ liệu. |
| MEDIA_DETAIL | Xem ảnh hoặc video | Xem ảnh phóng to/phát video, tên, người chụp, ngày, GPS, mô tả; dùng ảnh trong báo cáo, chia sẻ, sửa hoặc xóa/khôi phục theo quyền. |

Ảnh Camera vẫn lưu theo tài khoản. Cần thể hiện được trường hợp đã lưu trên máy nhưng chưa sao lưu server, bị mất mạng, thiếu quyền hoặc file không còn. Không ghép Camera thành một trang web thay thế các chức năng native.

## Team và thành viên cần bổ sung

| Mã | Màn hình | Thành phần và thao tác |
| --- | --- | --- |
| TEAM_LIST | Team/Đội của tôi | Nhóm đã tạo hoặc tham gia, ô tìm kiếm có dấu/không dấu, Tạo nhóm. Giữ nguyên tên hiển thị do người tạo nhập. |
| TEAM_CREATE | Tạo nhóm | Tên nhóm, loại Cá nhân/Tổ chức; Tạo/Hủy; báo lỗi tại trường và báo trùng trong phạm vi API cho phép. |
| TEAM_DETAIL | Nhóm và thành viên | Tên đầy đủ, loại nhóm, số thành viên; mũi tên bên phải thu gọn cả danh sách; tìm thành viên, thêm người, quản lý quyền khi được phép. |

Người tạo nhóm là điều phối viên và có quyền cấp vai trò điều phối viên cho thành viên khác; thành viên mới mặc định là kỹ thuật viên. Chủ nhóm và điều phối viên có thể khác nhau về quyền quản lý, nên giao diện phải dùng quyền server trả về, không suy luận rằng mọi điều phối viên là chủ nhóm.

Tên nhóm “Đắk Lắk” vẫn hiển thị “Đắk Lắk”. Đường dẫn có thể là `nhom/daklak`, `nhom/daklak1`; slug không thay tên hiển thị. Nhóm cùng tên phải được phân biệt bằng mã nhóm khi lọc và lưu dữ liệu.

## Trạng thái bắt buộc cho thiết kế

Mỗi màn hình tải dữ liệu có mẫu: đang tải, có dữ liệu, trống, lỗi có thử lại, offline, không đủ quyền, phiên đăng nhập hết hạn. Biểu mẫu thêm đang lưu và lỗi từng trường. Màn hình ảnh thêm chờ GPS, đang đọc tọa độ, chưa đọc được và đang gửi ảnh.

Không biểu thị trạng thái chỉ bằng màu; dùng chữ hoặc biểu tượng có nhãn. Kiểm tra tên dài, tiếng Việt có dấu, bàn phím mở, cỡ chữ lớn và danh sách thành viên nhiều.
