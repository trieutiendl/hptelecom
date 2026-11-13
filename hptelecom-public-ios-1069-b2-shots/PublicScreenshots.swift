import XCTest

final class PublicScreenshots: XCTestCase {
    private let app = XCUIApplication(bundleIdentifier: "vn.hptelecom.fieldwork")

    override func setUp() {
        super.setUp()
        continueAfterFailure = false
    }

    private func reveal(_ element: XCUIElement) {
        XCTAssertTrue(element.waitForExistence(timeout: 20), "Required control is missing")
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
        XCTAssertTrue(element.isHittable, "Could not reveal required control")
    }

    private func tap(_ element: XCUIElement) {
        reveal(element)
        element.tap()
    }

    private func enter(_ text: String, in field: XCUIElement, submitLine: Bool = false) {
        tap(field)
        var expected = ""
        for character in text {
            field.typeText(String(character))
            expected.append(character)
            let current = expected
            let changed = NSPredicate { _, _ in (field.value as? String) == current }
            XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: changed, object: field)],
                                         timeout: 5), .completed, "Text input did not retain the next character")
        }
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

    private enum PasswordUIError: Error {
        case ambiguousKnownControl
        case manualPasswordOptionMissing
        case registrationFieldMissing
    }
    
    private var passwordUIScopes: [(String, XCUIApplication)] {
        [("app", app), ("springboard", XCUIApplication(bundleIdentifier: "com.apple.springboard"))]
    }
    
    private func knownPasswordButtons(_ owner: XCUIApplication, _ label: String) -> [XCUIElement] {
        owner.buttons.matching(NSPredicate(format: "label == %@", label))
            .allElementsBoundByIndex.filter { $0.exists }
    }
    
    private func knownPasswordButton(_ label: String) throws -> XCUIElement? {
        // Prefer the app; a system presentation may instead belong to SpringBoard.
        for (_, owner) in passwordUIScopes {
            let candidates = knownPasswordButtons(owner, label).filter { $0.isHittable }
            if candidates.count > 1 { throw PasswordUIError.ambiguousKnownControl }
            if let candidate = candidates.first { return candidate }
        }
        return nil
    }
    
    private func registrationPasswordField() throws -> XCUIElement {
        let fields = app.secureTextFields.matching(identifier: "account-password-register")
        guard fields.count == 1, fields.firstMatch.exists, fields.firstMatch.isHittable else {
            throw PasswordUIError.registrationFieldMissing
        }
        return fields.firstMatch
    }
    
    private func prepareRegistrationPassword() throws -> XCUIElement {
        try registrationPasswordField().tap()
        RunLoop.current.run(until: Date(timeIntervalSinceNow: 0.5))
    
        // Fixed allowlist only: never enumerate labels, values, suggestions or AX trees.
        let allowed = [("strong", "Use Strong Password"),
                       ("options", "Other Options"),
                       ("manual", "Choose My Own Password")]
        for (scope, owner) in passwordUIScopes {
            for (code, label) in allowed {
                let matches = knownPasswordButtons(owner, label)
                print("[autofill] scope=\(scope) control=\(code) count=\(matches.count) hittableCount=\(matches.filter { $0.isHittable }.count)")
            }
        }
        print("[autofill] keyboardCount=\(app.keyboards.count)")
    
        var handled = false
        if let manual = try knownPasswordButton("Choose My Own Password") {
            manual.tap()
            handled = true
        } else if try knownPasswordButton("Use Strong Password") != nil,
                  let options = try knownPasswordButton("Other Options") {
            options.tap()
            let deadline = Date(timeIntervalSinceNow: 3)
            repeat {
                if let manual = try knownPasswordButton("Choose My Own Password") {
                    manual.tap()
                    handled = true
                    break
                }
                RunLoop.current.run(until: Date(timeIntervalSinceNow: 0.1))
            } while Date() < deadline
            guard handled else { throw PasswordUIError.manualPasswordOptionMissing }
        }
    
        // Re-query the native field by its build-2 testID after any system UI changed.
        let field = try registrationPasswordField()
        if handled { field.tap() }
        return field
    }
    
    
    private func fillPassword(_ password: String, in field: XCUIElement, alreadyFocused: Bool = false) {
        if !alreadyFocused { tap(field) }
        print("[focus] secureField=\(field.label) frame=\(field.frame) keyboardCount=\(app.keyboards.count)")
        // A hardware-backed simulator may expose no software Keyboard element.
        // typeText itself requires real keyboard focus; successful registration
        // and sign-in below remain the end-to-end checks of the entered password.
        // Keep keyboard focus on the field. Application-directed, paced input
        // avoids reselecting secure text and gives controlled inputs time to commit.
        for (index, character) in password.enumerated() {
            app.typeText(String(character))
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
            if index == 0 {
                XCTAssertEqual(app.textFields["Tên đăng nhập"].value as? String, "minhan",
                               "Secure input must not change the account name")
                print("[focus] firstSecureCharacter maskedValueCount=\((field.value as? String)?.count ?? -1)")
            }
        }
        RunLoop.current.run(until: Date().addingTimeInterval(0.5))
        let observedCount = (field.value as? String)?.count ?? -1
        print("[account-entry] field=\(field.label) typedCount=\(password.count) maskedValueCount=\(observedCount)")
        // Do not issue a second field-directed type operation to dismiss the
        // keyboard: XCTest can reselect secure text. Tap the next control.
    }

    func testStoreScreenshots() throws {
        app.launch()
        XCTAssertTrue(app.buttons["Đăng nhập"].waitForExistence(timeout: 20))
        capture("05-account-access")
        tap(app.buttons["Tạo tài khoản"])
        let password = String(Int64.random(in: 100_000_000_000...999_999_999_999))
        enter("minhan", in: app.textFields["Tên đăng nhập"])
        print("[focus] afterUsername keyboardCount=\(app.keyboards.count)")
        XCTAssertEqual(app.textFields["Tên đăng nhập"].value as? String, "minhan")
        reveal(app.secureTextFields["account-password-register"])
        let registrationPassword = try prepareRegistrationPassword()
        fillPassword(password, in: registrationPassword, alreadyFocused: true)
        fillPassword(password, in: app.secureTextFields["account-password-confirmation-register"])
        tap(app.buttons["Tạo tài khoản"])
        let registered = app.buttons["Đăng nhập"].waitForExistence(timeout: 25)
        XCTAssertTrue(registered, "Account creation did not reach the login screen")
        fillPassword(password, in: app.secureTextFields["account-password-login"])
        tap(app.buttons["Đăng nhập"])
        let incidentTile = app.buttons["Sự cố. Báo cáo và xử lý"]
        let routeTile = app.buttons["Tuyến cáp. Ảnh, GPS, KMZ"]
        let signedIn = incidentTile.waitForExistence(timeout: 25)
        XCTAssertTrue(signedIn, "Sign-in did not reach the home screen")
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
        XCTAssertTrue(app.buttons["Nguyên nhân sự cố: Đứt ODF"].waitForExistence(timeout: 10))
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
        XCTAssertTrue(app.buttons["Chủng loại cáp: 24FO"].waitForExistence(timeout: 10))
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
