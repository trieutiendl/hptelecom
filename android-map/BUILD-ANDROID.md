# Tạo APK bản đồ HP Telecom

Mã nguồn nằm trong `android-map/`. Trên máy Windows hiện tại, bộ biên dịch Android bị lỗi native khi đóng gói tài nguyên; GitHub Actions dùng Ubuntu để tạo APK cài thử.

Sau khi đẩy nhánh có thay đổi `android-map/` lên GitHub, mở **Actions → Build HP Telecom Map APK**. Khi chạy xong, tải artifact **HP-Telecom-Map-Android**, giải nén rồi cài `app-debug.apk` lên điện thoại. Có thể chạy thủ công bằng **Run workflow**.

Đây là APK debug để thử nội bộ, chưa phải bản phát hành chính thức. Mỗi lần CI tạo khóa ký debug mới; muốn cài bản mới sau đó cần gỡ bản cũ trước. App dùng tài khoản HP Telecom để xem dữ liệu bản đồ trên server.

**Điều kiện trước khi dùng KMZ cục bộ và chia sẻ tuyến đã chọn:** máy chủ cần công bố cầu nối `HPPatrolLiveMap.getActive()` cùng sự kiện `hp-map-ready`/`hp-map-disposed`. Bản server 4.0.44 đang chạy chưa có cầu nối này; đến khi cập nhật server, APK vẫn mở bản đồ server nhưng lớp KMZ nhập từ máy và nút chia sẻ tuyến trong app chưa dùng được.
