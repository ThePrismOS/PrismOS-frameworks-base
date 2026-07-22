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

import androidx.annotation.NonNull;

import java.util.List;

/**
 * Temporary render backend hook for shell-owned spatial panel bindings.
 *
 * <p>The current GLES prototype can eventually register as a debug implementation of this
 * interface. Shell remains the owner of task-to-panel lifecycle state; render hosts only consume
 * immutable snapshots and project them into their own renderer-specific scene graph.</p>
 */
public interface SpatialRenderHost {
    /** Returns a short name used in dumps and tracing. */
    @NonNull
    String getName();

    /** Called whenever Shell creates, updates, or removes spatial panel bindings. */
    void onSpatialPanelBindingsChanged(
            @NonNull List<SpatialCompositorController.SpatialPanelBindingSnapshot> bindings);
}
