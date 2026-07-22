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
import android.content.Context;
import android.content.res.Configuration;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Slog;
import android.util.SparseArray;
import android.view.SurfaceControl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.sysui.ShellCommandHandler;
import com.android.wm.shell.sysui.ShellCommandHandler.ShellCommandActionHandler;
import com.android.wm.shell.sysui.ShellInit;

import java.io.PrintWriter;

/**
 * Persistent WMShell-side owner for spatial environment lifecycle experiments.
 *
 * <p>This is the first step away from a user-launched fullscreen compositor activity. It owns
 * shell-level spatial session state and observes displays/tasks, but does not yet create rendering
 * surfaces or change task placement. The current GLES app remains the rendering backend until Shell
 * has explicit SurfaceControl ownership.</p>
 */
public final class SpatialEnvironmentController implements DisplayController.OnDisplaysChangedListener,
        ShellCommandActionHandler {
    private static final String TAG = "SpatialEnvironmentController";

    public static final String ENABLED_PROPERTY = "persist.wm.debug.spatial_environment";

    private final ShellCommandHandler mShellCommandHandler;
    private final DisplayController mDisplayController;
    private final RootTaskDisplayAreaOrganizer mRootTaskDisplayAreaOrganizer;
    private final SparseArray<SpatialDisplayRecord> mDisplays = new SparseArray<>();
    private final SpatialCompositorController mCompositorController =
            new SpatialCompositorController();
    private final SpatialTaskController mTaskController =
            new SpatialTaskController(mCompositorController);
    private final SpatialDisplayMirrorController mDisplayMirrorController =
            new SpatialDisplayMirrorController();
    private final SpatialDebugRenderHostBridge mDebugRenderHostBridge;

    private final boolean mEnabled;
    private long mInitializedAtMillis;
    private boolean mInitialized;
    private boolean mDebugRenderHostRegistered;

    public SpatialEnvironmentController(
            @NonNull Context context,
            @NonNull ShellInit shellInit,
            @NonNull ShellCommandHandler shellCommandHandler,
            @NonNull DisplayController displayController,
            @NonNull RootTaskDisplayAreaOrganizer rootTaskDisplayAreaOrganizer) {
        mShellCommandHandler = shellCommandHandler;
        mDisplayController = displayController;
        mRootTaskDisplayAreaOrganizer = rootTaskDisplayAreaOrganizer;
        mDebugRenderHostBridge = new SpatialDebugRenderHostBridge(context);
        mEnabled = SystemProperties.getBoolean(ENABLED_PROPERTY, false);
        shellInit.addInitCallback(this::onInit, this);
    }

    private void onInit() {
        mInitialized = true;
        mInitializedAtMillis = SystemClock.uptimeMillis();
        mShellCommandHandler.addDumpCallback(this::dump, this);
        mShellCommandHandler.addCommandCallback("spatial", this, this);
        mDisplayController.addDisplayWindowListener(this);
        ensureDebugRenderHostBridge();
        if (mEnabled) {
            Slog.i(TAG, "initialized persistent spatial environment shell state");
        }
    }

    public SpatialTaskController getTaskController() {
        return mTaskController;
    }

    public SpatialCompositorController getCompositorController() {
        return mCompositorController;
    }

    public void onTaskAppeared(RunningTaskInfo taskInfo, SurfaceControl leash) {
        ensureDebugRenderHostBridge();
        mTaskController.onTaskAppeared(taskInfo, leash);
    }

    public void onTaskInfoChanged(RunningTaskInfo taskInfo) {
        ensureDebugRenderHostBridge();
        mTaskController.onTaskInfoChanged(taskInfo);
    }

    public void onTaskVanished(RunningTaskInfo taskInfo) {
        ensureDebugRenderHostBridge();
        mTaskController.onTaskVanished(taskInfo);
    }

    @Override
    public void onDisplayAdded(int displayId) {
        SpatialDisplayRecord record = new SpatialDisplayRecord(displayId);
        mDisplays.put(displayId, record);
        boolean mirrorReady = mDisplayMirrorController.ensureMirrorForDisplay(displayId);
        mCompositorController.onMainDisplayMirrorStateChanged(displayId, mirrorReady);
        mCompositorController.onMainDisplayChanged(displayId, 0, 0);
        if (mEnabled) {
            Slog.i(TAG, "display added " + record);
        }
    }

    @Override
    public void onDisplayConfigurationChanged(int displayId, Configuration newConfig) {
        SpatialDisplayRecord record = mDisplays.get(displayId);
        if (record == null) {
            record = new SpatialDisplayRecord(displayId);
            mDisplays.put(displayId, record);
        }
        record.configurationChanges++;
        record.widthDp = newConfig.screenWidthDp;
        record.heightDp = newConfig.screenHeightDp;
        boolean mirrorReady = mDisplayMirrorController.ensureMirrorForDisplay(displayId);
        mCompositorController.onMainDisplayMirrorStateChanged(displayId, mirrorReady);
        mCompositorController.onMainDisplayChanged(displayId, record.widthDp, record.heightDp);
        if (mEnabled) {
            Slog.d(TAG, "display changed " + record);
        }
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        mDisplays.remove(displayId);
        if (mEnabled) {
            Slog.i(TAG, "display removed displayId=" + displayId);
        }
    }

    private void dump(PrintWriter pw, String prefix) {
        ensureDebugRenderHostBridge();
        pw.println(prefix + TAG);
        pw.println(prefix + "  enabled=" + mEnabled
                + " initialized=" + mInitialized
                + " uptimeMs=" + (mInitialized ? SystemClock.uptimeMillis() - mInitializedAtMillis : 0));
        pw.println(prefix + "  displays=" + mDisplays.size());
        for (int i = 0; i < mDisplays.size(); i++) {
            pw.println(prefix + "    " + mDisplays.valueAt(i));
        }
        pw.println(prefix + "  rootTaskDisplayAreaOrganizer="
                + mRootTaskDisplayAreaOrganizer.getClass().getSimpleName());
        mTaskController.dump(pw, prefix + "  ");
        mCompositorController.dump(pw, prefix + "  ");
        mDisplayMirrorController.dump(pw, prefix + "  ");
        pw.println(prefix + "  compositorBackend=activity-backed GLES debug client");
        pw.println(prefix + "  nextBackend=shell-owned Display 0 mirror, no app relaunch");
    }

    private void ensureDebugRenderHostBridge() {
        if (!mDebugRenderHostBridge.isEnabled()) {
            return;
        }
        if (!mDebugRenderHostRegistered) {
            mCompositorController.registerRenderHost(mDebugRenderHostBridge);
            mDebugRenderHostRegistered = true;
        }
        mDebugRenderHostBridge.bind();
    }

    @Override
    public boolean onShellCommand(String[] args, PrintWriter pw) {
        if (args.length == 0 || "dump".equals(args[0])) {
            dump(pw, "");
            return true;
        }
        if ("help".equals(args[0])) {
            printShellCommandHelp(pw, "");
            return true;
        }
        pw.println("Unknown spatial command: " + args[0]);
        printShellCommandHelp(pw, "");
        return false;
    }

    @Override
    public void printShellCommandHelp(PrintWriter pw, String prefix) {
        pw.println(prefix + "dump");
        pw.println(prefix
                + "  Dump persistent spatial shell session, displays, tasks, and compositor bindings.");
        pw.println(prefix + "help");
        pw.println(prefix + "  Print this help text.");
    }

    private static final class SpatialDisplayRecord {
        final int displayId;
        int configurationChanges;
        int widthDp;
        int heightDp;

        SpatialDisplayRecord(int displayId) {
            this.displayId = displayId;
        }

        @Override
        public String toString() {
            return "displayId=" + displayId
                    + " configurationChanges=" + configurationChanges
                    + " widthDp=" + widthDp
                    + " heightDp=" + heightDp;
        }
    }
}
