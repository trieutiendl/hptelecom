require 'fileutils'
require 'xcodeproj'

ios, source = ARGV
projects = Dir.glob(File.join(ios, '*.xcodeproj'))
raise 'Expected one generated app project' unless projects.one?
project = Xcodeproj::Project.open(projects.first)
apps = project.native_targets.select { |target| target.product_type == 'com.apple.product-type.application' }
raise 'Expected one generated app target' unless apps.one?
app = apps.first
release = app.build_configurations.find { |config| config.name == 'Release' }
raise 'Unexpected generated app bundle' unless release.build_settings['PRODUCT_BUNDLE_IDENTIFIER'] == 'vn.hptelecom.fieldwork'

# Ad-hoc simulator builds do not receive an embedded distribution profile. Give
# SecureStore the same private-by-app keychain namespace the signed app receives.
simulator_entitlements = 'PublicCapture-Simulator.entitlements'
Xcodeproj::Plist.write_to_path({
  'application-identifier' => 'FAKETEAMID.vn.hptelecom.fieldwork',
  'keychain-access-groups' => ['FAKETEAMID.vn.hptelecom.fieldwork']
}, File.join(ios, simulator_entitlements))
release.build_settings['CODE_SIGN_ENTITLEMENTS[sdk=iphonesimulator*]'] = simulator_entitlements

name = 'PublicScreenshots'
FileUtils.mkdir_p(File.join(ios, name))
FileUtils.cp(source, File.join(ios, name, 'PublicScreenshots.swift'))
tests = project.new_target(:ui_test_bundle, name, :ios, release.build_settings['IPHONEOS_DEPLOYMENT_TARGET'] || '16.0')
tests.add_dependency(app)
file = project.main_group.new_group(name, name).new_file('PublicScreenshots.swift')
tests.add_file_references([file])
tests.build_configurations.each do |config|
  config.build_settings.merge!(
    'PRODUCT_NAME' => name,
    'PRODUCT_MODULE_NAME' => name,
    'PRODUCT_BUNDLE_IDENTIFIER' => 'vn.hptelecom.fieldwork.simulator-tests',
    'GENERATE_INFOPLIST_FILE' => 'YES',
    'TARGETED_DEVICE_FAMILY' => '1',
    'SWIFT_VERSION' => '5.0',
    'TEST_TARGET_NAME' => app.name
  )
end
attributes = (project.root_object.attributes['TargetAttributes'] ||= {})
attributes[tests.uuid] = { 'TestTargetID' => app.uuid }
project.save

scheme = Xcodeproj::XCScheme.new
scheme.configure_with_targets(app, tests, launch_target: true)
scheme.test_action.build_configuration = 'Release'
scheme.save_as(project.path, name)
