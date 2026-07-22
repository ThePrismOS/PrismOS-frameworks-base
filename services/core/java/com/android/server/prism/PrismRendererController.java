package com.android.server.prism;

import android.annotation.NonNull;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.opengl.EGL14;
import android.opengl.EGLExt;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.SparseArray;
import android.util.Log;
import android.view.IWindowManager;
import android.view.Surface;
import android.view.WindowManagerGlobal;

import com.android.server.prism.PrismCamera;
import com.android.server.prism.PrismVirtualDisplay;
import com.android.server.prism.PrismSkybox;
import com.android.server.prism.PrismPlane;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Owns the Prism renderer lifecycle and all GL state.
 */
public final class PrismRendererController {
    private static final long FRAME_DELAY_MS = 16L;
    private static final int TEXTURE_SIZE = 1024;
    private static final int HUD_TEXTURE_WIDTH = 1024;
    private static final int HUD_TEXTURE_HEIGHT = 576;
    private static final int SKYBOX_TEXTURE_SIZE = 1024;
    private static final int DEFAULT_VIRTUAL_DISPLAY_DENSITY = 320;
    private static final float[] DEFAULT_CLEAR_COLOR = new float[] {
            0.02f, 0.02f, 0.03f, 1.0f
    };

    private final Object mLock = new Object();
    private final SparseArray<PrismPlane> mPlanes = new SparseArray<>();
    private final HandlerThread mRenderThread = new HandlerThread("PrismRendererThread");
    private final Handler mRenderHandler;
    private final FloatBuffer mQuadBuffer;
    private final float[] mProjection = new float[16];
    private final float[] mView = new float[16];
    private final float[] mViewProjection = new float[16];
    private final float[] mScratchMvp = new float[16];
    private final float[] mHudMvp = new float[16];
    private final float[] mHudForward = new float[3];
    private final float[] mHudRight = new float[3];
    private final float[] mHudUp = new float[3];
    private final float[] mSkyboxColor = new float[4];
    private final PrismCamera mCamera = new PrismCamera();
    private final PrismSkybox mSkybox = new PrismSkybox();
    private final float[] mVirtualDisplayTexMatrix = new float[16];
    private final FloatBuffer mSkyboxBuffer;
    private final Context mContext;

    private Surface mSurface;
    private int mSurfaceWidth = 1;
    private int mSurfaceHeight = 1;
    private boolean mStarted;
    private boolean mRenderLoopScheduled;
    private boolean mRendererInitialized;
    private boolean mLastSwapSuccess;
    private long mFrameCount;
    private long mLastFrameDurationNanos;
    private long mLastFrameTimestampNanos;
    private String mGlVersion = "n/a";
    private String mGlVendor = "n/a";
    private String mGlRenderer = "n/a";
    private PrismVirtualDisplay mVirtualDisplay;
    private SurfaceTexture mVirtualDisplaySurfaceTexture;
    private Surface mVirtualDisplaySurface;
    private int mVirtualDisplayTextureId;
    private boolean mVirtualDisplayFrameAvailable;
    private boolean mSkyboxVisible;
    private float mSkyboxSize = 1.0f;
    private String mSkyboxMode = "solid";
    private String mSkyboxCubemap = "none";

    private EGLDisplay mEglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext mEglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface mEglSurface = EGL14.EGL_NO_SURFACE;
    private EGLConfig mEglConfig;
    private int mProgram;
    private int mPositionHandle;
    private int mTexCoordHandle;
    private int mMvpHandle;
    private int mSamplerHandle;
    private int mExternalProgram;
    private int mExternalPositionHandle;
    private int mExternalTexCoordHandle;
    private int mExternalMvpHandle;
    private int mExternalSamplerHandle;
    private int mExternalTexMatrixHandle;
    private int mVbo;
    private int mSkyboxVbo;
    private int mHudTextureId;
    private int mSkyboxTextureId;
    private boolean mSkyboxTextureDirty = true;
    private boolean mDebugOverlayEnabled = true;
    private boolean mLandscapeLocked;

    PrismRendererController(@NonNull android.content.Context context) {
        mContext = context;
        mRenderThread.start();
        mRenderHandler = new Handler(mRenderThread.getLooper());
        mQuadBuffer = createFloatBuffer(new float[] {
                -1.0f, -1.0f, 0.0f, 0.0f, 1.0f,
                 1.0f, -1.0f, 0.0f, 1.0f, 1.0f,
                -1.0f,  1.0f, 0.0f, 0.0f, 0.0f,
                 1.0f,  1.0f, 0.0f, 1.0f, 0.0f,
        });
        mSkyboxBuffer = createFloatBuffer(new float[] {
                // Front
                -1.0f, -1.0f,  1.0f, 0.0f, 1.0f,
                 1.0f, -1.0f,  1.0f, 1.0f, 1.0f,
                -1.0f,  1.0f,  1.0f, 0.0f, 0.0f,
                 1.0f, -1.0f,  1.0f, 1.0f, 1.0f,
                 1.0f,  1.0f,  1.0f, 1.0f, 0.0f,
                -1.0f,  1.0f,  1.0f, 0.0f, 0.0f,
                // Back
                 1.0f, -1.0f, -1.0f, 0.0f, 1.0f,
                -1.0f, -1.0f, -1.0f, 1.0f, 1.0f,
                 1.0f,  1.0f, -1.0f, 0.0f, 0.0f,
                -1.0f, -1.0f, -1.0f, 1.0f, 1.0f,
                -1.0f,  1.0f, -1.0f, 1.0f, 0.0f,
                 1.0f,  1.0f, -1.0f, 0.0f, 0.0f,
                // Left
                -1.0f, -1.0f, -1.0f, 0.0f, 1.0f,
                -1.0f, -1.0f,  1.0f, 1.0f, 1.0f,
                -1.0f,  1.0f, -1.0f, 0.0f, 0.0f,
                -1.0f, -1.0f,  1.0f, 1.0f, 1.0f,
                -1.0f,  1.0f,  1.0f, 1.0f, 0.0f,
                -1.0f,  1.0f, -1.0f, 0.0f, 0.0f,
                // Right
                 1.0f, -1.0f,  1.0f, 0.0f, 1.0f,
                 1.0f, -1.0f, -1.0f, 1.0f, 1.0f,
                 1.0f,  1.0f,  1.0f, 0.0f, 0.0f,
                 1.0f, -1.0f, -1.0f, 1.0f, 1.0f,
                 1.0f,  1.0f, -1.0f, 1.0f, 0.0f,
                 1.0f,  1.0f,  1.0f, 0.0f, 0.0f,
                // Top
                -1.0f,  1.0f,  1.0f, 0.0f, 1.0f,
                 1.0f,  1.0f,  1.0f, 1.0f, 1.0f,
                -1.0f,  1.0f, -1.0f, 0.0f, 0.0f,
                 1.0f,  1.0f,  1.0f, 1.0f, 1.0f,
                 1.0f,  1.0f, -1.0f, 1.0f, 0.0f,
                -1.0f,  1.0f, -1.0f, 0.0f, 0.0f,
                // Bottom
                -1.0f, -1.0f, -1.0f, 0.0f, 1.0f,
                 1.0f, -1.0f, -1.0f, 1.0f, 1.0f,
                -1.0f, -1.0f,  1.0f, 0.0f, 0.0f,
                 1.0f, -1.0f, -1.0f, 1.0f, 1.0f,
                 1.0f, -1.0f,  1.0f, 1.0f, 0.0f,
                -1.0f, -1.0f,  1.0f, 0.0f, 0.0f,
        });
        Matrix.setIdentityM(mHudMvp, 0);
        Matrix.setIdentityM(mVirtualDisplayTexMatrix, 0);
        System.arraycopy(DEFAULT_CLEAR_COLOR, 0, mSkyboxColor, 0, mSkyboxColor.length);
        mCamera.setPosition(0.0f, 0.0f, 4.0f);
        mCamera.lookAt(0.0f, 0.0f, 0.0f);
    }

    int createPlane(@NonNull String name, @NonNull float[] modelMatrix,
            @NonNull PrismTextureSource textureSource) {
        synchronized (mLock) {
            int planeId = nextPlaneIdLocked();
            mPlanes.put(planeId, new PrismPlane(planeId, name, modelMatrix, textureSource));
            postRenderLocked();
            return planeId;
        }
    }

    void destroyPlane(int planeId) {
        synchronized (mLock) {
            PrismPlane plane = mPlanes.get(planeId);
            if (plane == null) {
                return;
            }
            releasePlaneTextureLocked(plane);
            mPlanes.remove(planeId);
            postRenderLocked();
        }
    }

