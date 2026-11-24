# HP Telecom

## Ứng dụng bản đồ Android

Ứng dụng mở bản đồ HP Telecom bằng tài khoản hiện có trên server. Khi server chuyển tới trang đăng nhập hoặc trả lỗi xác thực, nút “Đăng nhập” sẽ mở liên kết đăng nhập HP Telecom; thông tin tài khoản vẫn do server xác thực.

Ứng dụng hỗ trợ tạo thư mục bản vẽ trên thiết bị, chạm bản đồ để thêm điểm, và chạm nhiều vị trí để vẽ đường. Các bản vẽ mới được lưu cục bộ trên điện thoại và có thể xuất cùng bản đồ thành KMZ. Chúng chưa được ghi lên dữ liệu tuyến trên server vì hiện chưa có API lưu bản vẽ.

### Xem giao diện mẫu

Mở [`android-map/demo/index.html`](android-map/demo/index.html) để xem thử màn hình đăng nhập, danh sách tuyến, bộ lọc DWDM/CSG và dung lượng cáp, lớp bản đồ, nhập KMZ, thư mục, điểm, đường và chia sẻ. Sau khi bật GitHub Pages và chạy workflow, demo sẽ có tại <https://trieutiendl.github.io/hptelecom/>.

Phần đăng nhập trong trang mẫu chỉ chuyển sang giao diện giả lập; không nhập mật khẩu thật. Liên kết riêng sẽ mở trang HP Telecom thật. Tuyến trong demo là dữ liệu giả, còn thư mục/điểm/đường tạo thử chỉ lưu trong trạng thái demo và chưa ghi lên server.

### Tạo APK

Workflow [Build HP Telecom Map APK](.github/workflows/build-android-map.yml) tạo APK debug khi chạy thủ công hoặc khi có thay đổi trong `android-map/`. APK được lưu thành artifact của lần chạy GitHub Actions.
