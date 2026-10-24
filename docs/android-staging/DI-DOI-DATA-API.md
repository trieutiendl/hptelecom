# Dữ liệu và API Di dời Android 4.1.4

Tài liệu hợp đồng client phục vụ thiết kế Android staging. Đọc [đặc tả](DI-DOI-4.1.4.md) trước khi nối API. Các endpoint mobile dưới đây chưa được xác nhận tương thích với server staging.

## Mô hình

Nguồn: [IncidentReport](reference/4.1.4/relocation/source/IncidentReport.kt), [chuyển đổi wire](reference/4.1.4/relocation/excerpts/ServerSync.reportWire.kt.txt).

Place lưu `kind="Di dời"`, tên và ID local. IncidentRecord chứa report theo placeId. Payload đồng bộ có version=1, module=relocation, name, completed, photos và report. Danh sách ảnh nhóm nằm trong report.groups; không bỏ mất chỉ vì photos ở cấp payload rỗng.

| Trường report | Ý nghĩa |
| --- | --- |
| relocation | true cho Di dời. |
| completed | Hoàn thành nghiệp vụ, khác trạng thái đã gửi. |
| location, locationMode | Địa điểm và manual/gps. |
| reporter, date | Người thực hiện và ngày dd/MM/yyyy; date dùng lọc tháng. |
| cause, solution | Nguyên nhân và nội dung người dùng nhập. |
| cable, cableSpan | FO chung và khoảng vượt. |
| splitRelocation, newCableEnabled | Đã chuyển đổi cấu trúc cũ; có sử dụng cáp mới. |
| start, end | Hai đầu số cáp mới dưới dạng chuỗi. |
| relocationLength | Mét lưu theo bản cũ, chỉ dự phòng khi chưa nhập đầu số. |
| recovery | JSON object enabled, length, cable khi serialize hiện tại; parser còn đọc Boolean cũ. |
| recoveryLength, recoveryCable | Trường tương thích local cũ; giữ nhất quán với object recovery. |
| materials | Array name, type, quantity, unit, note. |
| groups | Array name, note, pairNotes, images. |
| privateGroups | Metadata cũ cần giữ, không tự dựng thêm chế độ UI từ sự tồn tại trường. |
| pendingPhoto, pendingOriginal, pendingGroup, pendingDate, pendingNote, pendingStamped | Trạng thái ảnh chờ trên máy; không đưa vào report wire. |

`distance`, `received`, `restored`, `closureLocation`, `closureLocation2` có thể còn trong model dùng chung, nhưng không là trường cần nhập của Di dời.

### Nhóm ảnh và metadata

- Group.name là khóa nghiệp vụ; **Trong thi công** chỉ đổi nhãn hiển thị thành Hình ảnh thi công.
- PairNotes[k] thuộc cặp ảnh 2k và 2k+1; thiếu thì dùng group.note. Xóa ảnh phải remap mô tả theo ảnh còn lại.
- IncidentImage local: path, width, height, caption, originalFile, metadata JSON.
- Metadata có thể chứa lat/lon, gps, gpsSource/source, photographer, uploadedByName, mxLocalReadVersion, mxLocalReadState (READABLE/UNREADABLE/ERROR), mxLocalReadSha256 và mxCoordinateDescriptionApplied. Giữ metadata chưa biết.
- Wire dùng `file` thay `path`; file/originalFile được đổi từ đường dẫn thành asset ID trước gửi. commonRelocationCable giữ loại cáp chung.
- Dữ liệu cũ “Măng xông / treo néo”, “Treo/néo”, “Treo / néo”, “Treo néo” đổi tên nhóm thành Vật tư nhưng giữ ảnh/mô tả; không tự phân loại lại ảnh cũ.
- Dossier/route cũ được xử lý qua lớp tương thích; UI 4.1.4 đang dùng một báo cáo cho một Place. Không tự thiết kế màn hình nhiều tuyến chỉ từ tên IncidentDossier.

## Tính toán cần giữ

```text
metCapMoi = abs(number(start) - number(end))
number chấp nhận số không âm và dấu "," hoặc "." thập phân

Ví dụ minh họa:
cable = 12FO
recovery.enabled = true, recovery.length = 50
newCableEnabled = true, start = 3067, end = 2917
=> cáp mới = 150 m
=> solution gồm "Thu hồi kéo lại 50m 12FO" + nội dung người dùng
=> summary gồm "150m cáp 12FO" + vật tư mới
```

