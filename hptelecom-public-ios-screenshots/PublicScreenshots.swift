import XCTest

final class PublicScreenshots: XCTestCase {
    private let app = XCUIApplication(bundleIdentifier: "vn.hptelecom.fieldwork")

    override func setUp() {
        super.setUp()
        continueAfterFailure = false
    }

    private func reveal(_ element: XCUIElement) {
        XCTAssertTrue(element.waitForExistence(timeout: 20), "Missing \(element)")
        let window = app.windows.firstMatch
        for _ in 0..<6 {
            if element.isHittable { return }
            let keyboard = app.keyboards.firstMatch
            let height = window.frame.height
            let usableHeight = keyboard.exists ? min(height, keyboard.frame.minY - window.frame.minY) : height
            let upper = window.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: usableHeight * 0.30 / height))
            let lower = window.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: usableHeight * 0.82 / height))
            if element.frame.midY < window.frame.minY + usableHeight * 0.5 {
                upper.press(forDuration: 0.05, thenDragTo: lower)
            } else {
                lower.press(forDuration: 0.05, thenDragTo: upper)
            }
        }
        XCTAssertTrue(element.isHittable, "Could not bring \(element) into view")
    }

    private func tap(_ element: XCUIElement) {
        reveal(element)
        element.tap()
    }

    private func enter(_ text: String, in field: XCUIElement, submitLine: Bool = false) {
        tap(field)
        field.typeText(text)
        if submitLine { field.typeText("\n") }
    }

    private func capture(_ name: String) {
        let keyboardGone = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "exists == false"), object: app.keyboards.firstMatch)
        XCTAssertEqual(XCTWaiter.wait(for: [keyboardGone], timeout: 6), .completed)
        XCTAssertFalse(app.alerts.firstMatch.exists)
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func createRecord(kind: String, name: String) {
        tap(app.buttons["＋ Thêm " + kind])
        enter(name, in: app.textFields["Tên " + kind], submitLine: true)
        tap(app.buttons["Lưu tên"])
        XCTAssertTrue(app.buttons[name].waitForExistence(timeout: 10))
    }

    private func screenMessages() -> String {
        // Print visible explanatory messages, never the contents of text inputs.
        return (app.staticTexts.allElementsBoundByIndex.map { $0.label }
            + app.alerts.allElementsBoundByIndex.map { $0.label }).joined(separator: " | ")
    }

    private func fillPassword(_ password: String, in field: XCUIElement) {
        tap(field)
        // Numeric input avoids keyboard layout changes and smart punctuation. Wait for
        // the controlled React Native input before leaving a secure-entry field.
        field.typeText(password)
        let filled = NSPredicate { _, _ in
            (field.value as? String)?.count == password.count
        }
        if XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: filled, object: field)],
                          timeout: 4) != .completed {
            // Some iOS runtime versions hide even the number of entered characters.
            print("Secure field accessibility length unavailable or incomplete. Continuing to the app's validation.")
        }
        field.typeText("\n")
    }

    func testStoreScreenshots() {
        app.launch()
        XCTAssertTrue(app.buttons["Đăng nhập"].waitForExistence(timeout: 20))
        capture("05-account-access")
        tap(app.buttons["Tạo tài khoản"])
        let password = String(Int64.random(in: 100_000_000_000...999_999_999_999))
        enter("minhan", in: app.textFields["Tên đăng nhập"], submitLine: true)
        XCTAssertEqual(app.textFields["Tên đăng nhập"].value as? String, "minhan")
        fillPassword(password, in: app.secureTextFields["Mật khẩu"])
        fillPassword(password, in: app.secureTextFields["Nhập lại mật khẩu"])
        tap(app.buttons["Tạo tài khoản"])
        XCTAssertTrue(app.buttons["Đăng nhập"].waitForExistence(timeout: 25),
                      "After registration: \(screenMessages())")
        fillPassword(password, in: app.secureTextFields["Mật khẩu"])
        tap(app.buttons["Đăng nhập"])
        let incidentTile = app.buttons["Sự cố. Báo cáo và xử lý"]
        let routeTile = app.buttons["Tuyến cáp. Ảnh, GPS, KMZ"]
        XCTAssertTrue(incidentTile.waitForExistence(timeout: 25), "After sign in: \(screenMessages())")
        XCTAssertTrue(routeTile.exists)
        XCTAssertTrue(app.buttons["Di dời. Theo dõi di dời"].exists)
        capture("01-home")

        // Capture the independent route-list feature before the more complex incident
        // editor. Attachments before a later failure are exported by the capture script.
        tap(routeTile)
        let routes = ["T01 - ODF A2 / MX-01", "T02 - MX-01 / DP-04", "T03 - DP-04 / DP-08"]
        for route in routes { createRecord(kind: "tuyến cáp", name: route) }
        for route in routes { XCTAssertTrue(app.buttons[route].isHittable) }
        capture("04-route-list")
        tap(app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "‹ Quay lại")).firstMatch)

        tap(incidentTile)
        let incident = "SC-07 - ODF A2"
        createRecord(kind: "sự cố", name: incident)
        tap(app.buttons[incident])
        let location = app.textViews["Địa điểm sự cố"]
        enter("ODF A2 - K12", in: location)
        tap(app.buttons["Nguyên nhân sự cố: Chọn ▾"])
        tap(app.buttons["Đứt ODF"])
        let materials = app.buttons["2. Cáp / vật tư"]
        let information = app.buttons["1. Thông tin"]
        // Changing steps removes the input focus and returns the editor to the top.
        tap(materials)
        tap(information)
        XCTAssertTrue(app.buttons["Nguyên nhân sự cố: Đứt ODF"].waitForExistence(timeout: 10))
        XCTAssertEqual(location.value as? String, "ODF A2 - K12")
        XCTAssertTrue(app.staticTexts["Đã lưu bản nháp"].waitForExistence(timeout: 10))
        capture("02-incident")

        tap(materials)
        tap(app.buttons["Chủng loại cáp: Chọn ▾"])
        tap(app.buttons["24FO"])
        enter("1250", in: app.textFields["Đầu số 1"])
        enter("1430", in: app.textFields["Đầu số 2"])
        tap(information)
        tap(materials)
        XCTAssertTrue(app.buttons["Chủng loại cáp: 24FO"].waitForExistence(timeout: 10))
        let measured = app.staticTexts["180 m"]
        reveal(measured)
        XCTAssertTrue(app.staticTexts["Đã lưu bản nháp"].waitForExistence(timeout: 10))
        capture("03-cable-materials")

    }
}
