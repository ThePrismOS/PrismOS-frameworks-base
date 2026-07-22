package com.android.server.prism;

/**
 * Simple holder for skybox state properties.
 */
public class PrismSkybox {
    private static final float[] DEFAULT_CLEAR_COLOR = new float[] {
            0.02f, 0.02f, 0.03f, 1.0f
    };

    private final float[] mSkyboxColor = new float[4];
    private boolean mSkyboxVisible;
    private float mSkyboxSize = 1.0f;
    private String mSkyboxMode = "solid";
    private String mSkyboxCubemap = "none";

    public PrismSkybox() {
        // Initialize to default values
        System.arraycopy(DEFAULT_CLEAR_COLOR, 0, mSkyboxColor, 0, mSkyboxColor.length);
    }

    /** Returns the skybox color as an array [r, g, b, a]. */
    public float[] getSkyboxColor() {
        return mSkyboxColor.clone();
    }

    /** Sets the skybox color. */
    public void setSkyboxColor(float r, float g, float b, float a) {
        mSkyboxColor[0] = r;
        mSkyboxColor[1] = g;
        mSkyboxColor[2] = b;
        mSkyboxColor[3] = a;
    }

    /** Returns whether the skybox is visible. */
    public boolean isSkyboxVisible() {
        return mSkyboxVisible;
    }

    /** Sets whether the skybox is visible. */
    public void setSkyboxVisible(boolean visible) {
        mSkyboxVisible = visible;
    }

    /** Returns the skybox size. */
    public float getSkyboxSize() {
        return mSkyboxSize;
    }

    /** Sets the skybox size. */
    public void setSkyboxSize(float size) {
        mSkyboxSize = size;
    }

    /** Returns the skybox mode (solid, gradient, cubemap). */
    public String getSkyboxMode() {
        return mSkyboxMode;
    }

    /** Sets the skybox mode. */
    public void setSkyboxMode(String mode) {
        mSkyboxMode = mode;
    }

    /** Returns the skybox cubemap name. */
    public String getSkyboxCubemap() {
        return mSkyboxCubemap;
    }

    /** Sets the skybox cubemap. */
    public void setSkyboxCubemap(String cubemap) {
        mSkyboxCubemap = cubemap;
    }

    /**
     * Returns a string representation of the skybox state for debugging.
     */
    public String skyboxDump() {
        final StringBuilder sb = new StringBuilder();
        sb.append("=== Skybox ===\n");
        sb.append("Visible: ").append(mSkyboxVisible).append('\n');
        sb.append("Color: [").append(mSkyboxColor[0]).append(", ")
                .append(mSkyboxColor[1]).append(", ").append(mSkyboxColor[2]).append(", ")
                .append(mSkyboxColor[3]).append("]\n");
        sb.append("Size: ").append(mSkyboxSize).append('\n');
        sb.append("Mode: ").append(mSkyboxMode).append('\n');
        sb.append("Cubemap: ").append(mSkyboxCubemap).append('\n');
        return sb.toString();
    }

    /** Resets the skybox to default values. */
    public void resetSkybox() {
        mSkyboxVisible = false;
        System.arraycopy(DEFAULT_CLEAR_COLOR, 0, mSkyboxColor, 0, mSkyboxColor.length);
        mSkyboxSize = 1.0f;
        mSkyboxMode = "solid";
        mSkyboxCubemap = "none";
    }
}