package com.mg4.control.tasker;

/**
 * Narrow IPC surface exposed by MG4Control to MG4Tasker — profiles only.
 *
 * MG4Tasker is an independent system app: it reads and writes the vehicle itself through
 * the shared MG4Hardware layer. The one thing it cannot do on its own is apply an
 * MG4Control *profile* (those live in MG4Control), so this bridge exposes exactly that and
 * nothing else. There is no vehicle read or raw property write here.
 *
 * Guarded by the signature permission com.mg4.control.permission.TASKER_BRIDGE.
 */
interface ITaskerBridge {

    /** MG4Control profiles. Bundle: "ids" String[], "names" String[], "defaultId" String. */
    Bundle listProfiles();

    /**
     * Applies a whole profile. Bundle result: "ok" boolean, "verdict" String
     * (ALLOWED | REFUSED_MOVING | REFUSED_UNKNOWN_SPEED | UNSUPPORTED | ERROR),
     * "detail" String optional.
     */
    Bundle applyProfile(String profileId);
}
