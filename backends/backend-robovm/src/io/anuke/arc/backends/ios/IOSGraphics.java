package io.anuke.arc.backends.ios;

import io.anuke.arc.Application;
import io.anuke.arc.ApplicationListener;
import io.anuke.arc.Core;
import io.anuke.arc.Graphics;
import io.anuke.arc.Graphics.Cursor.SystemCursor;
import io.anuke.arc.backends.ios.custom.HWMachine;
import io.anuke.arc.collection.Array;
import io.anuke.arc.graphics.GL20;
import io.anuke.arc.graphics.GL30;
import io.anuke.arc.graphics.Pixmap;
import io.anuke.arc.graphics.glutils.GLVersion;
import io.anuke.arc.util.Log;
import org.robovm.apple.coregraphics.CGRect;
import org.robovm.apple.foundation.NSObject;
import org.robovm.apple.glkit.*;
import org.robovm.apple.opengles.EAGLContext;
import org.robovm.apple.opengles.EAGLRenderingAPI;
import org.robovm.apple.uikit.UIEvent;
import org.robovm.apple.uikit.UIInterfaceOrientation;
import org.robovm.apple.uikit.UIInterfaceOrientationMask;
import org.robovm.apple.uikit.UIRectEdge;
import org.robovm.objc.Selector;
import org.robovm.objc.annotation.BindSelector;
import org.robovm.objc.annotation.Method;
import org.robovm.rt.bro.annotation.Callback;
import org.robovm.rt.bro.annotation.Pointer;

import java.lang.reflect.Proxy;

public class IOSGraphics extends Graphics{
    private static final String tag = "[IOSGraphics] ";

    IOSApplication app;
    IOSInput input;
    GL20 gl20;
    int width;
    int height;
    long lastFrameTime;
    float deltaTime;
    long framesStart;
    int frames;
    int fps;
    BufferFormat bufferFormat;
    String extensions;
    volatile boolean appPaused;
    IOSApplicationConfiguration config;
    EAGLContext context;
    GLVersion glVersion;
    GLKView view;
    IOSUIViewController viewController;
    boolean created = false;
    private float ppiX;
    private float ppiY;
    private float ppcX;
    private float ppcY;
    private float density;
    private long frameId = -1;
    private boolean isContinuous = true;
    private boolean isFrameRequested = true;

    public IOSGraphics(float scale, IOSApplication app, IOSApplicationConfiguration config, IOSInput input){
        this.config = config;

        IOSGraphicsDelegate gdel = new IOSGraphicsDelegate();

        final CGRect bounds = app.getBounds();
        // setup view and OpenGL
        width = (int)bounds.getWidth();
        height = (int)bounds.getHeight();

        context = new EAGLContext(EAGLRenderingAPI.OpenGLES2);
        gl20 = makeGL();

        view = new GLKView(new CGRect(0, 0, bounds.getWidth(), bounds.getHeight()), context){
            @Method(selector = "touchesBegan:withEvent:")
            public void touchesBegan(@Pointer long touches, UIEvent event){
                IOSGraphics.this.input.onTouch(touches);
            }

            @Method(selector = "touchesCancelled:withEvent:")
            public void touchesCancelled(@Pointer long touches, UIEvent event){
                IOSGraphics.this.input.onTouch(touches);
            }

            @Method(selector = "touchesEnded:withEvent:")
            public void touchesEnded(@Pointer long touches, UIEvent event){
                IOSGraphics.this.input.onTouch(touches);
            }

            @Method(selector = "touchesMoved:withEvent:")
            public void touchesMoved(@Pointer long touches, UIEvent event){
                IOSGraphics.this.input.onTouch(touches);
            }

            @Override
            public void draw(CGRect rect){
                IOSGraphics.this.draw(this, rect);
            }

        };
        view.setDelegate(gdel);
        view.setDrawableColorFormat(config.colorFormat);
        view.setDrawableDepthFormat(config.depthFormat);
        view.setDrawableStencilFormat(config.stencilFormat);
        view.setDrawableMultisample(config.multisample);
        view.setMultipleTouchEnabled(true);

        viewController = new IOSUIViewController(app, this);
        viewController.setView(view);
        viewController.setDelegate(gdel);
        viewController.setPreferredFramesPerSecond(config.preferredFramesPerSecond);

        this.app = app;
        this.input = input;

        int r , g, b, a, depth, stencil = 0, samples = 0;
        if(config.colorFormat == GLKViewDrawableColorFormat.RGB565){
            r = 5;
            g = 6;
            b = 5;
            a = 0;
        }else{
            r = g = b = a = 8;
        }
        if(config.depthFormat == GLKViewDrawableDepthFormat._16){
            depth = 16;
        }else if(config.depthFormat == GLKViewDrawableDepthFormat._24){
            depth = 24;
        }else{
            depth = 0;
        }
        if(config.stencilFormat == GLKViewDrawableStencilFormat._8){
            stencil = 8;
        }
        if(config.multisample == GLKViewDrawableMultisample._4X){
            samples = 4;
        }
        bufferFormat = new BufferFormat(r, g, b, a, depth, stencil, samples, false);

        String machineString = HWMachine.getMachineString();
        IOSDevice device = IOSDevice.getDevice(machineString);
        if(device == null) Log.err(tag + "Machine ID: " + machineString + " not found, please report to LibGDX");
        int ppi = device != null ? device.ppi : 163;
        density = device != null ? device.ppi / 160f : scale;
        ppiX = ppi;
        ppiY = ppi;
        ppcX = ppiX / 2.54f;
        ppcY = ppiY / 2.54f;
        Log.info(tag + "Display: ppi=" + ppi + ", density=" + density);

        // time + FPS
        lastFrameTime = System.nanoTime();
        framesStart = lastFrameTime;

        appPaused = false;
    }

