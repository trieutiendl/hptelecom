# Quyền riêng tư của HP Map

Ngày cập nhật 10/11/2026. Chính sách này áp dụng cho HP Map trên iPhone và cách ứng dụng kết nối với dịch vụ bản đồ của HP Telecom.

## Tài khoản và dữ liệu tuyến

HP Telecom sử dụng tên đăng nhập, mật khẩu và thông tin phiên để xác thực người dùng. Khi bạn đăng ký qua cổng tài khoản, hệ thống nhận họ tên, tên đăng nhập, đội hoặc đơn vị và mật khẩu. Bạn có thể cung cấp email và số điện thoại để liên hệ. Máy chủ dùng danh tính và quyền của tài khoản để xác định những tuyến, điểm và thư mục bạn có thể xem hoặc chỉnh sửa.

Để quản lý phiên đăng nhập riêng cho từng thiết bị, HP Map tạo một mã ngẫu nhiên (UUID), lưu mã trong Chuỗi khóa (Keychain) của iPhone và gửi mã đến HP Telecom khi thiết lập phiên. Máy chủ lưu giá trị băm SHA-256 của nền tảng và mã này cùng với phiên của tài khoản. Nhờ đó, hệ thống có thể thay thế hoặc thu hồi phiên trên từng thiết bị.

Khi bạn lưu hoặc nhập dữ liệu lên máy chủ, nội dung có thể gồm tên tuyến và trạm, tọa độ, đường đi, ghi chú, bản đồ KML/KMZ hoặc đề xuất tuyến MBF. Hệ thống có thể ghi nhận tài khoản, thời điểm đăng nhập và thao tác sửa dữ liệu để kiểm soát quyền truy cập và đối chiếu thay đổi. Nội dung được chia sẻ với người có quyền trên hệ thống của đơn vị.

Trong **Tuyến máy**, bản vẽ cá nhân được lưu trên iPhone theo từng tài khoản. Từ phiên bản 1.0.9, khi bạn chọn **Nhập KMZ vào Tuyến máy**, HP Map đọc tệp trên iPhone và thêm các điểm, đường cùng thông tin kèm theo vào thư mục của tài khoản đang mở. Khi bạn xuất KMZ từ **Tuyến máy**, ứng dụng tạo tệp trên thiết bị. Nơi lưu, ứng dụng hoặc người nhận do bạn chọn có thể giữ một bản sao.

HP Telecom chỉ sử dụng dữ liệu tài khoản, vị trí và dữ liệu tuyến để vận hành dịch vụ và hỗ trợ người dùng. HP Telecom không bán dữ liệu này hoặc sử dụng, chia sẻ dữ liệu này cho mục đích quảng cáo hay tiếp thị.

## Vị trí và bản đồ nền

Khi bạn chọn định vị hoặc thêm điểm bằng vị trí hiện tại, HP Map xin quyền dùng vị trí khi ứng dụng đang mở. iOS cung cấp tọa độ với độ chính xác mà bạn cho phép. Nút định vị dùng tọa độ để đưa bản đồ đến vị trí của bạn. Khi bạn lưu vị trí thành điểm trên máy chủ, tọa độ trở thành một phần của dữ liệu tuyến. Bạn có thể tắt vị trí chính xác hoặc thu hồi quyền của HP Map trong **Cài đặt → Quyền riêng tư & Bảo mật → Dịch vụ định vị**.

Tùy lớp nền, iPhone gửi yêu cầu ảnh bản đồ đến OpenStreetMap, OpenTopoMap hoặc MapTiler. Nhà cung cấp nhận địa chỉ IP của kết nối cùng vùng bản đồ và mức thu phóng cần hiển thị. Các yêu cầu có thể cho biết khu vực bạn đang xem, kể cả khi bạn đưa bản đồ tới vị trí hiện tại. OpenStreetMap ghi nhận thông tin yêu cầu để vận hành, chẩn đoán và bảo vệ dịch vụ, đồng thời công bố thống kê tổng hợp về các ảnh bản đồ được tải. MapTiler tổng hợp số lượt yêu cầu và có thể lưu địa chỉ IP tối đa hai tháng để kiểm tra bảo mật. Khi bạn chọn **Chỉ đường**, HP Map chuyển tọa độ đích sang Apple Maps. [Quyền riêng tư của OpenStreetMap](https://osmfoundation.org/wiki/Privacy_Policy) · [Chính sách sử dụng ảnh bản đồ OpenStreetMap](https://operations.osmfoundation.org/policies/tiles/) · [Thông tin về OpenTopoMap](https://opentopomap.org/about) · [Quyền riêng tư của MapTiler](https://www.maptiler.com/privacy-policy/) · [Thống kê lượt sử dụng của MapTiler](https://docs.maptiler.com/guides/account/usage-analytics/) · [Quyền riêng tư của Apple](https://www.apple.com/legal/privacy/)

## Lưu giữ và bảo vệ dữ liệu

Ứng dụng kết nối với HP Telecom qua HTTPS. Dịch vụ bảo vệ kết nối của Cloudflare có thể xử lý địa chỉ IP và thông tin yêu cầu khi chuyển lưu lượng tới cổng HP Telecom. Trên iPhone, ứng dụng bảo vệ thông tin phiên, bản lưu tuyến và thông tin đăng nhập mà bạn chọn ghi nhớ bằng bộ nhớ bảo mật hoặc dữ liệu được mã hóa. Bạn có thể dùng tùy chọn quên thông tin đăng nhập đã lưu. Đăng xuất sẽ xóa bản lưu dữ liệu máy chủ của tài khoản trên máy. Các bản vẽ cá nhân đã lưu trên iPhone có thể vẫn còn để bạn dùng lại. [Quyền riêng tư của Cloudflare](https://www.cloudflare.com/privacypolicy/)

Dữ liệu tài khoản phục vụ việc đăng nhập và quản lý quyền trong thời gian sử dụng dịch vụ. Dữ liệu tuyến đã chia sẻ có thể là hồ sơ công việc của đơn vị. Bạn có thể liên hệ HP Telecom hoặc quản trị viên của đơn vị để xác định dữ liệu được lưu giữ, yêu cầu truy cập, sửa hoặc xóa. Tệp đã xuất sang một thiết bị, ứng dụng hoặc người nhận khác cần được quản lý tại nơi nhận.

## Xóa tài khoản hoặc gửi yêu cầu

Đăng nhập vào cổng HP Telecom bằng tài khoản của bạn, mở **Tài khoản → Xóa tài khoản của tôi**, đọc phạm vi xóa và xác nhận bằng mật khẩu hiện tại. Nếu bạn không thể đăng nhập hoặc cần trợ giúp với dữ liệu tuyến cụ thể, hãy gửi yêu cầu tới HP Telecom. Khi liên hệ, chỉ cần nêu tài khoản và loại yêu cầu. Giữ riêng mật khẩu, mã xác minh và dữ liệu tuyến của đơn vị. [Cổng tài khoản HP Telecom](https://hptelecom.id.vn/dangnhap) · [trieutiendl@gmail.com](mailto:trieutiendl@gmail.com) · [0857 570 123](tel:+84857570123) · [Hỗ trợ HP Map](support.md)