    void updatePlaneTransform(int planeId, @NonNull float[] modelMatrix) {
        synchronized (mLock) {
            PrismPlane plane = mPlanes.get(planeId);
            if (plane == null) {
                return;
            }
            plane.applyModelMatrix(modelMatrix);
            postRenderLocked();
        }
    }

    void updatePlaneTexture(int planeId, @NonNull PrismTextureSource textureSource) {
        synchronized (mLock) {
            PrismPlane plane = mPlanes.get(planeId);
            if (plane == null) {
                return;
            }
            plane.textureSource = textureSource;
            plane.textureDirty = true;
            postRenderLocked();
        }
    }

    // Camera control methods for Binder interface
    public void moveCamera(float x, float y, float z) {
        synchronized (mLock) {
            mCamera.move(x, y, z);
            postRenderLocked();
        }
    }

    public void moveWorldCamera(float x, float y, float z) {
        synchronized (mLock) {
            mCamera.translateWorld(x, y, z);
            postRenderLocked();
        }
    }

    public void setCameraYaw(float angle) {
        synchronized (mLock) {
            mCamera.setYaw(angle);
            postRenderLocked();
        }
    }

    public void setCameraPitch(float angle) {
        synchronized (mLock) {
            mCamera.setPitch(angle);
            postRenderLocked();
        }
    }

    public void setCameraRoll(float angle) {
        synchronized (mLock) {
            mCamera.setRoll(angle);
            postRenderLocked();
        }
    }

    public void setCameraPosition(float x, float y, float z) {
        synchronized (mLock) {
            mCamera.setPosition(x, y, z);
            postRenderLocked();
        }
    }

    public void setCameraRotation(float yaw, float pitch, float roll) {
        synchronized (mLock) {
            mCamera.setRotation(yaw, pitch, roll);
            postRenderLocked();
        }
    }

    public void lookAtCamera(float x, float y, float z) {
        synchronized (mLock) {
            mCamera.lookAt(x, y, z);
            postRenderLocked();
        }
    }

    public void resetCamera() {
        synchronized (mLock) {
            mCamera.reset();
            postRenderLocked();
        }
    }

    public void setCameraFov(float fov) {
        synchronized (mLock) {
            mCamera.setFov(fov);
            postRenderLocked();
        }
    }

    public void setCameraNear(float near) {
        synchronized (mLock) {
            mCamera.setNear(near);
            postRenderLocked();
        }
    }

    public void setCameraFar(float far) {
        synchronized (mLock) {
            mCamera.setFar(far);
            postRenderLocked();
        }
    }

    public float getCameraNear() {
        synchronized (mLock) {
            return mCamera.getNear();
        }
    }

    public float getCameraFar() {
        synchronized (mLock) {
            return mCamera.getFar();
        }
    }

    public String cameraDump() {
        synchronized (mLock) {
            final float[] position = mCamera.getPosition();
            final StringBuilder sb = new StringBuilder();
            sb.append("=== Prism Camera Dump ===\n");
            sb.append("Position: [").append(position[0]).append(", ")
                    .append(position[1]).append(", ").append(position[2]).append("]\n");
            sb.append("Rotation: [yaw=").append(mCamera.getYaw()).append(", pitch=")
                    .append(mCamera.getPitch()).append(", roll=").append(mCamera.getRoll())
                    .append("]\n");
            sb.append("Projection: [fov=").append(mCamera.getFov()).append(", aspect=")
                    .append(mCamera.getAspect()).append(", near=").append(mCamera.getNear())
                    .append(", far=").append(mCamera.getFar()).append("]\n");
            return sb.toString();
        }
    }

    public String rendererInfo() {
        synchronized (mLock) {
            final StringBuilder sb = new StringBuilder();
            sb.append("=== Prism Renderer Info ===\n");
            sb.append("Renderer initialized: ").append(mRendererInitialized).append('\n');
            sb.append("Surface attached: ").append(mSurface != null).append('\n');
            sb.append("EGL display: ").append(eglStatus(mEglDisplay, EGL14.EGL_NO_DISPLAY))
                    .append('\n');
            sb.append("EGL context: ").append(eglStatus(mEglContext, EGL14.EGL_NO_CONTEXT))
                    .append('\n');
            sb.append("EGL surface: ").append(eglStatus(mEglSurface, EGL14.EGL_NO_SURFACE))
                    .append('\n');
            sb.append("GL version: ").append(mGlVersion).append('\n');
            sb.append("GL vendor: ").append(mGlVendor).append('\n');
            sb.append("GL renderer: ").append(mGlRenderer).append('\n');
            sb.append("Surface size: ").append(mSurfaceWidth).append('x').append(mSurfaceHeight)
                    .append('\n');
            sb.append("Render surfaces: ").append(mPlanes.size()).append('\n');
            for (int i = 0; i < mPlanes.size(); i++) {
                PrismPlane plane = mPlanes.valueAt(i);
                sb.append("  PrismPlane ").append(plane.id).append(": ")
                        .append(plane.name).append('\n');
            }
            final float[] position = mCamera.getPosition();
            sb.append("Camera position: [").append(position[0]).append(", ")
                    .append(position[1]).append(", ").append(position[2]).append("]\n");
            sb.append("Camera rotation: [yaw=").append(mCamera.getYaw()).append(", pitch=")
                    .append(mCamera.getPitch()).append(", roll=").append(mCamera.getRoll())
                    .append("]\n");
            sb.append("Render statistics:\n");
            sb.append("  Frames rendered: ").append(mFrameCount).append('\n');
            sb.append("  Last frame duration (ns): ").append(mLastFrameDurationNanos).append('\n');
            sb.append("  Last frame timestamp (ns): ").append(mLastFrameTimestampNanos)
                    .append('\n');
            sb.append("  Last swap success: ").append(mLastSwapSuccess).append('\n');
            return sb.toString();
        }
    }

    public void rendererReload() {
        synchronized (mLock) {
            // Reset camera to default position
            mCamera.setPosition(0.0f, 0.0f, 4.0f);
            mCamera.lookAt(0.0f, 0.0f, 0.0f);
            // Mark all textures as dirty so they will be reloaded
            for (int i = 0; i < mPlanes.size(); i++) {
                PrismPlane plane = mPlanes.valueAt(i);
                plane.textureDirty = true;
            }
            postRenderLocked();
        }
    }

    public void rendererRecreate() {
        synchronized (mLock) {
            // Full restart: shutdown and reinitialize if we have a surface
            if (mSurface != null) {
                shutdown();
                // Note: We don't automatically restart here because the surface
                // lifecycle is managed by the SurfaceView. The renderer will be
                // restarted when the surface becomes available again via
                // onSurfaceAvailable.
                // For immediate restart, we could call onSurfaceAvailable again,
                // but that would require storing the surface dimensions.
                // Instead, we'll just note that the surface is still valid and
                // the renderer will restart when needed.
            } else {
                // No surface, just reset state
                mStarted = false;
                mRenderLoopScheduled = false;
                releaseEglLocked();
                releaseAllPlanesLocked();
                mPlanes.clear();
            }
        }
    }

    public String planeList() {
        synchronized (mLock) {
            final StringBuilder sb = new StringBuilder();
            sb.append("Planes:\n");
            if (mPlanes.size() == 0) {
                sb.append("(none)\n");
                return sb.toString();
            }
            for (int i = 0; i < mPlanes.size(); i++) {
                PrismPlane plane = mPlanes.valueAt(i);
                sb.append(plane.id).append("  ").append(plane.name).append('\n');
            }
            return sb.toString();
        }
    }

