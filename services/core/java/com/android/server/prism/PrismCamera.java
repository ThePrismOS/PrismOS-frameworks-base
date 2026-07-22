package com.android.server.prism;
import java.io.FileOutputStream;
import com.android.internal.util.FastPrintWriter;
import android.opengl.Matrix;
import android.util.Log;
import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * PrismCamera owns all camera state and computes view and projection matrices.
 * The renderer should never manually build matrices; it should only use the getters.
 */
public class PrismCamera {
    private float posX, posY, posZ;
    private float yaw, pitch, roll;

    private float fov, aspect, near, far;

    private boolean viewDirty;
    private boolean projectionDirty;
    private boolean viewProjectionDirty;
    private boolean vectorsDirty;

    private final float[] viewMatrix = new float[16];
    private final float[] projectionMatrix = new float[16];
    private final float[] viewProjectionMatrix = new float[16];

    private final float[] rotationMatrix = new float[16];
    private final float[] forward = new float[3];
    private final float[] right = new float[3];
    private final float[] up = new float[3];

    private final float[] tempVec3 = new float[3];
    private final float[] tempVec4 = new float[4];
    private final float[] tempVec4Result = new float[4];

    private float moveSpeed = 1.0f;
    private float rotateSpeed = 1.0f;
    
    public PrismCamera() {
        posX = 0.0f;
        posY = 0.0f;
        posZ = 0.0f;
        yaw = 0.0f;
        pitch = 0.0f;
        roll = 0.0f;
        fov = 60.0f;
        aspect = 1.0f;
        near = 0.1f;
        far = 1000.0f;
        viewDirty = true;
        projectionDirty = true;
        viewProjectionDirty = true;
        vectorsDirty = true;
    }

    /**
     * Sets the camera's position in world space.
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     */
    public void setPosition(float x, float y, float z) {
        if (posX == x && posY == y && posZ == z) {
            return;
        }
        posX = x;
        posY = y;
        posZ = z;
        viewDirty = true;
        viewProjectionDirty = true;
    }

    /**
     * Resets the camera to Prism's default runtime pose and projection values.
     * Keeps the current aspect ratio intact so renderer-provided sizing is preserved.
     */
    public void reset() {
        posX = 0.0f;
        posY = 0.0f;
        posZ = 4.0f;
        yaw = 0.0f;
        pitch = 0.0f;
        roll = 0.0f;
        fov = 60.0f;
        near = 0.1f;
        far = 1000.0f;
        viewDirty = true;
        projectionDirty = true;
        viewProjectionDirty = true;
        vectorsDirty = true;
    }

    /**
     * Sets the camera's yaw rotation (around Y axis) in degrees.
     * @param yaw Yaw angle in degrees
     */
    public void setYaw(float yaw) {
        if (this.yaw == yaw) {
            return;
        }
        this.yaw = yaw;
        viewDirty = true;
        viewProjectionDirty = true;
        vectorsDirty = true;
    }

    /**
     * Sets the camera's pitch rotation (around X axis) in degrees, clamped to ±89°.
     * @param pitch Pitch angle in degrees
     */
    public void setPitch(float pitch) {
        float clampedPitch = Math.max(-89f, Math.min(89f, pitch));
        if (this.pitch == clampedPitch) {
            return;
        }
        this.pitch = clampedPitch;
        viewDirty = true;
        viewProjectionDirty = true;
        vectorsDirty = true;
    }

    /**
     * Sets the camera's roll rotation (around Z axis) in degrees.
     * @param roll Roll angle in degrees
     */
    public void setRoll(float roll) {
        if (this.roll == roll) {
            return;
        }
        this.roll = roll;
        viewDirty = true;
        viewProjectionDirty = true;
        vectorsDirty = true;
    }