Chỉ dùng relocationLength khi phù hợp cấu trúc cũ và cả start/end rỗng. Một đầu số có, một đầu thiếu thì không được thay bằng giá trị cũ. Không nhập lại số tổng vào hai phần và không cộng cáp thu hồi thành cáp mới.

## API dùng chung báo cáo

Nguồn: [gửi report](reference/4.1.4/excerpts/ServerSync.uploadLocked.kt.txt), [gửi ảnh cùng làm](reference/4.1.4/relocation/excerpts/ServerSync.uploadContribution.kt.txt), [SharedReports](reference/4.1.4/relocation/source/SharedReports.kt).

| Request tương đối với base API | Hợp đồng client |
| --- | --- |
| GET mobile/folders | Danh sách folder theo quyền. |
| GET mobile/folders/{id} | revision, payload, owner và thông tin quyền/chia sẻ. |
| POST mobile/assets | module=relocation, base64 và folderId khi cần; nhận id. Kiểm tra tệp thuộc tài khoản và ≤10 MB. |
| GET mobile/assets/{id} | Tải ảnh theo quyền. |
| PUT mobile/folders/{id} | operation UUID, revision và payload đầy đủ của người lập; nhận revision. |
| GET mobile/folders/{id}/collaborators | members, candidates, revision. |
| PUT mobile/folders/{id}/collaborators | revision, users array ID. |
| POST mobile/folders/{id}/photo-mutations | Delta ảnh của người cùng làm kèm operation/revision. |
| POST mobile/folders/{id}/contributions | Nhánh tương thích request pending cũ; không thay photo-mutations bằng gửi full report. |

PhotoMutationDelta định nghĩa additions/removals/caption changes và cách rebase. Dùng lớp tham chiếu, không tự suy đoán tên thuộc tính delta từ tên endpoint.

## Lưu nhận và lỗi

- Ghi DB local theo hàng đợi, giữ pending request trước gửi để tránh lặp thao tác khi mất mạng.
- HTTP 409: lấy bản server để đối chiếu baseline; giữ local nếu không thể gộp an toàn. Không ghi đè cả report của người lập bằng báo cáo contributor cũ.
- HTTP 403 khi gửi ảnh cùng làm: đánh dấu bị thu hồi quyền và giữ ảnh chưa gửi.
- HTTP 404/405 của tính năng cùng làm: báo server chưa hỗ trợ hoặc kiểm tra quyền, không âm thầm gửi vào một báo cáo mới.
- Sau thay thành viên, revision có thể đổi; đánh dấu cần nhận lại, tránh gửi nhầm revision cũ.
- [AutoSync](reference/4.1.4/relocation/source/AutoSync.kt) và [refreshLinked](reference/4.1.4/relocation/excerpts/ServerSync.refreshLinked.kt.txt) giữ việc nhận GPS MX khi revision không tăng. Không tiêu hết lượt chờ chỉ vì màn hình đang sửa.
- Không nhập các pending field/đường dẫn local như dữ liệu server. Không lấy chú thích người dùng làm bằng chứng GPS.

## Gỡ bản local của Di dời

[LocalReportRetention](reference/4.1.4/relocation/source/LocalReportRetention.kt) và [removePastMonthReports](reference/4.1.4/relocation/excerpts/ServerSync.removePastMonthReports.kt.txt) yêu cầu báo cáo tháng trước, local/server hoàn thành, đúng chủ sở hữu, payload/asset/baseline/revision/chữ ký đã xác nhận trùng, không pending/refresh và không đang mở sửa.

Nếu server bổ sung GPS mà revision giữ nguyên, nhận và lưu đầy đủ bản mới trước; giữ bản local trong vòng đó. Gỡ local không xóa báo cáo server. Không sao chép chính sách gỡ ngay của Sự cố hoặc tuyến đã hoàn thành sang Di dời.

## Staging và fixture

[relocation-example.json](fixtures/relocation-example.json) dùng dữ liệu giả, không có ảnh hoặc asset thật. Model trong fixture là tập trường minh họa, không phải payload đầy đủ đã được server kiểm chứng.

Mục tiêu API nằm dưới https://hptelecom.id.vn/staging/api/. Phải có adapter/hợp đồng mobile tương thích, đăng nhập/token/capability, nhóm theo ID và quyền do server kiểm soát. Bản staging không tự chuyển request sang /api/ server chính. Prototype phải ghi rõ Minh họa và trạng thái chưa nối API.
