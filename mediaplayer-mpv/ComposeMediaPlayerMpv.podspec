Pod::Spec.new do |spec|
    spec.name                     = 'ComposeMediaPlayerMpv'
    spec.version                  = '0.0.1-dev'
    spec.homepage                 = 'https://github.com/SuvioMedia/KMediaPlayer'
    spec.source                   = { :http=> ''}
    spec.authors                  = ''
    spec.license                  = ''
    spec.summary                  = 'Optional MPV backend for Compose Media Player'
    spec.vendored_frameworks      = 'build/cocoapods/framework/ComposeMediaPlayerMpv.framework'
    spec.libraries                = 'c++'
    spec.ios.deployment_target    = '16.2'
    spec.dependency 'KMediaAssRuntime', '0.1.0-rc.6'
    spec.dependency 'KMediaFfmpegRuntime', '0.1.0-rc.6'
    spec.dependency 'KMediaMpv', '0.3.0-rc.7'
    if !Dir.exist?('build/cocoapods/framework/ComposeMediaPlayerMpv.framework') || Dir.empty?('build/cocoapods/framework/ComposeMediaPlayerMpv.framework')
        raise "
        Kotlin framework 'ComposeMediaPlayerMpv' doesn't exist yet, so a proper Xcode project can't be generated.
        'pod install' should be executed after running ':generateDummyFramework' Gradle task:
            ./gradlew :mediaplayer-mpv:generateDummyFramework
        Alternatively, proper pod installation is performed during Gradle sync in the IDE (if Podfile location is set)"
    end
    spec.xcconfig = {
        'ENABLE_USER_SCRIPT_SANDBOXING' => 'NO',
    }
    spec.pod_target_xcconfig = {
        'KOTLIN_PROJECT_PATH' => ':mediaplayer-mpv',
        'PRODUCT_MODULE_NAME' => 'ComposeMediaPlayerMpv',
    }
    spec.script_phases = [
        {
            :name => 'Build ComposeMediaPlayerMpv',
            :execution_position => :before_compile,
            :shell_path => '/bin/sh',
            :script => <<-SCRIPT
                if [ "YES" = "$OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED" ]; then
                    echo "Skipping Gradle build task invocation due to OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED environment variable set to \"YES\""
                    exit 0
                fi
                set -ev
                REPO_ROOT="$PODS_TARGET_SRCROOT"
                "$REPO_ROOT/../gradlew" -p "$REPO_ROOT" $KOTLIN_PROJECT_PATH:syncFramework \
                    -Pkotlin.native.cocoapods.platform=$PLATFORM_NAME \
                    -Pkotlin.native.cocoapods.archs="$ARCHS" \
                    -Pkotlin.native.cocoapods.configuration="$CONFIGURATION"
            SCRIPT
        }
    ]
    spec.resources = ['build/compose/cocoapods/compose-resources']
end
