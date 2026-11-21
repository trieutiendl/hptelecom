import XCTest
import UIKit

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
        XCTAssertTrue(element.isHittable, "Could not bring the required control into view")
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
        // The account-access image is taken only on an empty account form.
        // No populated credential screen may become a screenshot attachment.
        for field in app.secureTextFields.allElementsBoundByIndex {
            XCTAssertTrue((field.value as? String ?? "").isEmpty, "Credential fields must be empty before capture")
        }
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

    private let accountName = "minhan"
    private var keyCenters: [String: CGVector] = [:]
    private var usePaste = false
    private var passedFirstStep = false

    private func compareStandardField() {
        let control = XCUIApplication(bundleIdentifier: "vn.hptelecom.simulator.password-control")
        control.launch()
        let name = control.textFields["control-name"]
        XCTAssertTrue(name.waitForExistence(timeout: 12), "The independent UIKit control was not installed")
        name.tap()
        name.typeText("fieldwork")
        let secure = control.secureTextFields["control-password"]
        secure.tap()
        let keyboard = control.keyboards.firstMatch.waitForExistence(timeout: 3)
        let system = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let systemKeyboard = system.keyboards.firstMatch.exists
        let pass = (0..<12).map { _ in ["a", "b", "c", "d"].randomElement()! }.joined()
        secure.typeText(pass)
        control.buttons["control-count"].tap()
        let label = control.staticTexts["control-length"]
        let length = label.exists ? (Int(label.label) ?? -1) : -1
        print("[FIELDWORK_CONTROL]length=\(min(128,max(-1,length))) keyboard=\(keyboard) system=\(systemKeyboard)")
        control.terminate()
    }

    private func rememberKeyboard() {
        let frame = app.frame
        guard frame.width > 0, frame.height > 0 else { return }
        for key in app.keyboards.firstMatch.keys.allElementsBoundByIndex {
            let label = key.label.lowercased()
            if key.isHittable && (label.count == 1 || label == "delete") {
                keyCenters[label] = CGVector(dx: (key.frame.midX - frame.minX) / frame.width,
                                             dy: (key.frame.midY - frame.minY) / frame.height)
            }
        }
    }

    private func editMenu(_ english: String, _ vietnamese: String) -> XCUIElement {
        let names = NSPredicate(format: "label ==[c] %@ OR label ==[c] %@", english, vietnamese)
        return app.descendants(matching: .any).matching(names).firstMatch
    }

    private func pastePassword(_ password: String, in field: XCUIElement, replacing: Bool) {
        // Only the test runner writes its throwaway password. The unmodified app
        // receives it through the standard iOS edit menu selected by a tap.
        UIPasteboard.general.setItems([["public.utf8-plain-text": password]],
                                      options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(120)])
        defer { UIPasteboard.general.items = [] }
        field.press(forDuration: 1.1)
        if replacing {
            let select = editMenu("Select All", "Chọn tất cả")
            if select.waitForExistence(timeout: 2) {
                select.tap()
            } else {
                field.doubleTap()
            }
        }
        let paste = editMenu("Paste", "Dán")
        if !paste.waitForExistence(timeout: 2) { field.press(forDuration: 1.1) }
        let available = paste.waitForExistence(timeout: 5)
        print("[FIELDWORK_MENU]available=\(available)")
        if available {
            paste.tap()
            print("[FIELDWORK_KEYBOARD]paste")
        } else {
            field.tap()
            if replacing { app.typeKey("a", modifierFlags: .command) }
            app.typeKey("v", modifierFlags: .command)
            print("[FIELDWORK_KEYBOARD]shortcut")
        }
        let allow = editMenu("Allow Paste", "Cho phép dán")
        if allow.waitForExistence(timeout: 2) { allow.tap() }
    }

    private func checkForm(_ stage: String, action: String) {
        let field = app.secureTextFields.firstMatch
        let name = app.textFields["Tên đăng nhập"]
        let button = app.buttons[action]
        let notices = [
            ("short", "Mật khẩu cần từ 10 đến 128 ký tự."),
            ("username", "Tên đăng nhập cần 3–40 ký tự, gồm chữ không dấu, số, dấu chấm, gạch ngang hoặc gạch dưới."),
            ("mismatch", "Hai mật khẩu chưa khớp."),
            ("wrong", "Tên đăng nhập hoặc mật khẩu chưa đúng."),
            ("missing", "Nhập tên đăng nhập và mật khẩu của bạn."),
            ("input", "Ô mật khẩu chưa sẵn sàng. Hãy thử lại."),
            ("duplicate", "Tên đăng nhập này đã được dùng trên iPhone."),
            ("created", "Đã tạo tài khoản. Nhập mật khẩu để mở báo cáo.")
        ]
        let notice = notices.first(where: {app.staticTexts[$0.1].exists})?.0 ?? "none"
        let secureLength = field.exists ? min(128, (field.value as? String)?.count ?? -1) : -1
        let nameState = !name.exists ? 0 : ((name.value as? String) == accountName ? 1 : 2)
        let record: [String: Any] = ["stage": stage, "notice": notice, "nameState": nameState,
            "secureLength": secureLength, "keyboard": app.keyboards.firstMatch.exists,
            "button": button.exists && button.isHittable, "secure": field.exists && field.isHittable]
        if let data = try? JSONSerialization.data(withJSONObject: record),
           let safe = String(data: data, encoding: .utf8) {
            print("[FIELDWORK_QA]" + safe)
        }
    }

    private func fillPassword(_ password: String, fieldID: String, replacingCount: Int = 0) {
        let field = app.secureTextFields[fieldID]
        tap(field)
        XCTAssertEqual(app.secureTextFields.count, 1)
        if usePaste {
            pastePassword(password, in: field, replacing: replacingCount > 0)
            return
        }
        let keyboard = app.keyboards.firstMatch
        if !keyboard.waitForExistence(timeout: 3) {
            tap(field)
        }
        let spring = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let springKeyboard = spring.keyboards.firstMatch
        let strong = ["Use Strong Password","Choose My Own Password","Use Other Password"]
        let prompt = strong.contains { spring.buttons[$0].exists || app.buttons[$0].exists }
        print("[FIELDWORK_SYSTEM]keyboard=\(springKeyboard.exists) prompt=\(prompt)")
        if keyboard.waitForExistence(timeout: 3) {
            print("[FIELDWORK_KEYBOARD]software")
            if replacingCount > 0 {
                let delete = keyboard.keys.matching(NSPredicate(format: "label ==[c] %@ OR identifier ==[c] %@",
                                                                 "delete", "delete")).firstMatch
                XCTAssertTrue(delete.waitForExistence(timeout: 3), "The keyboard delete key must be available")
                for _ in 0..<replacingCount { delete.tap() }
            }
            for character in password {
                let text = String(character)
                let key = keyboard.keys.matching(NSPredicate(format: "label ==[c] %@ OR identifier ==[c] %@",
                                                              text, text)).firstMatch
                XCTAssertTrue(key.waitForExistence(timeout: 3), "A character key was unavailable")
                key.tap()
            }
        } else if password.allSatisfy({ keyCenters[String($0)] != nil }) &&
                    (replacingCount == 0 || keyCenters["delete"] != nil) {
            // Secure keyboard keys can be hidden from the accessibility tree.
            // Use the actual key centers observed on this iPhone while entering
            // the username. Never tap a guessed screen position.
            print("[FIELDWORK_KEYBOARD]touch")
            let keys = Array(repeating: "delete", count: replacingCount) + password.map(String.init)
            for (index, key) in keys.enumerated() {
                app.coordinate(withNormalizedOffset: keyCenters[key]!).tap()
                if index == 0 && replacingCount == 0 {
                    // If no key reached the field, the secure keyboard is
                    // physically hidden or has a different layout. Stop before
                    // another screen control could be activated by a blind tap.
                    let count = field.exists ? (field.value as? String)?.count : nil
                    if count == nil || count == 0 { break }
                }
            }
        } else {
            print("[FIELDWORK_KEYBOARD]hardware")
            for _ in 0..<replacingCount { app.typeText(XCUIKeyboardKey.delete.rawValue) }
            for letter in password { app.typeText(String(letter)) }
        }
        if passedFirstStep && field.exists, let length = (field.value as? String)?.count,
           length != password.count {
            usePaste = true
            pastePassword(password, in: field, replacing: length > 0)
        }
    }

    private func openNewNotebook(captureAccess: Bool) -> String {
        app.launch()
        XCTAssertTrue(app.buttons["Tiếp tục"].waitForExistence(timeout: 20),
                      "A fresh iPhone should offer account creation")
        XCTAssertTrue((app.textFields["Tên đăng nhập"].value as? String ?? "").isEmpty,
                      "The fresh form should have an empty account name")
        if captureAccess { capture("05-account-access") }
        let alphabet = Array("abcdefghjkmnpqrstuvwxyz")
        let password = String((0..<12).map { _ in alphabet.randomElement()! })
        let differentPassword = String(password.dropLast()) + (password.last == "a" ? "b" : "a")
        enter(accountName, in: app.textFields["Tên đăng nhập"])
        rememberKeyboard()
        checkForm("ready", action: "Tiếp tục")
        fillPassword(password, fieldID: "account-password-register")
        checkForm("password", action: "Tiếp tục")
        if app.buttons["Tiếp tục"].exists { tap(app.buttons["Tiếp tục"]) }
        var advanced = app.buttons["Nhập lại từ đầu"].waitForExistence(timeout: 10)
        checkForm("advance", action: advanced ? "Tạo tài khoản" : "Tiếp tục")
        if !advanced {
            // Start with a new empty field. A paste-based pass is recorded as
            // such, independently of a failed attempt to type on this simulator.
            if app.buttons["Đã có tài khoản · Đăng nhập"].exists {
                tap(app.buttons["Đã có tài khoản · Đăng nhập"])
            }
            tap(app.buttons["Tạo tài khoản"])
            usePaste = true
            fillPassword(password, fieldID: "account-password-register")
            checkForm("pasted", action: "Tiếp tục")
            tap(app.buttons["Tiếp tục"])
            advanced = app.buttons["Nhập lại từ đầu"].waitForExistence(timeout: 10)
            checkForm("retry", action: advanced ? "Tạo tài khoản" : "Tiếp tục")
        }
        XCTAssertTrue(advanced)
        passedFirstStep = true
        XCTAssertFalse(app.textFields["Tên đăng nhập"].exists)
        fillPassword(differentPassword, fieldID: "account-password-confirmation-register")
        tap(app.buttons["Tạo tài khoản"])
        checkForm("mismatch", action: "Tạo tài khoản")
        XCTAssertTrue(app.staticTexts["Hai mật khẩu chưa khớp."].waitForExistence(timeout: 10),
                      "Registration must reject a different confirmation")
        XCTAssertFalse(app.buttons["Sự cố. Báo cáo và xử lý"].exists)
        if usePaste {
            // Explicit "start over" keeps the account name and supplies empty
            // fields for retry if the secure edit menu lacks Select All.
            tap(app.buttons["Nhập lại từ đầu"])
            fillPassword(password, fieldID: "account-password-register")
            tap(app.buttons["Tiếp tục"])
            fillPassword(password, fieldID: "account-password-confirmation-register")
        } else {
            fillPassword(password, fieldID: "account-password-confirmation-register",
                         replacingCount: differentPassword.count)
        }
        tap(app.buttons["Tạo tài khoản"])
        let registered = app.buttons["Đăng nhập"].waitForExistence(timeout: 25)
        checkForm("create", action: registered ? "Đăng nhập" : "Tạo tài khoản")
        XCTAssertTrue(registered, "Normal registration did not return to login")
        XCTAssertTrue(app.staticTexts["Đã tạo tài khoản. Nhập mật khẩu để mở báo cáo."].waitForExistence(timeout: 10))
        XCTAssertTrue((app.textFields["Tên đăng nhập"].value as? String) == accountName)

        fillPassword(differentPassword, fieldID: "account-password-login")
        tap(app.buttons["Đăng nhập"])
        checkForm("incorrect", action: "Đăng nhập")
        XCTAssertTrue(app.staticTexts["Tên đăng nhập hoặc mật khẩu chưa đúng."].waitForExistence(timeout: 25),
                      "Login must reject an incorrect password")
        XCTAssertFalse(app.buttons["Sự cố. Báo cáo và xử lý"].exists)
        if usePaste {
            tap(app.buttons["Tạo tài khoản"])
            tap(app.buttons["Đã có tài khoản · Đăng nhập"])
            fillPassword(password, fieldID: "account-password-login")
        } else {
            fillPassword(password, fieldID: "account-password-login", replacingCount: differentPassword.count)
        }
        tap(app.buttons["Đăng nhập"])
        checkForm("correct", action: "Đăng nhập")
        XCTAssertTrue(app.buttons["Sự cố. Báo cáo và xử lý"].waitForExistence(timeout: 25),
                      "Correcting the password must open the report workspace")
        return password
    }

    private func reopenSavedRoute(_ name: String, password: String) {
        app.terminate()
        app.launch()
        XCTAssertTrue(app.buttons["Đăng nhập"].waitForExistence(timeout: 20),
                      "A returning user should see login instead of first-time registration")
        enter(accountName, in: app.textFields["Tên đăng nhập"])
        fillPassword(password, fieldID: "account-password-login")
        tap(app.buttons["Đăng nhập"])
        checkForm("reopen", action: "Đăng nhập")
        tap(app.buttons["Tuyến cáp. Ảnh, GPS, KMZ"])
        let saved = app.buttons[name]
        XCTAssertTrue(saved.waitForExistence(timeout: 10), "The saved notebook route must survive a relaunch")
        tap(saved)
        XCTAssertTrue(app.buttons["＋ Chụp ảnh / thêm vị trí"].waitForExistence(timeout: 10),
                      "Opening the saved route should show its fieldwork controls")
        XCTAssertTrue(app.staticTexts[name].exists)
    }

    func testFreshInstallLoginAndReopenNotebook() {
        compareStandardField()
        let password = openNewNotebook(captureAccess: false)
        tap(app.buttons["Tuyến cáp. Ảnh, GPS, KMZ"])
        let name = "T01 - ODF A2 / MX-01"
        createRecord(kind: "tuyến cáp", name: name)
        reopenSavedRoute(name, password: password)
    }

    func testStoreScreenshots() {
        let password = openNewNotebook(captureAccess: true)
        let incidentTile = app.buttons["Sự cố. Báo cáo và xử lý"]
        let routeTile = app.buttons["Tuyến cáp. Ảnh, GPS, KMZ"]
        let signedIn = incidentTile.waitForExistence(timeout: 25)
        XCTAssertTrue(signedIn, "Normal sign-in did not open the report workspace")
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
        reopenSavedRoute(routes[0], password: password)
    }
}
