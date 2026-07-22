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

import android.app.ActivityManager.RunningTaskInfo;
import android.content.ComponentName;
import android.graphics.PointF;
import android.os.IBinder;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseArray;
import android.view.SurfaceControl;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Passive spatial task metadata registry for early framework-level experiments.
 *
 * <p>This class deliberately does not reparent, hide, crop, or transform task surfaces. It observes
 * tasks already owned by {@code ShellTaskOrganizer} and records the metadata a future spatial shell
 * policy will need once SurfaceControl ownership is moved into Shell.</p>
 */
public final class SpatialTaskController {
    private static final String TAG = "SpatialTaskController";
    public static final String ENABLED_PROPERTY = "persist.wm.debug.spatial_tasks";
    public static final String SPATIAL_LAUNCH_PROPERTY = "persist.wm.debug.spatial_launch";

    private final SparseArray<SpatialTaskRecord> mTasks = new SparseArray<>();
    private final ArrayMap<IBinder, PendingSpatialLaunch> mPendingLaunches = new ArrayMap<>();
    private final SpatialCompositorController mCompositorController;

    public SpatialTaskController() {
        this(null);
    }

    SpatialTaskController(SpatialCompositorController compositorController) {
        mCompositorController = compositorController;
        if (isTraceEnabled()) {
            Slog.i(TAG, "enabled passive spatial task metadata tracing");
        }
        if (isSpatialLaunchEnabled()) {
            Slog.i(TAG, "enabled launcher spatial launch association");
        }
    }

    public boolean isEnabled() {
        return isTraceEnabled() || isSpatialLaunchEnabled();
    }

    public boolean isSpatialLaunchEnabled() {
        return SystemProperties.getBoolean(SPATIAL_LAUNCH_PROPERTY, false);
    }

    private boolean isTraceEnabled() {
        return SystemProperties.getBoolean(ENABLED_PROPERTY, false);
    }

    public void onTaskAppeared(RunningTaskInfo taskInfo, SurfaceControl leash) {
        if (!isEnabled()) {
            return;
        }
        SpatialTaskRecord record = new SpatialTaskRecord(taskInfo, leash);
        attachSpatialLaunchIfRequested(record, taskInfo);
        mTasks.put(taskInfo.taskId, record);
        logInfo("appeared " + record);
    }

    public void onTaskInfoChanged(RunningTaskInfo taskInfo) {
        if (!isEnabled()) {
            return;
        }
        SpatialTaskRecord record = mTasks.get(taskInfo.taskId);
        if (record == null) {
            record = new SpatialTaskRecord(taskInfo, null);
            attachSpatialLaunchIfRequested(record, taskInfo);
            mTasks.put(taskInfo.taskId, record);
            logInfo("discovered " + record);
            return;
        }
        if (record.update(taskInfo)) {
            if (record.state == SpatialTaskState.VISIBLE || record.state == SpatialTaskState.ATTACHED) {
                if (taskInfo.isVisible) {
                    record.state = SpatialTaskState.VISIBLE;
                }
                notifyCompositor(record);
            }
            logDebug("changed " + record);
        }
    }

