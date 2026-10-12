# Mã nguồn và kết nối Android staging

## Nền đã đối chiếu

Cấu trúc được đọc từ mã native HP Telecom Android **4.1.4 (136)**. Giao diện đang tạo bằng Kotlin và Android Views; nhiều màn hình nằm trong cùng một Activity. Tên màn hình trong SCREENS.md là nhóm trải nghiệm, không khẳng định mỗi màn hình đã có một file riêng.

Bộ tài liệu này không kèm mã native, khóa ký, APK, cơ sở dữ liệu, tài khoản hoặc ảnh báo cáo. Trước khi triển khai trên Android, cần đưa đúng mã nguồn native 4.1.4 vào nhánh phát triển. Không dùng một dự án WebView cũ hoặc file HTML làm bằng chứng rằng các chức năng native đã được sao chép.

## Bản đồ mã native

Các tên dưới đây nằm dưới `app/src/main/java/vn/vltelecom/` của nguồn native 4.1.4.

| File/lớp | Trách nhiệm |
| --- | --- |
| ServerActivity.kt | Đăng nhập, đăng ký, chờ duyệt, tài khoản, đồng bộ và đăng xuất. |
| MainActivity.kt | Trang chủ, danh sách công việc, tuyến, điểm, nhập thông tin ảnh và xuất/chia sẻ. |
| IncidentActivity.kt | Biểu mẫu Sự cố/Di dời, bước báo cáo, vật tư, nhóm ảnh, GPS, người cùng làm. |
| CableRepairActivity.kt | Chi tiết sửa chữa của vị trí và ảnh trước/sau. |
| CaptureActivity.kt | Camera chụp ảnh, thông tin vị trí và chữ trên ảnh. |
| VideoCaptureActivity.kt | Quay video và lưu/sao lưu. |
| StandaloneCameraActivity.kt | Thư viện Camera trên máy, chọn ảnh cho báo cáo. |
| PhotoLibraryActivity.kt | Thư viện server, chọn ảnh, sửa, chia sẻ, thùng rác. |
| PhotoDownloadActivity.kt, PhotoViewer.kt | Lưu/chia sẻ và xem ảnh. |
| ServerSession.kt, ServerDiscovery.kt | Địa chỉ server, nhận dạng server, đăng nhập và phiên. |
| ServerSync.kt, AutoSync.kt | Gửi/nhận, xung đột, bản chờ và đồng bộ. |
| MxLocalReader.kt, MxLocalParser.kt, MxLocalCache.kt | Đọc tọa độ MX và bộ nhớ kết quả. |
| MxImageRegions.kt, MxReadEvidence.kt, MxRefreshSchedule.kt | Sửa đọc ảnh gốc và nhận lại tọa độ trong bản 136. |
| MxCoordinates.kt, MxPhotoUpload.kt | Metadata GPS, mô tả hàng ảnh và giữ ảnh gốc. |
| SharedReports.kt, IncidentReport.kt, WorkApp.kt | Quyền cùng làm, dữ liệu báo cáo, dữ liệu theo tài khoản. |
| Registration.kt | Kiểm tra đăng ký và xác minh server nhận đăng ký. |

Khi tách UI thành các thành phần dùng lại, giữ hành vi và hợp đồng dữ liệu của các lớp nghiệp vụ. Việc đổi màu, bố cục, biểu tượng không cần viết lại OCR, lưu ảnh, đồng bộ hoặc tính vật tư.

## Cấu trúc triển khai đề xuất

```text
android-staging/
  app/src/main/
    java/vn/vltelecom/
      ui/theme/          # màu, chữ, khoảng cách, trạng thái
      ui/components/     # thẻ, biểu mẫu, chọn nhóm/tháng, nhóm ảnh
      ui/auth/
      ui/home/
      ui/work/
      ui/incident/
      ui/patrol/
      ui/relocation/
      ui/camera/
      ui/library/
      ui/team/
      ui/account/
      ...                # giữ hoặc tách các lớp nghiệp vụ đã có
    res/                 # logo, biểu tượng, màu và chuỗi dịch
```

Đây là cấu trúc dự kiến, chưa phải thư mục native đã được thêm trong PR tài liệu.

## Tách staging khỏi server chính

