package com.android.server.prism;

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.view.Display;
import android.view.Surface;

/**
 * Owns the single Prism virtual display instance and its lifecycle state.
 */
public final class PrismVirtualDisplay {
    private static final String NAME = "Prism Virtual Display";
    private static final int DEFAULT_FLAGS = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
            | DisplayManager.VIRTUAL_DISPLAY_FLAG_TRUSTED;

    private final Object mLock = new Object();
    private final DisplayManager mDisplayManager;

    private VirtualDisplay mVirtualDisplay;
    private Surface mSurface;
    private int mWidth;
    private int mHeight;
    private int mDensityDpi;
    private boolean mCreated;
    private boolean mRunning;

    PrismVirtualDisplay(@NonNull Context context) {
        mDisplayManager = context.getSystemService(DisplayManager.class);
    }

    boolean isCreated() {
        synchronized (mLock) {
            return mCreated;
        }
    }

    boolean isRunning() {
        synchronized (mLock) {
            return mRunning;
        }
    }

    boolean isSurfaceAttached() {
        synchronized (mLock) {
            return mSurface != null;
        }
    }

    int getWidth() {
        synchronized (mLock) {
            return mWidth;
        }
    }

    int getHeight() {
        synchronized (mLock) {
            return mHeight;
        }
    }

    int getDensityDpi() {
        synchronized (mLock) {
            return mDensityDpi;
        }
    }

    int getDisplayId() {
        synchronized (mLock) {
            final VirtualDisplay display = mVirtualDisplay;
            final Display actualDisplay = display != null ? display.getDisplay() : null;
            return actualDisplay != null ? actualDisplay.getDisplayId() : Display.INVALID_DISPLAY;
        }
    }

    long getFramesReceived() {
        synchronized (mLock) {
            return mCreated ? mFramesReceived : 0L;
        }
    }

    long getLastFrameTimestampNanos() {
        synchronized (mLock) {
            return mLastFrameTimestampNanos;
        }
    }

    String create(@NonNull Surface surface, int width, int height, int densityDpi) {
        synchronized (mLock) {
            if (mDisplayManager == null) {
                return "DisplayManager unavailable";
            }
            destroyLocked();

            mWidth = Math.max(1, width);
            mHeight = Math.max(1, height);
            mDensityDpi = Math.max(1, densityDpi);
            mSurface = surface;

            final VirtualDisplay display = mDisplayManager.createVirtualDisplay(NAME, mWidth,
                    mHeight, mDensityDpi, surface, DEFAULT_FLAGS);
            if (display == null) {
                mSurface = null;
                mCreated = false;
                mRunning = false;
                return "Failed to create VirtualDisplay";
            }

            mVirtualDisplay = display;
            mCreated = true;
            mRunning = true;
            return null;
        }
    }

    String resize(int width, int height, int densityDpi) {
        synchronized (mLock) {
            if (!mCreated || mVirtualDisplay == null) {
                return "VirtualDisplay is not created";
            }
            mWidth = Math.max(1, width);
            mHeight = Math.max(1, height);
            mDensityDpi = Math.max(1, densityDpi);
            mVirtualDisplay.resize(mWidth, mHeight, mDensityDpi);
            return null;
        }
    }

    String enable() {
        synchronized (mLock) {
            if (!mCreated || mVirtualDisplay == null) {
                return "VirtualDisplay is not created";
            }
            mVirtualDisplay.setDisplayState(true);
            mRunning = true;
            return null;
        }
    }

    String disable() {
        synchronized (mLock) {
            if (!mCreated || mVirtualDisplay == null) {
                return "VirtualDisplay is not created";
            }
            mVirtualDisplay.setDisplayState(false);
            mRunning = false;
            return null;
        }
    }

    String destroy() {
        synchronized (mLock) {
            destroyLocked();
            return null;
        }
    }

    String dump() {
        synchronized (mLock) {
            final StringBuilder sb = new StringBuilder();
            sb.append("=== Prism Display Dump ===\n");
            sb.append("Created: ").append(mCreated).append('\n');
            sb.append("Running: ").append(mRunning).append('\n');
            sb.append("Width: ").append(mWidth).append('\n');
            sb.append("Height: ").append(mHeight).append('\n');
            sb.append("Density: ").append(mDensityDpi).append('\n');
            sb.append("DisplayId: ").append(getDisplayId()).append('\n');
            sb.append("Surface attached: ").append(mSurface != null).append('\n');
            sb.append("Frames received: ").append(mFramesReceived).append('\n');
            sb.append("Last frame timestamp: ").append(mLastFrameTimestampNanos).append('\n');
            return sb.toString();
        }
    }

    void onFrameReceived(long timestampNanos) {
        synchronized (mLock) {
            if (!mCreated) {
                return;
            }
            mFramesReceived++;
            mLastFrameTimestampNanos = timestampNanos;
        }
    }

    private long mFramesReceived;
    private long mLastFrameTimestampNanos;

    private void destroyLocked() {
        if (mVirtualDisplay != null) {
            try {
                mVirtualDisplay.release();
            } catch (Throwable ignored) {
                // Keep teardown resilient; the controller handles recovery.
            }
            mVirtualDisplay = null;
        }
        mSurface = null;
        mCreated = false;
        mRunning = false;
        mWidth = 0;
        mHeight = 0;
        mDensityDpi = 0;
        mFramesReceived = 0L;
        mLastFrameTimestampNanos = 0L;
    }
}
