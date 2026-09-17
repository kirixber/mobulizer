package com.mobulizer.visualizer;

import android.content.Context;
import android.opengl.GLSurfaceView;

public class VisualizerView extends GLSurfaceView {

    private final VisualizerRenderer renderer;

    public VisualizerView(Context context) {
        super(context);

        // Lightest useful surface: RGB888, no depth, no stencil.
        setEGLConfigChooser(8, 8, 8, 0, 0, 0);
        setEGLContextClientVersion(2);

        // Don't pay for a GL context rebuild on every pause/resume.
        setPreserveEGLContextOnPause(true);

        renderer = new VisualizerRenderer(context);
        setRenderer(renderer);

        // vsync-paced rendering: eglSwapBuffers blocks at 60 Hz,
        // no Thread.sleep guessing needed.
        setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    public VisualizerRenderer getRenderer() {
        return renderer;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // Frees the UDP port when the activity is destroyed, so a
        // relaunch doesn't die on a BindException.
        renderer.shutdown();
    }
}