    /**
     * Sets the camera's rotation (yaw, pitch, roll) in degrees.
     * Pitch is clamped to ±89°.
     * @param yaw   Yaw angle in degrees
     * @param pitch Pitch angle in degrees
     * @param roll  Roll angle  Roll angle in degrees
     */
    public void setRotation(float yaw, float pitch, float roll) {
        if (this.yaw == yaw && this.pitch == pitch && this.roll == roll) {
            return;
        }
        this.yaw = yaw;
        this.pitch = Math.max(-89f, Math.min(89f, pitch));
        this.roll = roll;
        viewDirty = true;
        viewProjectionDirty = true;
        vectorsDirty = true;
    }

    /**
     * Moves the camera in local space using the current orientation.
     * @param x Translation along camera's right vector
     * @param y Translation along camera's up vector
     * @param z Translation along camera's forward vector
     */
    public void move(float x, float y, float z) {
        translateLocal(x, y, z);
    }

    /**
     * Sets the field of view angle in degrees.
     * @param fov Field of view in degrees
     */
    public void setFov(float fov) {
        if (this.fov == fov) {
            return;
        }
        this.fov = fov;
        projectionDirty = true;
        viewProjectionDirty = true;
    }

    /**
     * Sets the aspect ratio (width/height). Must be positive.
     * @param aspect Aspect ratio
     * @throws IllegalArgumentException if aspect <= 0
     */
    public void setAspect(float aspect) {
        if (aspect <= 0) {
            throw new IllegalArgumentException("Aspect ratio must be positive");
        }
        if (this.aspect == aspect) {
            return;
        }
        this.aspect = aspect;
        projectionDirty = true;
        viewProjectionDirty = true;
    }

    /**
     * Returns the field of view in degrees.
     */
    public float getFov() {
        return fov;
    }

    /**
     * Returns the aspect ratio.
     */
    public float getAspect() {
        return aspect;
    }

    /**
     * Returns the near clipping plane distance.
     */
    public float getNear() {
        return near;
    }

    /**
     * Returns the far clipping plane distance.
     */
    public float getFar() {
        return far;
    }

    /**
     * Copies the camera basis vectors into the provided arrays.
     * Any output array may be null.
     */
    public void copyDirectionVectors(float[] forwardOut, float[] rightOut, float[] upOut) {
        if (vectorsDirty) {
            updateDirectionVectors();
        }
        if (forwardOut != null && forwardOut.length >= 3) {
            forwardOut[0] = forward[0];
            forwardOut[1] = forward[1];
            forwardOut[2] = forward[2];
        }
        if (rightOut != null && rightOut.length >= 3) {
            rightOut[0] = right[0];
            rightOut[1] = right[1];
            rightOut[2] = right[2];
        }
        if (upOut != null && upOut.length >= 3) {
            upOut[0] = up[0];
            upOut[1] = up[1];
            upOut[2] = up[2];
        }
    }

    /**
     * Sets the near clipping plane distance.
     * @param near Near plane distance
     */
    public void setNear(float near) {
        if (this.near == near) {
            return;
        }
        this.near = near;
        projectionDirty = true;
        viewProjectionDirty = true;
    }

    /**
     * Sets the far clipping plane distance.
     * @param far Far plane distance
     */
    public void setFar(float far) {
        if (this.far == far) {
            return;
        }
        this.far = far;
        projectionDirty = true;
        viewProjectionDirty = true;
    }

    /**
     * Sets the movement speed (units per second).
     * @param speed Movement speed
     */
    public void setMoveSpeed(float speed) {
        this.moveSpeed = speed;
    }

    /**
     * Sets the rotation speed (degrees per second).
     * @param speed Rotation speed
     */
    public void setRotateSpeed(float speed) {
        this.rotateSpeed = speed;
    }

    /**
     * Translates the camera in local space (relative to camera orientation).
     * Guarantees vectors are current before use.
     * @param x Translation along camera's right vector
     * @param y Translation along camera's up vector
     * @param z Translation along camera's forward vector
     */
    public void translateLocal(float x, float y, float z) {
        // Ensure vectors are current
        if (vectorsDirty) {
            updateDirectionVectors();
        }
        // Update position: pos += (x*right + y*up + z*forward) * moveSpeed
        posX += (x * right[0] + y * up[0] + z * forward[0]) * moveSpeed;
        posY += (x * right[1] + y * up[1] + z * forward[1]) * moveSpeed;
        posZ += (x * right[2] + y * up[2] + z * forward[2]) * moveSpeed;
        viewDirty = true;
        viewProjectionDirty = true;
    }

