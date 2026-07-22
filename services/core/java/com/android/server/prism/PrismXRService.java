package com.android.server.prism;

import static android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
import static android.view.WindowManager.LayoutParams.MATCH_PARENT;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

import android.annotation.NonNull;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.PixelFormat;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Slog;
import android.view.IWindowManager;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManagerGlobal;
import android.view.WindowManager;


import java.io.FileOutputStream;
import java.io.PrintWriter;

import com.android.server.SystemService;
import com.android.server.prism.PrismRenderer;
import com.android.server.prism.PrismRendererController;
import com.android.server.prism.PrismTextureSource;

/**
 * System service that owns the Prism renderer and its fullscreen boot-time surface.
 */
public final class PrismXRService extends SystemService {
    private static final String TAG = "PrismXRService";
    private static final String LOG_PREFIX = "[Prism] ";
    private static final String SETTING_RENDER_ENABLED = Settings.Global.PRISM_RENDERER_ENABLED;
    private static final String SERVICE_NAME = "prism";

    private final Object mLock = new Object();
    private final PrismRendererImpl mLocalService = new PrismRendererImpl();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final ContentObserver mSettingsObserver = new ContentObserver(mMainHandler) {
        @Override
        public void onChange(boolean selfChange) {
            synchronized (mLock) {
                updateRendererStateLocked();
            }
        }
    };

    private final IPrismManager.Stub mBinderService = new IPrismManager.Stub() {
        @Override
        public String dump() throws RemoteException {
            synchronized (mLock) {
                return buildServiceDumpLocked();
            }
        }

        @Override
        public String dumpCamera() throws RemoteException {
            synchronized (mLock) {
                return buildCameraDumpLocked();
            }
        }

        @Override
        public String listPlanes() throws RemoteException {
            synchronized (mLock) {
                return buildPlaneListLocked();
            }
        }

        @Override
        public String dumpPlane(int planeId) throws RemoteException {
            synchronized (mLock) {
                return buildPlaneDumpLocked(planeId);
            }
        }

        @Override
        public void cameraMove(float x, float y, float z) throws RemoteException {
            synchronized (mLock) {
                if (mRendererController != null) {
                    mRendererController.moveCamera(x, y, z);
                }
            }
        }

        @Override
        public void cameraMoveWorld(float x, float y, float z) throws RemoteException {
            synchronized (mLock) {
                if (mRendererController != null) {
                    mRendererController.moveWorldCamera(x, y, z);
                }
            }
        }

        @Override
        public void cameraRotate(float yaw, float pitch, float roll) throws RemoteException {
            synchronized (mLock) {
                if (mRendererController != null) {
                    mRendererController.setCameraYaw(yaw);
                    mRendererController.setCameraPitch(pitch);
                    mRendererController.setCameraRoll(roll);
                }
            }
        }

        @Override
        public void cameraLookAt(float x, float y, float z) throws RemoteException {
            synchronized (mLock) {
                if (mRendererController != null) {
                    mRendererController.lookAtCamera(x, y, z);
                }
            }
        }

        @Override
        public void rendererReload() throws RemoteException {
            synchronized (mLock) {
                if (mRendererController != null) {
                    mRendererController.rendererReload();
                }
            }
        }

        @Override
        public void rendererRecreate() throws RemoteException {
            synchronized (mLock) {
                if (mRendererController != null) {
                    mRendererController.rendererRecreate();
                }
            }
        }

        @Override
        public int handleShellCommand(ParcelFileDescriptor in, ParcelFileDescriptor out,
                ParcelFileDescriptor err, String[] args) {
            final PrintWriter pw = new PrintWriter(new FileOutputStream(out.getFileDescriptor()));
            final String cmd = args != null && args.length > 0 ? args[0] : "dump";

            synchronized (mLock) {
                if ("help".equals(cmd) || "-h".equals(cmd) || "--help".equals(cmd)) {
                    pw.print(buildHelpTextLocked());
                    pw.flush();
                    return 0;
                }

                final String output;

                switch (cmd) {

                    case "dump":
                        output = buildServiceDumpLocked();
                        break;

                    case "renderer":
                        output = buildRendererDumpLocked();
                        break;

                    case "camera":
                        output = handleCameraCommandLocked(args);
                        break;

                    case "plane":
                        output = handlePlaneCommandLocked(args);
                        break;

                    case "skybox":
                        output = handleSkyboxCommandLocked(args);
                        break;

                    case "debug":
                        output = handleDebugCommandLocked(args);
                        break;

                    case "display":
                        output = handleDisplayCommandLocked(args);
                        break;

                    default:
                        pw.println("Unknown command: " + cmd); 
                        pw.print(buildHelpTextLocked()); 
                        pw.flush(); 
                        return -1;
                }

                pw.print(output);
                if (!output.endsWith("\n")) {
                    pw.println();
                }
                pw.flush();
                return 0;
            }
        }
    };

