#!/usr/bin/env ruby
# Build only a small UI-test host. The frozen app is installed separately with
# simctl and is launched by the existing test's explicit bundle identifier.
require 'fileutils'
require 'xcodeproj'

abort 'Usage: make-harness.rb OUTDIR PUBLICSCREENSHOTS_SWIFT [DEPLOYMENT_TARGET]' unless (2..3).cover?(ARGV.length)
output, source = ARGV.first(2).map { |value| File.expand_path(value) }
deployment = ARGV[2] || '16.0'
abort 'Invalid iOS deployment target.' unless /\A\d+\.\d+(?:\.\d+)?\z/.match?(deployment)
abort 'UI-test source must be an ordinary file.' unless File.file?(source) && !File.symlink?(source)
abort 'Harness output must be a new or empty directory.' if File.symlink?(output) ||
  (File.exist?(output) && (!File.directory?(output) || !Dir.empty?(output)))

test_bytes = File.binread(source)
abort 'Expected the existing public-app UI test.' unless
  test_bytes.include?('final class PublicScreenshots: XCTestCase') &&
  test_bytes.include?('XCUIApplication(bundleIdentifier: "vn.hptelecom.fieldwork")')

FileUtils.mkdir_p(File.join(output, 'CaptureHost'))
FileUtils.mkdir_p(File.join(output, 'PublicScreenshots'))
File.binwrite(File.join(output, 'PublicScreenshots', 'PublicScreenshots.swift'), test_bytes)
File.write(File.join(output, 'CaptureHost', 'StubApp.swift'), <<~'SWIFT')
  import UIKit

  @main
  final class AppDelegate: UIResponder, UIApplicationDelegate {
      var window: UIWindow?

      func application(_ application: UIApplication,
                       didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
          let controller = UIViewController()
          controller.view.backgroundColor = .systemBackground
          let window = UIWindow(frame: UIScreen.main.bounds)
          window.rootViewController = controller
          window.makeKeyAndVisible()
          self.window = window
          return true
      }
  }
SWIFT

project = Xcodeproj::Project.new(File.join(output, 'CaptureHarness.xcodeproj'))
host = project.new_target(:application, 'CaptureHost', :ios, deployment)
tests = project.new_target(:ui_test_bundle, 'PublicScreenshots', :ios, deployment)

# Restrict every target/configuration to iPhone Simulator. Distribution signing
# is neither needed nor configured; the caller passes ad-hoc signing flags.
common = {
  'SDKROOT' => 'iphonesimulator',
  'SUPPORTED_PLATFORMS' => 'iphonesimulator',
  'IPHONEOS_DEPLOYMENT_TARGET' => deployment,
  'TARGETED_DEVICE_FAMILY' => '1',
  'SUPPORTS_MACCATALYST' => 'NO',
  'SWIFT_VERSION' => '5.0',
  'GENERATE_INFOPLIST_FILE' => 'YES'
}
project.build_configurations.each { |config| config.build_settings.merge!(common) }
host.build_configurations.each do |config|
  config.build_settings.merge!(common.merge(
    'PRODUCT_NAME' => 'CaptureHost',
    'PRODUCT_MODULE_NAME' => 'CaptureHost',
    'PRODUCT_BUNDLE_IDENTIFIER' => 'vn.hptelecom.fieldwork.capture-host',
    'INFOPLIST_KEY_UIApplicationSceneManifest_Generation' => 'NO',
    'INFOPLIST_KEY_UILaunchScreen_Generation' => 'YES',
    'INFOPLIST_KEY_UISupportedInterfaceOrientations' => 'UIInterfaceOrientationPortrait'
  ))
end
tests.build_configurations.each do |config|
  config.build_settings.merge!(common.merge(
    'PRODUCT_NAME' => 'PublicScreenshots',
    'PRODUCT_MODULE_NAME' => 'PublicScreenshots',
    'PRODUCT_BUNDLE_IDENTIFIER' => 'vn.hptelecom.fieldwork.cached-simulator-tests',
    'TEST_TARGET_NAME' => host.name
  ))
end

host_file = project.main_group.new_group('CaptureHost', 'CaptureHost').new_file('StubApp.swift')
test_file = project.main_group.new_group('PublicScreenshots', 'PublicScreenshots').new_file('PublicScreenshots.swift')
host.add_file_references([host_file])
tests.add_file_references([test_file])
tests.add_dependency(host)
attributes = (project.root_object.attributes['TargetAttributes'] ||= {})
attributes[tests.uuid] = { 'TestTargetID' => host.uuid }
project.save

scheme = Xcodeproj::XCScheme.new
scheme.configure_with_targets(host, tests, launch_target: true)
scheme.test_action.build_configuration = 'Release'
scheme.save_as(project.path, 'PublicScreenshots')

abort 'UI-test source changed while preparing the harness.' unless
  File.binread(File.join(output, 'PublicScreenshots', 'PublicScreenshots.swift')) == test_bytes
puts File.join(output, 'CaptureHarness.xcodeproj')