    /**
     * Translates the camera in world space.
     * @param x Translation in world X
     * @param y Translation in world Y
     * @param z Translation in world Z */
    public void translateWorld(float x, float y, float z) {
        posX += x * moveSpeed;
        posY += y * moveSpeed;
        posZ += z * moveSpeed;
        viewDirty = true;
        viewProjectionDirty = true;
    }

    /**
     * Moves the camera forward by moveSpeed units in local space.
     */
    public void moveForward() {
        translateLocal(0.0f, 0.0f, 1.0f);
    }

    /**
     * Moves the camera backward by moveSpeed units in local space.
     */
    public void moveBackward() {
        translateLocal(0.0f, 0.0f, -1.0f);
    }

    /**
     * Moves the camera right by moveSpeed units in local space.
     */
    public void moveRight() {
        translateLocal(1.0f, 0.0f, 0.0f);
    }

    /**
     * Moves the camera left by moveSpeed units in local space.
     */
    public void moveLeft() {
        translateLocal(-1.0f, 0.0f, 0.0f);
    }

    /**
     * Moves the camera up by moveSpeed units in local space.
     */
    public void moveUp() {
        translateLocal(0.0f, 1.0f, 0.0f);
    }

    /**
     * Moves the camera down by moveSpeed units in local space.
     */
    public void moveDown() {
        translateLocal(0.0f, -1.0f, 0.0f);
    }

    /**
     * Orients the camera to look at a target point in world space.
     * Current roll is preserved.
     * @param x Target X coordinate
     * @param y Target Y coordinate
     * @param z Target Z coordinate
     */
    public void lookAt(float x, float y, float z) {
        float dx = x - posX;
        float dy = y - posY;
        float dz = z - posZ;
        float distXZ = (float) Math.sqrt(dx * dx + dz * dz);
        // Avoid division by zero
        if (distXZ < 1e-6f) {
            distXZ = 1e-6f;
        }
        // Calculate pitch and yaw in radians, then convert to degrees
        float pitchRad = (float) Math.atan2(dy, distXZ);
        float yawRad = (float) Math.atan2(dx, dz);
        setRotation(
                yawRad * 180.0f / (float) Math.PI,
                pitchRad * 180.0f / (float) Math.PI,
                roll
        );
    }

    /**
     * Returns the camera's position as a new array.
     * @return Position array [x, y, z]
     */
    public float[] getPosition() {
        return new float[]{posX, posY, posZ};
    }

    /**
     * Copies the camera's position into the provided array.
     * @param out Array of length 3 to receive the position
     */
    public void copyPosition(float[] out) {
        if (out == null || out.length < 3) {
            throw new IllegalArgumentException("Output array must have at least 3 elements");
        }
        out[0] = posX;
        out[1] = posY;
        out[2] = posZ;
    }

    /**
     * Returns the camera's yaw rotation in degrees.
     * @return Yaw angle
     */
    public float getYaw() {
        return yaw;
    }

    /**
     * Returns the camera's pitch rotation in degrees.
     * @return Pitch angle
     */
    public float getPitch() {
        return pitch;
    }

    /**
     * Returns the camera's roll rotation in degrees.
     * @return Roll angle
     */
    public float getRoll() {
        return roll;
    }

    /**
     * Returns the view matrix. Recomputes if dirty.
     * @return View matrix (column-major 4x4)
     */
    public float[] getViewMatrix() {
        if (viewDirty) {
            updateViewMatrix();
            viewDirty = false;
        }
        return viewMatrix;
    }

    /**
     * Returns the projection matrix. Recomputes if dirty.
     * @return Projection matrix (column-major 4x4)
     */
    public float[] getProjectionMatrix() {
        if (projectionDirty) {
            updateProjectionMatrix();
            projectionDirty = false;
        }
        return projectionMatrix;
    }

