package com.binish.videoeditor.utils

import android.opengl.GLES20
import android.view.Surface
import com.binish.videoeditor.grafika.gles.EglCore
import com.binish.videoeditor.grafika.gles.WindowSurface

object SurfaceUtils {
    fun clearSurface(surface: Surface) {
        // We need to do this with OpenGL ES (*not* Canvas -- the "software render" bits
        // are sticky).  We can't stay connected to the Surface after we're done because
        // that'd prevent the video encoder from attaching.
        //
        // If the Surface is resized to be larger, the new portions will be black, so
        // clearing to something other than black may look weird unless we do the clear
        // post-resize.
        if(surface.isValid) {
            val eglCore = EglCore()
            val win = WindowSurface(eglCore, surface, false)
            win.makeCurrent()
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            win.swapBuffers()
            win.release()
            eglCore.release()
        }
    }
}