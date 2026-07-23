package com.mg4.control.api;

/**
 * MG4Control's external control API — driving profiles.
 *
 * Any app signed with the same platform key can bind this and list or apply MG4Control's
 * driving profiles. It is deliberately narrow: profiles only, no vehicle read and no raw
 * property write. It is not tied to any particular caller.
 *
 * Guarded by the signature permission com.mg4.control.permission.CONTROL_PROFILES.
 */
interface IProfileControl {

    /** MG4Control profiles. Bundle: "ids" String[], "names" String[], "defaultId" String. */
    Bundle listProfiles();

    /**
     * Applies a whole profile. Bundle result: "ok" boolean, "verdict" String
     * (ALLOWED | REFUSED_MOVING | REFUSED_UNKNOWN_SPEED | UNSUPPORTED | ERROR),
     * "detail" String optional.
     */
    Bundle applyProfile(String profileId);
}