    private IOSGL20 makeGL() {
        IOSGL20 rgl = new IOSGL20();
        return (IOSGL20) Proxy.newProxyInstance(
                this.getClass().getClassLoader(),
                new Class[]{IOSGL20.class},
                (proxy, method, args) -> {
                    Log.info("Invoking GL method '{0}'", method);
                    return method.invoke(rgl, args);
                });
    }


    public void resume(){
        if(!appPaused) return;
        appPaused = false;

        Array<ApplicationListener> listeners = app.listeners;
        synchronized(listeners){
            for(ApplicationListener listener : listeners){
                listener.resume();
            }
        }
    }

    public void pause(){
        if(appPaused) return;
        appPaused = true;

        Array<ApplicationListener> listeners = app.listeners;
        synchronized(listeners){
            for(ApplicationListener listener : listeners){
                listener.pause();
            }
        }
    }

    public void draw(GLKView view, CGRect rect){
        makeCurrent();
        // massive hack, GLKView resets the viewport on each draw call, so IOSGLES20
        // stores the last known viewport and we reset it here...
        gl20.glViewport(IOSGL20.x, IOSGL20.y, IOSGL20.width, IOSGL20.height);

        if(!created){
            gl20.glViewport(0, 0, width, height);

            String versionString = gl20.glGetString(GL20.GL_VERSION);
            String vendorString = gl20.glGetString(GL20.GL_VENDOR);
            String rendererString = gl20.glGetString(GL20.GL_RENDERER);
            glVersion = new GLVersion(Application.ApplicationType.iOS, versionString, vendorString, rendererString);

            for(ApplicationListener listener : app.listeners){
                listener.init();
                listener.resize(width, height);
            }
            created = true;
        }
        if(appPaused){
            return;
        }

        long time = System.nanoTime();
        deltaTime = (time - lastFrameTime) / 1000000000.0f;
        lastFrameTime = time;

        frames++;
        if(time - framesStart >= 1000000000L){
            framesStart = time;
            fps = frames;
            frames = 0;
        }

        input.processEvents();
        frameId++;
        for(ApplicationListener listener : app.listeners){
            listener.update();
        }
        input.processDevices();
    }

    void makeCurrent(){
        EAGLContext.setCurrentContext(context);
    }

