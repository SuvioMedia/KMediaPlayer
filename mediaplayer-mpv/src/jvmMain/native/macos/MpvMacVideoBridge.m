#include <jni.h>
#include <dispatch/dispatch.h>
#include <dlfcn.h>
#include <math.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#import <AppKit/AppKit.h>
#import <CoreGraphics/CoreGraphics.h>
#import <CoreVideo/CoreVideo.h>
#import <OpenGL/OpenGL.h>
#import <OpenGL/gl3.h>
#import <QuartzCore/QuartzCore.h>
#import <objc/runtime.h>

typedef struct mpv_handle mpv_handle;
typedef struct mpv_render_context mpv_render_context;

typedef struct {
    int type;
    void* data;
} mpv_render_param;

typedef struct {
    void* (*get_proc_address)(void* context, const char* name);
    void* get_proc_address_context;
} mpv_opengl_init_params;

typedef struct {
    int fbo;
    int width;
    int height;
    int internal_format;
} mpv_opengl_fbo;

enum {
    MPV_RENDER_PARAM_INVALID = 0,
    MPV_RENDER_PARAM_API_TYPE = 1,
    MPV_RENDER_PARAM_OPENGL_INIT_PARAMS = 2,
    MPV_RENDER_PARAM_OPENGL_FBO = 3,
    MPV_RENDER_PARAM_FLIP_Y = 4,
    MPV_RENDER_PARAM_DEPTH = 5,
};

typedef int (*mpv_render_context_create_fn)(
    mpv_render_context** result,
    mpv_handle* handle,
    mpv_render_param* parameters
);
typedef void (*mpv_render_context_set_update_callback_fn)(
    mpv_render_context* context,
    void (*callback)(void* callback_context),
    void* callback_context
);
typedef uint64_t (*mpv_render_context_update_fn)(mpv_render_context* context);
typedef int (*mpv_render_context_render_fn)(
    mpv_render_context* context,
    mpv_render_param* parameters
);
typedef void (*mpv_render_context_report_swap_fn)(mpv_render_context* context);
typedef void (*mpv_render_context_free_fn)(mpv_render_context* context);

typedef struct {
    mpv_render_context_create_fn create;
    mpv_render_context_set_update_callback_fn set_update_callback;
    mpv_render_context_update_fn update;
    mpv_render_context_render_fn render;
    mpv_render_context_report_swap_fn report_swap;
    mpv_render_context_free_fn free;
} KMPMpvRenderApi;

typedef struct KMPMpvNativeRenderer KMPMpvNativeRenderer;
@class KMPMpvOpenGLLayer;
@class KMPMpvVideoView;

struct KMPMpvNativeRenderer {
    atomic_int references;
    pthread_mutex_t render_mutex;
    BOOL shutting_down;
    int color_mode;
    int buffer_depth;
    int last_framebuffer;
    mpv_handle* mpv;
    void* libmpv_library;
    KMPMpvRenderApi api;
    mpv_render_context* render_context;
    CGLPixelFormatObj pixel_format;
    CGLContextObj gl_context;
    // The renderer owns both objects explicitly. AppKit/JBR may temporarily rebuild the AWT view
    // hierarchy during full-screen transitions; relying only on the superview's retain would leave
    // these pointers dangling before JNI gets a chance to detach them.
    KMPMpvOpenGLLayer* layer;
    KMPMpvVideoView* view;
};

static KMPMpvNativeRenderer* renderer_retain(KMPMpvNativeRenderer* renderer) {
    if (renderer) atomic_fetch_add_explicit(&renderer->references, 1, memory_order_relaxed);
    return renderer;
}

static void renderer_release(KMPMpvNativeRenderer* renderer) {
    if (!renderer) return;
    if (atomic_fetch_sub_explicit(&renderer->references, 1, memory_order_acq_rel) == 1) {
        pthread_mutex_destroy(&renderer->render_mutex);
        free(renderer);
    }
}

static void run_on_appkit_main_sync(dispatch_function_t operation, void* context) {
    if (pthread_main_np()) {
        operation(context);
    } else {
        dispatch_sync_f(dispatch_get_main_queue(), context, operation);
    }
}

static void gl_flush_noop(void) {
}

static void* resolve_opengl_function(void* context, const char* name) {
    (void)context;
    if (!name) return NULL;
    if (strcmp(name, "glFlush") == 0) return (void*)&gl_flush_noop;

    CFBundleRef bundle = CFBundleGetBundleWithIdentifier(CFSTR("com.apple.opengl"));
    if (!bundle) return NULL;
    CFStringRef symbol = CFStringCreateWithCString(kCFAllocatorDefault, name, kCFStringEncodingASCII);
    if (!symbol) return NULL;
    void* address = CFBundleGetFunctionPointerForName(bundle, symbol);
    CFRelease(symbol);
    return address;
}

