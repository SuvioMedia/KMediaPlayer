#!/usr/bin/env ruby

require "fileutils"
require "json"
require "open3"

repository = File.expand_path("../..", __dir__)
renderer = File.join(
  repository,
  "mediaplayer/src/iosMain/kotlin/io/github/kdroidfilter/composemediaplayer/AppleMetalProjectionRenderer.kt"
)
macos_shader = File.join(
  repository,
  "mediaplayer/src/jvmMain/native/macos/HdrMetalProjectionShader.swift"
)
generated_directory = File.join(repository, "mediaplayer/build/generated/metal")
generated_source = File.join(generated_directory, "AppleProjection.metal")
generated_air = File.join(generated_directory, "AppleProjection.air")

status_output, status_error, status = Open3.capture3(
  "xcodebuild",
  "-showComponent",
  "MetalToolchain",
  "-json"
)
abort(status_error) unless status.success?
component = JSON.parse(status_output)
abort("The optional Xcode Metal Toolchain component is not installed.") unless component["status"] == "installed"

kotlin_source = File.read(renderer)
match = kotlin_source.match(
  /private const val APPLE_METAL_PROJECTION_SHADER\s*=\s*\"\"\"(?<shader>.*?)\n\"\"\"/m
)
abort("The embedded Apple projection shader could not be extracted.") unless match
macos_source = File.read(macos_shader)
macos_match = macos_source.match(
  /let macMetalProjectionShader\s*=\s*\"\"\"(?<shader>.*?)\n\"\"\"/m
)
abort("The macOS projection shader could not be extracted.") unless macos_match
abort(
  "The iOS and macOS production Metal shaders diverged; both must share the CPU/GPU reference-tested source."
) unless match[:shader] == macos_match[:shader]

FileUtils.mkdir_p(generated_directory)
File.write(generated_source, match[:shader].sub(/\A\n/, ""))

success = system(
  "xcrun",
  "--sdk",
  "iphonesimulator",
  "metal",
  "-c",
  generated_source,
  "-o",
  generated_air
)
abort("The embedded Apple projection shader did not compile.") unless success

puts "Compiled the embedded iOS projection shader to #{generated_air}."