    public void update(GLKViewController controller){
        makeCurrent();
        app.processRunnables();
        // pause the GLKViewController render loop if we are no longer continuous
        // and if we haven't requested a frame in the last loop iteration
        if(!isContinuous && !isFrameRequested){
            viewController.setPaused(true);
        }
        isFrameRequested = false;
    }

    public void willPause(GLKViewController controller, boolean pause){ }

    @Override
    public GL20 getGL20(){
        return gl20;
    }

    @Override
    public void setGL20(GL20 gl20){
        this.gl20 = gl20;
        Core.gl = gl20;
        Core.gl20 = gl20;
    }

    @Override
    public boolean isGL30Available(){
        return false;
    }

    @Override
    public GL30 getGL30(){
        return null;
    }

    @Override
    public void setGL30(GL30 gl30){

    }

    @Override
    public int getWidth(){
        return width;
    }

    @Override
    public int getHeight(){
        return height;
    }

    @Override
    public int getBackBufferWidth(){
        return width;
    }

    @Override
    public int getBackBufferHeight(){
        return height;
    }

    @Override
    public float getDeltaTime(){
        return deltaTime;
    }

    @Override
    public float getRawDeltaTime(){
        return deltaTime;
    }

    @Override
    public int getFramesPerSecond(){
        return fps;
    }

    @Override
    public GLVersion getGLVersion(){
        return glVersion;
    }

    @Override
    public float getPpiX(){
        return ppiX;
    }

    @Override
    public float getPpiY(){
        return ppiY;
    }

    @Override
    public float getPpcX(){
        return ppcX;
    }

    @Override
    public float getPpcY(){
        return ppcY;
    }

    @Override
    public float getDensity(){
        return density;
    }

    @Override
    public boolean supportsDisplayModeChange(){
        return false;
    }

    @Override
    public DisplayMode[] getDisplayModes(){
        return new DisplayMode[]{getDisplayMode()};
    }

    @Override
    public DisplayMode getDisplayMode(){
        return new IOSDisplayMode(getWidth(), getHeight(), config.preferredFramesPerSecond, bufferFormat.r + bufferFormat.g
        + bufferFormat.b + bufferFormat.a);
    }

    @Override
    public Monitor getPrimaryMonitor(){
        return new IOSMonitor(0, 0, "Primary Monitor");
    }

    @Override
    public Monitor getMonitor(){
        return getPrimaryMonitor();
    }

    @Override
    public Monitor[] getMonitors(){
        return new Monitor[]{getPrimaryMonitor()};
    }

    @Override
    public DisplayMode[] getDisplayModes(Monitor monitor){
        return getDisplayModes();
    }

    @Override
    public DisplayMode getDisplayMode(Monitor monitor){
        return getDisplayMode();
    }

    @Override
    public boolean setFullscreenMode(DisplayMode displayMode){
        return false;
    }

    @Override
    public boolean setWindowedMode(int width, int height){
        return false;
    }

    @Override
    public void setTitle(String title){
    }

    @Override
    public void setUndecorated(boolean undecorated){
    }

    @Override
    public void setResizable(boolean resizable){
    }

    @Override
    public void setVSync(boolean vsync){
    }

    @Override
    public BufferFormat getBufferFormat(){
        return bufferFormat;
    }

    @Override
    public boolean supportsExtension(String extension){
        if(extensions == null) extensions = Core.gl.glGetString(GL20.GL_EXTENSIONS);
        return extensions.contains(extension);
    }

    @Override
    public boolean isContinuousRendering(){
        return isContinuous;
    }

    @Override
    public void setContinuousRendering(boolean isContinuous){
        if(isContinuous != this.isContinuous){
            this.isContinuous = isContinuous;
            // start the GLKViewController if we go from non-continuous -> continuous
            if(isContinuous) viewController.setPaused(false);
        }
    }

    @Override
    public void requestRendering(){
        isFrameRequested = true;
        // start the GLKViewController if we are in non-continuous mode
        // (we should already be started in continuous mode)
        if(!isContinuous) viewController.setPaused(false);
    }

    @Override
    public boolean isFullscreen(){
        return true;
    }

    @Override
    public long getFrameId(){
        return frameId;
    }