static BOOL load_render_api(KMPMpvNativeRenderer* renderer, const char* library_name) {
    if (!renderer || !library_name || !library_name[0]) return NO;
    renderer->libmpv_library = dlopen(library_name, RTLD_LAZY | RTLD_LOCAL);
    if (!renderer->libmpv_library) return NO;

#define KMP_LOAD_MPV_SYMBOL(field, symbol) \
    renderer->api.field = (symbol##_fn)dlsym(renderer->libmpv_library, #symbol); \
    if (!renderer->api.field) return NO

    KMP_LOAD_MPV_SYMBOL(create, mpv_render_context_create);
    KMP_LOAD_MPV_SYMBOL(set_update_callback, mpv_render_context_set_update_callback);
    KMP_LOAD_MPV_SYMBOL(update, mpv_render_context_update);
    KMP_LOAD_MPV_SYMBOL(render, mpv_render_context_render);
    KMP_LOAD_MPV_SYMBOL(report_swap, mpv_render_context_report_swap);
    KMP_LOAD_MPV_SYMBOL(free, mpv_render_context_free);
#undef KMP_LOAD_MPV_SYMBOL
    return YES;
}

static BOOL choose_pixel_format(
    CGLPixelFormatObj* output,
    GLint* output_depth
) {
    if (!output || !output_depth) return NO;

    const CGLPixelFormatAttribute hdr_attributes[] = {
        kCGLPFAOpenGLProfile,
        (CGLPixelFormatAttribute)kCGLOGLPVersion_3_2_Core,
        kCGLPFAAccelerated,
        kCGLPFADoubleBuffer,
        kCGLPFAColorSize,
        (CGLPixelFormatAttribute)64,
        kCGLPFAColorFloat,
        kCGLPFAAllowOfflineRenderers,
        (CGLPixelFormatAttribute)0,
    };
    GLint count = 0;
    CGLError error = CGLChoosePixelFormat(hdr_attributes, output, &count);
    if (error == kCGLNoError && *output) {
        *output_depth = 16;
        return YES;
    }

    const CGLPixelFormatAttribute sdr_attributes[] = {
        kCGLPFAOpenGLProfile,
        (CGLPixelFormatAttribute)kCGLOGLPVersion_3_2_Core,
        kCGLPFAAccelerated,
        kCGLPFADoubleBuffer,
        kCGLPFAAllowOfflineRenderers,
        (CGLPixelFormatAttribute)0,
    };
    error = CGLChoosePixelFormat(sdr_attributes, output, &count);
    if (error == kCGLNoError && *output) {
        *output_depth = 8;
        return YES;
    }

    const CGLPixelFormatAttribute legacy_attributes[] = {
        kCGLPFAOpenGLProfile,
        (CGLPixelFormatAttribute)kCGLOGLPVersion_Legacy,
        kCGLPFAAccelerated,
        kCGLPFADoubleBuffer,
        (CGLPixelFormatAttribute)0,
    };
    error = CGLChoosePixelFormat(legacy_attributes, output, &count);
    if (error == kCGLNoError && *output) {
        *output_depth = 8;
        return YES;
    }
    return NO;
}

@interface KMPMpvOpenGLLayer : CAOpenGLLayer {
    KMPMpvNativeRenderer* _renderer;
}
- (instancetype)initWithRenderer:(KMPMpvNativeRenderer*)renderer;
- (void)setRenderer:(KMPMpvNativeRenderer*)renderer;
- (void)requestFrame;
@end

@interface KMPMpvVideoView : NSView {
    KMPMpvOpenGLLayer* _videoLayer;
}
@property(nonatomic, retain) KMPMpvOpenGLLayer* videoLayer;
- (void)refreshNativeFrameAndGeometry;
- (void)refreshNativeGeometry;
@end

static BOOL is_window_fullscreen(NSWindow* window);

static NSRect mpv_video_frame_for_host_view(NSView* host_view) {
    if (!host_view) return NSZeroRect;
    NSWindow* window = [host_view window];
    if (is_window_fullscreen(window)) return [host_view bounds];
    NSView* content_view = [window contentView];
    if (content_view && [content_view superview] == host_view) {
        return [content_view frame];
    }
    return [host_view bounds];
}

static CGColorSpaceRef create_output_color_space(KMPMpvNativeRenderer* renderer) {
    switch (renderer ? renderer->color_mode : 0) {
        case 1:
            return CGColorSpaceCreateWithName(kCGColorSpaceITUR_2100_PQ);
        case 2:
            return CGColorSpaceCreateWithName(kCGColorSpaceITUR_2100_HLG);
        default: {
            NSScreen* screen = [[renderer->view window] screen];
            CGColorSpaceRef screen_space = [[screen colorSpace] CGColorSpace];
            if (screen_space) return CGColorSpaceRetain(screen_space);
            return CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
        }
    }
}

static void apply_output_color_space(KMPMpvNativeRenderer* renderer) {
    if (!renderer || !renderer->layer) return;
    BOOL extended_range = renderer->color_mode != 0;
    CGColorSpaceRef color_space = create_output_color_space(renderer);
    if (color_space) {
        [renderer->layer setColorspace:color_space];
        CGColorSpaceRelease(color_space);
    }
    [renderer->layer setWantsExtendedDynamicRangeContent:extended_range];
    if (renderer->view) {
        [renderer->view setWantsExtendedDynamicRangeOpenGLSurface:YES];
    }
}

static void renderer_update_callback(void* context) {
    KMPMpvNativeRenderer* renderer = (KMPMpvNativeRenderer*)context;
    if (!renderer || pthread_mutex_trylock(&renderer->render_mutex) != 0) return;
    KMPMpvOpenGLLayer* layer = nil;
    if (!renderer->shutting_down && renderer->layer) {
        layer = [renderer->layer retain];
    }
    pthread_mutex_unlock(&renderer->render_mutex);
    if (!layer) return;
    dispatch_async(dispatch_get_main_queue(), ^{
        [layer requestFrame];
        [layer release];
    });
}

@implementation KMPMpvOpenGLLayer

- (instancetype)initWithRenderer:(KMPMpvNativeRenderer*)renderer {
    self = [super init];
    if (self) {
        _renderer = NULL;
        [self setRenderer:renderer];
        [self setAutoresizingMask:kCALayerWidthSizable | kCALayerHeightSizable];
        [self setBackgroundColor:[[NSColor blackColor] CGColor]];
        [self setOpaque:YES];
        // CAOpenGLLayer owns a render thread. Keeping rendering off AppKit's main thread makes
        // live resize and Compose input independent from libmpv's GPU work.
        [self setAsynchronous:YES];
        if (renderer && renderer->buffer_depth > 8) {
            [self setContentsFormat:kCAContentsFormatRGBA16Float];
        }
    }
    return self;
}

- (void)dealloc {
    [self setRenderer:NULL];
    [super dealloc];
}

- (void)setRenderer:(KMPMpvNativeRenderer*)renderer {
    @synchronized(self) {
        if (_renderer == renderer) return;
        KMPMpvNativeRenderer* previous = _renderer;
        _renderer = renderer_retain(renderer);
        renderer_release(previous);
    }
}

- (KMPMpvNativeRenderer*)retainedRenderer {
    @synchronized(self) {
        return renderer_retain(_renderer);
    }
}

- (CGLPixelFormatObj)copyCGLPixelFormatForDisplayMask:(uint32_t)mask {
    (void)mask;
    KMPMpvNativeRenderer* renderer = [self retainedRenderer];
    CGLPixelFormatObj format = renderer ? renderer->pixel_format : NULL;
    if (format) CGLRetainPixelFormat(format);
    renderer_release(renderer);
    return format;
}

- (CGLContextObj)copyCGLContextForPixelFormat:(CGLPixelFormatObj)pixelFormat {
    (void)pixelFormat;
    KMPMpvNativeRenderer* renderer = [self retainedRenderer];
    CGLContextObj context = renderer ? renderer->gl_context : NULL;
    if (context) CGLRetainContext(context);
    renderer_release(renderer);
    return context;
}

- (BOOL)canDrawInCGLContext:(CGLContextObj)context
                pixelFormat:(CGLPixelFormatObj)pixelFormat
               forLayerTime:(CFTimeInterval)timeInterval
                displayTime:(const CVTimeStamp*)timeStamp {
    (void)context;
    (void)pixelFormat;
    (void)timeInterval;
    (void)timeStamp;
    KMPMpvNativeRenderer* renderer = [self retainedRenderer];
    BOOL can_draw = renderer && !renderer->shutting_down && renderer->render_context;
    renderer_release(renderer);
    return can_draw;
}

- (void)drawInCGLContext:(CGLContextObj)context
             pixelFormat:(CGLPixelFormatObj)pixelFormat
            forLayerTime:(CFTimeInterval)timeInterval
             displayTime:(const CVTimeStamp*)timeStamp {
    (void)pixelFormat;
    (void)timeInterval;
    (void)timeStamp;
    KMPMpvNativeRenderer* renderer = [self retainedRenderer];
    if (!renderer) return;

    pthread_mutex_lock(&renderer->render_mutex);
    if (!renderer->shutting_down && renderer->render_context && context) {
        CGLSetCurrentContext(context);
        (void)renderer->api.update(renderer->render_context);

        GLint viewport[4] = {0, 0, 0, 0};
        glGetIntegerv(GL_VIEWPORT, viewport);
        GLint framebuffer = 0;
        glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &framebuffer);
        if (framebuffer != 0) renderer->last_framebuffer = framebuffer;

        int width = viewport[2];
        int height = viewport[3];
        if (width <= 0 || height <= 0) {
            width = (int)llround(self.bounds.size.width * self.contentsScale);
            height = (int)llround(self.bounds.size.height * self.contentsScale);
        }
        if (width > 0 && height > 0) {
            mpv_opengl_fbo target = {
                .fbo = renderer->last_framebuffer,
                .width = width,
                .height = height,
                .internal_format = 0,
            };
            int flip_y = 1;
            int depth = renderer->buffer_depth;
            mpv_render_param parameters[] = {
                {MPV_RENDER_PARAM_OPENGL_FBO, &target},
                {MPV_RENDER_PARAM_FLIP_Y, &flip_y},
                {MPV_RENDER_PARAM_DEPTH, &depth},
                {MPV_RENDER_PARAM_INVALID, NULL},
            };
            (void)renderer->api.render(renderer->render_context, parameters);
            CGLFlushDrawable(context);
            renderer->api.report_swap(renderer->render_context);
        }
    }
    pthread_mutex_unlock(&renderer->render_mutex);
    renderer_release(renderer);
}

