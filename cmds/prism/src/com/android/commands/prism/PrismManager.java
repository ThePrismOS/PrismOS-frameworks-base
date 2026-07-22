/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.commands.prism;

import android.os.ServiceManager;
import android.util.Slog;

import com.android.server.prism.IPrismManager;

/**
 * Manager for interacting with the PrismXRService.
 * This class uses the generated IPrismManager Binder interface to communicate with the service.
 */
public final class PrismManager {
    private static final String TAG = "PrismManager";
    private static final String SERVICE_NAME = "prism";

    private PrismManager() {
        // Prevent instantiation.
    }

    public static IPrismManager getService() {
        return IPrismManager.Stub.asInterface(ServiceManager.getService(SERVICE_NAME));
    }

    public static String dump() {
        final IPrismManager service = getService();
        if (service != null) {
            try {
                return service.dump();
            } catch (Exception e) {
                Slog.e(TAG, "Failed to call dump on Prism service", e);
            }
        }
        return "Prism service not available";
    }

    public static String dumpCamera() {
        final IPrismManager service = getService();
        if (service != null) {
            try {
                return service.dumpCamera();
            } catch (Exception e) {
                Slog.e(TAG, "Failed to call dumpCamera on Prism service", e);
            }
        }
        return "Prism service not available";
    }

    public static void cameraMove(float x, float y, float z) {
        final IPrismManager service = getService();
        if (service != null) {
            try {
                service.cameraMove(x, y, z);
            } catch (Exception e) {
                Slog.e(TAG, "Failed to call cameraMove on Prism service", e);
            }
        }
    }

    public static void cameraMoveWorld(float x, float y, float z) {
        final IPrismManager service = getService();
        if (service != null) {
            try {
                service.cameraMoveWorld(x, y, z);
            } catch (Exception e) {
                Slog.e(TAG, "Failed to call cameraMoveWorld on Prism service", e);
            }
        }
    }

    public static void cameraRotate(float yaw, float pitch, float roll) {
        final IPrismManager service = getService();
        if (service != null) {
            try {
                service.cameraRotate(yaw, pitch, roll);
            } catch (Exception e) {
                Slog.e(TAG, "Failed to call cameraRotate on Prism service", e);
            }
        }
    }

    public static void cameraLookAt(float x, float y, float z) {
        final IPrismManager service = getService();
        if (service != null) {
            try {
                service.cameraLookAt(x, y, z);
            } catch (Exception e) {
                Slog.e(TAG, "Failed to call cameraLookAt on Prism service", e);
            }
        }
    }

    public static String listPlanes() {
        final IPrismManager service = getService();
        if (service != null) {
            try {
                return service.listPlanes();
            } catch (Exception e) {
                Slog.e(TAG, "Failed to call listPlanes on Prism service", e);
            }
        }
        return "Prism service not available";
    }

    public static String dumpPlane(int id) {
        final IPrismManager service = getService();
        if (service != null) {
            try {
                return service.dumpPlane(id);
            } catch (Exception e) {
                Slog.e(TAG, "Failed to call dumpPlane on Prism service", e);
            }
        }
        return "Prism service not available";
    }

    public static void rendererReload() {
        final IPrismManager service = getService();
        if (service != null) {
            try {
                service.rendererReload();
            } catch (Exception e) {
                Slog.e(TAG, "Failed to call rendererReload on Prism service", e);
            }
        }
    }

    public static void rendererRecreate() {
        final IPrismManager service = getService();
        if (service != null) {
            try {
                service.rendererRecreate();
            } catch (Exception e) {
                Slog.e(TAG, "Failed to call rendererRecreate on Prism service", e);
            }
        }
    }
}
