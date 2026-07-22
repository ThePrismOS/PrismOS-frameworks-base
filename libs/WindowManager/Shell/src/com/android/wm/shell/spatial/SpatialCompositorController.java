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

import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;

import androidx.annotation.NonNull;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Shell-owned compositor binding registry.
 *
 * <p>This is the first compositor lifecycle object that is not owned by the prototype Activity.
 * It does not render yet. It owns task-to-panel binding state so that future Shell/SystemUI
 * renderer infrastructure can attach surfaces without changing the task lifecycle contract.</p>
 */
public final class SpatialCompositorController {
    private static final String TAG = "SpatialCompositorController";
    private static final int MAIN_DISPLAY_PANEL_ID = -10_000;

    public static final String ENABLED_PROPERTY = "persist.wm.debug.spatial_compositor";
    public static final String MAIN_DISPLAY_PROPERTY = "persist.wm.debug.spatial_main_display";

    private final SparseArray<SpatialPanelBinding> mPanelBindings = new SparseArray<>();
    private final ArraySet<SpatialRenderHost> mRenderHosts = new ArraySet<>();
    private long mStartedAtMillis = SystemClock.uptimeMillis();

    public SpatialCompositorController() {
        if (isEnabled()) {
            Slog.i(TAG, "enabled shell-owned spatial compositor binding registry");
        }
    }

    public boolean isEnabled() {
        return SystemProperties.getBoolean(ENABLED_PROPERTY, false);
    }

    public void onSpatialTaskAttached(SpatialTaskController.SpatialTaskSnapshot task) {
        if (!isEnabled()) {
            return;
        }
        SpatialPanelBinding binding = mPanelBindings.get(task.taskId);
        if (binding == null) {
            binding = new SpatialPanelBinding(task);
            mPanelBindings.put(task.taskId, binding);
            updateDebugBindingState();
            Slog.i(TAG, "created " + binding);
            dispatchBindingsChanged();
            return;
        }
        binding.update(task);
        updateDebugBindingState();
        Slog.d(TAG, "updated " + binding);
        dispatchBindingsChanged();
    }

    public void onSpatialTaskVanished(int taskId) {
        if (!isEnabled()) {
            return;
        }
        SpatialPanelBinding binding = mPanelBindings.get(taskId);
        if (binding == null) {
            return;
        }
        binding.state = BindingState.RELEASED;
        binding.updatedAtMillis = SystemClock.uptimeMillis();
        Slog.i(TAG, "released " + binding);
        mPanelBindings.remove(taskId);
        dispatchBindingsChanged();
    }

    public void onMainDisplayChanged(int displayId, int widthDp, int heightDp) {
        if (!isEnabled() || !isMainDisplayEnabled() || displayId != 0) {
            return;
        }
        SpatialPanelBinding binding = mPanelBindings.get(MAIN_DISPLAY_PANEL_ID);
        if (binding == null) {
            binding = SpatialPanelBinding.createMainDisplay(displayId, widthDp, heightDp);
            mPanelBindings.put(MAIN_DISPLAY_PANEL_ID, binding);
            updateDebugBindingState();
            Slog.i(TAG, "created " + binding);
            dispatchBindingsChanged();
            return;
        }
        binding.updateMainDisplay(widthDp, heightDp);
        updateDebugBindingState();
        Slog.d(TAG, "updated " + binding);
        dispatchBindingsChanged();
    }

    public void onMainDisplayMirrorStateChanged(int displayId, boolean mirrorReady) {
        if (!isEnabled() || !isMainDisplayEnabled() || displayId != 0) {
            return;
        }
        SpatialPanelBinding binding = mPanelBindings.get(MAIN_DISPLAY_PANEL_ID);
        if (binding == null) {
            binding = SpatialPanelBinding.createMainDisplay(displayId, 0, 0);
            mPanelBindings.put(MAIN_DISPLAY_PANEL_ID, binding);
        }
        binding.updateMainDisplayMirrorState(mirrorReady);
        updateDebugBindingState();
        dispatchBindingsChanged();
    }

    private boolean isMainDisplayEnabled() {
        return SystemProperties.getBoolean(MAIN_DISPLAY_PROPERTY, true);
    }

    /**
     * Registers a render backend as a consumer of Shell-owned spatial panel bindings.
     *
     * <p>This is deliberately in-process for the first lifecycle migration step. A future external
     * binder bridge can expose the same snapshot contract to a temporary debug Activity without
     * making that Activity the owner of spatial task state.</p>
     */
    public void registerRenderHost(@NonNull SpatialRenderHost host) {
        if (!mRenderHosts.add(host)) {
            return;
        }
        Slog.i(TAG, "registered render host " + host.getName());
        updateDebugBindingState();
        dispatchBindingsChanged(host, getPanelBindingSnapshots());
    }