- (void)requestFrame {
    [self setNeedsDisplay];
}

@end

@implementation KMPMpvVideoView

@synthesize videoLayer = _videoLayer;

- (void)dealloc {
    [[NSNotificationCenter defaultCenter] removeObserver:self];
    [_videoLayer release];
    [super dealloc];
}

- (NSView*)hitTest:(NSPoint)point {
    (void)point;
    return nil;
}

- (BOOL)acceptsFirstResponder {
    return NO;
}

- (void)setVideoLayer:(KMPMpvOpenGLLayer*)videoLayer {
    if (_videoLayer == videoLayer) return;
    [_videoLayer release];
    _videoLayer = [videoLayer retain];
    [self setWantsLayer:YES];
    [self setLayer:_videoLayer];
    [self refreshNativeGeometry];
}

- (void)viewDidMoveToWindow {
    [super viewDidMoveToWindow];
    [[NSNotificationCenter defaultCenter] removeObserver:self];
    NSWindow* window = [self window];
    if (window) {
        NSNotificationCenter* center = [NSNotificationCenter defaultCenter];
        [center addObserver:self
                   selector:@selector(windowColorOrGeometryChanged:)
                       name:NSWindowDidChangeScreenNotification
                     object:window];
        [center addObserver:self
                   selector:@selector(windowColorOrGeometryChanged:)
                       name:NSWindowDidChangeBackingPropertiesNotification
                     object:window];
        [center addObserver:self
                   selector:@selector(windowColorOrGeometryChanged:)
                       name:NSWindowDidResizeNotification
                     object:window];
        [center addObserver:self
                   selector:@selector(windowColorOrGeometryChanged:)
                       name:NSWindowDidEnterFullScreenNotification
                     object:window];
        [center addObserver:self
                   selector:@selector(windowColorOrGeometryChanged:)
                       name:NSWindowDidExitFullScreenNotification
                     object:window];
    }
    [self refreshNativeFrameAndGeometry];
}

- (void)viewDidChangeBackingProperties {
    [super viewDidChangeBackingProperties];
    [self refreshNativeGeometry];
}

- (void)setFrameSize:(NSSize)newSize {
    [super setFrameSize:newSize];
    [self refreshNativeGeometry];
}

- (void)windowColorOrGeometryChanged:(NSNotification*)notification {
    (void)notification;
    [self refreshNativeFrameAndGeometry];
}

- (void)refreshNativeFrameAndGeometry {
    NSView* host_view = [self superview];
    if (host_view) {
        NSRect target_frame = mpv_video_frame_for_host_view(host_view);
        if (!NSEqualRects([self frame], target_frame)) {
            [self setFrame:target_frame];
        }
    }
    [self refreshNativeGeometry];
}

- (void)refreshNativeGeometry {
    if (!_videoLayer) return;
    CGFloat scale = [[self window] backingScaleFactor];
    if (scale <= 0.0) scale = 1.0;
    [_videoLayer setFrame:[self bounds]];
    [_videoLayer setContentsScale:scale];
    KMPMpvNativeRenderer* renderer = [_videoLayer retainedRenderer];
    if (renderer) {
        apply_output_color_space(renderer);
        renderer_release(renderer);
    }
    [_videoLayer requestFrame];
}

@end

