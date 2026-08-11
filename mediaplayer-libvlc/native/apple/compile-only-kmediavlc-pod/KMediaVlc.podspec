Pod::Spec.new do |spec|
  spec.name                  = 'KMediaVlc'
  spec.version               = '0.1.0'
  spec.summary               = 'Compile-only placeholder for KMediaPlayer iOS tests without a native runtime.'
  spec.homepage              = 'https://github.com/SuvioMedia/KMediaVlc'
  spec.license               = { :type => 'Proprietary' }
  spec.author                = { 'SuvioMedia' => 'SuvioMedia' }
  spec.source                = { :git => 'https://github.com/SuvioMedia/KMediaVlc.git', :tag => 'compile-only' }
  spec.ios.deployment_target = '16.2'
  spec.source_files          = 'Sources/**/*.{c,h}'
end
