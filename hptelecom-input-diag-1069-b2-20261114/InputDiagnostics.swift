import XCTest

final class InputDiagnostics: XCTestCase {
    private let app = XCUIApplication(bundleIdentifier: "vn.hptelecom.fieldwork")
    override func setUp() { super.setUp(); continueAfterFailure = false }

    private func reveal(_ element: XCUIElement) {
        XCTAssertTrue(element.waitForExistence(timeout: 20), "Required control is missing")
        let window = app.windows.firstMatch
        for _ in 0..<6 {
            if element.isHittable { return }
            let keyboard = app.keyboards.firstMatch
            let height = window.frame.height
            let usable = keyboard.exists ? min(height, keyboard.frame.minY - window.frame.minY) : height
            let upper = window.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: usable * 0.30 / height))
            let lower = window.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: usable * 0.82 / height))
            if element.frame.midY < window.frame.minY + usable * 0.5 {
                upper.press(forDuration: 0.05, thenDragTo: lower)
            } else { lower.press(forDuration: 0.05, thenDragTo: upper) }
        }
        XCTAssertTrue(element.isHittable, "Required control is not visible")
    }
    private func tap(_ element: XCUIElement) { reveal(element); element.tap() }
    private func status(_ outcome: String, _ counts: [Int]) {
        let value: [String: Any] = ["schema": 1, "outcome": outcome, "keyboardCounts": counts]
        let data = try! JSONSerialization.data(withJSONObject: value, options: [.sortedKeys])
        print("[HP_INPUT_DIAG_STATUS]" + String(data: data, encoding: .utf8)!)
    }

    func testSyntheticRegistrationInput() throws {
        app.launch()
        XCTAssertTrue(app.buttons["Đăng nhập"].waitForExistence(timeout: 20))
        tap(app.buttons["Tạo tài khoản"])
        let name = app.textFields["Tên đăng nhập"]
        tap(name)
        var expected = ""
        for character in "minhan" {
            name.typeText(String(character)); expected.append(character)
            let current = expected
            let predicate = NSPredicate { _, _ in (name.value as? String) == current }
            XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: predicate, object: name)], timeout: 5), .completed)
        }
        let usernameKeyboard = app.keyboards.count
        let password = String(Int64.random(in: 100_000_000_000...999_999_999_999))
        let first = app.secureTextFields["account-password-register"]
        tap(first)
        RunLoop.current.run(until: Date(timeIntervalSinceNow: 0.5))
        // Observe only. The previous strict keyboard gate stopped before input.
        // Replay the prior one-call typing flow so the app can record the failure.
        let firstKeyboard = app.keyboards.count
        first.typeText(password)
        RunLoop.current.run(until: Date(timeIntervalSinceNow: 0.5))
        let confirmation = app.secureTextFields["account-password-confirmation-register"]
        tap(confirmation)
        let confirmationKeyboard = app.keyboards.count
        confirmation.typeText(password)
        RunLoop.current.run(until: Date(timeIntervalSinceNow: 0.5))
        tap(app.buttons["Tạo tài khoản"])
        let counts = [usernameKeyboard, firstKeyboard, confirmationKeyboard]
        if app.buttons["Đăng nhập"].waitForExistence(timeout: 8) {
            status("REGISTER_SUCCEEDED", counts)
            return
        }
        let trace = app.descendants(matching: .any).matching(identifier: "diagnostic-secure-input-trace").firstMatch
        guard trace.waitForExistence(timeout: 8), let raw = trace.value as? String else {
            status("TRACE_MISSING", counts)
            XCTFail("Diagnostic trace was not available")
            return
        }
        // This goes only to a private redirected log. Node validates and reserializes
        // the strict numeric/enum schema before any encrypted artifact is produced.
        print("[HP_INPUT_DIAG_TRACE]" + raw)
        status("REGISTER_FAILED", counts)
    }
}
