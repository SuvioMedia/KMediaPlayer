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
    MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME = 12,
};

enum {
    MPV_RENDER_UPDATE_FRAME = 1 << 0,
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
    atomic_bool shutting_down;
    atomic_bool update_pending;
    atomic_bool redraw_requested;
    atomic_bool live_resize_active;
    pthread_mutex_t render_mutex;
    int color_mode;
    int buffer_depth;
    int last_framebuffer;
    mpv_handle* mpv;
    void* libmpv_library;
    KMPMpvRenderApi api;
    mpv_render_context* render_context;
    CGLPixelFormatObj pixel_format;
    CGLContextObj gl_context;
    // The renderer owns the native view and layer while Nucleus Tao mounts the view.
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
    BOOL _liveResizeActive;
    NSSize _liveResizeRenderSize;
}
@property(nonatomic, retain) KMPMpvOpenGLLayer* videoLayer;
- (void)beginLiveResizeIfNeeded;
- (void)endLiveResizeIfNeeded;
- (void)refreshNativeFrameAndGeometry;
- (void)refreshNativeGeometry;
@end

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
    if (!renderer ||
        atomic_load_explicit(&renderer->shutting_down, memory_order_acquire)) {
        return;
    }

    // libmpv permits coalescing multiple callbacks into one update() call. CAOpenGLLayer polls
    // canDrawInCGLContext at the display cadence when asynchronous=YES, so this callback only
    // publishes work. It never waits on the render mutex and never queues work on AppKit's main
    // thread, both of which used to make live resize drop frames and contend with window events.
    atomic_store_explicit(&renderer->update_pending, true, memory_order_release);
}

@implementation KMPMpvOpenGLLayer

- (instancetype)initWithRenderer:(KMPMpvNativeRenderer*)renderer {
    self = [super init];
    if (self) {
        _renderer = NULL;
        [self setRenderer:renderer];
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
    BOOL can_draw =
        renderer &&
        !atomic_load_explicit(&renderer->shutting_down, memory_order_acquire) &&
        (atomic_load_explicit(&renderer->update_pending, memory_order_acquire) ||
            atomic_load_explicit(&renderer->redraw_requested, memory_order_acquire));
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
    if (!atomic_load_explicit(&renderer->shutting_down, memory_order_acquire) &&
        renderer->render_context && context) {
        CGLSetCurrentContext(context);
        BOOL update_pending = atomic_exchange_explicit(
            &renderer->update_pending,
            false,
            memory_order_acq_rel
        );
        BOOL redraw_requested = atomic_exchange_explicit(
            &renderer->redraw_requested,
            false,
            memory_order_acq_rel
        );
        uint64_t update_flags = update_pending
            ? renderer->api.update(renderer->render_context)
            : 0;
        BOOL should_render =
            redraw_requested || (update_flags & MPV_RENDER_UPDATE_FRAME) != 0;

        if (should_render) {
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
                // CAOpenGLLayer already invokes this draw on its display-driven render thread.
                // During live resize, waiting again for libmpv's target time can miss the
                // current Core Animation transaction and make otherwise smooth window motion
                // advance in visible steps. Keep libmpv's normal A/V scheduling everywhere
                // else and only remove that duplicate wait while AppKit is resizing the view.
                int block_for_target_time = atomic_load_explicit(
                    &renderer->live_resize_active,
                    memory_order_acquire
                ) ? 0 : 1;
                mpv_render_param parameters[] = {
                    {MPV_RENDER_PARAM_OPENGL_FBO, &target},
                    {MPV_RENDER_PARAM_FLIP_Y, &flip_y},
                    {MPV_RENDER_PARAM_DEPTH, &depth},
                    {MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME, &block_for_target_time},
                    {MPV_RENDER_PARAM_INVALID, NULL},
                };
                (void)renderer->api.render(renderer->render_context, parameters);
                CGLFlushDrawable(context);
                renderer->api.report_swap(renderer->render_context);
            }
        }
    }
    pthread_mutex_unlock(&renderer->render_mutex);
    renderer_release(renderer);
}

