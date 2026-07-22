/*
 * Copyright (c) 2023 The LineageOS Project
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

package com.android.server.prism;

/**
 * Interface for the PrismXRService Binder interface.
 * This interface is used for developer and debugging commands only.
 * Internal communication uses LocalServices.
 */
interface IPrismManager {
    // Dump command for debugging
    String dump();

    // Camera control commands
    String dumpCamera();
    void cameraMove(float x, float y, float z);
    void cameraMoveWorld(float x, float y, float z);
    void cameraRotate(float yaw, float pitch, float roll);
    void cameraLookAt(float x, float y, float z);

    // Renderer commands
    String listPlanes();
    String dumpPlane(int id);
    void rendererReload();
    void rendererRecreate();
}
