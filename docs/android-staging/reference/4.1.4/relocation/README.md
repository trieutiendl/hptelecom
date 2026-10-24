# Tham chiếu Di dời Android 4.1.4

Mã đọc từ Android native 4.1.4, build 136, đối chiếu ngày 12/10/2026. Dùng cho GitHub Copilot/người triển khai thiết kế lại phần Di dời staging theo [đặc tả](../../../DI-DOI-4.1.4.md), [API](../../../DI-DOI-DATA-API.md) và [nghiệm thu](../../../DI-DOI-CHECKLIST.md).

Bộ này gồm 19 file nguồn, 14 file test nền và 4 đoạn đồng bộ. Nội dung tham chiếu giữ nguyên logic mã nền, có thể chuẩn hóa xuống dòng. [Manifest](manifest.json) ghi đường dẫn và SHA256 của file nguồn đầy đủ; đoạn trích có thêm số dòng gốc.

Chưa phải dự án Android build độc lập: các lớp còn phụ thuộc tài nguyên, database, camera, thư viện OCR, nguồn ứng dụng đầy đủ. Không đưa thư mục này thành module build. Khi sửa UI, thay nguồn ứng dụng và ghi nhận điểm khác bản nền.

## Điểm bắt đầu

- IncidentActivity: ba bước, quyền người lập/thành viên, camera/GPS, nhóm ảnh và xuất.
- IncidentReport: model, IncidentRules, tính cáp, chuyển đổi dữ liệu cũ và IncidentExport.
- SharedReports, PhotoMutationDelta, ReportPhotoRemoval: quyền cùng làm, delta ảnh và giữ mô tả khi xóa.
- MxCoordinates, MxLocalReader và các helper: ảnh gốc, EXIF/OCR, kiểm tra, cache, điền mô tả một lần.
- AutoSync, MxRefreshSchedule và các đoạn ServerSync: pending/revision, nhận GPS muộn và gỡ local có xác nhận.
- MainActivity, WorkModel, CaptureActivity, Exports và PhotoRenderer dùng chung có trong [bộ Tuần tuyến](../README.md).
- [Snapshot payload](../excerpts/ServerSync.snapshot.kt.txt), [gửi report người lập](../excerpts/ServerSync.uploadLocked.kt.txt), [bảo vệ bản đang sửa](../excerpts/WorkApp.canReceiveReport.kt.txt) cũng dùng chung.

## File nguồn

- [IncidentActivity.kt](source/IncidentActivity.kt)
- [IncidentReport.kt](source/IncidentReport.kt)
- [IncidentDossier.kt](source/IncidentDossier.kt)
- [IncidentPhotoGps.kt](source/IncidentPhotoGps.kt)
- [SharedReports.kt](source/SharedReports.kt)
- [ReportPhotoRemoval.kt](source/ReportPhotoRemoval.kt)
- [ReportCompletion.kt](source/ReportCompletion.kt)
- [ReportMonthSelection.kt](source/ReportMonthSelection.kt)
- [LocalReportRetention.kt](source/LocalReportRetention.kt)
- [PhotoMutationDelta.kt](source/PhotoMutationDelta.kt)
- [MxCoordinates.kt](source/MxCoordinates.kt)
- [MxPhotoUpload.kt](source/MxPhotoUpload.kt)
- [MxLocalReader.kt](source/MxLocalReader.kt)
- [MxLocalParser.kt](source/MxLocalParser.kt)
- [MxLocalCache.kt](source/MxLocalCache.kt)
- [MxImageRegions.kt](source/MxImageRegions.kt)
- [MxReadEvidence.kt](source/MxReadEvidence.kt)
- [MxRefreshSchedule.kt](source/MxRefreshSchedule.kt)
- [AutoSync.kt](source/AutoSync.kt)

## Test nền

- [RelocationTest.kt](tests/RelocationTest.kt)
- [IncidentReportTest.kt](tests/IncidentReportTest.kt)
- [MxGroupsTest.kt](tests/MxGroupsTest.kt)
- [MxCoordinatesTest.kt](tests/MxCoordinatesTest.kt)
- [MxPhotoUploadTest.kt](tests/MxPhotoUploadTest.kt)
- [MxLocalParserTest.kt](tests/MxLocalParserTest.kt)
- [MxLocalCacheTest.kt](tests/MxLocalCacheTest.kt)
- [MxReadingRegressionTest.kt](tests/MxReadingRegressionTest.kt)
- [MxRefreshScheduleTest.kt](tests/MxRefreshScheduleTest.kt)
- [ReportPhotoRemovalTest.kt](tests/ReportPhotoRemovalTest.kt)
- [ReportCompletionTest.kt](tests/ReportCompletionTest.kt)
- [ReportMonthSelectionTest.kt](tests/ReportMonthSelectionTest.kt)
- [LocalReportRetentionTest.kt](tests/LocalReportRetentionTest.kt)
- [PhotoMutationDeltaTest.kt](tests/PhotoMutationDeltaTest.kt)

## Đoạn đồng bộ

- [ServerSync.reportWire.kt.txt](excerpts/ServerSync.reportWire.kt.txt) — dòng 45–54 của ServerSync.kt
- [ServerSync.uploadContribution.kt.txt](excerpts/ServerSync.uploadContribution.kt.txt) — dòng 63–132 của ServerSync.kt
- [ServerSync.removePastMonthReports.kt.txt](excerpts/ServerSync.removePastMonthReports.kt.txt) — dòng 342–413 của ServerSync.kt
- [ServerSync.refreshLinked.kt.txt](excerpts/ServerSync.refreshLinked.kt.txt) — dòng 257–290 của ServerSync.kt

Mã dùng chung chứa nhánh Sự cố: chỉ áp dụng nhánh relocation cho Di dời. Constructor ban đầu có 5 nhóm nhưng luồng người lập gọi prepare để thành 7 nhóm; test constructor không thay cho kiểm thử màn hình thật.

Không kèm mật khẩu, khóa ký, database hoặc ảnh báo cáo. [Fixture](../../../fixtures/relocation-example.json) là dữ liệu giả; không gọi server thật từ prototype.
