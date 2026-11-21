
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

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;

import android.annotation.Nullable;
import android.graphics.Rect;
import android.app.ActivityOptions;
import android.content.pm.ActivityInfo.WindowLayout;

/**
 * PrismOS launch policy.
 *
 * While the spatial scene is active, application tasks without an
 * explicitly requested windowing mode are launched in freeform mode.
 */
final class PrismLaunchParamsModifier
        implements LaunchParamsController.LaunchParamsModifier {

    @Override
    public int onCalculate(
            @Nullable Task task,
            @Nullable WindowLayout layout,
            @Nullable ActivityRecord activity,
            @Nullable ActivityRecord source,
            @Nullable ActivityOptions options,
            @Nullable ActivityStarter.Request request,
            int phase,
            LaunchParamsController.LaunchParams currentParams,
            LaunchParamsController.LaunchParams outParams) {

        // Only modify windowing mode calculation.
        if (phase != PHASE_WINDOWING_MODE) {
            return RESULT_CONTINUE;
        }

        // Preserve parameters calculated by previous modifiers.
        outParams.set(currentParams);

        // Spatial windowing must be active.
        if (!PrismWindowPolicy.spatialWindowingActive()) {
            return RESULT_CONTINUE;
        }

        // PrismOS tablet format: spatial tasks launch with a landscape tablet buffer so
        // applications render their tablet layout inside the world-space window.
        if (phase == PHASE_BOUNDS && PrismWindowPolicy.spatialWindowingActive()
                && (outParams.mBounds == null || outParams.mBounds.isEmpty())) {
            outParams.mBounds.set(0, 0, 1800, 1125);
            return RESULT_CONTINUE;
        }
        // Apply freeform only when no windowing mode was requested.
        if (outParams.mWindowingMode == WINDOWING_MODE_UNDEFINED) {
            outParams.mWindowingMode = WINDOWING_MODE_FREEFORM;
            // The controller iterates from the last registered modifier back toward the
            // platform one; this modifier is registered last, so stopping the chain here is
            // the only way the freeform decision survives TaskLaunchParamsModifier.
            return RESULT_DONE;
        }

        return RESULT_CONTINUE;
    }
}