    private String handleCameraCommandLocked(String[] args) {
        final PrismRendererController renderer = getRendererControllerLocked();
        if (renderer == null) {
            return "Renderer controller unavailable\n";
        }

        if (args == null || args.length < 2) {
            return buildCameraDumpLocked();
        }

        final String subcommand = args[1];
        switch (subcommand) {
            case "dump":
                if (args.length != 2) {
                    return buildCameraInvalidArgumentsLocked("cmd prism camera dump");
                }
                return buildCameraDumpLocked();

            case "help":
                if (args.length != 2) {
                    return buildCameraInvalidArgumentsLocked("cmd prism camera help");
                }
                return buildCameraHelpLocked();

            case "pos":
                return handleCameraPositionLocked(renderer, args);

            case "move":
                return handleCameraMoveLocked(renderer, args);

            case "rot":
                return handleCameraRotationLocked(renderer, args);

            case "reset":
                if (args.length != 2) {
                    return buildCameraInvalidArgumentsLocked("cmd prism camera reset");
                }
                renderer.resetCamera();
                return buildCameraDumpLocked();

            case "lookat":
                return handleCameraLookAtLocked(renderer, args);

            case "fov":
                return handleCameraFovLocked(renderer, args);

            case "near":
                return handleCameraNearLocked(renderer, args);

            case "far":
                return handleCameraFarLocked(renderer, args);

            default:
                return buildCameraUnknownCommandLocked(subcommand);
        }
    }

    private String handleCameraPositionLocked(PrismRendererController renderer, String[] args) {
        if (args.length != 5) {
            return buildCameraInvalidArgumentsLocked("cmd prism camera pos <x> <y> <z>");
        }
        final Float x = parseCameraFiniteFloatOrNull(args[2]);
        final Float y = parseCameraFiniteFloatOrNull(args[3]);
        final Float z = parseCameraFiniteFloatOrNull(args[4]);
        if (x == null || y == null || z == null) {
            return buildCameraInvalidArgumentsLocked("cmd prism camera pos <x> <y> <z>");
        }
        renderer.setCameraPosition(x, y, z);
        return buildCameraDumpLocked();
    }

    private String handleCameraMoveLocked(PrismRendererController renderer, String[] args) {
        if (args.length != 5) {
            return buildCameraInvalidArgumentsLocked("cmd prism camera move <dx> <dy> <dz>");
        }
        final Float dx = parseCameraFiniteFloatOrNull(args[2]);
        final Float dy = parseCameraFiniteFloatOrNull(args[3]);
        final Float dz = parseCameraFiniteFloatOrNull(args[4]);
        if (dx == null || dy == null || dz == null) {
            return buildCameraInvalidArgumentsLocked("cmd prism camera move <dx> <dy> <dz>");
        }
        renderer.moveCamera(dx, dy, dz);
        return buildCameraDumpLocked();
    }

    private String handleCameraRotationLocked(PrismRendererController renderer, String[] args) {
        if (args.length != 5) {
            return buildCameraInvalidArgumentsLocked("cmd prism camera rot <yaw> <pitch> <roll>");
        }
        final Float yaw = parseCameraFiniteFloatOrNull(args[2]);
        final Float pitch = parseCameraFiniteFloatOrNull(args[3]);
        final Float roll = parseCameraFiniteFloatOrNull(args[4]);
        if (yaw == null || pitch == null || roll == null) {
            return buildCameraInvalidArgumentsLocked("cmd prism camera rot <yaw> <pitch> <roll>");
        }
        renderer.setCameraRotation(yaw, pitch, roll);
        return buildCameraDumpLocked();
    }

    private String handleCameraLookAtLocked(PrismRendererController renderer, String[] args) {
        if (args.length != 5) {
            return buildCameraInvalidArgumentsLocked("cmd prism camera lookat <x> <y> <z>");
        }
        final Float x = parseCameraFiniteFloatOrNull(args[2]);
        final Float y = parseCameraFiniteFloatOrNull(args[3]);
        final Float z = parseCameraFiniteFloatOrNull(args[4]);
        if (x == null || y == null || z == null) {
            return buildCameraInvalidArgumentsLocked("cmd prism camera lookat <x> <y> <z>");
        }
        renderer.lookAtCamera(x, y, z);
        return buildCameraDumpLocked();
    }

    private String handleCameraFovLocked(PrismRendererController renderer, String[] args) {
        if (args.length != 3) {
            return buildCameraInvalidArgumentsLocked("cmd prism camera fov <degrees>");
        }
        final Float fov = parseCameraFiniteFloatOrNull(args[2]);
        if (fov == null || fov <= 0.0f || fov >= 180.0f) {
            return buildCameraInvalidArgumentsLocked("cmd prism camera fov <degrees>");
        }
        renderer.setCameraFov(fov);
        return buildCameraDumpLocked();
    }

    private String handleCameraNearLocked(PrismRendererController renderer, String[] args) {
        if (args.length != 3) {
            return buildCameraInvalidArgumentsLocked("cmd prism camera near <value>");
        }
        final Float near = parseCameraFiniteFloatOrNull(args[2]);
        if (near == null || near <= 0.0f || near >= renderer.getCameraFar()) {
            return buildCameraInvalidArgumentsLocked("cmd prism camera near <value>");
        }
        renderer.setCameraNear(near);
        return buildCameraDumpLocked();
    }

    private String handleCameraFarLocked(PrismRendererController renderer, String[] args) {
        if (args.length != 3) {
            return buildCameraInvalidArgumentsLocked("cmd prism camera far <value>");
        }
        final Float far = parseCameraFiniteFloatOrNull(args[2]);
        if (far == null || far <= 0.0f || far <= renderer.getCameraNear()) {
            return buildCameraInvalidArgumentsLocked("cmd prism camera far <value>");
        }
        renderer.setCameraFar(far);
        return buildCameraDumpLocked();
    }

    private String buildCameraHelpLocked() {
        return "Camera commands:\n"
                + "\n"
                + "cmd prism camera\n"
                + "cmd prism camera dump\n"
                + "cmd prism camera help\n"
                + "\n"
                + "cmd prism camera pos <x> <y> <z>\n"
                + "cmd prism camera move <dx> <dy> <dz>\n"
                + "\n"
                + "cmd prism camera rot <yaw> <pitch> <roll>\n"
                + "\n"
                + "cmd prism camera reset\n"
                + "\n"
                + "cmd prism camera lookat <x> <y> <z>\n"
                + "\n"
                + "cmd prism camera fov <degrees>\n"
                + "\n"
                + "cmd prism camera near <value>\n"
                + "cmd prism camera far <value>\n";
    }

