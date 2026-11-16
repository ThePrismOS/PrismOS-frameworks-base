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

import android.os.SystemProperties;
import android.view.WindowManager.LayoutParams;

/**
 * PrismOS spatial window policy.
 *
 * While the spatial scene is active, application windows live in a world-space scene
 * rendered by SurfaceFlinger instead of the flat 2D stack. This class is the single
 * source of truth for which windows participate in the scene and how the 2D stack
 * treats them:
 *
 *  - tagging:      only buffer-bearing main application surfaces become spatial windows
 *                  (see WindowState#prepareSurfaces, metadata 10000/10001);
 *  - visibility:   a spatial activity stays visible and keeps its surface when covered,
 *                  so the compositor can keep rendering it in its world slot;
 *  - transitions:  spatial focus changes must not run 2D transition animations — the
 *                  scene animates its own windows.
 *
 * Everything is property-gated so the stock 2D pipeline remains a reboot away.
 */
final class PrismWindowPolicy {

    private static final String PROP_STEREO = "debug.prismos.stereo";
    private static final String PROP_SCENE = "debug.prismos.3d";
    private static final String PROP_SPATIAL = "debug.prismos.spatial_window";

    private PrismWindowPolicy() {}

    /** True when the world-space window scene is currently switched on. */
    static boolean spatialWindowingActive() {
        return SystemProperties.getBoolean(PROP_STEREO, false)
                && SystemProperties.getBoolean(PROP_SCENE, false)
                && SystemProperties.getBoolean(PROP_SPATIAL, false);
    }

    /**
     * True when the window's buffer surface is tagged as a spatial window: the buffer
     * surface of an activity's main application window (attached dialogs share the
     * activity's placement). Container layers, starting windows, sub-windows and system
     * bars stay flat.
     */
    static boolean isSpatialWindow(WindowState win) {
        if (!spatialWindowingActive() || win == null || win.mActivityRecord == null) {
            return false;
        }
        final LayoutParams attrs = win.mAttrs;
        final boolean isMainApplicationSurface =
                attrs.type >= LayoutParams.FIRST_APPLICATION_WINDOW
                        && attrs.type <= LayoutParams.LAST_APPLICATION_WINDOW
                        && attrs.type != LayoutParams.TYPE_APPLICATION_STARTING;
        final String legacyTarget =
                SystemProperties.get("debug.prismos.spatial_window_layer", "");
        final boolean matchesLegacyTarget = legacyTarget.isEmpty()
                || String.valueOf(win.getWindowTag()).equals(legacyTarget)
                || (attrs.packageName != null && attrs.packageName.equals(legacyTarget));
        return isMainApplicationSurface && matchesLegacyTarget;
    }

    /**
     * PrismOS: stable placement identity of a spatial window — a hash of the activity
     * component name. Unlike an identity hash code it survives surface recreation, app
     * switches and system_server restarts, which keeps each Spatial Window bound to its
     * Android Task and lets the renderer restore its world placement for the whole
     * session. Distinct for two windows of one app (split screen uses two activities).
     */
    static int stablePlacementId(ActivityRecord activity) {
        if (activity == null || activity.info == null || activity.info.getComponentName() == null) {
            return 0;
        }
        return activity.info.getComponentName().flattenToString().hashCode();
    }

    /**
     * True when the activity's window is tagged as a spatial window.
     */
    static boolean isSpatialActivity(ActivityRecord activity) {
        if (!spatialWindowingActive() || activity == null) {
            return false;
        }
        for (int i = activity.getChildCount() - 1; i >= 0; --i) {
            if (isSpatialWindow(activity.getChildAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * PrismOS visibility rule: a spatial activity covered in the 2D stack stays visible
     * and keeps its surface so the compositor keeps rendering it in its world slot. Only
     * multi-window stacks opt in — fullscreen and pinned keep the stock occlusion
     * behavior, and the focused activity still owns input and lifecycle.
     */
    static boolean keepsSurfaceWhenOccluded(ActivityRecord activity, Task task) {
        // Horizon-style persistence: a tagged window keeps its surface in every
        // windowing mode — the scene renders it at its world placement while the 2D
        // stack shows whatever is in front. Only the spatial gate applies.
        return spatialWindowingActive() && isSpatialActivity(activity);
    }

    /**
     * True when transitions between two spatial activities must not run 2D animations:
     * the flat surfaces of spatial windows are not what the wearer sees, so animating
     * them is wasted work and can delay the surface commit the scene samples.
     */
    static boolean suppressTransitionAnimation(ActivityRecord activity) {
        return spatialWindowingActive() && isSpatialActivity(activity);
    }
}