typedef struct {
    NSString* window_title;
    KMPMpvNativeRenderer* renderer;
    BOOL attached;
} KMPMpvAttachContext;

static NSWindow* find_awt_window(NSString* window_title) {
    if ([window_title length] > 0) {
        for (NSWindow* candidate in [NSApp windows]) {
            if ([[candidate title] isEqualToString:window_title]) return candidate;
        }
    }
    return nil;
}

typedef struct {
    NSString* window_title;
    BOOL requested_fullscreen;
    BOOL should_update;
    BOOL found;
    BOOL accepted;
    BOOL fullscreen;
} KMPMpvWindowFullscreenContext;

static char KMP_MPV_MANAGED_FULLSCREEN_KEY;

static BOOL window_frame_covers_screen(NSWindow* window) {
    if (!window) return NO;
    NSScreen* screen = [window screen] ?: [NSScreen mainScreen];
    if (!screen) return NO;
    NSRect frame = [window frame];
    NSRect screen_frame = [screen frame];
    const CGFloat tolerance = 1.0;
    return fabs(frame.origin.x - screen_frame.origin.x) <= tolerance &&
        fabs(frame.origin.y - screen_frame.origin.y) <= tolerance &&
        fabs(frame.size.width - screen_frame.size.width) <= tolerance &&
        fabs(frame.size.height - screen_frame.size.height) <= tolerance;
}

static BOOL is_window_fullscreen(NSWindow* window) {
    if (!window) return NO;
    if (([window styleMask] & NSWindowStyleMaskFullScreen) != 0) return YES;
    NSNumber* managed = objc_getAssociatedObject(window, &KMP_MPV_MANAGED_FULLSCREEN_KEY);
    return [managed boolValue] && window_frame_covers_screen(window);
}

static void prepare_window_for_native_fullscreen(NSWindow* window) {
    if (!window) return;
    NSWindowCollectionBehavior behavior = [window collectionBehavior];
    behavior &= ~(NSWindowCollectionBehaviorFullScreenAuxiliary |
        NSWindowCollectionBehaviorFullScreenNone);
    behavior |= NSWindowCollectionBehaviorFullScreenPrimary;
    [window setCollectionBehavior:behavior];
    [window setTitleVisibility:NSWindowTitleHidden];
    [window setTitlebarAppearsTransparent:YES];
}

static void set_standard_window_buttons_hidden(NSWindow* window, BOOL hidden) {
    if (!window) return;
    [[window standardWindowButton:NSWindowCloseButton] setHidden:hidden];
    [[window standardWindowButton:NSWindowMiniaturizeButton] setHidden:hidden];
    [[window standardWindowButton:NSWindowZoomButton] setHidden:hidden];
}

@interface KMPMpvWindowFullscreenCoordinator : NSObject {
    NSWindow* _window;
    NSString* _windowTitle;
    BOOL _managedFullscreen;
    BOOL _hasWindowedState;
    NSRect _windowedFrame;
    NSInteger _windowedLevel;
    NSWindowCollectionBehavior _windowedCollectionBehavior;
    NSApplicationPresentationOptions _windowedPresentationOptions;
    BOOL _windowedHasShadow;
    BOOL _nativeTransitionInProgress;
    BOOL _hasPendingNativeRequest;
    BOOL _pendingNativeFullscreen;
}
- (instancetype)initWithWindow:(NSWindow*)window;
- (NSWindow*)resolveLiveWindow;
- (BOOL)enterManagedFullscreen:(NSWindow*)window;
- (BOOL)leaveManagedFullscreen:(NSWindow*)window;
- (BOOL)requestFullscreen:(BOOL)fullscreen;
- (void)nativeWindowWillChangeFullscreen:(NSNotification*)notification;
- (void)nativeWindowDidChangeFullscreen:(NSNotification*)notification;
- (void)windowWillClose:(NSNotification*)notification;
@end

@implementation KMPMpvWindowFullscreenCoordinator

- (instancetype)initWithWindow:(NSWindow*)window {
    self = [super init];
    if (self) {
        _window = window;
        _windowTitle = [[window title] copy];
        NSNotificationCenter* center = [NSNotificationCenter defaultCenter];
        [center addObserver:self
                   selector:@selector(windowWillClose:)
                       name:NSWindowWillCloseNotification
                     object:window];
        [center addObserver:self
                   selector:@selector(nativeWindowWillChangeFullscreen:)
                       name:NSWindowWillEnterFullScreenNotification
                     object:window];
        [center addObserver:self
                   selector:@selector(nativeWindowWillChangeFullscreen:)
                       name:NSWindowWillExitFullScreenNotification
                     object:window];
        [center addObserver:self
                   selector:@selector(nativeWindowDidChangeFullscreen:)
                       name:NSWindowDidEnterFullScreenNotification
                     object:window];
        [center addObserver:self
                   selector:@selector(nativeWindowDidChangeFullscreen:)
                       name:NSWindowDidExitFullScreenNotification
                     object:window];
    }
    return self;
}

- (void)dealloc {
    [[NSNotificationCenter defaultCenter] removeObserver:self];
    _window = nil;
    [_windowTitle release];
    _windowTitle = nil;
    [super dealloc];
}

- (NSWindow*)resolveLiveWindow {
    NSWindow* live_window = find_awt_window(_windowTitle);
    if (!live_window || ![live_window isVisible]) {
        _window = nil;
        return nil;
    }
    _window = live_window;
    return live_window;
}

