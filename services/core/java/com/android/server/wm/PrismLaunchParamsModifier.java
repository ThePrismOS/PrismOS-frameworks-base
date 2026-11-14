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

/**
 * PrismOS launch policy: while the spatial scene is active, application tasks that did
 * not explicitly request a windowing mode launch in freeform. Freeform tasks keep their
 * surfaces alive and independently placed, which is what the spatial scene needs to
 * present them as world-space windows — a fullscreen task would be occluded and
 * surface-detached the moment the next task launches.
 *
 * Runs last in the {@link LaunchParamsController} chain: explicit windowing modes
 * (games, dialogs, options-driven launches) are respected; only the "no preference"
 * case is redirected.
 */
final class PrismLaunchParamsModifier implements LaunchParamsController.LaunchParamsModifier {

    @Override
    public int onCalculate(@Nullable Task task, @Nullable WindowLayout layout,
            @Nullable ActivityRecord activity, @Nullable ActivityRecord source,
            @Nullable ActivityOptions options, @Nullable ActivityStarter.Request request,
            int phase, LaunchParamsController.LaunchParams outParams) {
        if (phase != PHASE_WINDOWING_MODE) {
            return RESULT_CONTINUE;
        }
        if (!PrismWindowPolicy.spatialWindowingActive()) {
            return RESULT_CONTINUE;
        }
        if (outParams.mWindowingMode == WINDOWING_MODE_UNDEFINED) {
            outParams.mWindowingMode = WINDOWING_MODE_FREEFORM;
        }
        return RESULT_CONTINUE;
    }
}
