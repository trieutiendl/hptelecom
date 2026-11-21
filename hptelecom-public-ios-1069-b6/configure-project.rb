require 'xcodeproj'
require 'json'
work = ENV.fetch('BUILD_WORK')
release = JSON.parse(File.read(File.join(ENV.fetch('BUILD_HELPERS'), 'release.json')))
project_path = Dir.glob(File.join(work, 'app/ios/*.xcodeproj')).first
raise 'Missing iOS project' unless project_path
project = Xcodeproj::Project.open(project_path)
targets = project.native_targets.select { |target| target.product_type == 'com.apple.product-type.application' }
raise 'Expected exactly one app target' unless targets.length == 1
target = targets.first
target.build_configurations.each do |configuration|
  settings = configuration.build_settings
  settings['CODE_SIGN_STYLE'] = 'Manual'
  settings['CODE_SIGN_IDENTITY'] = File.read(File.join(work, 'signing-identity.txt')).strip
  settings['DEVELOPMENT_TEAM'] = release.fetch('teamId')
  settings['PROVISIONING_PROFILE_SPECIFIER'] = File.read(File.join(work, 'profile-uuid.txt')).strip
  settings['PRODUCT_BUNDLE_IDENTIFIER'] = release.fetch('bundleIdentifier')
  settings['CURRENT_PROJECT_VERSION'] = release.fetch('build')
  settings['MARKETING_VERSION'] = release.fetch('version')
end
project.save
File.write(File.join(work, 'scheme.txt'), target.name)
puts 'Manual signing configured for the app target.'