    public void unregisterRenderHost(@NonNull SpatialRenderHost host) {
        if (!mRenderHosts.remove(host)) {
            return;
        }
        Slog.i(TAG, "unregistered render host " + host.getName());
        updateDebugBindingState();
        dispatchBindingsChanged();
    }

    @NonNull
    public List<SpatialPanelBindingSnapshot> getPanelBindingSnapshots() {
        if (mPanelBindings.size() == 0) {
            return Collections.emptyList();
        }
        ArrayList<SpatialPanelBindingSnapshot> snapshots = new ArrayList<>(mPanelBindings.size());
        for (int i = 0; i < mPanelBindings.size(); i++) {
            snapshots.add(mPanelBindings.valueAt(i).toSnapshot());
        }
        return Collections.unmodifiableList(snapshots);
    }

    public void dump(PrintWriter pw, String prefix) {
        boolean enabled = isEnabled();
        pw.println(prefix + "SpatialCompositorController enabled=" + enabled
                + " mainDisplay=" + isMainDisplayEnabled()
                + " uptimeMs=" + (SystemClock.uptimeMillis() - mStartedAtMillis));
        if (!enabled) {
            pw.println(prefix + "  set " + ENABLED_PROPERTY
                    + "=true to trace shell-owned compositor bindings");
            return;
        }
        pw.println(prefix + "  backend=metadata-only shell registry");
        pw.println(prefix + "  renderHosts=" + mRenderHosts.size());
        for (int i = 0; i < mRenderHosts.size(); i++) {
            SpatialRenderHost host = mRenderHosts.valueAt(i);
            pw.println(prefix + "    " + host.getName());
        }
        pw.println(prefix + "  " + mPanelBindings.size() + " panel bindings");
        for (int i = 0; i < mPanelBindings.size(); i++) {
            pw.println(prefix + "  " + mPanelBindings.valueAt(i));
        }
    }

    private void dispatchBindingsChanged() {
        if (mRenderHosts.isEmpty()) {
            return;
        }
        List<SpatialPanelBindingSnapshot> snapshots = getPanelBindingSnapshots();
        for (int i = 0; i < mRenderHosts.size(); i++) {
            dispatchBindingsChanged(mRenderHosts.valueAt(i), snapshots);
        }
    }

    private void dispatchBindingsChanged(SpatialRenderHost host,
            List<SpatialPanelBindingSnapshot> snapshots) {
        try {
            host.onSpatialPanelBindingsChanged(snapshots);
        } catch (RuntimeException e) {
            Slog.w(TAG, "render host callback failed host=" + host.getName(), e);
        }
    }

    private void updateDebugBindingState() {
        for (int i = 0; i < mPanelBindings.size(); i++) {
            SpatialPanelBinding binding = mPanelBindings.valueAt(i);
            if (binding.state == BindingState.RELEASED || !binding.hasRenderableTask()) {
                continue;
            }
            binding.state = mRenderHosts.isEmpty()
                    ? BindingState.PENDING_RENDER_HOST
                    : BindingState.BOUND_DEBUG;
            binding.updatedAtMillis = SystemClock.uptimeMillis();
        }
    }

    private enum BindingState {
        PENDING_RENDER_HOST,
        BOUND_DEBUG,
        RELEASED
    }

    private enum BindingSourceType {
        TASK,
        MAIN_DISPLAY
    }

    public static final class SpatialPanelBindingSnapshot {
        public final int taskId;
        public final int displayId;
        public final boolean visible;
        public final String topActivity;
        public final String transform;
        public final String state;
        public final String sourceType;
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
        public final long createdAtMillis;
        public final long updatedAtMillis;

        SpatialPanelBindingSnapshot(int taskId, int displayId, boolean visible,
                String topActivity, String transform, String state, String sourceType,
                String renderingMode, String surfaceState, boolean hasTaskLeash,
                boolean taskLeashValid, int taskLeashIdentity, float x, float y, float z,
                float scale, float rotationX, float rotationY, float rotationZ,
                long createdAtMillis, long updatedAtMillis) {
            this.taskId = taskId;
            this.displayId = displayId;
            this.visible = visible;
            this.topActivity = topActivity;
            this.transform = transform;
            this.state = state;
            this.sourceType = sourceType;
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
            this.createdAtMillis = createdAtMillis;
            this.updatedAtMillis = updatedAtMillis;
        }

        @Override
        public String toString() {
            return "SpatialPanelBindingSnapshot{taskId=" + taskId
                    + " state=" + state
                    + " display=" + displayId
                    + " visible=" + visible
                    + " top=" + topActivity
                    + " sourceType=" + sourceType
                    + " renderingMode=" + renderingMode
                    + " surfaceState=" + surfaceState
                    + " leash=" + hasTaskLeash + "/" + taskLeashValid
                    + " transform=" + transform
                    + "}";
        }
    }

