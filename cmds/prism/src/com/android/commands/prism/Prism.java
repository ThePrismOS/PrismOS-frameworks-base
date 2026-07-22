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

public class Prism {

    public static abstract class Command {
        private String mName;

        public Command(String name) {
            mName = name;
        }

        public String name() {
            return mName;
        }

        public abstract String shortHelp();         // should fit on one short line
        public abstract String longHelp();          // take as much space as you need, 75 col max
        public abstract void run(String[] args);    // run the command
    }

    public static void main(String[] args) {
        if (args.length >= 1) {
            Command c = lookupCommand(args[0]);
            if (c != null) {
                c.run(args);
                return;
            }
        }
        COMMAND_HELP.run(args);
    }

    private static Command lookupCommand(String name) {
        final int N = COMMANDS.length;
        for (int i=0; i<N; i++) {
            Command c = COMMANDS[i];
            if (c.name().equals(name)) {
                return c;
            }
        }
        return null;
    }

    public static final Command COMMAND_HELP = new Command("help") {
        public String shortHelp() {
            return "Show information about the subcommands";
        }
        public String longHelp() {
            return shortHelp();
        }
        public void run(String[] args) {
            if (args.length == 2) {
                Command c = lookupCommand(args[1]);
                if (c != null) {
                    System.err.println(c.longHelp());
                    return;
                }
            }

            System.err.println("Available commands:");
            final int N = COMMANDS.length;
            int maxlen = 0;
            for (int i=0; i<N; i++) {
                Command c = COMMANDS[i];
                int len = c.name().length();
                if (maxlen < len) {
                    maxlen = len;
                }
            }
            String format = "    %-" + maxlen + "s    %s";
            for (int i=0; i<N; i++) {
                Command c = COMMANDS[i];
                System.err.println(String.format(format, c.name(), c.shortHelp()));
            }
        }
    };

    // Dump command
    public static final Command COMMAND_DUMP = new Command("dump") {
        public String shortHelp() {
            return "Dump Prism service information";
        }
        public String longHelp() {
            return "Dump detailed information about the Prism service state";
        }
        public void run(String[] args) {
            System.err.println(PrismManager.dump());
        }
    };

    // Dump camera command
    public static final Command COMMAND_DUMPCAMERA = new Command("dumpcamera") {
        public String shortHelp() {
            return "Dump Prism camera information";
        }
        public String longHelp() {
            return "Dump detailed information about the Prism camera state";
        }
        public void run(String[] args) {
            System.err.println(PrismManager.dumpCamera());
        }
    };

    // Camera move command
    public static final Command COMMAND_CAMERAMOVE = new Command("cameramove") {
        public String shortHelp() {
            return "Move camera relative to current position";
        }
        public String longHelp() {
            return "Move camera by X, Y, Z offset (local coordinates)\n" +
                   "Usage: cameramove <x> <y> <z>";
        }
        public void run(String[] args) {
            if (args.length < 4) {
                System.err.println("Error: Missing arguments. Usage: cameramove <x> <y> <z>");
                return;
            }
            try {
                float x = Float.parseFloat(args[1]);
                float y = Float.parseFloat(args[2]);
                float z = Float.parseFloat(args[3]);
                PrismManager.cameraMove(x, y, z);
                System.err.println("Camera moved by (" + x + ", " + y + ", " + z + ")");
            } catch (NumberFormatException e) {
                System.err.println("Error: Invalid number format for coordinates");
            }
        }
    };

    // Camera move world command
    public static final Command COMMAND_CAMERAMOVEWORLD = new Command("cameramoveworld") {
        public String shortHelp() {
            return "Move camera to world position";
        }
        public String longHelp() {
            return "Move camera to world coordinates X, Y, Z\n" +
                   "Usage: cameramoveworld <x> <y> <z>";
        }
        public void run(String[] args) {
            if (args.length < 4) {
                System.err.println("Error: Missing arguments. Usage: cameramoveworld <x> <y> <z>");
                return;
            }
            try {
                float x = Float.parseFloat(args[1]);
                float y = Float.parseFloat(args[2]);
                float z = Float.parseFloat(args[3]);
                PrismManager.cameraMoveWorld(x, y, z);
                System.err.println("Camera moved to world position (" + x + ", " + y + ", " + z + ")");
            } catch (NumberFormatException e) {
                System.err.println("Error: Invalid number format for coordinates");
            }
        }
    };

