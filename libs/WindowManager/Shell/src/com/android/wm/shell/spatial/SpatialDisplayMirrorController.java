/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.wm.shell.spatial;

import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Slog;
import android.view.SurfaceControl;
import android.view.WindowManagerGlobal;

import java.io.PrintWriter;

/**
 * Shell-owned Display 0 mirror probe.
 *
 * <p>This deliberately stops short of attaching the mirror to a renderer. Its job is to validate
 * that WMShell can obtain a real display mirror SurfaceControl without launching apps onto private
 * displays. A later backend can parent this mirror into a shell-owned layer tree or export a
 * renderer-consumable buffer path.</p>
 */
public final class SpatialDisplayMirrorController {
    private static final String TAG = "SpatialDisplayMirrorController";

    public static final String ENABLED_PROPERTY = "persist.wm.debug.spatial_display_mirror";

    private SurfaceControl mDisplayMirror;
    private long mMirrorCreatedAtMillis;
    private int mLastDisplayId = -1;
    private String mLastState = "disabled";

    public boolean isEnabled() {
        return SystemProperties.getBoolean(ENABLED_PROPERTY, false);
    }

    public boolean ensureMirrorForDisplay(int displayId) {
        if (!isEnabled()) {
            mLastState = "disabled";
            return false;
        }
        if (displayId != 0) {
            mLastState = "ignored-non-main-display-" + displayId;
            return false;
        }
        if (mDisplayMirror != null && mDisplayMirror.isValid() && mLastDisplayId == displayId) {
            return true;
        }

        SurfaceControl mirror = new SurfaceControl();
        final boolean success;
        try {
            success = WindowManagerGlobal.getWindowManagerService().mirrorDisplay(displayId, mirror);
        } catch (RemoteException | RuntimeException e) {
            mLastState = "mirror-failed-" + e.getClass().getSimpleName();
            Slog.w(TAG, "failed to create Display " + displayId + " mirror", e);
            return false;
        }

        if (!success || !mirror.isValid()) {
            mLastState = "mirror-unavailable";
            return false;
        }

        releaseMirror();
        mDisplayMirror = mirror;
        mLastDisplayId = displayId;
        mMirrorCreatedAtMillis = SystemClock.uptimeMillis();
        mLastState = "mirror-ready";
        Slog.i(TAG, "created shell-owned Display " + displayId + " mirror");
        return true;
    }

    public boolean hasValidMirror() {
        return mDisplayMirror != null && mDisplayMirror.isValid();
    }

    public void releaseMirror() {
        if (mDisplayMirror == null) {
            return;
        }
        mDisplayMirror.release();
        mDisplayMirror = null;
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "SpatialDisplayMirrorController enabled=" + isEnabled()
                + " state=" + mLastState
                + " displayId=" + mLastDisplayId
                + " valid=" + hasValidMirror()
                + " ageMs=" + (mMirrorCreatedAtMillis > 0
                        ? SystemClock.uptimeMillis() - mMirrorCreatedAtMillis : 0));
        if (!isEnabled()) {
            pw.println(prefix + "  set " + ENABLED_PROPERTY
                    + "=true to probe WMShell Display 0 mirror creation");
        }
    }
}