    @Override
    public Cursor newCursor(Pixmap pixmap, int xHotspot, int yHotspot){
        return null;
    }

    @Override
    public void setCursor(Cursor cursor){
    }

    @Override
    public void setSystemCursor(SystemCursor systemCursor){
    }

    static class IOSUIViewController extends GLKViewController{
        final IOSApplication app;
        final IOSGraphics graphics;
        boolean created = false;

        IOSUIViewController(IOSApplication app, IOSGraphics graphics){
            this.app = app;
            this.graphics = graphics;
        }

        @Callback
        @BindSelector("shouldAutorotateToInterfaceOrientation:")
        private static boolean shouldAutorotateToInterfaceOrientation(IOSUIViewController self, Selector sel,
                                                                      UIInterfaceOrientation orientation){
            return self.shouldAutorotateToInterfaceOrientation(orientation);
        }

        @Override
        public void viewWillAppear(boolean arg0){
            super.viewWillAppear(arg0);
            // start GLKViewController even though we may only draw a single frame
            // (we may be in non-continuous mode)
            setPaused(false);
        }

        @Override
        public void viewDidAppear(boolean animated){
            if(app.viewControllerListener != null) app.viewControllerListener.viewDidAppear(animated);
        }

        @Override
        public UIInterfaceOrientationMask getSupportedInterfaceOrientations(){
            long mask = 0;
            if(app.config.orientationLandscape){
                mask |= ((1 << UIInterfaceOrientation.LandscapeLeft.value()) | (1 << UIInterfaceOrientation.LandscapeRight.value()));
            }
            if(app.config.orientationPortrait){
                mask |= ((1 << UIInterfaceOrientation.Portrait.value()) | (1 << UIInterfaceOrientation.PortraitUpsideDown.value()));
            }
            return new UIInterfaceOrientationMask(mask);
        }

        @Override
        public boolean shouldAutorotate(){
            return true;
        }

        public boolean shouldAutorotateToInterfaceOrientation(UIInterfaceOrientation orientation){
            // we return "true" if we support the orientation
            switch(orientation){
                case LandscapeLeft:
                case LandscapeRight:
                    return app.config.orientationLandscape;
                default:
                    // assume portrait
                    return app.config.orientationPortrait;
            }
        }



        @Override
        public UIRectEdge getPreferredScreenEdgesDeferringSystemGestures(){
            return app.config.screenEdgesDeferringSystemGestures;
        }

        @Override
        public void viewDidLayoutSubviews(){
            super.viewDidLayoutSubviews();
            // get the view size and update graphics
            CGRect bounds = app.getBounds();
            graphics.width = (int)bounds.getWidth();
            graphics.height = (int)bounds.getHeight();
            graphics.makeCurrent();
            if(graphics.created){
                for(ApplicationListener list : app.listeners){
                    list.resize(graphics.width, graphics.height);
                }
            }
        }

        @Override
        public boolean prefersHomeIndicatorAutoHidden(){
            return app.config.hideHomeIndicator;
        }
    }

    class IOSGraphicsDelegate extends NSObject implements GLKViewDelegate, GLKViewControllerDelegate{
        @Override
        public void update(GLKViewController glkViewController){
            IOSGraphics.this.update(glkViewController);
        }

        @Override
        public void willPause(GLKViewController glkViewController, boolean b){
            IOSGraphics.this.willPause(glkViewController, b);
        }

        @Override
        public void draw(GLKView glkView, CGRect cgRect){
            IOSGraphics.this.draw(glkView, cgRect);
        }
    }

    static class IOSUIView extends GLKView{

        public IOSUIView(CGRect frame, EAGLContext context){
            super(frame, context);
        }
    }

    private class IOSDisplayMode extends DisplayMode{
        protected IOSDisplayMode(int width, int height, int refreshRate, int bitsPerPixel){
            super(width, height, refreshRate, bitsPerPixel);
        }
    }

    private class IOSMonitor extends Monitor{
        protected IOSMonitor(int virtualX, int virtualY, String name){
            super(virtualX, virtualY, name);
        }
    }
}
