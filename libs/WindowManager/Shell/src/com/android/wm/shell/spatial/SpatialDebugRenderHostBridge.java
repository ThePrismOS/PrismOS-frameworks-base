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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.Slog;

import androidx.annotation.NonNull;

import java.util.List;

/**
 * Temporary WMShell-to-prototype renderer bridge.
 *
 * <p>WMShell remains authoritative: it registers this object as a {@link SpatialRenderHost},
 * converts shell-owned panel snapshots into an immutable bundle, and pushes that state to the
 * debug GLES backend if it is available. If the backend dies or starts late, the latest state is
 * replayed after reconnect.</p>
 */
public final class SpatialDebugRenderHostBridge implements SpatialRenderHost {
    private static final String TAG = "SpatialDebugRenderHostBridge";

    public static final String ENABLED_PROPERTY = "persist.wm.debug.spatial_render_host";

    public static final String KEY_SEQUENCE = "sequence";
    public static final String KEY_SENT_AT_MILLIS = "sent_at_millis";
    public static final String KEY_COUNT = "count";
    public static final String KEY_TASK_IDS = "task_ids";
    public static final String KEY_DISPLAY_IDS = "display_ids";
    public static final String KEY_VISIBLE = "visible";
    public static final String KEY_TOP_ACTIVITIES = "top_activities";
    public static final String KEY_STATES = "states";
    public static final String KEY_SOURCE_TYPES = "source_types";
    public static final String KEY_RENDERING_MODES = "rendering_modes";
    public static final String KEY_SURFACE_STATES = "surface_states";
    public static final String KEY_HAS_TASK_LEASH = "has_task_leash";
    public static final String KEY_TASK_LEASH_VALID = "task_leash_valid";
    public static final String KEY_TASK_LEASH_IDENTITIES = "task_leash_identities";
    public static final String KEY_X = "x";
    public static final String KEY_Y = "y";
    public static final String KEY_Z = "z";
    public static final String KEY_SCALE = "scale";
    public static final String KEY_ROTATION_X = "rotation_x";
    public static final String KEY_ROTATION_Y = "rotation_y";
    public static final String KEY_ROTATION_Z = "rotation_z";

    private static final ComponentName DEBUG_RENDER_HOST_COMPONENT = new ComponentName(
            "com.doublevr.spatialsurface",
            "com.doublevr.spatialsurface.SpatialRenderHostService");