- (BOOL)enterManagedFullscreen:(NSWindow*)window {
    if (!window) return NO;
    if (_managedFullscreen) return YES;

    NSScreen* screen = [window screen] ?: [NSScreen mainScreen];
    if (!screen) return NO;

    _windowedFrame = [window frame];
    _windowedLevel = [window level];
    _windowedCollectionBehavior = [window collectionBehavior];
    _windowedPresentationOptions = [NSApp presentationOptions];
    _windowedHasShadow = [window hasShadow];
    _hasWindowedState = YES;

    @try {
        prepare_window_for_native_fullscreen(window);
        set_standard_window_buttons_hidden(window, YES);
        objc_setAssociatedObject(
            window,
            &KMP_MPV_MANAGED_FULLSCREEN_KEY,
            [NSNumber numberWithBool:YES],
            OBJC_ASSOCIATION_RETAIN_NONATOMIC
        );
        NSApplicationPresentationOptions presentation = _windowedPresentationOptions;
        presentation &= ~(NSApplicationPresentationHideDock |
            NSApplicationPresentationHideMenuBar);
        presentation |= NSApplicationPresentationAutoHideDock |
            NSApplicationPresentationAutoHideMenuBar;
        [NSApp setPresentationOptions:presentation];
        [window setHasShadow:NO];
        [window setFrame:[screen frame] display:YES animate:NO];
        [window makeKeyAndOrderFront:nil];
        if (!window_frame_covers_screen(window)) {
            objc_setAssociatedObject(
                window,
                &KMP_MPV_MANAGED_FULLSCREEN_KEY,
                nil,
                OBJC_ASSOCIATION_ASSIGN
            );
            [NSApp setPresentationOptions:_windowedPresentationOptions];
            set_standard_window_buttons_hidden(window, NO);
            [window setCollectionBehavior:_windowedCollectionBehavior];
            [window setLevel:_windowedLevel];
            [window setHasShadow:_windowedHasShadow];
            [window setFrame:_windowedFrame display:YES animate:NO];
            _hasWindowedState = NO;
            return NO;
        }
        _managedFullscreen = YES;
        return YES;
    } @catch (NSException* exception) {
        (void)exception;
        objc_setAssociatedObject(
            window,
            &KMP_MPV_MANAGED_FULLSCREEN_KEY,
            nil,
            OBJC_ASSOCIATION_ASSIGN
        );
        if (_hasWindowedState) {
            [NSApp setPresentationOptions:_windowedPresentationOptions];
            set_standard_window_buttons_hidden(window, NO);
            [window setCollectionBehavior:_windowedCollectionBehavior];
            [window setLevel:_windowedLevel];
            [window setHasShadow:_windowedHasShadow];
            [window setFrame:_windowedFrame display:YES animate:NO];
        }
        _hasWindowedState = NO;
        return NO;
    }
}

- (BOOL)leaveManagedFullscreen:(NSWindow*)window {
    if (!window) return NO;
    if (!_managedFullscreen) return YES;
    @try {
        objc_setAssociatedObject(
            window,
            &KMP_MPV_MANAGED_FULLSCREEN_KEY,
            nil,
            OBJC_ASSOCIATION_ASSIGN
        );
        if (_hasWindowedState) {
            [NSApp setPresentationOptions:_windowedPresentationOptions];
            set_standard_window_buttons_hidden(window, NO);
            [window setCollectionBehavior:_windowedCollectionBehavior];
            [window setLevel:_windowedLevel];
            [window setHasShadow:_windowedHasShadow];
            [window setFrame:_windowedFrame display:YES animate:NO];
        }
        [window makeKeyAndOrderFront:nil];
        _managedFullscreen = NO;
        _hasWindowedState = NO;
        return YES;
    } @catch (NSException* exception) {
        (void)exception;
        return NO;
    }
}

- (BOOL)requestFullscreen:(BOOL)fullscreen {
    NSWindow* window = [self resolveLiveWindow];
    if (!window) return NO;
    BOOL appkit_native_fullscreen =
        ([window styleMask] & NSWindowStyleMaskFullScreen) != 0;
    if (_nativeTransitionInProgress || appkit_native_fullscreen) {
        if (_nativeTransitionInProgress) {
            _hasPendingNativeRequest = YES;
            _pendingNativeFullscreen = fullscreen;
            return YES;
        }
        if (is_window_fullscreen(window) == fullscreen) return YES;
        @try {
            prepare_window_for_native_fullscreen(window);
            _nativeTransitionInProgress = YES;
            [window toggleFullScreen:nil];
            return YES;
        } @catch (NSException* exception) {
            (void)exception;
            _nativeTransitionInProgress = NO;
            return NO;
        }
    }
    if (_managedFullscreen) {
        return fullscreen ? YES : [self leaveManagedFullscreen:window];
    }
    return fullscreen ? [self enterManagedFullscreen:window] : YES;
}

- (void)nativeWindowWillChangeFullscreen:(NSNotification*)notification {
    if ([notification object] != _window) return;
    _nativeTransitionInProgress = YES;
}

- (void)nativeWindowDidChangeFullscreen:(NSNotification*)notification {
    if ([notification object] != _window) return;
    _nativeTransitionInProgress = NO;
    if (!_hasPendingNativeRequest) return;
    BOOL requested = _pendingNativeFullscreen;
    _hasPendingNativeRequest = NO;
    if (is_window_fullscreen(_window) != requested) {
        [self requestFullscreen:requested];
    }
}

- (void)windowWillClose:(NSNotification*)notification {
    if ([notification object] != _window) return;
    if (_managedFullscreen) {
        [NSApp setPresentationOptions:_windowedPresentationOptions];
        objc_setAssociatedObject(
            _window,
            &KMP_MPV_MANAGED_FULLSCREEN_KEY,
            nil,
            OBJC_ASSOCIATION_ASSIGN
        );
    }
    _window = nil;
    _managedFullscreen = NO;
    _hasWindowedState = NO;
    _nativeTransitionInProgress = NO;
    _hasPendingNativeRequest = NO;
}

@end

static char KMP_MPV_FULLSCREEN_COORDINATOR_KEY;

static KMPMpvWindowFullscreenCoordinator* fullscreen_coordinator_for_window(
    NSWindow* window
) {
    if (!window) return nil;
    KMPMpvWindowFullscreenCoordinator* coordinator =
        objc_getAssociatedObject(window, &KMP_MPV_FULLSCREEN_COORDINATOR_KEY);
    if (!coordinator) {
        coordinator =
            [[KMPMpvWindowFullscreenCoordinator alloc] initWithWindow:window];
        objc_setAssociatedObject(
            window,
            &KMP_MPV_FULLSCREEN_COORDINATOR_KEY,
            coordinator,
            OBJC_ASSOCIATION_RETAIN_NONATOMIC
        );
        [coordinator release];
        coordinator =
            objc_getAssociatedObject(window, &KMP_MPV_FULLSCREEN_COORDINATOR_KEY);
    }
    return coordinator;
}

static const void* mpv_window_zoom_coordinator_key(void) {
    return (const void*)sel_registerName("KMPSharedNativeWindowZoomCoordinator");
}