    // Camera rotate command
    public static final Command COMMAND_CAMERAROTATE = new Command("camerarotate") {
        public String shortHelp() {
            return "Rotate camera (yaw, pitch, roll)";
        }
        public String longHelp() {
            return "Rotate camera by yaw, pitch, roll angles (in degrees)\n" +
                   "Usage: camerarotate <yaw> <pitch> <roll>";
        }
        public void run(String[] args) {
            if (args.length < 4) {
                System.err.println("Error: Missing arguments. Usage: camerarotate <yaw> <pitch> <roll>");
                return;
            }
            try {
                float yaw = Float.parseFloat(args[1]);
                float pitch = Float.parseFloat(args[2]);
                float roll = Float.parseFloat(args[3]);
                PrismManager.cameraRotate(yaw, pitch, roll);
                System.err.println("Camera rotated: yaw=" + yaw + "°, pitch=" + pitch + "°, roll=" + roll + "°");
            } catch (NumberFormatException e) {
                System.err.println("Error: Invalid number format for angles");
            }
        }
    };

    // Camera lookat command
    public static final Command COMMAND_CAMERALOOKAT = new Command("cameralookat") {
        public String shortHelp() {
            return "Point camera at a specific position";
        }
        public String longHelp() {
            return "Point camera at world coordinates X, Y, Z\n" +
                   "Usage: cameraloocat <x> <y> <z>";
        }
        public void run(String[] args) {
            if (args.length < 4) {
                System.err.println("Error: Missing arguments. Usage: cameraloocat <x> <y> <z>");
                return;
            }
            try {
                float x = Float.parseFloat(args[1]);
                float y = Float.parseFloat(args[2]);
                float z = Float.parseFloat(args[3]);
                PrismManager.cameraLookAt(x, y, z);
                System.err.println("Camera looking at point (" + x + ", " + y + ", " + z + ")");
            } catch (NumberFormatException e) {
                System.err.println("Error: Invalid number format for coordinates");
            }
        }
    };

    // List planes command
    public static final Command COMMAND_LISTPLANES = new Command("listplanes") {
        public String shortHelp() {
            return "List all registered planes";
        }
        public String longHelp() {
            return "List all planes currently registered with the Prism renderer";
        }
        public void run(String[] args) {
            System.err.println(PrismManager.listPlanes());
        }
    };

    // Dump plane command
    public static final Command COMMAND_DUMPPLANE = new Command("dumplane") {
        public String shortHelp() {
            return "Dump information about a specific plane";
        }
        public String longHelp() {
            return "Dump detailed information about a specific plane by ID\n" +
                   "Usage: dumplane <planeId>";
        }
        public void run(String[] args) {
            if (args.length < 2) {
                System.err.println("Error: Missing plane ID. Usage: dumplane <planeId>");
                return;
            }
            try {
                int planeId = Integer.parseInt(args[1]);
                System.err.println(PrismManager.dumpPlane(planeId));
            } catch (NumberFormatException e) {
                System.err.println("Error: Invalid plane ID format");
            }
        }
    };

    // Reload renderer command
    public static final Command COMMAND_RENDERERRELOAD = new Command("rendererreload") {
        public String shortHelp() {
            return "Reload the Prism renderer (shaders, textures)";
        }
        public String longHelp() {
            return "Reload the Prism renderer, refreshing shaders and textures";
        }
        public void run(String[] args) {
            PrismManager.rendererReload();
            System.err.println("Prism renderer reloaded");
        }
    };

    // Recreate renderer command
    public static final Command COMMAND_RENDERERRECREATE = new Command("rendererrecreate") {
        public String shortHelp() {
            return "Recreate the Prism renderer (EGL context, etc.)";
        }
        public String longHelp() {
            return "Fully recreate the Prism renderer (shuts down and reinitializes EGL context)";
        }
        public void run(String[] args) {
            PrismManager.rendererRecreate();
            System.err.println("Prism renderer recreated");
        }
    };

    public static final Command[] COMMANDS = new Command[] {
            COMMAND_HELP,
            COMMAND_DUMP,
            COMMAND_DUMPCAMERA,
            COMMAND_CAMERAMOVE,
            COMMAND_CAMERAMOVEWORLD,
            COMMAND_CAMERAROTATE,
            COMMAND_CAMERALOOKAT,
            COMMAND_LISTPLANES,
            COMMAND_DUMPPLANE,
            COMMAND_RENDERERRELOAD,
            COMMAND_RENDERERRECREATE
    };
}
