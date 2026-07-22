package com.android.server.prism;

import android.annotation.NonNull;

/**
 * Local in-process API for Prism renderer clients inside system_server.
 */
public interface PrismRenderer {
    int createPlane(@NonNull String name, @NonNull float[] modelMatrix,
            @NonNull PrismTextureSource textureSource);

    void destroyPlane(int planeId);

    void updatePlaneTransform(int planeId, @NonNull float[] modelMatrix);

    void updatePlaneTexture(int planeId, @NonNull PrismTextureSource textureSource);
}
