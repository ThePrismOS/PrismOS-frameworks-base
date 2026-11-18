/*
 * Copyright (C) 2026 ThePrismOS
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

package com.android.server.wm;

import static android.hardware.input.InputManager.INJECT_INPUT_EVENT_MODE_ASYNC;

import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Slog;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.SurfaceControl;

import java.io.PrintWriter;

/**
 * PrismOS spatial input manager.
 *
 * This is a deliberately separate component from the window policy and the renderer: it
 * tracks objects in the world-space scene (currently the gaze ray against spatial window
 * planes, later hand/controller rays from the camera trackers), processes the events they
 * produce and acts on the regular Android input machinery.
 *
 * Mechanic: SurfaceFlinger casts the gaze ray every frame and publishes the nearest hit
 * (window tag + normalized in-window coordinates). A spatial tap resolves the hit to a
 * WindowState, brings its task to the front (the flat 2D stack decides which application
 * receives input) and injects a tap at the center of the window's flat frame — so the
 * event flows through the stock InputDispatcher and keeps every permission and focus
 * rule Android applies.
 *
 * Spatial focus and input focus are separate: the gaze highlight is purely visual, the
 * injection only happens on an explicit spatial tap.
 */
final class PrismInputManager {
    private static final String TAG = "PrismInputManager";

    private final WindowManagerService mService;

    PrismInputManager(WindowManagerService service) {
        mService = service;
    }

    /**
     * Spatial tap: act on whatever the gaze ray currently points at.
     */
    void onGazeTap() {
        if (!PrismWindowPolicy.spatialWindowingActive()) {
            return;
        }
        final float[] uv = new float[2];
        final String hitTag = SurfaceControl.getPrismGazeHit(uv);
        if (hitTag == null) {
            Slog.d(TAG, "gaze tap: no window under gaze");
            return;
        }

        final WindowState[] hit = new WindowState[1];
        mService.mRoot.forAllWindows(win -> {
            if (hit[0] != null) {
                return;
            }
            // The renderer reports the full layer tag; the stable prefix before the
            // "#<sequence>" suffix identifies the window.
            final String tag = String.valueOf(win.getWindowTag());
            if (!tag.isEmpty() && hitTag.startsWith(tag)) {
                hit[0] = win;
            }
        }, true /* traverseTopToBottom */);
        final WindowState win = hit[0];
        if (win == null || win.mActivityRecord == null) {
            Slog.d(TAG, "gaze tap: hit '" + hitTag + "' resolved to no live window");
            return;
        }

        // Spatial focus: bring the task forward without a 2D transition. The scene keeps
        // drawing everything; only the flat input stack changes order.
        final Task task = win.mActivityRecord.getTask();
        if (task != null) {
            task.moveToFront("prismos-gaze-tap");
        }

        // Input focus: inject the tap at the center of the window's flat frame so the
        // stock InputDispatcher applies its own focus, occlusion and permission rules.
        final Rect frame = win.getFrame();
        if (frame.isEmpty()) {
            Slog.d(TAG, "gaze tap: empty flat frame for '" + win.getName() + "'");
            return;
        }
        final float x = frame.exactCenterX();
        final float y = frame.exactCenterY();
        final long now = SystemClock.uptimeMillis();
        injectTap(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0), "DOWN");
        injectTap(MotionEvent.obtain(now, now + 5, MotionEvent.ACTION_UP, x, y, 0), "UP");
        Slog.d(TAG, "gaze tap: '" + win.getName() + "' uv=(" + uv[0] + "," + uv[1]
                + ") at 2D " + x + "," + y);
    }

    private void injectTap(MotionEvent event, String what) {
        try {
            final boolean ok = mService.mInputManager.injectInputEvent(event,
                    INJECT_INPUT_EVENT_MODE_ASYNC);
            if (!ok) {
                Slog.w(TAG, "gaze tap: injection " + what + " rejected");
            }
        } catch (Exception e) {
            Slog.w(TAG, "gaze tap: injection " + what + " failed", e);
        } finally {
            event.recycle();
        }
    }

    void dump(PrintWriter pw) {
        pw.println("  PrismInputManager:");
        final float[] uv = new float[2];
        final String hitTag = SurfaceControl.getPrismGazeHit(uv);
        pw.println("    gazeHit=" + (hitTag != null ? hitTag + " uv=(" + uv[0] + "," + uv[1] + ")"
                : "none"));
    }
}
