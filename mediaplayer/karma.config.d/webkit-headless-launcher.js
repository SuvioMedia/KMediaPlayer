// Native Safari cannot run headlessly and its legacy Karma launcher relies on a file://
// redirect that modern hosted runners do not capture reliably. Keep the browser gate on the
// WebKit engine by replacing Kotlin's Safari selection with Playwright's headless MiniBrowser.
const configuredBrowsers = config.browsers || [];
if (configuredBrowsers.includes("Safari")) {
    config.plugins = config.plugins || [];
    config.plugins.push(require("karma-webkit-launcher"));
    config.set({
        browsers: configuredBrowsers.map((browser) =>
            browser === "Safari" ? "WebkitHeadless" : browser
        )
    });
}