@interface KMPMpvWindowZoomCoordinator : NSObject {
    NSWindow* _window;
    id _eventMonitor;
    BOOL _zoomed;
    BOOL _requestedZoomed;
    BOOL _animationInProgress;
    NSRect _restoreFrame;
}
- (instancetype)initWithWindow:(NSWindow*)window;
- (NSEvent*)handleLocalMouseDown:(NSEvent*)event;
- (BOOL)isTitleBarEvent:(NSEvent*)event;
- (void)toggleZoom;
- (void)startPendingZoomAnimation;
- (void)removeEventMonitor;
- (void)windowWillClose:(NSNotification*)notification;
@end

@implementation KMPMpvWindowZoomCoordinator

- (instancetype)initWithWindow:(NSWindow*)window {
    self = [super init];
    if (self) {
        _window = window;
        __block KMPMpvWindowZoomCoordinator* unretained_self = self;
        _eventMonitor = [[NSEvent
            addLocalMonitorForEventsMatchingMask:NSEventMaskLeftMouseDown
            handler:^NSEvent*(NSEvent* event) {
                return [unretained_self handleLocalMouseDown:event];
            }] retain];
        [[NSNotificationCenter defaultCenter]
            addObserver:self
            selector:@selector(windowWillClose:)
            name:NSWindowWillCloseNotification
            object:window];
    }
    return self;
}

- (void)dealloc {
    [[NSNotificationCenter defaultCenter] removeObserver:self];
    [self removeEventMonitor];
    _window = nil;
    [super dealloc];
}

- (void)removeEventMonitor {
    if (!_eventMonitor) return;
    [NSEvent removeMonitor:_eventMonitor];
    [_eventMonitor release];
    _eventMonitor = nil;
}

- (void)windowWillClose:(NSNotification*)notification {
    if ([notification object] != _window) return;
    [self removeEventMonitor];
    _window = nil;
}

- (BOOL)isTitleBarEvent:(NSEvent*)event {
    NSWindow* window = _window;
    if (!window || [event window] != window) return NO;
    NSPoint window_point = [event locationInWindow];

    for (NSWindowButton button_type = NSWindowCloseButton;
         button_type <= NSWindowZoomButton;
         button_type++) {
        NSButton* button = [window standardWindowButton:button_type];
        if (!button || [button isHidden]) continue;
        NSPoint button_point = [button convertPoint:window_point fromView:nil];
        if (NSPointInRect(button_point, [button bounds])) return NO;
    }

    NSView* content_view = [window contentView];
    if (content_view) {
        NSPoint content_point = [content_view convertPoint:window_point fromView:nil];
        if (NSPointInRect(content_point, [content_view bounds])) return NO;
    }
    return window_point.y >= 0.0 && window_point.y <= [window frame].size.height;
}

- (NSEvent*)handleLocalMouseDown:(NSEvent*)event {
    NSInteger click_count = [event clickCount];
    if (click_count < 2 || (click_count % 2) != 0 || ![self isTitleBarEvent:event]) {
        return event;
    }
    BOOL system_fullscreen = ([_window styleMask] & NSWindowStyleMaskFullScreen) != 0;
    NSButton* zoom_button = [_window standardWindowButton:NSWindowZoomButton];
    if (system_fullscreen || [zoom_button isHidden]) return event;
    [self toggleZoom];
    return nil;
}

- (void)toggleZoom {
    NSWindow* window = _window;
    if (!window) return;
    _requestedZoomed = !_requestedZoomed;
    if (_requestedZoomed && !_zoomed && !_animationInProgress) {
        _restoreFrame = [window frame];
    }
    [self startPendingZoomAnimation];
}

- (void)startPendingZoomAnimation {
    NSWindow* window = _window;
    if (!window || _animationInProgress || _zoomed == _requestedZoomed) return;

    BOOL target_zoomed = _requestedZoomed;
    NSRect target_frame;
    if (target_zoomed) {
        NSScreen* screen = [window screen] ?: [NSScreen mainScreen];
        if (!screen) return;
        target_frame = [screen visibleFrame];
    } else {
        target_frame = _restoreFrame;
    }
    _animationInProgress = YES;

    [window makeKeyAndOrderFront:nil];
    KMPMpvWindowZoomCoordinator* retained_self = [self retain];
    [NSAnimationContext
        runAnimationGroup:^(NSAnimationContext* context) {
            [context setDuration:0.22];
            [context setTimingFunction:
                [CAMediaTimingFunction functionWithName:kCAMediaTimingFunctionEaseInEaseOut]];
            [[window animator] setFrame:target_frame display:YES];
        }
        completionHandler:^{
            retained_self->_zoomed = target_zoomed;
            retained_self->_animationInProgress = NO;
            [retained_self startPendingZoomAnimation];
            [retained_self release];
        }];
}

@end

static void install_mpv_window_zoom_coordinator(NSWindow* window) {
    const void* key = mpv_window_zoom_coordinator_key();
    if (!window || objc_getAssociatedObject(window, key)) return;
    KMPMpvWindowZoomCoordinator* coordinator =
        [[KMPMpvWindowZoomCoordinator alloc] initWithWindow:window];
    objc_setAssociatedObject(
        window,
        key,
        coordinator,
        OBJC_ASSOCIATION_RETAIN_NONATOMIC
    );
    [coordinator release];
}

static void synchronize_window_fullscreen_on_main(void* raw_context) {
    KMPMpvWindowFullscreenContext* context =
        (KMPMpvWindowFullscreenContext*)raw_context;
    @autoreleasepool {
        if (!context) return;
        NSWindow* window = find_awt_window(context->window_title);
        if (!window) return;
        context->found = YES;
        context->fullscreen = is_window_fullscreen(window);
        if (!context->should_update) {
            context->accepted = YES;
            return;
        }
        KMPMpvWindowFullscreenCoordinator* coordinator =
            fullscreen_coordinator_for_window(window);
        context->accepted =
            coordinator &&
            [coordinator requestFullscreen:context->requested_fullscreen];
    }
}

typedef struct {
    NSString* window_title;
    BOOL configured;
} KMPMpvWindowConfigurationContext;