    /**
     * Returns the view-projection matrix. Recomputes if dirty.
     * @return View-projection matrix (column-major 4x4)
     */
    public float[] getViewProjectionMatrix() {
        if (viewProjectionDirty) {
            if (viewDirty) {
                updateViewMatrix();
            }
            if (projectionDirty) {
                updateProjectionMatrix();
            }
            Matrix.multiplyMM(viewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0);
            viewProjectionDirty = false;
        }
        return viewProjectionMatrix;
    }

    /**
     * Updates the view matrix based on current position and orientation.
     * Uses Matrix.setLookAtM for efficiency and correctness.
     * Called when view matrix is dirty.
     */
    private void updateViewMatrix() {
        // Ensure direction vectors are current for lookAt target calculation
        if (vectorsDirty) {
            updateDirectionVectors();
        }
        // Build view matrix using eye position, target (eye + forward), and up vector
        Matrix.setLookAtM(viewMatrix, 0,
                posX, posY, posZ,           // eye
                posX + forward[0], posY + forward[1], posZ + forward[2], // center
                up[0], up[1], up[2]);       // up
    }

    /**
     * Updates the projection matrix based on current FOV, aspect, near, and far.
     * Called when projection matrix is dirty.
     */
    private void updateProjectionMatrix() {
        Matrix.perspectiveM(projectionMatrix, 0, fov, aspect, near, far);
    }

    /**
     * Updates the direction vectors (forward, right, up) based on current rotation.
     * Uses the rotation matrix to derive vectors via Matrix.multiplyMV() and normalizes them.
     * Called when vectors are dirty.
     */
    private void updateDirectionVectors() {
        // Build rotation matrix from yaw, pitch, roll (in degrees)
        Matrix.setIdentityM(rotationMatrix, 0);
        Matrix.rotateM(rotationMatrix, 0, yaw, 0, 1, 0);   // Yaw around Y
        Matrix.rotateM(rotationMatrix, 0, pitch, 1, 0, 0); // Pitch around X
        Matrix.rotateM(rotationMatrix, 0, roll, 0, 0, 1);  // Roll around Z

        // Forward vector: (0, 0, -1) in camera space
        tempVec3[0] = 0.0f;
        tempVec3[1] = 0.0f;
        tempVec3[2] = -1.0f;
        // Convert to homogeneous coordinates
        tempVec4[0] = tempVec3[0];
        tempVec4[1] = tempVec3[1];
        tempVec4[2] = tempVec3[2];
        tempVec4[3] = 1.0f;
        // Rotate the vector
        Matrix.multiplyMV(tempVec4Result, 0, rotationMatrix, 0, tempVec4, 0);
        // Extract and normalize
        float len = (float) Math.sqrt(
                tempVec4Result[0] * tempVec4Result[0] +
                tempVec4Result[1] * tempVec4Result[1] +
                tempVec4Result[2] * tempVec4Result[2]);
        if (len > 0) {
            forward[0] = tempVec4Result[0] / len;
            forward[1] = tempVec4Result[1] / len;
            forward[2] = tempVec4Result[2] / len;
        } else {
            forward[0] = 0.0f;
            forward[1] = 0.0f;
            forward[2] = -1.0f;
        }

        // Right vector: (1, 0, 0) in camera space
        tempVec3[0] = 1.0f;
        tempVec3[1] = 0.0f;
        tempVec3[2] = 0.0f;
        tempVec4[0] = tempVec3[0];
        tempVec4[1] = tempVec3[1];
        tempVec4[2] = tempVec3[2];
        tempVec4[3] = 1.0f;
        Matrix.multiplyMV(tempVec4Result, 0, rotationMatrix, 0, tempVec4, 0);
        len = (float) Math.sqrt(
                tempVec4Result[0] * tempVec4Result[0] +
                tempVec4Result[1] * tempVec4Result[1] +
                tempVec4Result[2] * tempVec4Result[2]);
        if (len > 0) {
            right[0] = tempVec4Result[0] / len;
            right[1] = tempVec4Result[1] / len;
            right[2] = tempVec4Result[2] / len;
        } else {
            right[0] = 1.0f;
            right[1] = 0.0f;
            right[2] = 0.0f;
        }

        // Up vector: (0, 1, 0) in camera space
        tempVec3[0] = 0.0f;
        tempVec3[1] = 1.0f;
        tempVec3[2] = 0.0f;
        tempVec4[0] = tempVec3[0];
        tempVec4[1] = tempVec3[1];
        tempVec4[2] = tempVec3[2];
        tempVec4[3] = 1.0f;
        Matrix.multiplyMV(tempVec4Result, 0, rotationMatrix, 0, tempVec4, 0);
        len = (float) Math.sqrt(
                tempVec4Result[0] * tempVec4Result[0] +
                tempVec4Result[1] * tempVec4Result[1] +
                tempVec4Result[2] * tempVec4Result[2]);
        if (len > 0) {
            up[0] = tempVec4Result[0] / len;
            up[1] = tempVec4Result[1] / len;
            up[2] = tempVec4Result[2] / len;
        } else {
            up[0] = 0.0f;
            up[1] = 1.0f;
            up[2] = 0.0f;
        }

        vectorsDirty = false;
    }

