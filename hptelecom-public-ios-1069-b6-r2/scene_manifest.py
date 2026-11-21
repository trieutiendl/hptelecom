"""Check the scene configuration in the built, packaged application."""


SCENE_DELEGATE = 'EXExpoAppSceneDelegate'


def verified_scene(info):
    manifest = info.get('UIApplicationSceneManifest')
    if not isinstance(manifest, dict) or manifest.get('UIApplicationSupportsMultipleScenes') is not False:
        raise ValueError('The built app does not declare its single-window scene.')
    roles = manifest.get('UISceneConfigurations')
    configurations = roles.get('UIWindowSceneSessionRoleApplication') if isinstance(roles, dict) else None
    if not isinstance(configurations, list) or len(configurations) != 1 or not isinstance(configurations[0], dict):
        raise ValueError('The built app does not have a unique application window configuration.')
    config = configurations[0]
    if config.get('UISceneDelegateClassName') != SCENE_DELEGATE:
        raise ValueError('The built application window has an unexpected scene delegate.')
    return config['UISceneDelegateClassName']