static void configure_native_window_on_main(void* raw_context) {
    KMPMpvWindowConfigurationContext* context =
        (KMPMpvWindowConfigurationContext*)raw_context;
    @autoreleasepool {
        if (!context) return;
        NSWindow* window = find_awt_window(context->window_title);
        if (!window) return;

        NSWindowStyleMask style_mask = [window styleMask];
        NSWindowStyleMask native_style_mask =
            (style_mask |
                NSWindowStyleMaskTitled |
                NSWindowStyleMaskClosable |
                NSWindowStyleMaskMiniaturizable |
                NSWindowStyleMaskResizable) &
            ~NSWindowStyleMaskFullSizeContentView;
        if (native_style_mask != style_mask) {
            [window setStyleMask:native_style_mask];
        }
        [window setOpaque:YES];
        [window setAlphaValue:1.0];
        [window setBackgroundColor:[NSColor blackColor]];
        [window setAppearance:[NSAppearance appearanceNamed:NSAppearanceNameDarkAqua]];
        [window setTitleVisibility:NSWindowTitleHidden];
        [window setTitlebarAppearsTransparent:YES];
        [window setMovableByWindowBackground:NO];
        [window setHasShadow:YES];
        install_mpv_window_zoom_coordinator(window);
        context->configured = YES;
    }
}

static void attach_renderer_on_main(void* raw_context) {
    KMPMpvAttachContext* context = (KMPMpvAttachContext*)raw_context;
    @autoreleasepool {
        KMPMpvNativeRenderer* renderer = context->renderer;
        NSWindow* window = find_awt_window(context->window_title);
        NSView* compose_view = [window contentView];
        NSView* host_view = [compose_view superview] ?: compose_view;
        if (!renderer || !window || !compose_view || !host_view ||
            host_view.bounds.size.width <= 0.0 || host_view.bounds.size.height <= 0.0) {
            return;
        }

        if (!choose_pixel_format(&renderer->pixel_format, &renderer->buffer_depth)) return;
        if (CGLCreateContext(renderer->pixel_format, NULL, &renderer->gl_context) != kCGLNoError ||
            !renderer->gl_context) {
            return;
        }
        GLint swap_interval = 1;
        CGLSetParameter(renderer->gl_context, kCGLCPSwapInterval, &swap_interval);
        CGLSetCurrentContext(renderer->gl_context);

        const char* api_type = "opengl";
        mpv_opengl_init_params open_gl = {
            .get_proc_address = resolve_opengl_function,
            .get_proc_address_context = NULL,
        };
        mpv_render_param create_parameters[] = {
            {MPV_RENDER_PARAM_API_TYPE, (void*)api_type},
            {MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &open_gl},
            {MPV_RENDER_PARAM_INVALID, NULL},
        };
        if (renderer->api.create(
                &renderer->render_context,
                renderer->mpv,
                create_parameters
            ) < 0 || !renderer->render_context) {
            return;
        }

        KMPMpvOpenGLLayer* layer = [[KMPMpvOpenGLLayer alloc] initWithRenderer:renderer];
        KMPMpvVideoView* view =
            [[KMPMpvVideoView alloc] initWithFrame:mpv_video_frame_for_host_view(host_view)];
        renderer->layer = [layer retain];
        renderer->view = [view retain];
        [view setAutoresizingMask:NSViewWidthSizable | NSViewHeightSizable];
        [view setVideoLayer:layer];
        [view setWantsExtendedDynamicRangeOpenGLSurface:YES];
        [host_view addSubview:view positioned:NSWindowBelow relativeTo:compose_view];
        apply_output_color_space(renderer);
        renderer->api.set_update_callback(
            renderer->render_context,
            renderer_update_callback,
            renderer
        );
        [view refreshNativeFrameAndGeometry];
        context->attached = YES;
        [layer release];
        [view release];
    }
}

static void detach_renderer_on_main(void* raw_renderer) {
    KMPMpvNativeRenderer* renderer = (KMPMpvNativeRenderer*)raw_renderer;
    if (!renderer) return;
    @autoreleasepool {
        pthread_mutex_lock(&renderer->render_mutex);
        renderer->shutting_down = YES;
        if (renderer->render_context) {
            CGLSetCurrentContext(renderer->gl_context);
            renderer->api.set_update_callback(renderer->render_context, NULL, NULL);
            renderer->api.free(renderer->render_context);
            renderer->render_context = NULL;
        }
        pthread_mutex_unlock(&renderer->render_mutex);

        // These are the renderer's owned references, so they remain valid even when AppKit has
        // already removed the AWT content hierarchy while entering/leaving full screen.
        KMPMpvOpenGLLayer* layer = renderer->layer;
        KMPMpvVideoView* view = renderer->view;
        renderer->layer = nil;
        renderer->view = nil;
        [layer setRenderer:NULL];
        [view setVideoLayer:nil];
        [view removeFromSuperview];

        if (renderer->gl_context) {
            CGLSetCurrentContext(NULL);
            CGLReleaseContext(renderer->gl_context);
            renderer->gl_context = NULL;
        }
        if (renderer->pixel_format) {
            CGLReleasePixelFormat(renderer->pixel_format);
            renderer->pixel_format = NULL;
        }
        if (renderer->libmpv_library) {
            dlclose(renderer->libmpv_library);
            renderer->libmpv_library = NULL;
        }
        [layer release];
        [view release];
    }
}

typedef struct {
    KMPMpvNativeRenderer* renderer;
    int color_mode;
} KMPMpvColorContext;

static void update_color_mode_on_main(void* raw_context) {
    KMPMpvColorContext* context = (KMPMpvColorContext*)raw_context;
    if (!context || !context->renderer || context->renderer->shutting_down) return;
    context->renderer->color_mode = context->color_mode;
    apply_output_color_space(context->renderer);
    [context->renderer->layer requestFrame];
}

static void request_redraw_on_main(void* raw_renderer) {
    KMPMpvNativeRenderer* renderer = (KMPMpvNativeRenderer*)raw_renderer;
    if (renderer && !renderer->shutting_down) [renderer->layer requestFrame];
}