    /**
     * Logs the camera's position.
     */
    public void logPosition() {
        Log.d("PrismCamera", "Position: (" + posX + ", " + posY + ", " + posZ + ")");
    }

    /**
     * Logs the camera's direction vectors (forward, right, up).
     * Ensures vectors are current before logging.
     */
    public void logDirection() {
        if (vectorsDirty) {
            updateDirectionVectors();
        }
        Log.d("PrismCamera", "Forward: (" + forward[0] + ", " + forward[1] + ", " + forward[2] + ")");
        Log.d("PrismCamera", "Right: (" + right[0] + ", " + right[1] + ", " + right[2] + ")");
        Log.d("PrismCamera", "Up: (" + up[0] + ", " + up[1] + ", " + up[2] + ")");
    }

    /**
     * Dumps all camera state for debugging.
     * Emits a single log entry using StringBuilder.
     * Ensures vectors are current before dumping.
     */
    public void dump() {
        if (vectorsDirty) {
            updateDirectionVectors();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("=== PrismCamera Dump ===\n");
        sb.append("Position: (").append(posX).append(", ").append(posY).append(", ").append(posZ).append(")\n");
        sb.append("Yaw: ").append(yaw).append(", Pitch: ").append(pitch).append(", Roll: ").append(roll).append("\n");
        sb.append("Forward: (").append(forward[0]).append(", ").append(forward[1]).append(", ").append(forward[2]).append(")\n");
        sb.append("Right: (").append(right[0]).append(", ").append(right[1]).append(", ").append(right[2]).append(")\n");
        sb.append("Up: (").append(up[0]).append(", ").append(up[1]).append(", ").append(up[2]).append(")\n");
        sb.append("FOV: ").append(fov).append(", Aspect: ").append(aspect).append(", Near: ").append(near).append(", Far: ").append(far);
        Log.d("PrismCamera", sb.toString());
    }

    /**
     * Dumps camera state to a file descriptor.
     * @param fd The file descriptor to write to
     * @param args Arguments (unused)
     */
    public void dump(FileDescriptor fd, String[] args) {
        if (vectorsDirty) {
            updateDirectionVectors();
        }
        final PrintWriter pw = new FastPrintWriter(new FileOutputStream(fd));
        pw.println("Position: (" + posX + ", " + posY + ", " + posZ + ")");
        pw.println("Yaw: " + yaw + ", Pitch: " + pitch + ", Roll: " + roll);
        pw.println("Forward: (" + forward[0] + ", " + forward[1] + ", " + forward[2] + ")");
        pw.println("Right: (" + right[0] + ", " + right[1] + ", " + right[2] + ")");
        pw.println("Up: (" + up[0] + ", " + up[1] + ", " + up[2] + ")");
        pw.println("FOV: " + fov + ", Aspect: " + aspect + ", Near: " + near + ", Far: " + far);
        pw.flush();
    }
}