    public String planeDump(int planeId) {
        synchronized (mLock) {
            final StringBuilder sb = new StringBuilder();
            sb.append("=== Prism PrismPlane Dump ===\n");
            PrismPlane plane = mPlanes.get(planeId);
            if (plane == null) {
                sb.append("PrismPlane ").append(planeId).append(": not found\n");
                return sb.toString();
            }
            sb.append("PrismPlane ID: ").append(plane.id).append('\n');
            sb.append("Name: ").append(plane.name).append('\n');
            sb.append("Visible: ").append(plane.visible).append('\n');
            sb.append("Position: [").append(plane.posX).append(", ")
                    .append(plane.posY).append(", ").append(plane.posZ).append("]\n");
            sb.append("Rotation: [yaw=").append(plane.yaw).append(", pitch=")
                    .append(plane.pitch).append(", roll=").append(plane.roll).append("]\n");
            sb.append("Scale: [").append(plane.scaleX).append(", ")
                    .append(plane.scaleY).append(", ").append(plane.scaleZ).append("]\n");
            sb.append("Color: [").append(plane.color[0]).append(", ")
                    .append(plane.color[1]).append(", ").append(plane.color[2]).append(", ")
                    .append(plane.color[3]).append("]\n");
            sb.append("Texture dirty: ").append(plane.textureDirty).append('\n');
            sb.append("Texture ID: ").append(plane.textureId).append('\n');
            sb.append("Texture source: ")
                    .append(plane.textureSource != null ? plane.textureSource.getLabel()
                            : "null")
                    .append('\n');
            sb.append("Model matrix:\n");
            sb.append("  [").append(plane.modelMatrix[0]).append(", ")
                    .append(plane.modelMatrix[4]).append(", ")
                    .append(plane.modelMatrix[8]).append(", ")
                    .append(plane.modelMatrix[12]).append("]\n");
            sb.append("  [").append(plane.modelMatrix[1]).append(", ")
                    .append(plane.modelMatrix[5]).append(", ")
                    .append(plane.modelMatrix[9]).append(", ")
                    .append(plane.modelMatrix[13]).append("]\n");
            sb.append("  [").append(plane.modelMatrix[2]).append(", ")
                    .append(plane.modelMatrix[6]).append(", ")
                    .append(plane.modelMatrix[10]).append(", ")
                    .append(plane.modelMatrix[14]).append("]\n");
            sb.append("  [").append(plane.modelMatrix[3]).append(", ")
                    .append(plane.modelMatrix[7]).append(", ")
                    .append(plane.modelMatrix[11]).append(", ")
                    .append(plane.modelMatrix[15]).append("]\n");
            return sb.toString();
        }
    }

    public String planeDumpAll() {
        synchronized (mLock) {
        final StringBuilder sb = new StringBuilder();
        sb.append("=== PrismPlane ===\n");
        if (mPlanes.size() == 0) {
            sb.append("(none)\n");
            return sb.toString();
        }
            for (int i = 0; i < mPlanes.size(); i++) {
                final PrismPlane plane = mPlanes.valueAt(i);
                sb.append(buildPlaneDumpBodyLocked(plane));
            }
            return sb.toString();
        }
    }

    public String skyboxDump() {
        synchronized (mLock) {
            final StringBuilder sb = new StringBuilder();
            sb.append("=== Skybox ===\n");
            sb.append("Visible: ").append(mSkyboxVisible).append('\n');
            sb.append("Color: [").append(mSkyboxColor[0]).append(", ")
                    .append(mSkyboxColor[1]).append(", ").append(mSkyboxColor[2]).append(", ")
                    .append(mSkyboxColor[3]).append("]\n");
            sb.append("Size: ").append(mSkyboxSize).append('\n');
            sb.append("Mode: ").append(mSkyboxMode).append('\n');
            sb.append("Cubemap: ").append(mSkyboxCubemap).append('\n');
            return sb.toString();
        }
    }

    public void setPlanePosition(int planeId, float x, float y, float z) {
        synchronized (mLock) {
            final PrismPlane plane = getPlaneLocked(planeId);
        if (plane == null) {
            return;
        }
            plane.setPosition(x, y, z);
            postRenderLocked();
        }
    }

    public void movePlane(int planeId, float dx, float dy, float dz) {
        synchronized (mLock) {
            final PrismPlane plane = getPlaneLocked(planeId);
            if (plane == null) {
                return;
            }
            plane.move(dx, dy, dz);
            postRenderLocked();
        }
    }

    public void setPlaneRotation(int planeId, float yaw, float pitch, float roll) {
        synchronized (mLock) {
            final PrismPlane plane = getPlaneLocked(planeId);
            if (plane == null) {
                return;
            }
            plane.setRotation(yaw, pitch, roll);
            postRenderLocked();
        }
    }

    public void setPlaneScale(int planeId, float sx, float sy, float sz) {
        synchronized (mLock) {
            final PrismPlane plane = getPlaneLocked(planeId);
            if (plane == null) {
                return;
            }
            plane.setScale(sx, sy, sz);
            postRenderLocked();
        }
    }

    public void setPlaneColor(int planeId, float r, float g, float b, float a) {
        synchronized (mLock) {
            final PrismPlane plane = getPlaneLocked(planeId);
            if (plane == null) {
                return;
            }
            plane.setColor(r, g, b, a);
            postRenderLocked();
        }
    }

    public void setPlaneVisible(int planeId, boolean visible) {
        synchronized (mLock) {
            final PrismPlane plane = getPlaneLocked(planeId);
            if (plane == null) {
                return;
            }
            plane.setVisible(visible);
            postRenderLocked();
        }
    }

    public void resetPlane(int planeId) {
        synchronized (mLock) {
            final PrismPlane plane = getPlaneLocked(planeId);
            if (plane == null) {
                return;
            }
            plane.reset();
            postRenderLocked();
        }
    }

    public void setSkyboxVisible(boolean visible) {
        synchronized (mLock) {
            mSkyboxVisible = visible;
            postRenderLocked();
        }
    }

    public void setSkyboxColor(float r, float g, float b, float a) {
        synchronized (mLock) {
            mSkyboxColor[0] = r;
            mSkyboxColor[1] = g;
            mSkyboxColor[2] = b;
            mSkyboxColor[3] = a;
            postRenderLocked();
        }
    }

    public void setSkyboxSize(float size) {
        synchronized (mLock) {
            mSkyboxSize = size;
            postRenderLocked();
        }
    }

    public void setSkyboxMode(String mode) {
        synchronized (mLock) {
            mSkyboxMode = mode;
            mSkyboxTextureDirty = true;
            postRenderLocked();
        }
    }

    public void setSkyboxCubemap(String cubemap) {
        synchronized (mLock) {
            mSkyboxCubemap = cubemap;
            mSkyboxTextureDirty = true;
            postRenderLocked();
        }
    }

    public void resetSkybox() {
        synchronized (mLock) {
            mSkyboxVisible = false;
            System.arraycopy(DEFAULT_CLEAR_COLOR, 0, mSkyboxColor, 0, mSkyboxColor.length);
            mSkyboxSize = 1.0f;
            mSkyboxMode = "solid";
            mSkyboxCubemap = "none";
            mSkyboxTextureDirty = true;
            postRenderLocked();
        }
    }

    public String virtualDisplayDump() {
        synchronized (mLock) {
            if (mVirtualDisplay == null) {
                return "=== Prism Display Dump ===\nCreated: false\nRunning: false\nWidth: 0\nHeight: 0\nDensity: 0\nDisplayId: -1\nSurface attached: false\nFrames received: 0\nLast frame timestamp: 0\n";
            }
            return mVirtualDisplay.dump();
        }
    }

    public String createVirtualDisplay() {
        final int width;
        final int height;
        synchronized (mLock) {
            width = mSurfaceWidth;
            height = mSurfaceHeight;
        }
        return runOnRenderThreadBlocking(() -> createVirtualDisplayLocked(width, height,
                DEFAULT_VIRTUAL_DISPLAY_DENSITY));
    }

    public String createVirtualDisplay(int width, int height, int densityDpi) {
        return runOnRenderThreadBlocking(() -> createVirtualDisplayLocked(width, height,
                densityDpi));
    }

    public String resizeVirtualDisplay(int width, int height) {
        return runOnRenderThreadBlocking(() -> resizeVirtualDisplayLocked(width, height));
    }

    public String enableVirtualDisplay() {
        return runOnRenderThreadBlocking(this::enableVirtualDisplayLocked);
    }

    public String disableVirtualDisplay() {
        return runOnRenderThreadBlocking(this::disableVirtualDisplayLocked);
    }

    public String destroyVirtualDisplay() {
        return runOnRenderThreadBlocking(this::destroyVirtualDisplayLocked);
    }

