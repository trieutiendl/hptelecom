import UIKit

@main
final class ControlApp: UIResponder, UIApplicationDelegate {
  func application(_ application: UIApplication, configurationForConnecting session: UISceneSession,
                   options: UIScene.ConnectionOptions) -> UISceneConfiguration {
    let config = UISceneConfiguration(name: "Control", sessionRole: session.role)
    config.delegateClass = ControlScene.self
    return config
  }
}

final class ControlScene: UIResponder, UIWindowSceneDelegate {
  var window: UIWindow?

  func scene(_ scene: UIScene, willConnectTo session: UISceneSession,
             options connectionOptions: UIScene.ConnectionOptions) {
    guard let scene = scene as? UIWindowScene else { return }
    let window = UIWindow(windowScene: scene)
    window.rootViewController = ControlForm()
    self.window = window
    window.makeKeyAndVisible()
  }
}

final class ControlForm: UIViewController {
  private let password = UITextField()
  private let length = UILabel()

  override func viewDidLoad() {
    super.viewDidLoad()
    view.backgroundColor = .systemBackground
    let name = UITextField()
    name.placeholder = "Name"
    name.textContentType = .username
    name.accessibilityIdentifier = "control-name"
    name.accessibilityLabel = "Name"
    password.isSecureTextEntry = true
    password.placeholder = "Password"
    password.accessibilityIdentifier = "control-password"
    password.accessibilityLabel = "Password"
    for field in [name, password] {
      field.autocapitalizationType = .none
      field.autocorrectionType = .no
      field.borderStyle = .roundedRect
      field.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
    }
    let button = UIButton(type: .system)
    button.setTitle("Count", for: .normal)
    button.accessibilityIdentifier = "control-count"
    button.addTarget(self, action: #selector(showLength), for: .touchUpInside)
    length.isAccessibilityElement = true
    length.accessibilityIdentifier = "control-length"
    showLength()
    let stack = UIStackView(arrangedSubviews: [name, password, button, length])
    stack.axis = .vertical
    stack.spacing = 12
    stack.translatesAutoresizingMaskIntoConstraints = false
    view.addSubview(stack)
    NSLayoutConstraint.activate([
      stack.leadingAnchor.constraint(equalTo: view.layoutMarginsGuide.leadingAnchor),
      stack.trailingAnchor.constraint(equalTo: view.layoutMarginsGuide.trailingAnchor),
      stack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 16)
    ])
  }

  @objc private func showLength() {
    let count = String((password.text ?? "").utf16.count)
    length.text = count
    length.accessibilityLabel = count
  }
}