static NSString* awt_window_title(JNIEnv* environment, jobject window) {
    if (!environment || !window) return nil;
    jclass window_class = (*environment)->GetObjectClass(environment, window);
    if (!window_class) return nil;
    jmethodID method = (*environment)->GetMethodID(
        environment,
        window_class,
        "getTitle",
        "()Ljava/lang/String;"
    );
    (*environment)->DeleteLocalRef(environment, window_class);
    if (!method) {
        if ((*environment)->ExceptionCheck(environment)) (*environment)->ExceptionClear(environment);
        return nil;
    }
    jstring value = (jstring)(*environment)->CallObjectMethod(environment, window, method);
    if ((*environment)->ExceptionCheck(environment) || !value) {
        if ((*environment)->ExceptionCheck(environment)) (*environment)->ExceptionClear(environment);
        return nil;
    }
    const char* utf8 = (*environment)->GetStringUTFChars(environment, value, NULL);
    NSString* title = utf8 ? [[NSString alloc] initWithUTF8String:utf8] : nil;
    if (utf8) (*environment)->ReleaseStringUTFChars(environment, value, utf8);
    (*environment)->DeleteLocalRef(environment, value);
    return title;
}

JNIEXPORT jlong JNICALL
Java_io_github_kdroidfilter_composemediaplayer_mpv_MpvMacNativeBridge_nAttach(
    JNIEnv* environment,
    jclass bridge_class,
    jlong raw_mpv_handle,
    jobject window,
    jstring library_load_name,
    jint color_mode
) {
    (void)bridge_class;
    if (!environment || !window || !library_load_name || raw_mpv_handle == 0) return 0;

    const char* library_name = (*environment)->GetStringUTFChars(
        environment,
        library_load_name,
        NULL
    );
    if (!library_name) return 0;
    KMPMpvNativeRenderer* renderer = calloc(1, sizeof(KMPMpvNativeRenderer));
    if (!renderer) {
        (*environment)->ReleaseStringUTFChars(environment, library_load_name, library_name);
        return 0;
    }
    atomic_init(&renderer->references, 1);
    pthread_mutex_init(&renderer->render_mutex, NULL);
    renderer->color_mode = color_mode;
    renderer->mpv = (mpv_handle*)(uintptr_t)raw_mpv_handle;

    BOOL api_loaded = load_render_api(renderer, library_name);
    (*environment)->ReleaseStringUTFChars(environment, library_load_name, library_name);
    if (!api_loaded) {
        if (renderer->libmpv_library) dlclose(renderer->libmpv_library);
        renderer_release(renderer);
        return 0;
    }

    NSString* title = awt_window_title(environment, window);
    KMPMpvAttachContext context = {
        .window_title = title,
        .renderer = renderer,
        .attached = NO,
    };
    run_on_appkit_main_sync(attach_renderer_on_main, &context);
    [title release];

    if (!context.attached) {
        run_on_appkit_main_sync(detach_renderer_on_main, renderer);
        renderer_release(renderer);
        return 0;
    }
    return (jlong)(uintptr_t)renderer;
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_composemediaplayer_mpv_MpvMacNativeBridge_nDetach(
    JNIEnv* environment,
    jclass bridge_class,
    jlong native_renderer
) {
    (void)environment;
    (void)bridge_class;
    KMPMpvNativeRenderer* renderer = (KMPMpvNativeRenderer*)(uintptr_t)native_renderer;
    if (!renderer) return;
    run_on_appkit_main_sync(detach_renderer_on_main, renderer);
    renderer_release(renderer);
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_composemediaplayer_mpv_MpvMacNativeBridge_nSetColorMode(
    JNIEnv* environment,
    jclass bridge_class,
    jlong native_renderer,
    jint color_mode
) {
    (void)environment;
    (void)bridge_class;
    KMPMpvNativeRenderer* renderer = (KMPMpvNativeRenderer*)(uintptr_t)native_renderer;
    if (!renderer) return;
    KMPMpvColorContext context = {.renderer = renderer, .color_mode = color_mode};
    run_on_appkit_main_sync(update_color_mode_on_main, &context);
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_composemediaplayer_mpv_MpvMacNativeBridge_nRequestRedraw(
    JNIEnv* environment,
    jclass bridge_class,
    jlong native_renderer
) {
    (void)environment;
    (void)bridge_class;
    KMPMpvNativeRenderer* renderer = (KMPMpvNativeRenderer*)(uintptr_t)native_renderer;
    if (!renderer) return;
    run_on_appkit_main_sync(request_redraw_on_main, renderer);
}

JNIEXPORT jboolean JNICALL
Java_io_github_kdroidfilter_composemediaplayer_mpv_MpvMacNativeBridge_nSetWindowFullscreen(
    JNIEnv* environment,
    jclass bridge_class,
    jobject window,
    jboolean fullscreen
) {
    (void)bridge_class;
    NSString* title = awt_window_title(environment, window);
    if (!title) return JNI_FALSE;
    KMPMpvWindowFullscreenContext context = {
        .window_title = title,
        .requested_fullscreen = fullscreen == JNI_TRUE,
        .should_update = YES,
        .found = NO,
        .accepted = NO,
        .fullscreen = NO,
    };
    run_on_appkit_main_sync(synchronize_window_fullscreen_on_main, &context);
    [title release];
    return context.found && context.accepted ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_kdroidfilter_composemediaplayer_mpv_MpvMacNativeBridge_nConfigureNativeWindow(
    JNIEnv* environment,
    jclass bridge_class,
    jobject window
) {
    (void)bridge_class;
    NSString* title = awt_window_title(environment, window);
    if (!title) return JNI_FALSE;
    KMPMpvWindowConfigurationContext context = {
        .window_title = title,
        .configured = NO,
    };
    run_on_appkit_main_sync(configure_native_window_on_main, &context);
    [title release];
    return context.configured ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_kdroidfilter_composemediaplayer_mpv_MpvMacNativeBridge_nIsWindowFullscreen(
    JNIEnv* environment,
    jclass bridge_class,
    jobject window
) {
    (void)bridge_class;
    NSString* title = awt_window_title(environment, window);
    if (!title) return JNI_FALSE;
    KMPMpvWindowFullscreenContext context = {
        .window_title = title,
        .requested_fullscreen = NO,
        .should_update = NO,
        .found = NO,
        .accepted = NO,
        .fullscreen = NO,
    };
    run_on_appkit_main_sync(synchronize_window_fullscreen_on_main, &context);
    [title release];
    return context.found && context.fullscreen ? JNI_TRUE : JNI_FALSE;
}