- (void)requestFrame {
    KMPMpvNativeRenderer* renderer = [self retainedRenderer];
    if (renderer &&
        !atomic_load_explicit(&renderer->shutting_down, memory_order_acquire)) {
        atomic_store_explicit(&renderer->redraw_requested, true, memory_order_release);
    }
    renderer_release(renderer);
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
    [_videoLayer removeFromSuperlayer];
    [_videoLayer release];
    _videoLayer = [videoLayer retain];
    [self setWantsLayer:YES];
    [[self layer] setMasksToBounds:YES];
    if (_videoLayer) [[self layer] addSublayer:_videoLayer];
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

- (void)viewWillStartLiveResize {
    [super viewWillStartLiveResize];
    [self beginLiveResizeIfNeeded];
    [self refreshNativeGeometry];
}

- (void)viewDidEndLiveResize {
    [super viewDidEndLiveResize];
    [self endLiveResizeIfNeeded];
}

- (void)windowColorOrGeometryChanged:(NSNotification*)notification {
    (void)notification;
    [self refreshNativeFrameAndGeometry];
}

- (void)refreshNativeFrameAndGeometry {
    // Nucleus owns the NSView frame. The backend only updates its rendering layer.
    [self refreshNativeGeometry];
}

- (void)beginLiveResizeIfNeeded {
    if (_liveResizeActive || !_videoLayer) return;
    _liveResizeActive = YES;
    KMPMpvNativeRenderer* renderer = [_videoLayer retainedRenderer];
    if (renderer) {
        atomic_store_explicit(&renderer->live_resize_active, true, memory_order_release);
        renderer_release(renderer);
    }
    _liveResizeRenderSize = [_videoLayer bounds].size;
    if (_liveResizeRenderSize.width <= 0.0 || _liveResizeRenderSize.height <= 0.0) {
        _liveResizeRenderSize = [self bounds].size;
    }
}

- (void)endLiveResizeIfNeeded {
    if (!_liveResizeActive) return;
    _liveResizeActive = NO;
    KMPMpvNativeRenderer* renderer = [_videoLayer retainedRenderer];
    if (renderer) {
        atomic_store_explicit(&renderer->live_resize_active, false, memory_order_release);
        renderer_release(renderer);
    }
    _liveResizeRenderSize = NSZeroSize;
    [self refreshNativeGeometry];
}

- (void)refreshNativeGeometry {
    if (!_videoLayer) return;
    if ([self inLiveResize]) [self beginLiveResizeIfNeeded];

    NSRect bounds = [self bounds];
    CGFloat scale = [[self window] backingScaleFactor];
    if (scale <= 0.0) scale = 1.0;

    [CATransaction begin];
    [CATransaction setDisableActions:YES];
    [_videoLayer setAnchorPoint:CGPointMake(0.5, 0.5)];
    [_videoLayer setPosition:CGPointMake(NSMidX(bounds), NSMidY(bounds))];
    if (_liveResizeActive &&
        _liveResizeRenderSize.width > 0.0 &&
        _liveResizeRenderSize.height > 0.0) {
        // Keep libmpv's OpenGL drawable stable while AppKit is delivering intermediate resize
        // steps. Core Animation scales the most recent surface, so playback keeps presenting
        // without reallocating and rerendering a different FBO for every mouse movement.
        [_videoLayer setBounds:CGRectMake(
            0.0,
            0.0,
            _liveResizeRenderSize.width,
            _liveResizeRenderSize.height
        )];
        CGFloat scale_x = bounds.size.width / _liveResizeRenderSize.width;
        CGFloat scale_y = bounds.size.height / _liveResizeRenderSize.height;
        [_videoLayer setTransform:CATransform3DMakeScale(scale_x, scale_y, 1.0)];
    } else {
        [_videoLayer setTransform:CATransform3DIdentity];
        [_videoLayer setBounds:CGRectMake(0.0, 0.0, bounds.size.width, bounds.size.height)];
        [_videoLayer setContentsScale:scale];
    }
    [CATransaction commit];

    KMPMpvNativeRenderer* renderer = [_videoLayer retainedRenderer];
    if (renderer) {
        apply_output_color_space(renderer);
        renderer_release(renderer);
    }
    if (!_liveResizeActive) [_videoLayer requestFrame];
}

@end


typedef struct {
    KMPMpvNativeRenderer* renderer;
    BOOL created;
} KMPMpvCreateContext;

static void create_renderer_on_main(void* raw_context) {
    KMPMpvCreateContext* context = (KMPMpvCreateContext*)raw_context;
    @autoreleasepool {
        if (!context || !context->renderer) return;
        KMPMpvNativeRenderer* renderer = context->renderer;

        if (!choose_pixel_format(&renderer->pixel_format, &renderer->buffer_depth)) return;
        if (CGLCreateContext(renderer->pixel_format, NULL, &renderer->gl_context) != kCGLNoError ||
            !renderer->gl_context) {
            return;
        }
        // CAOpenGLLayer already schedules draw callbacks against the display. Waiting for a
        // second OpenGL swap interval here serializes Core Animation and libmpv during live
        // resize, which is visible as a staircase. Flush immediately and let CA present it.
        GLint swap_interval = 0;
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
            [[KMPMpvVideoView alloc] initWithFrame:NSMakeRect(0.0, 0.0, 1.0, 1.0)];
        renderer->layer = [layer retain];
        renderer->view = [view retain];
        // Nucleus is the only frame writer. AppKit autoresizing would race its deferred
        // interop transaction during live resize and briefly apply a different rectangle.
        [view setAutoresizingMask:NSViewNotSizable];
        [view setVideoLayer:layer];
        [view setWantsExtendedDynamicRangeOpenGLSurface:YES];
        apply_output_color_space(renderer);
        renderer->api.set_update_callback(
            renderer->render_context,
            renderer_update_callback,
            renderer
        );
        [view refreshNativeGeometry];
        context->created = YES;
        [layer release];
        [view release];
    }
}

static void detach_renderer_on_main(void* raw_renderer) {
    KMPMpvNativeRenderer* renderer = (KMPMpvNativeRenderer*)raw_renderer;
    if (!renderer) return;
    @autoreleasepool {
        atomic_store_explicit(&renderer->shutting_down, true, memory_order_release);
        pthread_mutex_lock(&renderer->render_mutex);
        if (renderer->render_context) {
            CGLSetCurrentContext(renderer->gl_context);
            renderer->api.set_update_callback(renderer->render_context, NULL, NULL);
            renderer->api.free(renderer->render_context);
            renderer->render_context = NULL;
        }
        pthread_mutex_unlock(&renderer->render_mutex);

        KMPMpvOpenGLLayer* layer = renderer->layer;
        KMPMpvVideoView* view = renderer->view;
        renderer->layer = nil;
        renderer->view = nil;
        [layer setRenderer:NULL];
        [view setVideoLayer:nil];
        // Nucleus owns the native hierarchy and removes this child in its queued detach
        // transaction. Keeping the superview's retain until then also keeps older queued
        // setFrame transactions from dereferencing a freed NSView.

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
    if (!context || !context->renderer ||
        atomic_load_explicit(&context->renderer->shutting_down, memory_order_acquire)) {
        return;
    }
    context->renderer->color_mode = context->color_mode;
    apply_output_color_space(context->renderer);
    [context->renderer->layer requestFrame];
}

static void request_redraw_on_main(void* raw_renderer) {
    KMPMpvNativeRenderer* renderer = (KMPMpvNativeRenderer*)raw_renderer;
    if (renderer &&
        !atomic_load_explicit(&renderer->shutting_down, memory_order_acquire)) {
        [renderer->layer requestFrame];
    }
}

JNIEXPORT jlong JNICALL
Java_io_github_kdroidfilter_composemediaplayer_mpv_MpvMacNativeBridge_nCreateRenderer(
    JNIEnv* environment,
    jclass bridge_class,
    jlong raw_mpv_handle,
    jstring library_load_name,
    jint color_mode
) {
    (void)bridge_class;
    if (!environment || !library_load_name || raw_mpv_handle == 0) return 0;

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
    atomic_init(&renderer->shutting_down, false);
    atomic_init(&renderer->update_pending, false);
    atomic_init(&renderer->redraw_requested, true);
    atomic_init(&renderer->live_resize_active, false);
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

    KMPMpvCreateContext context = {
        .renderer = renderer,
        .created = NO,
    };
    run_on_appkit_main_sync(create_renderer_on_main, &context);
    if (!context.created) {
        run_on_appkit_main_sync(detach_renderer_on_main, renderer);
        renderer_release(renderer);
        return 0;
    }
    return (jlong)(uintptr_t)renderer;
}

JNIEXPORT jlong JNICALL
Java_io_github_kdroidfilter_composemediaplayer_mpv_MpvMacNativeBridge_nGetViewHandle(
    JNIEnv* environment,
    jclass bridge_class,
    jlong native_renderer
) {
    (void)environment;
    (void)bridge_class;
    KMPMpvNativeRenderer* renderer = (KMPMpvNativeRenderer*)(uintptr_t)native_renderer;
    if (!renderer ||
        atomic_load_explicit(&renderer->shutting_down, memory_order_acquire) ||
        !renderer->view) {
        return 0;
    }
    return (jlong)(uintptr_t)renderer->view;
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