    private String buildCameraInvalidArgumentsLocked(String usage) {
        return "Invalid arguments.\n\nUsage:\n\n" + usage + "\n";
    }

    private String buildCameraUnknownCommandLocked(String command) {
        return "Unknown camera command: " + command + "\n\n" + buildCameraHelpLocked();
    }

    private Float parseCameraFiniteFloatOrNull(String value) {
        try {
            final float parsed = Float.parseFloat(value);
            if (Float.isNaN(parsed) || Float.isInfinite(parsed)) {
                return null;
            }
            return parsed;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String handlePlaneCommandLocked(String[] args) {
        final PrismRendererController renderer = getRendererControllerLocked();
        if (renderer == null) {
            return "Renderer controller unavailable\n";
        }

        if (args == null || args.length < 2 || "help".equals(args[1])) {
            return buildPlaneHelpLocked();
        }

        final String subcommand = args[1];
        switch (subcommand) {
            case "list":
                if (args.length != 2) {
                    return buildPlaneInvalidArgumentsLocked("cmd prism plane list");
                }
                return buildPlaneListLocked();

            case "dump":
                return handlePlaneDumpLocked(args);

            case "pos":
                return handlePlanePositionLocked(renderer, args);

            case "move":
                return handlePlaneMoveLocked(renderer, args);

            case "rot":
                return handlePlaneRotationLocked(renderer, args);

            case "scale":
                return handlePlaneScaleLocked(renderer, args);

            case "color":
                return handlePlaneColorLocked(renderer, args);

            case "visible":
                return handlePlaneVisibleLocked(renderer, args);

            case "reset":
                return handlePlaneResetLocked(renderer, args);

            default:
                return buildPlaneUnknownCommandLocked(subcommand);
        }
    }

    private String handlePlaneDumpLocked(String[] args) {
        if (args.length != 3) {
            return buildPlaneInvalidArgumentsLocked("cmd prism plane dump <id>");
        }
        final Integer planeId = parsePlaneIdOrNull(args[2]);
        if (planeId == null) {
            return buildPlaneInvalidArgumentsLocked("cmd prism plane dump <id>");
        }
        return buildPlaneDumpLocked(planeId);
    }

    private String handlePlanePositionLocked(PrismRendererController renderer, String[] args) {
        if (args.length != 6) {
            return buildPlaneInvalidArgumentsLocked("cmd prism plane pos <id> <x> <y> <z>");
        }
        final Integer planeId = parsePlaneIdOrNull(args[2]);
        final Float x = parseCameraFiniteFloatOrNull(args[3]);
        final Float y = parseCameraFiniteFloatOrNull(args[4]);
        final Float z = parseCameraFiniteFloatOrNull(args[5]);
        if (planeId == null || x == null || y == null || z == null) {
            return buildPlaneInvalidArgumentsLocked("cmd prism plane pos <id> <x> <y> <z>");
        }
        renderer.setPlanePosition(planeId, x, y, z);
        return buildPlaneDumpLocked(planeId);
    }

    private String handlePlaneMoveLocked(PrismRendererController renderer, String[] args) {
        if (args.length != 6) {
            return buildPlaneInvalidArgumentsLocked("cmd prism plane move <id> <dx> <dy> <dz>");
        }
        final Integer planeId = parsePlaneIdOrNull(args[2]);
        final Float dx = parseCameraFiniteFloatOrNull(args[3]);
        final Float dy = parseCameraFiniteFloatOrNull(args[4]);
        final Float dz = parseCameraFiniteFloatOrNull(args[5]);
        if (planeId == null || dx == null || dy == null || dz == null) {
            return buildPlaneInvalidArgumentsLocked("cmd prism plane move <id> <dx> <dy> <dz>");
        }
        renderer.movePlane(planeId, dx, dy, dz);
        return buildPlaneDumpLocked(planeId);
    }

    private String handlePlaneRotationLocked(PrismRendererController renderer, String[] args) {
        if (args.length != 6) {
            return buildPlaneInvalidArgumentsLocked("cmd prism plane rot <id> <yaw> <pitch> <roll>");
        }
        final Integer planeId = parsePlaneIdOrNull(args[2]);
        final Float yaw = parseCameraFiniteFloatOrNull(args[3]);
        final Float pitch = parseCameraFiniteFloatOrNull(args[4]);
        final Float roll = parseCameraFiniteFloatOrNull(args[5]);
        if (planeId == null || yaw == null || pitch == null || roll == null) {
            return buildPlaneInvalidArgumentsLocked("cmd prism plane rot <id> <yaw> <pitch> <roll>");
        }
        renderer.setPlaneRotation(planeId, yaw, pitch, roll);
        return buildPlaneDumpLocked(planeId);
    }

    private String handlePlaneScaleLocked(PrismRendererController renderer, String[] args) {
        if (args.length != 6) {
            return buildPlaneInvalidArgumentsLocked("cmd prism plane scale <id> <sx> <sy> <sz>");
        }
        final Integer planeId = parsePlaneIdOrNull(args[2]);
        final Float sx = parseCameraFiniteFloatOrNull(args[3]);
        final Float sy = parseCameraFiniteFloatOrNull(args[4]);
        final Float sz = parseCameraFiniteFloatOrNull(args[5]);
        if (planeId == null || sx == null || sy == null || sz == null) {
            return buildPlaneInvalidArgumentsLocked("cmd prism plane scale <id> <sx> <sy> <sz>");
        }
        renderer.setPlaneScale(planeId, sx, sy, sz);
        return buildPlaneDumpLocked(planeId);
    }

    private String handlePlaneColorLocked(PrismRendererController renderer, String[] args) {
        if (args.length != 7) {
            return buildPlaneInvalidArgumentsLocked("cmd prism plane color <id> <r> <g> <b> <a>");
        }
        final Integer planeId = parsePlaneIdOrNull(args[2]);
        final Float r = parseCameraFiniteFloatOrNull(args[3]);
        final Float g = parseCameraFiniteFloatOrNull(args[4]);
        final Float b = parseCameraFiniteFloatOrNull(args[5]);
        final Float a = parseCameraFiniteFloatOrNull(args[6]);
        if (planeId == null || r == null || g == null || b == null || a == null
                || r < 0.0f || r > 1.0f
                || g < 0.0f || g > 1.0f
                || b < 0.0f || b > 1.0f
                || a < 0.0f || a > 1.0f) {
            return buildPlaneInvalidArgumentsLocked("cmd prism plane color <id> <r> <g> <b> <a>");
        }
        renderer.setPlaneColor(planeId, r, g, b, a);
        return buildPlaneDumpLocked(planeId);
    }

    private String handlePlaneVisibleLocked(PrismRendererController renderer, String[] args) {
        if (args.length != 4) {
            return buildPlaneInvalidArgumentsLocked("cmd prism plane visible <id> true|false");
        }
        final Integer planeId = parsePlaneIdOrNull(args[2]);
        final Boolean visible = parseBooleanOrNull(args[3]);
        if (planeId == null || visible == null) {
            return buildPlaneInvalidArgumentsLocked("cmd prism plane visible <id> true|false");
        }
        renderer.setPlaneVisible(planeId, visible);
        return buildPlaneDumpLocked(planeId);
    }

    private String handlePlaneResetLocked(PrismRendererController renderer, String[] args) {
        if (args.length != 3) {
            return buildPlaneInvalidArgumentsLocked("cmd prism plane reset <id>");
        }
        final Integer planeId = parsePlaneIdOrNull(args[2]);
        if (planeId == null) {
            return buildPlaneInvalidArgumentsLocked("cmd prism plane reset <id>");
        }
        renderer.resetPlane(planeId);
        return buildPlaneDumpLocked(planeId);
    }

    private String buildPlaneHelpLocked() {
        return "Plane commands:\n"
                + "\n"
                + "cmd prism plane\n"
                + "cmd prism plane help\n"
                + "cmd prism plane list\n"
                + "\n"
                + "cmd prism plane dump <id>\n"
                + "\n"
                + "cmd prism plane pos <id> <x> <y> <z>\n"
                + "cmd prism plane move <id> <dx> <dy> <dz>\n"
                + "\n"
                + "cmd prism plane rot <id> <yaw> <pitch> <roll>\n"
                + "cmd prism plane scale <id> <sx> <sy> <sz>\n"
                + "cmd prism plane color <id> <r> <g> <b> <a>\n"
                + "cmd prism plane visible <id> true\n"
                + "cmd prism plane visible <id> false\n"
                + "\n"
                + "cmd prism plane reset <id>\n";
    }

    private String buildPlaneInvalidArgumentsLocked(String usage) {
        return "Invalid arguments.\n\nUsage:\n\n" + usage + "\n";
    }

    private String buildPlaneUnknownCommandLocked(String command) {
        return "Unknown plane command: " + command + "\n\n" + buildPlaneHelpLocked();
    }

    private Integer parsePlaneIdOrNull(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Boolean parseBooleanOrNull(String value) {
        if ("true".equalsIgnoreCase(value)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(value)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private String handleSkyboxCommandLocked(String[] args) {
        final PrismRendererController renderer = getRendererControllerLocked();
        if (renderer == null) {
            return "Renderer controller unavailable\n";
        }

        if (args == null || args.length < 2) {
            return buildSkyboxDumpLocked();
        }

        final String subcommand = args[1];
        switch (subcommand) {
            case "dump":
                if (args.length != 2) {
                    return buildSkyboxInvalidArgumentsLocked("cmd prism skybox dump");
                }
                return buildSkyboxDumpLocked();

            case "help":
                if (args.length != 2) {
                    return buildSkyboxInvalidArgumentsLocked("cmd prism skybox help");
                }
                return buildSkyboxHelpLocked();

            case "enable":
                if (args.length != 2) {
                    return buildSkyboxInvalidArgumentsLocked("cmd prism skybox enable");
                }
                renderer.setSkyboxVisible(true);
                return buildSkyboxDumpLocked();

            case "disable":
                if (args.length != 2) {
                    return buildSkyboxInvalidArgumentsLocked("cmd prism skybox disable");
                }
                renderer.setSkyboxVisible(false);
                return buildSkyboxDumpLocked();

            case "visible":
                return handleSkyboxVisibleLocked(renderer, args);

            case "color":
                return handleSkyboxColorLocked(renderer, args);

            case "size":
                return handleSkyboxSizeLocked(renderer, args);

            case "mode":
                return handleSkyboxModeLocked(renderer, args);

            case "cubemap":
                return handleSkyboxCubemapLocked(renderer, args);

            case "reset":
                if (args.length != 2) {
                    return buildSkyboxInvalidArgumentsLocked("cmd prism skybox reset");
                }
                renderer.resetSkybox();
                return buildSkyboxDumpLocked();

            default:
                return buildSkyboxUnknownCommandLocked(subcommand);
        }
    }

    private String handleSkyboxVisibleLocked(PrismRendererController renderer, String[] args) {
        if (args.length != 3) {
            return buildSkyboxInvalidArgumentsLocked("cmd prism skybox visible true|false");
        }
        final Boolean visible = parseBooleanOrNull(args[2]);
        if (visible == null) {
            return buildSkyboxInvalidArgumentsLocked("cmd prism skybox visible true|false");
        }
        renderer.setSkyboxVisible(visible);
        return buildSkyboxDumpLocked();
    }

    private String handleSkyboxColorLocked(PrismRendererController renderer, String[] args) {
        if (args.length != 6) {
            return buildSkyboxInvalidArgumentsLocked("cmd prism skybox color <r> <g> <b> <a>");
        }
        final Float r = parseCameraFiniteFloatOrNull(args[2]);
        final Float g = parseCameraFiniteFloatOrNull(args[3]);
        final Float b = parseCameraFiniteFloatOrNull(args[4]);
        final Float a = parseCameraFiniteFloatOrNull(args[5]);
        if (r == null || g == null || b == null || a == null
                || r < 0.0f || r > 1.0f
                || g < 0.0f || g > 1.0f
                || b < 0.0f || b > 1.0f
                || a < 0.0f || a > 1.0f) {
            return buildSkyboxInvalidArgumentsLocked("cmd prism skybox color <r> <g> <b> <a>");
        }
        renderer.setSkyboxColor(r, g, b, a);
        return buildSkyboxDumpLocked();
    }

    private String handleSkyboxSizeLocked(PrismRendererController renderer, String[] args) {
        if (args.length != 3) {
            return buildSkyboxInvalidArgumentsLocked("cmd prism skybox size <value>");
        }
        final Float size = parseCameraFiniteFloatOrNull(args[2]);
        if (size == null || size <= 0.0f) {
            return buildSkyboxInvalidArgumentsLocked("cmd prism skybox size <value>");
        }
        renderer.setSkyboxSize(size);
        return buildSkyboxDumpLocked();
    }

    private String handleSkyboxModeLocked(PrismRendererController renderer, String[] args) {
        if (args.length != 3) {
            return buildSkyboxInvalidArgumentsLocked("cmd prism skybox mode solid|gradient|cubemap");
        }
        final String mode = args[2];
        if (!"solid".equals(mode) && !"gradient".equals(mode) && !"cubemap".equals(mode)) {
            return buildSkyboxInvalidArgumentsLocked("cmd prism skybox mode solid|gradient|cubemap");
        }
        renderer.setSkyboxMode(mode);
        return buildSkyboxDumpLocked();
    }

    private String handleSkyboxCubemapLocked(PrismRendererController renderer, String[] args) {
        if (args.length != 3) {
            return buildSkyboxInvalidArgumentsLocked("cmd prism skybox cubemap <name>");
        }
        renderer.setSkyboxCubemap(args[2]);
        return buildSkyboxDumpLocked();
    }

    private String buildSkyboxHelpLocked() {
        return "Skybox commands:\n"
                + "\n"
                + "cmd prism skybox\n"
                + "cmd prism skybox dump\n"
                + "cmd prism skybox help\n"
                + "\n"
                + "cmd prism skybox enable\n"
                + "cmd prism skybox disable\n"
                + "cmd prism skybox visible true\n"
                + "cmd prism skybox visible false\n"
                + "\n"
                + "cmd prism skybox color <r> <g> <b> <a>\n"
                + "cmd prism skybox size <value>\n"
                + "cmd prism skybox mode solid\n"
                + "cmd prism skybox mode gradient\n"
                + "cmd prism skybox mode cubemap\n"
                + "cmd prism skybox cubemap <name>\n"
                + "\n"
                + "cmd prism skybox reset\n";
    }

    private String buildSkyboxInvalidArgumentsLocked(String usage) {
        return "Invalid arguments.\n\nUsage:\n\n" + usage + "\n";
    }

    private String buildSkyboxUnknownCommandLocked(String command) {
        return "Unknown skybox command: " + command + "\n\n" + buildSkyboxHelpLocked();
    }

    private String buildSkyboxDumpLocked() {
        final PrismRendererController renderer = getRendererControllerLocked();
        if (renderer == null) {
            return "Renderer controller unavailable\n";
        }
        return renderer.skyboxDump();
    }

    private String handleDebugCommandLocked(String[] args) {
        final PrismRendererController renderer = getRendererControllerLocked();
        if (renderer == null) {
            return "Renderer controller unavailable\n";
        }

        if (args == null || args.length < 2 || "help".equals(args[1])) {
            return buildDebugHelpLocked(renderer.isDebugOverlayEnabled());
        }

        if (args.length != 2) {
            return buildDebugInvalidArgumentsLocked("cmd prism debug true|false");
        }

        final Boolean enabled = parseBooleanOrNull(args[1]);
        if (enabled == null) {
            return buildDebugUnknownCommandLocked(args[1], renderer.isDebugOverlayEnabled());
        }
        renderer.setDebugOverlayEnabled(enabled);
        return buildServiceDumpLocked();
    }

    private String buildDebugHelpLocked(boolean enabled) {
        return "Debug commands:\n"
                + "\n"
                + "cmd prism debug true\n"
                + "cmd prism debug false\n"
                + "\n"
                + "Current: " + enabled + "\n";
    }

    private String buildDebugInvalidArgumentsLocked(String usage) {
        return "Invalid arguments.\n\nUsage:\n\n" + usage + "\n";
    }

    private String buildDebugUnknownCommandLocked(String command, boolean enabled) {
        return "Unknown debug command: " + command + "\n\n"
                + buildDebugHelpLocked(enabled);
    }

    private String handleDisplayCommandLocked(String[] args) {
        final PrismRendererController renderer = getRendererControllerLocked();
        if (renderer == null) {
            return "Renderer controller unavailable\n";
        }

        if (args == null || args.length < 2) {
            return buildDisplayDumpLocked();
        }

        final String subcommand = args[1];
        switch (subcommand) {
            case "dump":
                if (args.length != 2) {
                    return buildDisplayInvalidArgumentsLocked("cmd prism display dump");
                }
                return buildDisplayDumpLocked();

            case "help":
                if (args.length != 2) {
                    return buildDisplayInvalidArgumentsLocked("cmd prism display help");
                }
                return buildDisplayHelpLocked();

            case "create":
                return handleDisplayCreateLocked(renderer, args);

            case "destroy":
                if (args.length != 2) {
                    return buildDisplayInvalidArgumentsLocked("cmd prism display destroy");
                }
                return renderer.destroyVirtualDisplay();

            case "resize":
                return handleDisplayResizeLocked(renderer, args);

            case "enable":
                if (args.length != 2) {
                    return buildDisplayInvalidArgumentsLocked("cmd prism display enable");
                }
                return renderer.enableVirtualDisplay();

            case "disable":
                if (args.length != 2) {
                    return buildDisplayInvalidArgumentsLocked("cmd prism display disable");
                }
                return renderer.disableVirtualDisplay();

            default:
                return buildDisplayUnknownCommandLocked(subcommand);
        }
    }

    private String handleDisplayCreateLocked(PrismRendererController renderer, String[] args) {
        if (args.length == 2) {
            return renderer.createVirtualDisplay();
        }
        if (args.length != 5) {
            return buildDisplayInvalidArgumentsLocked(
                    "cmd prism display create <width> <height> <density>");
        }
        final Integer width = parseNonNegativeIntegerOrNull(args[2]);
        final Integer height = parseNonNegativeIntegerOrNull(args[3]);
        final Integer density = parseNonNegativeIntegerOrNull(args[4]);
        if (width == null || height == null || density == null) {
            return buildDisplayInvalidArgumentsLocked(
                    "cmd prism display create <width> <height> <density>");
        }
        return renderer.createVirtualDisplay(width, height, density);
    }

    private String handleDisplayResizeLocked(PrismRendererController renderer, String[] args) {
        if (args.length != 4) {
            return buildDisplayInvalidArgumentsLocked("cmd prism display resize <width> <height>");
        }
        final Integer width = parseNonNegativeIntegerOrNull(args[2]);
        final Integer height = parseNonNegativeIntegerOrNull(args[3]);
        if (width == null || height == null) {
            return buildDisplayInvalidArgumentsLocked("cmd prism display resize <width> <height>");
        }
        return renderer.resizeVirtualDisplay(width, height);
    }

    private String buildDisplayHelpLocked() {
        return "Display commands:\n"
                + "\n"
                + "cmd prism display\n"
                + "cmd prism display help\n"
                + "cmd prism display dump\n"
                + "\n"
                + "cmd prism display create\n"
                + "cmd prism display create <width> <height> <density>\n"
                + "cmd prism display destroy\n"
                + "cmd prism display resize <width> <height>\n"
                + "cmd prism display enable\n"
                + "cmd prism display disable\n";
    }

    private String buildDisplayInvalidArgumentsLocked(String usage) {
        return "Invalid arguments.\n\nUsage:\n\n" + usage + "\n";
    }

    private String buildDisplayUnknownCommandLocked(String command) {
        return "Unknown display command: " + command + "\n\n" + buildDisplayHelpLocked();
    }

    private String buildDisplayDumpLocked() {
        final PrismRendererController renderer = getRendererControllerLocked();
        if (renderer == null) {
            return "Renderer controller unavailable\n";
        }
        return renderer.virtualDisplayDump();
    }

    private Integer parseNonNegativeIntegerOrNull(String value) {
        try {
            final int parsed = Integer.parseInt(value);
            return parsed >= 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private PrismSurfaceView mSurfaceView;
    private WindowManager mWindowManager;
    private PrismRendererController mRendererController;
    private int mDefaultPlaneId;
    private boolean mWindowAttached;
    private boolean mLandscapeLocked;
    private boolean mBootPhaseReady;
    private boolean mRendererPermanentlyDisabled;
    private boolean mRenderEnabled;

    public PrismXRService(@NonNull Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        try {
            publishLocalService(PrismRenderer.class, mLocalService);
        } catch (Throwable t) {
            synchronized (mLock) {
                disableRendererLocked(t);
            }
        }
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase != PHASE_SYSTEM_SERVICES_READY) {
            return;
        }
        try {
            synchronized (mLock) {
                if (mRendererPermanentlyDisabled) {
                    return;
                }
                getContext().getContentResolver().registerContentObserver(
                        Settings.Global.getUriFor(SETTING_RENDER_ENABLED),
                        false,
                        mSettingsObserver);
                mBootPhaseReady = true;
                updateRendererStateLocked();
                publishBinderService(SERVICE_NAME, mBinderService);
            }
        } catch (Throwable t) {
            synchronized (mLock) {
                disableRendererLocked(t);
            }
        }
    }

    private void updateRendererStateLocked() {
        final boolean enabled = isRenderEnabled();
        if (!enabled) {
            mRenderEnabled = false;
            stopRendererLocked();
            Slog.i(TAG, LOG_PREFIX + "Render disabled by settings");
            return;
        }

        mRenderEnabled = true;
        if (mRendererPermanentlyDisabled) {
            return;
        }

        initializeRendererLocked();
        if (mBootPhaseReady) {
            attachFullscreenSurfaceLocked();
        }
    }

    private boolean isRenderEnabled() {
        try {
            return Settings.Global.getInt(
                    getContext().getContentResolver(), SETTING_RENDER_ENABLED, 1) != 0;
        } catch (Throwable t) {
            Slog.e(TAG, LOG_PREFIX + "Failed reading Prism render setting", t);
            return true;
        }
    }

    private void initializeRendererLocked() {
        if (mRendererPermanentlyDisabled || mRendererController != null || !mRenderEnabled) {
            return;
        }

        Slog.i(TAG, LOG_PREFIX + "Initializing renderer");
        try {
            mRendererController = new PrismRendererController(getContext());

            final float[] transform = new float[16];
            Matrix.setIdentityM(transform, 0);
            Matrix.translateM(transform, 0, 0.0f, 0.0f, -2.8f);
            Matrix.scaleM(transform, 0, 2.1f, 1.2f, 1.0f);
            mDefaultPlaneId = mRendererController.createPlane(
                    "Prism Virtual Display #1",
                    transform,
                    PrismTextureSource.surfaceTexture("PrismOS - Virtual Display #1"));
            lockLandscapeRotationLocked();
            Slog.i(TAG, LOG_PREFIX + "Renderer initialized");
        } catch (Throwable t) {
            disableRendererLocked(t);
        }
    }

    private void attachFullscreenSurfaceLocked() {
        if (mRendererPermanentlyDisabled || mRendererController == null || mWindowAttached) {
            return;
        }

        final Context windowContext = getContext().createWindowContext(TYPE_APPLICATION_OVERLAY,
                null);
        final WindowManager windowManager = windowContext.getSystemService(WindowManager.class);
        if (windowManager == null) {
            throw new IllegalStateException("WindowManager unavailable");
        }

        final PrismSurfaceView surfaceView = new PrismSurfaceView(windowContext);
        final WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams(
                MATCH_PARENT,
                MATCH_PARENT,
                TYPE_APPLICATION_OVERLAY,
                FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE | FLAG_LAYOUT_IN_SCREEN
                        | FLAG_LAYOUT_NO_LIMITS | FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);
        layoutParams.setTitle(TAG);
        windowManager.addView(surfaceView, layoutParams);

        synchronized (mLock) {
            if (mRendererPermanentlyDisabled || !mRenderEnabled) {
                try {
                    windowManager.removeViewImmediate(surfaceView);
                } catch (Throwable ignored) {
                    // Ignore cleanup failures while disabling Prism.
                }
                return;
            }
            mWindowManager = windowManager;
            mSurfaceView = surfaceView;
            mWindowAttached = true;
        }
        Slog.i(TAG, LOG_PREFIX + "Attached fullscreen Prism surface");
    }

    private void stopRendererLocked() {
        mDefaultPlaneId = -1;
        mWindowAttached = false;
        unlockLandscapeRotationLocked();

        if (mSurfaceView != null && mWindowManager != null) {
            try {
                mWindowManager.removeViewImmediate(mSurfaceView);
            } catch (Throwable ignored) {
                // Ignore cleanup failures while disabling Prism.
            }
        }
        mSurfaceView = null;
        mWindowManager = null;

        if (mRendererController != null) {
            try {
                mRendererController.shutdown();
            } catch (Throwable ignored) {
                // Ignore cleanup failures while disabling Prism.
            }
            mRendererController = null;
        }
    }

    private void disableRendererLocked(Throwable t) {
        Slog.e(TAG, LOG_PREFIX + "Renderer disabled due to initialization failure", t);
        mRendererPermanentlyDisabled = true;
        stopRendererLocked();
    }

    private PrismRendererController getRendererControllerLocked() {
        if (mRendererPermanentlyDisabled || !mRenderEnabled) {
            return null;
        }
        return mRendererController;
    }

    private String buildServiceDumpLocked() {
        final StringBuilder sb = new StringBuilder();
        sb.append("=== Prism Service Dump ===\n");
        sb.append("Service name: ").append(SERVICE_NAME).append('\n');
        sb.append("Boot phase ready: ").append(mBootPhaseReady).append('\n');
        sb.append("Window attached: ").append(mWindowAttached).append('\n');
        sb.append("Render enabled: ").append(mRenderEnabled).append('\n');
        sb.append("Renderer permanently disabled: ").append(mRendererPermanentlyDisabled)
                .append('\n');
        final PrismRendererController renderer = getRendererControllerLocked();
        if (renderer != null) {
            sb.append("Debug overlay: ").append(renderer.isDebugOverlayEnabled()).append('\n');
        } else {
            sb.append("Debug overlay: unavailable\n");
        }
        sb.append("Default plane id: ").append(mDefaultPlaneId).append('\n');
        if (mSurfaceView != null) {
            sb.append("Surface view: ").append(mSurfaceView).append('\n');
        }
        if (mWindowManager != null) {
            sb.append("Window manager: ").append(mWindowManager).append('\n');
        }
        if (renderer != null) {
            sb.append(renderer.rendererInfo());
            sb.append(renderer.planeDumpAll());
            sb.append(renderer.skyboxDump());
            sb.append(renderer.virtualDisplayDump());
            sb.append("Landscape locked: ").append(mLandscapeLocked).append('\n');
        } else {
            sb.append("Renderer controller: unavailable\n");
            sb.append("=== Plane ===\n");
            sb.append("(renderer unavailable)\n");
            sb.append("=== Skybox ===\n");
            sb.append("(renderer unavailable)\n");
            sb.append("=== Prism Display Dump ===\n");
            sb.append("(renderer unavailable)\n");
            sb.append("Landscape locked: ").append(mLandscapeLocked).append('\n');
        }
        return sb.toString();
    }

    private String buildRendererDumpLocked() {
        final PrismRendererController renderer = getRendererControllerLocked();
        if (renderer == null) {
            return "Renderer controller unavailable\n";
        }
        return renderer.rendererInfo();
    }

    private String buildCameraDumpLocked() {
        final PrismRendererController renderer = getRendererControllerLocked();
        if (renderer == null) {
            return "Renderer controller unavailable\n";
        }
        return renderer.cameraDump();
    }

    private String buildPlaneListLocked() {
        final PrismRendererController renderer = getRendererControllerLocked();
        if (renderer == null) {
            return "Renderer controller unavailable\n";
        }
        return renderer.planeList();
    }

    private String buildPlaneDumpLocked(int planeId) {
        final PrismRendererController renderer = getRendererControllerLocked();
        if (renderer == null) {
            return "Renderer controller unavailable\n";
        }
        return renderer.planeDump(planeId);
    }

    private String buildHelpTextLocked() {
        return "Prism commands:\n"
                + "  dump\n"
                + "  renderer\n"
                + "  camera\n"
                + "  plane\n"
                + "  skybox\n"
                + "  debug\n"
                + "  display\n";
    }

    private void lockLandscapeRotationLocked() {
        if (mLandscapeLocked) {
            return;
        }
        try {
            final IWindowManager windowManager = WindowManagerGlobal.getWindowManagerService();
            if (windowManager != null) {
                windowManager.freezeRotation(Surface.ROTATION_90);
                mLandscapeLocked = true;
            }
        } catch (Throwable t) {
            Slog.w(TAG, LOG_PREFIX + "Failed to lock landscape rotation", t);
        }
    }

    private void unlockLandscapeRotationLocked() {
        if (!mLandscapeLocked) {
            return;
        }
        try {
            final IWindowManager windowManager = WindowManagerGlobal.getWindowManagerService();
            if (windowManager != null) {
                windowManager.thawRotation();
            }
        } catch (Throwable t) {
            Slog.w(TAG, LOG_PREFIX + "Failed to unlock landscape rotation", t);
        } finally {
            mLandscapeLocked = false;
        }
    }

    private final class PrismRendererImpl implements PrismRenderer {
        @Override
        public int createPlane(@NonNull String name, @NonNull float[] modelMatrix,
                @NonNull PrismTextureSource textureSource) {
            synchronized (mLock) {
                final PrismRendererController renderer = getRendererControllerLocked();
                if (renderer == null) {
                    return -1;
                }
                try {
                    return renderer.createPlane(name, modelMatrix, textureSource);
                } catch (Throwable t) {
                    disableRendererLocked(t);
                    return -1;
                }
            }
        }

        @Override
        public void destroyPlane(int planeId) {
            synchronized (mLock) {
                final PrismRendererController renderer = getRendererControllerLocked();
                if (renderer == null) {
                    return;
                }
                try {
                    renderer.destroyPlane(planeId);
                } catch (Throwable t) {
                    disableRendererLocked(t);
                }
            }
        }

        @Override
        public void updatePlaneTransform(int planeId, @NonNull float[] modelMatrix) {
            synchronized (mLock) {
                final PrismRendererController renderer = getRendererControllerLocked();
                if (renderer == null) {
                    return;
                }
                try {
                    renderer.updatePlaneTransform(planeId, modelMatrix);
                } catch (Throwable t) {
                    disableRendererLocked(t);
                }
            }
        }

        @Override
        public void updatePlaneTexture(int planeId, @NonNull PrismTextureSource textureSource) {
            synchronized (mLock) {
                final PrismRendererController renderer = getRendererControllerLocked();
                if (renderer == null) {
                    return;
                }
                try {
                    renderer.updatePlaneTexture(planeId, textureSource);
                } catch (Throwable t) {
                    disableRendererLocked(t);
                }
            }
        }
    }

    private final class PrismSurfaceView extends SurfaceView implements SurfaceHolder.Callback {
        PrismSurfaceView(Context context) {
            super(context);
            getHolder().addCallback(this);
        }

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            final PrismRendererController renderer;
            synchronized (mLock) {
                renderer = getRendererControllerLocked();
            }
            if (renderer == null) {
                return;
            }
            try {
                renderer.onSurfaceAvailable(holder.getSurface(),
                        Math.max(1, getWidth()), Math.max(1, getHeight()));
            } catch (Throwable t) {
                synchronized (mLock) {
                    disableRendererLocked(t);
                }
            }
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            final PrismRendererController renderer;
            synchronized (mLock) {
                renderer = getRendererControllerLocked();
            }
            if (renderer == null) {
                return;
            }
            try {
                renderer.onSurfaceSizeChanged(width, height);
            } catch (Throwable t) {
                synchronized (mLock) {
                    disableRendererLocked(t);
                }
            }
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            final PrismRendererController renderer;
            synchronized (mLock) {
                renderer = getRendererControllerLocked();
            }
            if (renderer == null) {
                return;
            }
            try {
                renderer.onSurfaceDestroyed();
            } catch (Throwable t) {
                synchronized (mLock) {
                    disableRendererLocked(t);
                }
            }
        }
    }
}