    private final Context mContext;
    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mRemoteHost = ISpatialRenderHost.Stub.asInterface(service);
            mConnectedAtMillis = SystemClock.uptimeMillis();
            Slog.i(TAG, "connected debug spatial render host " + name);
            sendLatestState();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Slog.w(TAG, "debug spatial render host disconnected " + name);
            mRemoteHost = null;
        }

        @Override
        public void onBindingDied(ComponentName name) {
            Slog.w(TAG, "debug spatial render host binding died " + name);
            mRemoteHost = null;
            mBound = false;
            bind();
        }
    };

    private ISpatialRenderHost mRemoteHost;
    private Bundle mLatestState;
    private boolean mBound;
    private int mSequence;
    private int mSentCallbacks;
    private long mConnectedAtMillis;

    public SpatialDebugRenderHostBridge(@NonNull Context context) {
        mContext = context;
    }

    public boolean isEnabled() {
        return SystemProperties.getBoolean(ENABLED_PROPERTY, true);
    }

    public void bind() {
        if (!isEnabled() || mBound) {
            return;
        }
        Intent intent = new Intent().setComponent(DEBUG_RENDER_HOST_COMPONENT);
        mBound = mContext.bindServiceAsUser(intent, mConnection, Context.BIND_AUTO_CREATE,
                UserHandle.SYSTEM);
        Slog.i(TAG, "bind debug spatial render host bound=" + mBound
                + " component=" + DEBUG_RENDER_HOST_COMPONENT);
    }

    public void unbind() {
        if (!mBound) {
            return;
        }
        mContext.unbindService(mConnection);
        mBound = false;
        mRemoteHost = null;
    }

    @NonNull
    @Override
    public String getName() {
        return "debug-gles-service"
                + "{enabled=" + isEnabled()
                + " bound=" + mBound
                + " connected=" + (mRemoteHost != null)
                + " sent=" + mSentCallbacks
                + "}";
    }

    @Override
    public void onSpatialPanelBindingsChanged(
            @NonNull List<SpatialCompositorController.SpatialPanelBindingSnapshot> bindings) {
        mLatestState = buildBundle(bindings);
        sendLatestState();
    }

    private Bundle buildBundle(
            List<SpatialCompositorController.SpatialPanelBindingSnapshot> bindings) {
        int count = bindings.size();
        int[] taskIds = new int[count];
        int[] displayIds = new int[count];
        boolean[] visible = new boolean[count];
        String[] topActivities = new String[count];
        String[] states = new String[count];
        String[] sourceTypes = new String[count];
        String[] renderingModes = new String[count];
        String[] surfaceStates = new String[count];
        boolean[] hasTaskLeash = new boolean[count];
        boolean[] taskLeashValid = new boolean[count];
        int[] taskLeashIdentities = new int[count];
        float[] x = new float[count];
        float[] y = new float[count];
        float[] z = new float[count];
        float[] scale = new float[count];
        float[] rotationX = new float[count];
        float[] rotationY = new float[count];
        float[] rotationZ = new float[count];

        for (int i = 0; i < count; i++) {
            SpatialCompositorController.SpatialPanelBindingSnapshot binding = bindings.get(i);
            taskIds[i] = binding.taskId;
            displayIds[i] = binding.displayId;
            visible[i] = binding.visible;
            topActivities[i] = binding.topActivity;
            states[i] = binding.state;
            sourceTypes[i] = binding.sourceType;
            renderingModes[i] = binding.renderingMode;
            surfaceStates[i] = binding.surfaceState;
            hasTaskLeash[i] = binding.hasTaskLeash;
            taskLeashValid[i] = binding.taskLeashValid;
            taskLeashIdentities[i] = binding.taskLeashIdentity;
            x[i] = binding.x;
            y[i] = binding.y;
            z[i] = binding.z;
            scale[i] = binding.scale;
            rotationX[i] = binding.rotationX;
            rotationY[i] = binding.rotationY;
            rotationZ[i] = binding.rotationZ;
        }

        Bundle bundle = new Bundle();
        bundle.putInt(KEY_SEQUENCE, ++mSequence);
        bundle.putLong(KEY_SENT_AT_MILLIS, SystemClock.uptimeMillis());
        bundle.putInt(KEY_COUNT, count);
        bundle.putIntArray(KEY_TASK_IDS, taskIds);
        bundle.putIntArray(KEY_DISPLAY_IDS, displayIds);
        bundle.putBooleanArray(KEY_VISIBLE, visible);
        bundle.putStringArray(KEY_TOP_ACTIVITIES, topActivities);
        bundle.putStringArray(KEY_STATES, states);
        bundle.putStringArray(KEY_SOURCE_TYPES, sourceTypes);
        bundle.putStringArray(KEY_RENDERING_MODES, renderingModes);
        bundle.putStringArray(KEY_SURFACE_STATES, surfaceStates);
        bundle.putBooleanArray(KEY_HAS_TASK_LEASH, hasTaskLeash);
        bundle.putBooleanArray(KEY_TASK_LEASH_VALID, taskLeashValid);
        bundle.putIntArray(KEY_TASK_LEASH_IDENTITIES, taskLeashIdentities);
        bundle.putFloatArray(KEY_X, x);
        bundle.putFloatArray(KEY_Y, y);
        bundle.putFloatArray(KEY_Z, z);
        bundle.putFloatArray(KEY_SCALE, scale);
        bundle.putFloatArray(KEY_ROTATION_X, rotationX);
        bundle.putFloatArray(KEY_ROTATION_Y, rotationY);
        bundle.putFloatArray(KEY_ROTATION_Z, rotationZ);
        return bundle;
    }

    private void sendLatestState() {
        if (mRemoteHost == null || mLatestState == null) {
            return;
        }
        try {
            mRemoteHost.onSpatialPanelBindingsChanged(mLatestState);
            mSentCallbacks++;
            Slog.d(TAG, "sent spatial panel snapshot sequence="
                    + mLatestState.getInt(KEY_SEQUENCE)
                    + " count=" + mLatestState.getInt(KEY_COUNT));
        } catch (RemoteException e) {
            Slog.w(TAG, "failed to send spatial panel snapshot", e);
            mRemoteHost = null;
        }
    }
}