    private String runOnRenderThreadBlocking(StringOperation operation) {
        if (Looper.myLooper() == mRenderHandler.getLooper()) {
            synchronized (mLock) {
                return operation.run();
            }
        }

        final String[] result = new String[1];
        final CountDownLatch latch = new CountDownLatch(1);
        mRenderHandler.post(() -> {
            synchronized (mLock) {
                result[0] = operation.run();
            }
            latch.countDown();
        });
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) {
                return "Timed out waiting for renderer thread";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Interrupted while waiting for renderer thread";
        }
        return result[0] != null ? result[0] : "";
    }

    private void ensureVirtualDisplayTextureLocked() {
        if (mVirtualDisplaySurfaceTexture != null) {
            return;
        }
        if (mVirtualDisplayTextureId == 0) {
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            mVirtualDisplayTextureId = textures[0];
        }
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mVirtualDisplayTextureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);

        mVirtualDisplaySurfaceTexture = new SurfaceTexture(mVirtualDisplayTextureId);
        mVirtualDisplaySurfaceTexture.setDefaultBufferSize(mSurfaceWidth, mSurfaceHeight);
        mVirtualDisplaySurfaceTexture.setOnFrameAvailableListener(
                surfaceTexture -> {
                    synchronized (mLock) {
                        mVirtualDisplayFrameAvailable = true;
                        postRenderLocked();
                    }
                },
                mRenderHandler);
        mVirtualDisplaySurface = new Surface(mVirtualDisplaySurfaceTexture);
        Matrix.setIdentityM(mVirtualDisplayTexMatrix, 0);
        mVirtualDisplayFrameAvailable = false;
    }

    private void releaseVirtualDisplayTextureLocked() {
        mVirtualDisplayFrameAvailable = false;
        if (mVirtualDisplaySurfaceTexture != null) {
            try {
                mVirtualDisplaySurfaceTexture.release();
            } catch (Throwable ignored) {
                // Keep teardown resilient.
            }
            mVirtualDisplaySurfaceTexture = null;
        }
        if (mVirtualDisplaySurface != null) {
            try {
                mVirtualDisplaySurface.release();
            } catch (Throwable ignored) {
                // Keep teardown resilient.
            }
            mVirtualDisplaySurface = null;
        }
        if (mVirtualDisplayTextureId != 0) {
            GLES20.glDeleteTextures(1, new int[] { mVirtualDisplayTextureId }, 0);
            mVirtualDisplayTextureId = 0;
        }
        Matrix.setIdentityM(mVirtualDisplayTexMatrix, 0);
    }

    private void markSurfaceTexturePlanesDirtyLocked() {
        for (int i = 0; i < mPlanes.size(); i++) {
            final PrismPlane plane = mPlanes.valueAt(i);
            if (plane.textureSource != null
                    && plane.textureSource.getKind() == PrismTextureSource.KIND_SURFACE_TEXTURE) {
                plane.textureDirty = true;
            }
        }
    }

    private void consumeVirtualDisplayFrameLocked() {
        if (!mVirtualDisplayFrameAvailable || mVirtualDisplaySurfaceTexture == null) {
            return;
        }
        mVirtualDisplaySurfaceTexture.updateTexImage();
        mVirtualDisplaySurfaceTexture.getTransformMatrix(mVirtualDisplayTexMatrix);
        if (mVirtualDisplay != null) {
            mVirtualDisplay.onFrameReceived(mVirtualDisplaySurfaceTexture.getTimestamp());
        }
        mVirtualDisplayFrameAvailable = false;
    }

    private boolean hasLiveVirtualDisplayTextureLocked() {
        return mVirtualDisplay != null && mVirtualDisplay.isCreated()
                && mVirtualDisplaySurfaceTexture != null && mVirtualDisplayTextureId != 0;
    }

    private boolean isSurfaceTexturePlaneLocked(PrismPlane plane) {
        return plane.textureSource != null
                && plane.textureSource.getKind() == PrismTextureSource.KIND_SURFACE_TEXTURE;
    }

    private interface StringOperation {
        String run();
    }

    private String createVirtualDisplayLocked(int width, int height, int densityDpi) {
        ensureVirtualDisplayTextureLocked();
        if (mVirtualDisplaySurfaceTexture == null || mVirtualDisplaySurface == null) {
            return "Failed to create SurfaceTexture backing for VirtualDisplay";
        }

        if (mVirtualDisplay == null) {
            mVirtualDisplay = new PrismVirtualDisplay(mContext);
        }

        mVirtualDisplaySurfaceTexture.setDefaultBufferSize(Math.max(1, width),
                Math.max(1, height));
        final String error = mVirtualDisplay.create(mVirtualDisplaySurface,
                Math.max(1, width), Math.max(1, height), Math.max(1, densityDpi));
        if (error != null) {
            return error + "\n";
        }
        markSurfaceTexturePlanesDirtyLocked();
        postRenderLocked();
        return mVirtualDisplay.dump();
    }

    private String resizeVirtualDisplayLocked(int width, int height) {
        if (mVirtualDisplay == null) {
            return "VirtualDisplay is not created\n";
        }
        final String error = mVirtualDisplay.resize(Math.max(1, width), Math.max(1, height),
                Math.max(1, mVirtualDisplay.getDensityDpi()));
        if (error != null) {
            return error + "\n";
        }
        if (mVirtualDisplaySurfaceTexture != null) {
            mVirtualDisplaySurfaceTexture.setDefaultBufferSize(Math.max(1, width),
                    Math.max(1, height));
        }
        markSurfaceTexturePlanesDirtyLocked();
        postRenderLocked();
        return mVirtualDisplay.dump();
    }

    private String enableVirtualDisplayLocked() {
        if (mVirtualDisplay == null) {
            return "VirtualDisplay is not created\n";
        }
        final String error = mVirtualDisplay.enable();
        if (error != null) {
            return error + "\n";
        }
        postRenderLocked();
        return mVirtualDisplay.dump();
    }

    private String disableVirtualDisplayLocked() {
        if (mVirtualDisplay == null) {
            return "VirtualDisplay is not created\n";
        }
        final String error = mVirtualDisplay.disable();
        if (error != null) {
            return error + "\n";
        }
        postRenderLocked();
        return mVirtualDisplay.dump();
    }

    private String destroyVirtualDisplayLocked() {
        if (mVirtualDisplay != null) {
            mVirtualDisplay.destroy();
            mVirtualDisplay = null;
        }
        releaseVirtualDisplayTextureLocked();
        for (int i = 0; i < mPlanes.size(); i++) {
            final PrismPlane plane = mPlanes.valueAt(i);
            if (isSurfaceTexturePlaneLocked(plane)) {
                plane.textureId = 0;
                plane.textureDirty = true;
            }
        }
        markSurfaceTexturePlanesDirtyLocked();
        postRenderLocked();
        return virtualDisplayDump();
    }

    public void setDebugOverlayEnabled(boolean enabled) {
        synchronized (mLock) {
            mDebugOverlayEnabled = enabled;
            postRenderLocked();
        }
    }

    public boolean isDebugOverlayEnabled() {
        synchronized (mLock) {
            return mDebugOverlayEnabled;
        }
    }

    private PrismPlane getPlaneLocked(int planeId) {
        return mPlanes.get(planeId);
    }

    private String buildPlaneDumpBodyLocked(PrismPlane plane) {
        final StringBuilder sb = new StringBuilder();
        sb.append("PrismPlane ").append(plane.id).append(":\n");
        sb.append("  Name: ").append(plane.name).append('\n');
        sb.append("  Visible: ").append(plane.visible).append('\n');
        sb.append("  Position: [").append(plane.posX).append(", ")
                .append(plane.posY).append(", ").append(plane.posZ).append("]\n");
        sb.append("  Rotation: [yaw=").append(plane.yaw).append(", pitch=")
                .append(plane.pitch).append(", roll=").append(plane.roll).append("]\n");
        sb.append("  Scale: [").append(plane.scaleX).append(", ")
                .append(plane.scaleY).append(", ").append(plane.scaleZ).append("]\n");
        sb.append("  Color: [").append(plane.color[0]).append(", ")
                .append(plane.color[1]).append(", ").append(plane.color[2]).append(", ")
                .append(plane.color[3]).append("]\n");
        sb.append("  Texture source: ")
                .append(plane.textureSource != null ? plane.textureSource.getLabel() : "null")
                .append('\n');
        sb.append("  Texture dirty: ").append(plane.textureDirty).append('\n');
        sb.append("  Texture ID: ").append(plane.textureId).append('\n');
        return sb.toString();
    }

    void onSurfaceAvailable(@NonNull Surface surface, int width, int height) {
        synchronized (mLock) {
            mSurface = surface;
            mSurfaceWidth = Math.max(1, width);
            mSurfaceHeight = Math.max(1, height);
            mCamera.setAspect((float) mSurfaceWidth / (float) mSurfaceHeight);
            mCamera.setFov(58.0f);
            mStarted = true;
            postRenderLocked();
        }
    }

    void onSurfaceSizeChanged(int width, int height) {
        synchronized (mLock) {
            mSurfaceWidth = Math.max(1, width);
            mSurfaceHeight = Math.max(1, height);
            mCamera.setAspect((float) mSurfaceWidth / (float) mSurfaceHeight);
            mCamera.setFov(58.0f);
            postRenderLocked();
        }
    }

    void onSurfaceDestroyed() {
        synchronized (mLock) {
            mStarted = false;
            mRenderHandler.post(this::releaseOnRenderThreadLocked);
        }
    }

    void shutdown() {
        synchronized (mLock) {
            mStarted = false;
            mRenderHandler.post(() -> {
                synchronized (mLock) {
                    releaseAllPlanesLocked();
                    releaseEglLocked();
                }
                mRenderThread.quitSafely();
            });
        }
    }

    private int nextPlaneIdLocked() {
        int id = 1;
        while (mPlanes.get(id) != null) {
            id++;
        }
        return id;
    }

    private void postRenderLocked() {
        if (mRenderLoopScheduled || !mStarted) {
            return;
        }
        mRenderLoopScheduled = true;
        mRenderHandler.post(mRenderFrame);
    }

    private final Runnable mRenderFrame = new Runnable() {
        @Override
        public void run() {
            try {
                synchronized (mLock) {
                    mRenderLoopScheduled = false;
                    if (!mStarted || mSurface == null || mEglSurface == EGL14.EGL_NO_SURFACE) {
                        if (mStarted && mSurface != null && mEglDisplay == EGL14.EGL_NO_DISPLAY) {
                            ensureEglLocked();
                            postRenderLocked();
                        }
                        return;
                    }
                    drawFrameLocked();
                    if (mStarted) {
                        mRenderLoopScheduled = true;
                        mRenderHandler.postDelayed(this, FRAME_DELAY_MS);
                    }
                }
            } catch (RuntimeException e) {
                Log.e("PrismRenderer", "render loop failed", e);
                synchronized (mLock) {
                    mStarted = false;
                    releaseEglLocked();
                }
            }
        }
    };

    private void ensureEglLocked() {
        if (mEglDisplay != EGL14.EGL_NO_DISPLAY) {
            return;
        }

        mEglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (mEglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new IllegalStateException("Unable to acquire EGL display");
        }

        int[] version = new int[2];
        if (!EGL14.eglInitialize(mEglDisplay, version, 0, version, 1)) {
            throw new IllegalStateException("Unable to initialize EGL");
        }

        int[] configAttribs = new int[] {
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 16,
                EGL14.EGL_STENCIL_SIZE, 0,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(mEglDisplay, configAttribs, 0, configs, 0, 1,
                numConfigs, 0) || numConfigs[0] <= 0) {
            throw new IllegalStateException("Unable to choose EGL config");
        }
        mEglConfig = configs[0];

        int[] contextAttribs = new int[] {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
        };
        mEglContext = EGL14.eglCreateContext(mEglDisplay, mEglConfig, EGL14.EGL_NO_CONTEXT,
                contextAttribs, 0);
        if (mEglContext == null || mEglContext == EGL14.EGL_NO_CONTEXT) {
            throw new IllegalStateException("Unable to create EGL context");
        }

        mEglSurface = EGL14.eglCreateWindowSurface(mEglDisplay, mEglConfig, mSurface,
                new int[] { EGL14.EGL_NONE }, 0);
        if (mEglSurface == null || mEglSurface == EGL14.EGL_NO_SURFACE) {
            throw new IllegalStateException("Unable to create EGL window surface");
        }

        if (!EGL14.eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface, mEglContext)) {
            throw new IllegalStateException("Unable to make EGL current");
        }

        mGlVersion = safeGlString(GLES20.GL_VERSION);
        mGlVendor = safeGlString(GLES20.GL_VENDOR);
        mGlRenderer = safeGlString(GLES20.GL_RENDERER);
        initGlResourcesLocked();
        mRendererInitialized = true;
    }

    private void releaseEglLocked() {
        if (mEglDisplay == EGL14.EGL_NO_DISPLAY) {
            return;
        }
        releaseVirtualDisplayTextureLocked();
        if (mVbo != 0) {
            GLES20.glDeleteBuffers(1, new int[] { mVbo }, 0);
            mVbo = 0;
        }
        if (mSkyboxVbo != 0) {
            GLES20.glDeleteBuffers(1, new int[] { mSkyboxVbo }, 0);
            mSkyboxVbo = 0;
        }
        if (mProgram != 0) {
            GLES20.glDeleteProgram(mProgram);
            mProgram = 0;
        }
        if (mExternalProgram != 0) {
            GLES20.glDeleteProgram(mExternalProgram);
            mExternalProgram = 0;
        }
        if (mEglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(mEglDisplay, mEglSurface);
            mEglSurface = EGL14.EGL_NO_SURFACE;
        }
        if (mEglContext != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroyContext(mEglDisplay, mEglContext);
            mEglContext = EGL14.EGL_NO_CONTEXT;
        }
        if (mHudTextureId != 0) {
            GLES20.glDeleteTextures(1, new int[] { mHudTextureId }, 0);
            mHudTextureId = 0;
        }
        if (mSkyboxTextureId != 0) {
            GLES20.glDeleteTextures(1, new int[] { mSkyboxTextureId }, 0);
            mSkyboxTextureId = 0;
        }
        mSkyboxTextureDirty = true;
        for (int i = 0; i < mPlanes.size(); i++) {
            PrismPlane plane = mPlanes.valueAt(i);
            plane.textureId = 0;
            plane.textureDirty = true;
        }
        EGL14.eglTerminate(mEglDisplay);
        mEglDisplay = EGL14.EGL_NO_DISPLAY;
        mSurface = null;
        mRendererInitialized = false;
        mRenderLoopScheduled = false;
        mGlVersion = "n/a";
        mGlVendor = "n/a";
        mGlRenderer = "n/a";
    }

    private void releaseOnRenderThreadLocked() {
        synchronized (mLock) {
            releaseEglLocked();
        }
    }

    private void releaseAllPlanesLocked() {
        for (int i = 0; i < mPlanes.size(); i++) {
            releasePlaneTextureLocked(mPlanes.valueAt(i));
        }
        mPlanes.clear();
    }

    private void initGlResourcesLocked() {
        mProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        mPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
        mTexCoordHandle = GLES20.glGetAttribLocation(mProgram, "aTexCoord");
        mMvpHandle = GLES20.glGetUniformLocation(mProgram, "uMvp");
        mSamplerHandle = GLES20.glGetUniformLocation(mProgram, "uTexture");

        mExternalProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER_EXTERNAL);
        mExternalPositionHandle = GLES20.glGetAttribLocation(mExternalProgram, "aPosition");
        mExternalTexCoordHandle = GLES20.glGetAttribLocation(mExternalProgram, "aTexCoord");
        mExternalMvpHandle = GLES20.glGetUniformLocation(mExternalProgram, "uMvp");
        mExternalSamplerHandle = GLES20.glGetUniformLocation(mExternalProgram, "uTexture");
        mExternalTexMatrixHandle = GLES20.glGetUniformLocation(mExternalProgram, "uTexMatrix");

        int[] buffers = new int[1];
        GLES20.glGenBuffers(1, buffers, 0);
        mVbo = buffers[0];
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mVbo);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, mQuadBuffer.capacity() * Float.BYTES,
                mQuadBuffer, GLES20.GL_STATIC_DRAW);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);

        GLES20.glGenBuffers(1, buffers, 0);
        mSkyboxVbo = buffers[0];
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mSkyboxVbo);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, mSkyboxBuffer.capacity() * Float.BYTES,
                mSkyboxBuffer, GLES20.GL_STATIC_DRAW);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);

        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthFunc(GLES20.GL_LEQUAL);
        applyClearColorLocked();
        GLES20.glViewport(0, 0, mSurfaceWidth, mSurfaceHeight);
        System.arraycopy(mCamera.getViewMatrix(), 0, mView, 0, 16);
    }

    private void drawFrameLocked() {
        final long frameStartNanos = System.nanoTime();
        System.arraycopy(mCamera.getViewMatrix(), 0, mView, 0, 16);
        System.arraycopy(mCamera.getProjectionMatrix(), 0, mProjection, 0, 16);
        System.arraycopy(mCamera.getViewProjectionMatrix(), 0, mViewProjection, 0, 16);

        applyClearColorLocked();
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        GLES20.glUseProgram(mProgram);
        GLES20.glUniform1i(mSamplerHandle, 0);
        consumeVirtualDisplayFrameLocked();
        drawSkyboxLocked();

        for (int i = 0; i < mPlanes.size(); i++) {
            PrismPlane plane = mPlanes.valueAt(i);
            if (!plane.visible) {
                continue;
            }
            ensurePlaneTextureLocked(plane);
            if (isSurfaceTexturePlaneLocked(plane)) {
                drawSurfaceTexturePlaneLocked(plane);
            } else {
                drawBitmapPlaneLocked(plane);
            }
        }

        GLES20.glDisableVertexAttribArray(mPositionHandle);
        GLES20.glDisableVertexAttribArray(mTexCoordHandle);
        GLES20.glDisableVertexAttribArray(mExternalPositionHandle);
        GLES20.glDisableVertexAttribArray(mExternalTexCoordHandle);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);

        GLES20.glUseProgram(mProgram);
        GLES20.glUniform1i(mSamplerHandle, 0);
        drawHudLocked();

        mLastSwapSuccess = EGL14.eglSwapBuffers(mEglDisplay, mEglSurface);
        mFrameCount++;
        mLastFrameDurationNanos = System.nanoTime() - frameStartNanos;
        mLastFrameTimestampNanos = SystemClock.elapsedRealtimeNanos();
        EGLExt.eglPresentationTimeANDROID(mEglDisplay, mEglSurface,
                android.os.SystemClock.elapsedRealtimeNanos());
    }

    private static String safeGlString(int name) {
        final String value = GLES20.glGetString(name);
        return value != null ? value : "unknown";
    }

    private static String eglStatus(Object value, Object noValue) {
        return value == noValue ? "not initialized" : "initialized";
    }

    private void applyClearColorLocked() {
        GLES20.glClearColor(mSkyboxColor[0], mSkyboxColor[1], mSkyboxColor[2],
                mSkyboxColor[3]);
    }

    private void drawSkyboxLocked() {
        if (!mSkyboxVisible || !"cubemap".equals(mSkyboxMode)) {
            return;
        }

        final int skyboxTextureId = ensureSkyboxTextureLocked();
        if (skyboxTextureId == 0) {
            return;
        }

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mSkyboxVbo);
        GLES20.glEnableVertexAttribArray(mPositionHandle);
        GLES20.glEnableVertexAttribArray(mTexCoordHandle);
        GLES20.glVertexAttribPointer(mPositionHandle, 3, GLES20.GL_FLOAT, false,
                5 * Float.BYTES, 0);
        GLES20.glVertexAttribPointer(mTexCoordHandle, 2, GLES20.GL_FLOAT, false,
                5 * Float.BYTES, 3 * Float.BYTES);

        GLES20.glDepthMask(false);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, skyboxTextureId);
        final float[] position = mCamera.getPosition();
        Matrix.setIdentityM(mScratchMvp, 0);
        Matrix.translateM(mScratchMvp, 0, position[0], position[1], position[2]);
        Matrix.scaleM(mScratchMvp, 0, mSkyboxSize, mSkyboxSize, mSkyboxSize);
        Matrix.multiplyMM(mHudMvp, 0, mViewProjection, 0, mScratchMvp, 0);
        GLES20.glUniformMatrix4fv(mMvpHandle, 1, false, mHudMvp, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 36);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glDepthMask(true);
    }

    private void drawBitmapPlaneLocked(PrismPlane plane) {
        GLES20.glUseProgram(mProgram);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mVbo);
        GLES20.glEnableVertexAttribArray(mPositionHandle);
        GLES20.glEnableVertexAttribArray(mTexCoordHandle);
        GLES20.glVertexAttribPointer(mPositionHandle, 3, GLES20.GL_FLOAT, false,
                5 * Float.BYTES, 0);
        GLES20.glVertexAttribPointer(mTexCoordHandle, 2, GLES20.GL_FLOAT, false,
                5 * Float.BYTES, 3 * Float.BYTES);
        Matrix.multiplyMM(mScratchMvp, 0, mViewProjection, 0, plane.modelMatrix, 0);
        GLES20.glUniformMatrix4fv(mMvpHandle, 1, false, mScratchMvp, 0);
        GLES20.glUniform1i(mSamplerHandle, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, plane.textureId);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private void drawSurfaceTexturePlaneLocked(PrismPlane plane) {
        if (!hasLiveVirtualDisplayTextureLocked()) {
            drawBitmapPlaneLocked(plane);
            return;
        }
        GLES20.glUseProgram(mExternalProgram);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mVbo);
        GLES20.glEnableVertexAttribArray(mExternalPositionHandle);
        GLES20.glEnableVertexAttribArray(mExternalTexCoordHandle);
        GLES20.glVertexAttribPointer(mExternalPositionHandle, 3, GLES20.GL_FLOAT, false,
                5 * Float.BYTES, 0);
        GLES20.glVertexAttribPointer(mExternalTexCoordHandle, 2, GLES20.GL_FLOAT, false,
                5 * Float.BYTES, 3 * Float.BYTES);
        Matrix.multiplyMM(mScratchMvp, 0, mViewProjection, 0, plane.modelMatrix, 0);
        GLES20.glUniformMatrix4fv(mExternalMvpHandle, 1, false, mScratchMvp, 0);
        GLES20.glUniformMatrix4fv(mExternalTexMatrixHandle, 1, false, mVirtualDisplayTexMatrix,
                0);
        GLES20.glUniform1i(mExternalSamplerHandle, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mVirtualDisplayTextureId);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private void drawHudLocked() {
        if (!mDebugOverlayEnabled) {
            return;
        }
        final int hudTextureId = ensureHudTextureLocked();
        if (hudTextureId == 0) {
            return;
        }

        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mVbo);
        GLES20.glEnableVertexAttribArray(mPositionHandle);
        GLES20.glEnableVertexAttribArray(mTexCoordHandle);
        GLES20.glVertexAttribPointer(mPositionHandle, 3, GLES20.GL_FLOAT, false,
                5 * Float.BYTES, 0);
        GLES20.glVertexAttribPointer(mTexCoordHandle, 2, GLES20.GL_FLOAT, false,
                5 * Float.BYTES, 3 * Float.BYTES);
        GLES20.glUniform1i(mSamplerHandle, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, hudTextureId);

        Matrix.setIdentityM(mHudMvp, 0);
        mHudMvp[0] = 0.96f;
        mHudMvp[5] = 0.52f;
        mHudMvp[10] = 1.0f;
        mHudMvp[12] = -0.02f;
        mHudMvp[13] = 0.40f;
        mHudMvp[15] = 1.0f;
        GLES20.glUniformMatrix4fv(mMvpHandle, 1, false, mHudMvp, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
    }

    private int ensureHudTextureLocked() {
        if (mHudTextureId == 0) {
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            mHudTextureId = textures[0];
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mHudTextureId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE);

        final Bitmap hudBitmap = createHudBitmapLocked();
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, hudBitmap, 0);
        hudBitmap.recycle();
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        return mHudTextureId;
    }

    private int ensureSkyboxTextureLocked() {
        if (!"cubemap".equals(mSkyboxMode)) {
            return 0;
        }
        if (!mSkyboxTextureDirty && mSkyboxTextureId != 0) {
            return mSkyboxTextureId;
        }
        if (mSkyboxTextureId == 0) {
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            mSkyboxTextureId = textures[0];
        }

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mSkyboxTextureId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE);

        Bitmap bitmap = createSkyboxBitmapLocked();
        if (bitmap == null) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            mSkyboxTextureDirty = false;
            return mSkyboxTextureId;
        }

        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        bitmap.recycle();
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        mSkyboxTextureDirty = false;
        return mSkyboxTextureId;
    }

    private void ensurePlaneTextureLocked(PrismPlane plane) {
        if (!plane.textureDirty && plane.textureId != 0) {
            return;
        }
        if (isSurfaceTexturePlaneLocked(plane)) {
            if (hasLiveVirtualDisplayTextureLocked()) {
                if (plane.textureId != 0 && plane.textureId != mVirtualDisplayTextureId) {
                    GLES20.glDeleteTextures(1, new int[] { plane.textureId }, 0);
                }
                plane.textureId = mVirtualDisplayTextureId;
                plane.textureDirty = false;
                return;
            }
            if (plane.textureId != 0) {
                GLES20.glDeleteTextures(1, new int[] { plane.textureId }, 0);
                plane.textureId = 0;
            }
        }
        if (plane.textureId == 0) {
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            plane.textureId = textures[0];
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, plane.textureId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE);

        Bitmap bitmap = createTextureBitmap(plane.textureSource.getLabel(), plane.color);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        bitmap.recycle();
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        plane.textureDirty = false;
    }

    private void releasePlaneTextureLocked(PrismPlane plane) {
        if (plane.textureId == 0) {
            return;
        }
        if (plane.textureSource != null
                && plane.textureSource.getKind() == PrismTextureSource.KIND_SURFACE_TEXTURE
                && plane.textureId == mVirtualDisplayTextureId) {
            plane.textureId = 0;
            plane.textureDirty = true;
            return;
        }
        GLES20.glDeleteTextures(1, new int[] { plane.textureId }, 0);
        plane.textureId = 0;
        plane.textureDirty = true;
    }

    private static FloatBuffer createFloatBuffer(float[] values) {
        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(values.length * Float.BYTES);
        byteBuffer.order(ByteOrder.nativeOrder());
        FloatBuffer floatBuffer = byteBuffer.asFloatBuffer();
        floatBuffer.put(values);
        floatBuffer.position(0);
        return floatBuffer;
    }

    private static Bitmap createTextureBitmap(String label, float[] color) {
        Bitmap bitmap = Bitmap.createBitmap(TEXTURE_SIZE, TEXTURE_SIZE,
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        final int bgColor = Color.argb(255,
                clampColorComponent(color[0] * 255.0f),
                clampColorComponent(color[1] * 255.0f),
                clampColorComponent(color[2] * 255.0f));
        final int borderColor = Color.argb(255,
                clampColorComponent(255.0f - (255.0f - color[0] * 255.0f) * 0.25f),
                clampColorComponent(255.0f - (255.0f - color[1] * 255.0f) * 0.25f),
                clampColorComponent(255.0f - (255.0f - color[2] * 255.0f) * 0.25f));
        canvas.drawColor(bgColor);

        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setColor(borderColor);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(24.0f);
        canvas.drawRect(new Rect(24, 24, TEXTURE_SIZE - 24, TEXTURE_SIZE - 24), border);

        Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setColor(Color.WHITE);
        titlePaint.setTextAlign(Paint.Align.CENTER);
        titlePaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        titlePaint.setTextSize(72.0f);

        Paint subtitlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        subtitlePaint.setColor(Color.rgb(184, 194, 223));
        subtitlePaint.setTextAlign(Paint.Align.CENTER);
        subtitlePaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        subtitlePaint.setTextSize(42.0f);

        float centerX = TEXTURE_SIZE / 2.0f;
        canvas.drawText("PrismOS", centerX, TEXTURE_SIZE * 0.42f, titlePaint);
        canvas.drawText(label, centerX, TEXTURE_SIZE * 0.56f, subtitlePaint);
        canvas.drawText("Placeholder render plane", centerX, TEXTURE_SIZE * 0.66f,
                subtitlePaint);
        return bitmap;
    }

    private Bitmap createSkyboxBitmapLocked() {
        final File file = resolveSkyboxBitmapFileLocked();
        if (file != null) {
            final Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
            if (bitmap != null) {
                return bitmap;
            }
        }
        return createSkyboxPlaceholderBitmap();
    }

    private File resolveSkyboxBitmapFileLocked() {
        if (mSkyboxCubemap == null || mSkyboxCubemap.isEmpty() || "none".equals(mSkyboxCubemap)) {
            return null;
        }
        final File direct = new File(mSkyboxCubemap);
        if (direct.isFile()) {
            return direct;
        }
        if (!mSkyboxCubemap.endsWith(".png")) {
            final File png = new File(mSkyboxCubemap + ".png");
            if (png.isFile()) {
                return png;
            }
        }
        return null;
    }

    private Bitmap createSkyboxPlaceholderBitmap() {
        final Bitmap bitmap = Bitmap.createBitmap(SKYBOX_TEXTURE_SIZE, SKYBOX_TEXTURE_SIZE,
                Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        final int top = Color.rgb(18, 24, 40);
        final int bottom = Color.rgb(60, 36, 86);
        canvas.drawColor(top);
        final Paint grad = new Paint(Paint.ANTI_ALIAS_FLAG);
        grad.setColor(bottom);
        canvas.drawRect(0.0f, SKYBOX_TEXTURE_SIZE * 0.5f, SKYBOX_TEXTURE_SIZE,
                SKYBOX_TEXTURE_SIZE, grad);
        final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        label.setColor(Color.WHITE);
        label.setTextSize(64.0f);
        label.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        label.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("Prism Skybox", SKYBOX_TEXTURE_SIZE * 0.5f,
                SKYBOX_TEXTURE_SIZE * 0.45f, label);
        label.setTextSize(34.0f);
        canvas.drawText(mSkyboxCubemap, SKYBOX_TEXTURE_SIZE * 0.5f,
                SKYBOX_TEXTURE_SIZE * 0.56f, label);
        return bitmap;
    }

    private Bitmap createHudBitmapLocked() {
        final Bitmap bitmap = Bitmap.createBitmap(HUD_TEXTURE_WIDTH, HUD_TEXTURE_HEIGHT,
                Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.argb(0, 0, 0, 0));

        final Paint panelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        panelPaint.setColor(Color.argb(220, 10, 14, 22));
        canvas.drawRoundRect(new RectF(12.0f, 12.0f, HUD_TEXTURE_WIDTH - 12.0f,
                HUD_TEXTURE_HEIGHT - 12.0f), 28.0f, 28.0f, panelPaint);

        final Paint headerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        headerPaint.setColor(Color.WHITE);
        headerPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        headerPaint.setTextSize(38.0f);
        headerPaint.setTextAlign(Paint.Align.LEFT);

        final Paint bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bodyPaint.setColor(Color.rgb(220, 226, 240));
        bodyPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        bodyPaint.setTextSize(28.0f);
        bodyPaint.setTextAlign(Paint.Align.LEFT);

        final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dimPaint.setColor(Color.rgb(150, 160, 180));
        dimPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        dimPaint.setTextSize(24.0f);
        dimPaint.setTextAlign(Paint.Align.LEFT);

        mCamera.copyDirectionVectors(mHudForward, mHudRight, mHudUp);

        canvas.drawText("PRISM EDITOR HUD", 34.0f, 58.0f, headerPaint);
        canvas.drawText("Landscape locked", 34.0f, 92.0f, dimPaint);

        final float[] position = mCamera.getPosition();
        float y = 136.0f;
        y = drawHudLine(canvas, "Renderer: " + mRendererInitialized, 34.0f, y, bodyPaint);
        y = drawHudLine(canvas, "Surface: " + mSurfaceWidth + "x" + mSurfaceHeight, 34.0f, y,
                bodyPaint);
        y = drawHudLine(canvas, "Frames: " + mFrameCount + "  Swap: " + mLastSwapSuccess,
                34.0f, y, bodyPaint);
        y = drawHudLine(canvas, "Debug overlay: " + mDebugOverlayEnabled, 34.0f, y, bodyPaint);
        y = drawHudLine(canvas, "Camera pos: " + formatVec3(position), 34.0f, y, bodyPaint);
        y = drawHudLine(canvas, "Camera rot: yaw " + mCamera.getYaw()
                + "  pitch " + mCamera.getPitch() + "  roll " + mCamera.getRoll(),
                34.0f, y, bodyPaint);
        y = drawHudLine(canvas, "Projection: fov " + mCamera.getFov()
                + "  near " + mCamera.getNear() + "  far " + mCamera.getFar(),
                34.0f, y, bodyPaint);
        y = drawHudLine(canvas, "Planes: " + mPlanes.size() + "  Skybox: " + mSkyboxMode
                + "  visible " + mSkyboxVisible, 34.0f, y, bodyPaint);
        y = drawHudLine(canvas, "Skybox cubemap: " + mSkyboxCubemap
                + "  size " + mSkyboxSize, 34.0f, y, dimPaint);
        y = drawHudLine(canvas, "GL: " + mGlRenderer, 34.0f, y, dimPaint);
        y = drawHudLine(canvas, "Version: " + mGlVersion, 34.0f, y, dimPaint);

        drawMiniMapLocked(canvas, 34.0f, 300.0f, 360.0f, 230.0f);
        drawEditorGizmoLocked(canvas, 742.0f, 282.0f, 165.0f);
        drawGizmoLabels(canvas, 656.0f, 470.0f, dimPaint);

        return bitmap;
    }

    private static float drawHudLine(Canvas canvas, String text, float x, float y, Paint paint) {
        canvas.drawText(text, x, y, paint);
        return y + 32.0f;
    }

    private static String formatVec3(float[] value) {
        return "(" + value[0] + ", " + value[1] + ", " + value[2] + ")";
    }

    private void drawEditorGizmoLocked(Canvas canvas, float centerX, float centerY,
            float size) {
        final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
        outline.setColor(Color.argb(120, 255, 255, 255));
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(3.0f);

        final float front = size * 0.58f;
        final float back = size * 0.34f;
        final float offset = size * 0.30f;
        final float x1 = centerX - front;
        final float y1 = centerY - front;
        final float x2 = centerX + front;
        final float y2 = centerY + front;
        final float bx1 = x1 + offset;
        final float by1 = y1 - offset;
        final float bx2 = x2 + offset;
        final float by2 = y2 - offset;

        canvas.drawRect(x1, y1, x2, y2, outline);
        canvas.drawRect(bx1, by1, bx2, by2, outline);
        canvas.drawLine(x1, y1, bx1, by1, outline);
        canvas.drawLine(x2, y1, bx2, by1, outline);
        canvas.drawLine(x1, y2, bx1, by2, outline);
        canvas.drawLine(x2, y2, bx2, by2, outline);

        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(Color.argb(45, 255, 255, 255));
        canvas.drawRect(x1 + 1.0f, y1 + 1.0f, x2 - 1.0f, y2 - 1.0f, fill);
        fill.setColor(Color.argb(22, 255, 255, 255));
        canvas.drawRect(bx1 + 1.0f, by1 + 1.0f, bx2 - 1.0f, by2 - 1.0f, fill);

        drawAxis(canvas, centerX, centerY, size * 0.98f, new float[] { 1.0f, 0.0f, 0.0f },
                Color.rgb(255, 80, 72), "X");
        drawAxis(canvas, centerX, centerY, size * 0.98f, new float[] { 0.0f, 1.0f, 0.0f },
                Color.rgb(72, 220, 120), "Y");
        drawAxis(canvas, centerX, centerY, size * 0.98f, new float[] { 0.0f, 0.0f, 1.0f },
                Color.rgb(80, 160, 255), "Z");
    }

    private void drawAxis(Canvas canvas, float centerX, float centerY, float radius,
            float[] worldAxis, int color, String label) {
        final float projectedX = worldAxis[0] * mHudRight[0]
                + worldAxis[1] * mHudRight[1]
                + worldAxis[2] * mHudRight[2];
        final float projectedY = worldAxis[0] * mHudUp[0]
                + worldAxis[1] * mHudUp[1]
                + worldAxis[2] * mHudUp[2];
        final float length = (float) Math.sqrt(projectedX * projectedX + projectedY * projectedY);
        final float normX = length > 1e-4f ? projectedX / length : 0.0f;
        final float normY = length > 1e-4f ? projectedY / length : 0.0f;
        final float endX = centerX + normX * radius;
        final float endY = centerY - normY * radius;

        final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        axisPaint.setColor(color);
        axisPaint.setStyle(Paint.Style.STROKE);
        axisPaint.setStrokeWidth(10.0f);
        axisPaint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(centerX, centerY, endX, endY, axisPaint);

        final float headX1 = endX - normX * 18.0f + normY * 8.0f;
        final float headY1 = endY + normY * 18.0f + normX * 8.0f;
        final float headX2 = endX - normX * 18.0f - normY * 8.0f;
        final float headY2 = endY + normY * 18.0f - normX * 8.0f;
        canvas.drawLine(endX, endY, headX1, headY1, axisPaint);
        canvas.drawLine(endX, endY, headX2, headY2, axisPaint);

        final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelPaint.setColor(color);
        labelPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        labelPaint.setTextSize(26.0f);
        canvas.drawText(label, endX + 12.0f, endY - 10.0f, labelPaint);
    }

    private static void drawGizmoLabels(Canvas canvas, float x, float y, Paint paint) {
        canvas.drawText("X = red   Y = green   Z = blue", x, y, paint);
    }

    private void drawMiniMapLocked(Canvas canvas, float left, float top, float width,
            float height) {
        mCamera.copyDirectionVectors(mHudForward, mHudRight, mHudUp);

        final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(Color.argb(170, 8, 11, 16));
        canvas.drawRoundRect(new RectF(left, top, left + width, top + height), 22.0f, 22.0f, bg);

        final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setColor(Color.argb(120, 255, 255, 255));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2.0f);
        canvas.drawRoundRect(new RectF(left, top, left + width, top + height), 22.0f, 22.0f,
                border);

        final Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
        title.setColor(Color.WHITE);
        title.setTextSize(24.0f);
        title.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        canvas.drawText("World Map", left + 18.0f, top + 32.0f, title);

        final float pad = 18.0f;
        final float mapLeft = left + pad;
        final float mapTop = top + 44.0f;
        final float mapWidth = width - pad * 2.0f;
        final float mapHeight = height - 56.0f;
        final float mapRight = mapLeft + mapWidth;
        final float mapBottom = mapTop + mapHeight;

        final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
        grid.setColor(Color.argb(55, 255, 255, 255));
        grid.setStrokeWidth(1.0f);
        final int steps = 4;
        for (int i = 1; i < steps; i++) {
            final float x = mapLeft + mapWidth * i / steps;
            final float y = mapTop + mapHeight * i / steps;
            canvas.drawLine(x, mapTop, x, mapBottom, grid);
            canvas.drawLine(mapLeft, y, mapRight, y, grid);
        }

        final float[] cameraPos = mCamera.getPosition();
        float bound = 1.0f;
        for (int i = 0; i < mPlanes.size(); i++) {
            final PrismPlane plane = mPlanes.valueAt(i);
            bound = Math.max(bound, Math.max(Math.abs(plane.posX), Math.abs(plane.posZ)));
        }
        bound = Math.max(bound, Math.max(Math.abs(cameraPos[0]), Math.abs(cameraPos[2])));
        bound *= 1.2f;

        final Paint planePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        planePaint.setColor(Color.rgb(255, 190, 80));
        planePaint.setStyle(Paint.Style.FILL);
        final Paint planeStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        planeStroke.setColor(Color.BLACK);
        planeStroke.setStyle(Paint.Style.STROKE);
        planeStroke.setStrokeWidth(1.5f);

        for (int i = 0; i < mPlanes.size(); i++) {
            final PrismPlane plane = mPlanes.valueAt(i);
            final float px = mapLeft + ((plane.posX / bound) + 1.0f) * 0.5f * mapWidth;
            final float pz = mapTop + ((plane.posZ / bound) + 1.0f) * 0.5f * mapHeight;
            canvas.drawCircle(px, pz, 8.0f, planePaint);
            canvas.drawCircle(px, pz, 8.0f, planeStroke);
            canvas.drawText(String.valueOf(plane.id), px + 10.0f, pz - 10.0f, planeStroke);
        }

        final float camX = mapLeft + ((cameraPos[0] / bound) + 1.0f) * 0.5f * mapWidth;
        final float camZ = mapTop + ((cameraPos[2] / bound) + 1.0f) * 0.5f * mapHeight;
        final Paint cameraPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        cameraPaint.setColor(Color.rgb(110, 200, 255));
        cameraPaint.setStyle(Paint.Style.FILL_AND_STROKE);
        cameraPaint.setStrokeWidth(2.0f);
        final float dirX = mHudForward[0];
        final float dirZ = mHudForward[2];
        final float arrowLen = 18.0f;
        final float tipX = camX + dirX * arrowLen;
        final float tipY = camZ + dirZ * arrowLen;
        canvas.drawCircle(camX, camZ, 7.0f, cameraPaint);
        canvas.drawLine(camX, camZ, tipX, tipY, cameraPaint);
        canvas.drawLine(tipX, tipY, tipX - dirZ * 6.0f - dirX * 4.0f,
                tipY + dirX * 6.0f - dirZ * 4.0f, cameraPaint);
        canvas.drawLine(tipX, tipY, tipX + dirZ * 6.0f - dirX * 4.0f,
                tipY - dirX * 6.0f - dirZ * 4.0f, cameraPaint);
    }

    private static int clampColorComponent(float value) {
        return Math.max(0, Math.min(255, Math.round(value)));
    }

    private static int createProgram(String vertexSource, String fragmentSource) {
        int vertex = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragment = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertex);
        GLES20.glAttachShader(program, fragment);
        GLES20.glLinkProgram(program);

        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] == 0) {
            String error = GLES20.glGetProgramInfoLog(program);
            GLES20.glDeleteProgram(program);
            throw new IllegalStateException("Program link failed: " + error);
        }

        GLES20.glDeleteShader(vertex);
        GLES20.glDeleteShader(fragment);
        return program;
    }

    private static int loadShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);

        int[] compileStatus = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0);
        if (compileStatus[0] == 0) {
            String error = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IllegalStateException("Shader compile failed: " + error);
        }
        return shader;
    }

    private static final String VERTEX_SHADER =
            "uniform mat4 uMvp;\n"
                    + "attribute vec4 aPosition;\n"
                    + "attribute vec2 aTexCoord;\n"
                    + "varying vec2 vTexCoord;\n"
                    + "void main() {\n"
                    + "  gl_Position = uMvp * aPosition;\n"
                    + "  vTexCoord = aTexCoord;\n"
                    + "}\n";

    private static final String FRAGMENT_SHADER =
            "precision mediump float;\n"
                    + "uniform sampler2D uTexture;\n"
                    + "varying vec2 vTexCoord;\n"
                    + "void main() {\n"
                    + "  gl_FragColor = texture2D(uTexture, vTexCoord);\n"
                    + "}\n";

    private static final String FRAGMENT_SHADER_EXTERNAL =
            "#extension GL_OES_EGL_image_external : require\n"
                    + "precision mediump float;\n"
                    + "uniform samplerExternalOES uTexture;\n"
                    + "uniform mat4 uTexMatrix;\n"
                    + "varying vec2 vTexCoord;\n"
                    + "void main() {\n"
                    + "  vec4 tex = uTexMatrix * vec4(vTexCoord, 0.0, 1.0);\n"
                    + "  gl_FragColor = texture2D(uTexture, tex.xy);\n"
                    + "}\n";

    }
