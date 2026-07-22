package com.android.server.prism;

import android.opengl.Matrix;
import com.android.server.prism.PrismTextureSource;

/**
 * Represents a renderable plane in the Prism scene.
 */
public class PrismPlane {
    final int id;
    final String name;
    final float[] modelMatrix = new float[16];
    final float[] initialModelMatrix = new float[16];
    final float[] color = new float[] { 1.0f, 1.0f, 1.0f, 1.0f };
    final float[] initialColor = new float[] { 1.0f, 1.0f, 1.0f, 1.0f };
    PrismTextureSource textureSource;
    PrismTextureSource initialTextureSource;
    int textureId;
    boolean textureDirty = true;
    boolean visible = true;
    boolean initialVisible = true;
    float posX;
    float posY;
    float posZ;
    float yaw;
    float pitch;
    float roll;
    float scaleX = 1.0f;
    float scaleY = 1.0f;
    float scaleZ = 1.0f;
    float initialPosX;
    float initialPosY;
    float initialPosZ;
    float initialYaw;
    float initialPitch;
    float initialRoll;
    float initialScaleX = 1.0f;
    float initialScaleY = 1.0f;
    float initialScaleZ = 1.0f;

    PrismPlane(int planeId, String planeName, float[] matrix, PrismTextureSource source) {
        id = planeId;
        name = planeName;
        textureSource = source;
        initialTextureSource = source;
        Matrix.setIdentityM(modelMatrix, 0);
        System.arraycopy(matrix, 0, modelMatrix, 0,
                Math.min(modelMatrix.length, modelMatrix.length));
        System.arraycopy(modelMatrix, 0, initialModelMatrix, 0, initialModelMatrix.length);
        loadStateFromMatrix(matrix);
        captureInitialState();
    }

    void applyModelMatrix(float[] matrix) {
        System.arraycopy(matrix, 0, modelMatrix, 0,
                Math.min(matrix.length, modelMatrix.length));
        loadStateFromMatrix(matrix);
    }

    void setPosition(float x, float y, float z) {
        posX = x;
        posY = y;
        posZ = z;
        rebuildModelMatrix();
    }

    void move(float dx, float dy, float dz) {
        posX += dx;
        posY += dy;
        posZ += dz;
        rebuildModelMatrix();
    }

    void setRotation(float yaw, float pitch, float roll) {
        this.yaw = yaw;
        this.pitch = pitch;
        this.roll = roll;
        rebuildModelMatrix();
    }

    void setScale(float sx, float sy, float sz) {
        scaleX = sx;
        scaleY = sy;
        scaleZ = sz;
        rebuildModelMatrix();
    }

    void setColor(float r, float g, float b, float a) {
        color[0] = r;
        color[1] = g;
        color[2] = b;
        color[3] = a;
        textureDirty = true;
    }

    void setVisible(boolean visible) {
        this.visible = visible;
    }

    void reset() {
        posX = initialPosX;
        posY = initialPosY;
        posZ = initialPosZ;
        yaw = initialYaw;
        pitch = initialPitch;
        roll = initialRoll;
        scaleX = initialScaleX;
        scaleY = initialScaleY;
        scaleZ = initialScaleZ;
        color[0] = initialColor[0];
        color[1] = initialColor[1];
        color[2] = initialColor[2];
        color[3] = initialColor[3];
        visible = initialVisible;
        textureSource = initialTextureSource;
        System.arraycopy(initialModelMatrix, 0, modelMatrix, 0, modelMatrix.length);
        textureDirty = true;
    }

    private void captureInitialState() {
        initialPosX = posX;
        initialPosY = posY;
        initialPosZ = posZ;
        initialYaw = yaw;
        initialPitch = pitch;
        initialRoll = roll;
        initialScaleX = scaleX;
        initialScaleY = scaleY;
        initialScaleZ = scaleZ;
        initialColor[0] = color[0];
        initialColor[1] = color[1];
        initialColor[2] = color[2];
        initialColor[3] = color[3];
        initialVisible = visible;
    }

    private void loadStateFromMatrix(float[] matrix) {
        posX = matrix.length > 12 ? matrix[12] : 0.0f;
        posY = matrix.length > 13 ? matrix[13] : 0.0f;
        posZ = matrix.length > 14 ? matrix[14] : 0.0f;

        final float sx = vectorLength(matrix, 0, 1, 2);
        final float sy = vectorLength(matrix, 4, 5, 6);
        final float sz = vectorLength(matrix, 8, 9, 10);
        scaleX = sx > 0.0f ? sx : 1.0f;
        scaleY = sy > 0.0f ? sy : 1.0f;
        scaleZ = sz > 0.0f ? sz : 1.0f;

        yaw = 0.0f;
        pitch = 0.0f;
        roll = 0.0f;
    }

    private void rebuildModelMatrix() {
        Matrix.setIdentityM(modelMatrix, 0);
        Matrix.translateM(modelMatrix, 0, posX, posY, posZ);
        Matrix.rotateM(modelMatrix, 0, yaw, 0.0f, 1.0f, 0.0f);
        Matrix.rotateM(modelMatrix, 0, pitch, 1.0f, 0.0f, 0.0f);
        Matrix.rotateM(modelMatrix, 0, roll, 0.0f, 0.0f, 1.0f);
        Matrix.scaleM(modelMatrix, 0, scaleX, scaleY, scaleZ);
    }

    private static float vectorLength(float[] matrix, int xIndex, int yIndex, int zIndex) {
        final float x = matrix.length > xIndex ? matrix[xIndex] : 0.0f;
        final float y = matrix.length > yIndex ? matrix[yIndex] : 0.0f;
        final float z = matrix.length > zIndex ? matrix[zIndex] : 0.0f;
        return (float) Math.sqrt(x * x + y * y + z * z);
    }
}