    private static final class SpatialPanelBinding {
        final int taskId;
        final long createdAtMillis = SystemClock.uptimeMillis();
        BindingSourceType sourceType = BindingSourceType.TASK;
        BindingState state = BindingState.PENDING_RENDER_HOST;
        long updatedAtMillis = createdAtMillis;
        int displayId;
        boolean visible;
        String topActivity;
        String transform;
        String renderingMode;
        String surfaceState;
        boolean hasTaskLeash;
        boolean taskLeashValid;
        int taskLeashIdentity;
        float x;
        float y;
        float z;
        float scale;
        float rotationX;
        float rotationY;
        float rotationZ;

        SpatialPanelBinding(SpatialTaskController.SpatialTaskSnapshot task) {
            taskId = task.taskId;
            update(task);
        }

        static SpatialPanelBinding createMainDisplay(int displayId, int widthDp, int heightDp) {
            SpatialPanelBinding binding = new SpatialPanelBinding(MAIN_DISPLAY_PANEL_ID);
            binding.sourceType = BindingSourceType.MAIN_DISPLAY;
            binding.state = BindingState.PENDING_RENDER_HOST;
            binding.displayId = displayId;
            binding.visible = true;
            binding.topActivity = "Display 0";
            binding.renderingMode = "DISPLAY_MIRROR_PENDING";
            binding.surfaceState = "DISPLAY_OBSERVED";
            binding.hasTaskLeash = false;
            binding.taskLeashValid = false;
            binding.taskLeashIdentity = 0;
            binding.x = 0.0f;
            binding.y = -0.15f;
            binding.z = -4.25f;
            binding.scale = 1.65f;
            binding.rotationX = -5.0f;
            binding.rotationY = 0.0f;
            binding.rotationZ = 0.0f;
            binding.updateMainDisplay(widthDp, heightDp);
            return binding;
        }

        private SpatialPanelBinding(int panelId) {
            taskId = panelId;
        }

        void update(SpatialTaskController.SpatialTaskSnapshot task) {
            sourceType = BindingSourceType.TASK;
            displayId = task.displayId;
            visible = task.visible;
            topActivity = task.topActivity;
            transform = task.transform;
            renderingMode = task.renderingMode;
            surfaceState = task.surfaceState;
            hasTaskLeash = task.hasTaskLeash;
            taskLeashValid = task.taskLeashValid;
            taskLeashIdentity = task.taskLeashIdentity;
            x = task.x;
            y = task.y;
            z = task.z;
            scale = task.scale;
            rotationX = task.rotationX;
            rotationY = task.rotationY;
            rotationZ = task.rotationZ;
            updatedAtMillis = SystemClock.uptimeMillis();
        }

        void updateMainDisplay(int widthDp, int heightDp) {
            transform = "mainDisplay0 widthDp=" + widthDp + " heightDp=" + heightDp;
            updatedAtMillis = SystemClock.uptimeMillis();
        }

        void updateMainDisplayMirrorState(boolean mirrorReady) {
            renderingMode = mirrorReady ? "DISPLAY_MIRROR_SURFACE_CONTROL"
                    : "DISPLAY_MIRROR_PENDING";
            surfaceState = mirrorReady ? "DISPLAY_MIRROR_READY" : "DISPLAY_OBSERVED";
            updatedAtMillis = SystemClock.uptimeMillis();
        }

        SpatialPanelBindingSnapshot toSnapshot() {
            return new SpatialPanelBindingSnapshot(taskId, displayId, visible, topActivity,
                    transform, state.name(), sourceType.name(), renderingMode, surfaceState, hasTaskLeash,
                    taskLeashValid, taskLeashIdentity, x, y, z, scale, rotationX, rotationY,
                    rotationZ, createdAtMillis, updatedAtMillis);
        }

        boolean hasRenderableTask() {
            return sourceType == BindingSourceType.MAIN_DISPLAY
                    || (topActivity != null && !"null".equals(topActivity));
        }

        @Override
        public String toString() {
            return "SpatialPanelBinding{taskId=" + taskId
                    + " state=" + state
                    + " display=" + displayId
                    + " visible=" + visible
                    + " top=" + topActivity
                    + " sourceType=" + sourceType
                    + " renderingMode=" + renderingMode
                    + " surfaceState=" + surfaceState
                    + " leash=" + hasTaskLeash + "/" + taskLeashValid
                    + "@0x" + Integer.toHexString(taskLeashIdentity)
                    + " transform=" + transform
                    + " ageMs=" + (SystemClock.uptimeMillis() - createdAtMillis)
                    + " updatedAgoMs=" + (SystemClock.uptimeMillis() - updatedAtMillis)
                    + "}";
        }
    }
}
