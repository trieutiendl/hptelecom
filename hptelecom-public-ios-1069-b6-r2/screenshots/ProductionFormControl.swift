import UIKit

@main
final class ProductionFormApp: UIResponder, UIApplicationDelegate {
  func application(_ application: UIApplication, configurationForConnecting session: UISceneSession,
                   options: UIScene.ConnectionOptions) -> UISceneConfiguration {
    let configuration = UISceneConfiguration(name: "Control", sessionRole: session.role)
    configuration.delegateClass = ProductionFormScene.self
    return configuration
  }
}

final class ProductionFormScene: UIResponder, UIWindowSceneDelegate {
  var window: UIWindow?

  func scene(_ scene: UIScene, willConnectTo session: UISceneSession,
             options connectionOptions: UIScene.ConnectionOptions) {
    guard let windowScene = scene as? UIWindowScene else { return }
    let window = UIWindow(windowScene: windowScene)
    window.rootViewController = ProductionFormHost()
    self.window = window
    window.makeKeyAndVisible()
  }
}

final class ProductionFormHost: UIViewController {
  private var didPresentForm = false

  override func viewDidLoad() {
    super.viewDidLoad()
    view.backgroundColor = .systemBackground
  }

  override func viewDidAppear(_ animated: Bool) {
    super.viewDidAppear(animated)
    guard !didPresentForm else { return }
    didPresentForm = true
    let form = AccountEntryController(mode: .register, username: "", notice: "") { [weak self] result in
      guard result["action"] == "continue", let password = result["password"] else { return }
      self?.showCount(password.utf16.count)
    }
    present(form, animated: false)
  }

  private func showCount(_ count: Int) {
    let length = UILabel()
    length.text = String(count)
    length.isAccessibilityElement = true
    length.accessibilityIdentifier = "control-length"
    length.accessibilityLabel = String(count)
    length.translatesAutoresizingMaskIntoConstraints = false
    view.addSubview(length)
    NSLayoutConstraint.activate([
      length.centerXAnchor.constraint(equalTo: view.centerXAnchor),
      length.centerYAnchor.constraint(equalTo: view.centerYAnchor)
    ])
  }
}