- Tên ứng dụng mục tiêu: **HP Telecom Staging**.
- Application ID đề xuất: `vn.hptelecom.staging`, khác `vn.hptelecom.server`, để cài song song.
- Địa chỉ mục tiêu: `https://hptelecom.id.vn/staging`; API mục tiêu ở `/staging/api/`.
- Nhận dạng server, phiên, dữ liệu local, bản nháp, cache, ảnh và hàng chờ phải thuộc staging. Không dùng lại token hoặc mã server chính.
- Giữ tiền tố `/staging` khi tạo URL. Chặn đường dẫn hoặc chuyển hướng làm mất tiền tố rồi đi vào `/api/` của server chính.
- Không tự tìm server chính khi staging mất kết nối. Hiển thị offline/lỗi và giữ dữ liệu chưa gửi.
- Tên album/file xuất nên cho biết Staging để tránh nhầm khi chia sẻ.

## Khác biệt API cần giải quyết

Kiểm tra ngày 12/10/2026: GET `https://hptelecom.id.vn/staging/api/status` trả HTTP 401. Mã API staging đã đọc dùng các tuyến `/auth/login`, `/auth/me`, `/teams`, `/incidents`, `/relocations` và phiên cookie. Hợp đồng mobile của APK chưa được xác nhận hoạt động trên staging.

APK native hiện tại yêu cầu các nhóm API sau, tương đối dưới base API:

| Nhóm | Hợp đồng đang dùng |
| --- | --- |
| Trạng thái và đăng nhập | `status`, `login`, `me`, `logout`; nhận dạng server, capability, token và quyền người dùng. |
| Đăng ký | `register`, kết quả chờ duyệt. |
| Báo cáo | `mobile/folders`, `mobile/folders/{id}`; phiên bản, chống gửi trùng, quyền và xung đột. |
| Ảnh báo cáo | `mobile/assets`, `mobile/assets/{id}`; giữ metadata và quyền truy cập. |
| Người cùng làm | `mobile/folders/{id}/collaborators`, `contributions`, `photo-mutations`. |
| Tuần tuyến | `mobile/cable-tasks`, cập nhật tăng dần khi được server hỗ trợ. |
| Thư viện | `mobile/library`, chi tiết, sửa, thùng rác/khôi phục, video theo capability. |

Ngoài khác biệt API, `ServerSession.normalize` của bản chính hiện chỉ nhận URL gốc, chưa nhận đường dẫn `/staging`. Phải xử lý đường dẫn và nhận dạng server đúng cách; đổi mỗi hằng URL sẽ không đủ.

Cần quyết định và kiểm thử adapter hoặc API mobile tương thích ở bước tích hợp. Không bỏ kiểm tra quyền/capability để làm màn hình đăng nhập có vẻ thành công. Trong prototype, dùng nguồn giả có nhãn Minh họa; tính năng chưa nối API phải hiện rõ trạng thái đó.

## Quyền staging cần thể hiện

Staging có vai trò toàn ứng dụng admin/coordinator/operator; vai trò trong nhóm owner/coordinator/technician là phạm vi khác. Thiết kế chỉ dùng nhãn tiếng Việt thân thiện nhưng phải giữ phân biệt hai phạm vi.

Trang quản lý người dùng chỉ dành cho quản trị. Luồng đăng nhập web chuyển quản trị đến Người dùng là quy tắc của web; không tự áp dụng chuyển trang web đó cho màn hình khởi đầu native nếu chưa có yêu cầu. Thiết kế Android ưu tiên trang công việc và các thao tác được cấp quyền.

## Kiểm thử sau khi triển khai

- Cài bản chính và staging cùng máy, đăng nhập hai tài khoản, dữ liệu và phiên tách biệt.
- Gửi báo cáo/ảnh mới đến staging và xác nhận xuất hiện trên staging; theo dõi request để xác nhận không ghi server chính.
- Đăng ký chờ duyệt; sai quyền, hết phiên, mất mạng và thử lại.
- Team có dấu, nhóm trùng tên, mã nhóm khác nhau, nhiều thành viên và thu gọn toàn bộ.
- MX ảnh mới không có EXIF, đọc lại, GPS server đến muộn nhưng revision giữ nguyên, đang mở sửa báo cáo.
- Bản nháp, Camera/video, GPS, quyền Android, thư viện, Excel/KMZ và thao tác cùng làm.
- Kiểm thử thiết bị thật. 226 kiểm thử JVM đạt của bản chính 136 chỉ là kết quả nền, không thay cho kiểm thử bản staging.