    public void onTaskVanished(RunningTaskInfo taskInfo) {
        if (!isEnabled()) {
            return;
        }
        mTasks.remove(taskInfo.taskId);
        if (mCompositorController != null) {
            mCompositorController.onSpatialTaskVanished(taskInfo.taskId);
        }
        logInfo("vanished taskId=" + taskInfo.taskId);
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "SpatialTaskController traceEnabled=" + isTraceEnabled()
                + " spatialLaunchEnabled=" + isSpatialLaunchEnabled());
        if (!isEnabled()) {
            pw.println(prefix + "  set " + ENABLED_PROPERTY + "=true to trace Shell task metadata");
            pw.println(prefix + "  set " + SPATIAL_LAUNCH_PROPERTY
                    + "=true to associate launcher cookies with spatial sessions");
            return;
        }
        pw.println(prefix + "  " + mPendingLaunches.size() + " pending spatial launches");
        for (int i = 0; i < mPendingLaunches.size(); i++) {
            pw.println(prefix + "  " + mPendingLaunches.valueAt(i));
        }
        pw.println(prefix + "  " + mTasks.size() + " spatial task records");
        for (int i = 0; i < mTasks.size(); i++) {
            pw.println(prefix + "  " + mTasks.valueAt(i));
        }
    }

    private void attachSpatialLaunchIfRequested(SpatialTaskRecord record,
            RunningTaskInfo taskInfo) {
        if (!isSpatialLaunchEnabled() || taskInfo.launchCookies == null
                || taskInfo.launchCookies.isEmpty()) {
            return;
        }
        ArrayList<IBinder> launchCookies = taskInfo.launchCookies;
        for (int i = launchCookies.size() - 1; i >= 0; i--) {
            IBinder cookie = launchCookies.get(i);
            PendingSpatialLaunch pending = mPendingLaunches.get(cookie);
            if (pending == null) {
                pending = new PendingSpatialLaunch(cookie);
                mPendingLaunches.put(cookie, pending);
                logInfo("pending " + pending);
            }
            pending.attachTask(taskInfo.taskId);
            record.spatialLaunchCookie = cookie;
            record.state = taskInfo.isVisible ? SpatialTaskState.VISIBLE : SpatialTaskState.ATTACHED;
            record.updateSurfaceState();
            notifyCompositor(record);
            logInfo("attached launchCookie=" + cookie + " taskId=" + taskInfo.taskId);
            return;
        }
    }

    private void notifyCompositor(SpatialTaskRecord record) {
        if (mCompositorController == null) {
            return;
        }
        if (record.state == SpatialTaskState.ATTACHED || record.state == SpatialTaskState.VISIBLE) {
            mCompositorController.onSpatialTaskAttached(record.toSnapshot());
        }
    }

    private void logInfo(String message) {
        if (isTraceEnabled() || isSpatialLaunchEnabled()) {
            Slog.i(TAG, message);
        }
    }

    private void logDebug(String message) {
        if (isTraceEnabled()) {
            Slog.d(TAG, message);
        }
    }

    private enum SpatialTaskState {
        OBSERVED,
        PENDING,
        ATTACHED,
        VISIBLE,
        IMMERSIVE,
        SUSPENDED
    }

    private enum SpatialTaskRenderingMode {
        OBSERVED,
        TASK_SURFACE,
        IMMERSIVE
    }

    private enum SpatialTaskSurfaceState {
        NONE,
        LEASH_OBSERVED,
        READY_FOR_EXPERIMENT,
        DETACHED,
        INVALID
    }

    public static final class SpatialTaskSnapshot {
        public final int taskId;
        public final int displayId;
        public final boolean visible;
        public final String topActivity;
        public final String transform;
        public final String renderingMode;
        public final String surfaceState;
        public final boolean hasTaskLeash;
        public final boolean taskLeashValid;
        public final int taskLeashIdentity;
        public final float x;
        public final float y;
        public final float z;
        public final float scale;
        public final float rotationX;
        public final float rotationY;
        public final float rotationZ;

        SpatialTaskSnapshot(int taskId, int displayId, boolean visible, String topActivity,
                String transform, String renderingMode, String surfaceState, boolean hasTaskLeash,
                boolean taskLeashValid, int taskLeashIdentity, float x, float y, float z,
                float scale, float rotationX, float rotationY, float rotationZ) {
            this.taskId = taskId;
            this.displayId = displayId;
            this.visible = visible;
            this.topActivity = topActivity;
            this.transform = transform;
            this.renderingMode = renderingMode;
            this.surfaceState = surfaceState;
            this.hasTaskLeash = hasTaskLeash;
            this.taskLeashValid = taskLeashValid;
            this.taskLeashIdentity = taskLeashIdentity;
            this.x = x;
            this.y = y;
            this.z = z;
            this.scale = scale;
            this.rotationX = rotationX;
            this.rotationY = rotationY;
            this.rotationZ = rotationZ;
        }
    }

    private static final class PendingSpatialLaunch {
        final IBinder cookie;
        final long createdAtMillis = SystemClock.uptimeMillis();
        int taskId = -1;
        SpatialTaskState state = SpatialTaskState.PENDING;

        PendingSpatialLaunch(IBinder cookie) {
            this.cookie = cookie;
        }

        void attachTask(int attachedTaskId) {
            taskId = attachedTaskId;
            state = SpatialTaskState.ATTACHED;
        }

        @Override
        public String toString() {
            return "PendingSpatialLaunch{cookie=" + cookie
                    + " taskId=" + taskId
                    + " state=" + state
                    + " ageMs=" + (SystemClock.uptimeMillis() - createdAtMillis)
                    + "}";
        }
    }

    private static final class SpatialTaskRecord {
        final int taskId;
        final SpatialTransform transform = new SpatialTransform();
        SurfaceControl leash;
        IBinder spatialLaunchCookie;
        SpatialTaskState state = SpatialTaskState.OBSERVED;
        SpatialTaskRenderingMode renderingMode = SpatialTaskRenderingMode.OBSERVED;
        SpatialTaskSurfaceState surfaceState = SpatialTaskSurfaceState.NONE;
        int displayId;
        int windowingMode;
        boolean visible;
        boolean focused;
        ComponentName topActivity;

        SpatialTaskRecord(RunningTaskInfo taskInfo, SurfaceControl taskLeash) {
            taskId = taskInfo.taskId;
            leash = taskLeash;
            transform.position.set(defaultXForTask(taskId), 0.0f);
            transform.depth = -2.5f - ((taskId % 3) * 0.35f);
            update(taskInfo);
        }

        boolean update(RunningTaskInfo taskInfo) {
            int oldDisplayId = displayId;
            int oldWindowingMode = windowingMode;
            boolean oldVisible = visible;
            boolean oldFocused = focused;
            ComponentName oldTopActivity = topActivity;
            SpatialTaskSurfaceState oldSurfaceState = surfaceState;

            displayId = taskInfo.displayId;
            windowingMode = taskInfo.getWindowingMode();
            visible = taskInfo.isVisible;
            focused = taskInfo.isFocused;
            topActivity = taskInfo.topActivity;
            updateSurfaceState();

            return oldDisplayId != displayId
                    || oldWindowingMode != windowingMode
                    || oldVisible != visible
                    || oldFocused != focused
                    || !java.util.Objects.equals(oldTopActivity, topActivity)
                    || oldSurfaceState != surfaceState;
        }

        @Override
        public String toString() {
            return "taskId=" + taskId
                    + " state=" + state
                    + " display=" + displayId
                    + " windowingMode=" + windowingMode
                    + " visible=" + visible
                    + " focused=" + focused
                    + " top=" + (topActivity != null ? topActivity.flattenToShortString() : "null")
                    + " renderingMode=" + renderingMode
                    + " surfaceState=" + surfaceState
                    + " leash=" + leashSummary()
                    + " spatialCookie=" + spatialLaunchCookie
                    + " transform=" + transform;
        }

        SpatialTaskSnapshot toSnapshot() {
            return new SpatialTaskSnapshot(taskId, displayId, visible,
                    topActivity != null ? topActivity.flattenToShortString() : "null",
                    transform.toString(), renderingMode.name(), surfaceState.name(),
                    leash != null, leash != null && leash.isValid(), leashIdentity(),
                    transform.position.x, transform.position.y, transform.depth, transform.scale,
                    transform.rotationX, transform.rotationY, transform.rotationZ);
        }

        private void updateSurfaceState() {
            if (leash == null) {
                surfaceState = SpatialTaskSurfaceState.NONE;
                renderingMode = SpatialTaskRenderingMode.OBSERVED;
                return;
            }
            if (!leash.isValid()) {
                surfaceState = SpatialTaskSurfaceState.INVALID;
                return;
            }
            surfaceState = spatialLaunchCookie != null
                    ? SpatialTaskSurfaceState.READY_FOR_EXPERIMENT
                    : SpatialTaskSurfaceState.LEASH_OBSERVED;
            renderingMode = spatialLaunchCookie != null
                    ? SpatialTaskRenderingMode.TASK_SURFACE
                    : SpatialTaskRenderingMode.OBSERVED;
        }

        private int leashIdentity() {
            return leash != null ? System.identityHashCode(leash) : 0;
        }

        private String leashSummary() {
            if (leash == null) {
                return "none";
            }
            return "{valid=" + leash.isValid()
                    + " id=0x" + Integer.toHexString(leashIdentity())
                    + "}";
        }

        private static float defaultXForTask(int taskId) {
            return ((taskId % 5) - 2) * 0.45f;
        }
    }

    private static final class SpatialTransform {
        final PointF position = new PointF();
        float depth;
        float scale = 1.0f;
        float rotationX;
        float rotationY;
        float rotationZ;

        @Override
        public String toString() {
            return String.format(Locale.US, "pos=%.2f,%.2f depth=%.2f scale=%.2f rot=%.1f,%.1f,%.1f",
                    position.x, position.y, depth, scale, rotationX, rotationY, rotationZ);
        }
    }
}
