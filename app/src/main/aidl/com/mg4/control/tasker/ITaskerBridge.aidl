package com.mg4.control.tasker;

/**
 * Pont IPC exposé par MG4Control à MG4Tasker.
 *
 * Contrat volontairement ÉTROIT : 4 méthodes, jamais une par réglage véhicule.
 * Ajouter une action au catalogue ne change pas cette interface, seulement le
 * dispatch interne de applyAction(). Toute écriture véhicule reste exécutée
 * dans le processus MG4Control, donc soumise à VehicleWriteGate.
 *
 * Protégé par la permission signature com.mg4.control.permission.TASKER_BRIDGE.
 */
interface ITaskerBridge {

    /**
     * Instantané de l'état véhicule pour l'évaluation des conditions.
     * Toutes les clés sont optionnelles : une valeur absente = donnée illisible.
     * Voir TaskerBridgeService.KEY_* pour la liste.
     */
    Bundle readSnapshot();

    /** Profils MG4Control. Bundle : "ids" String[], "names" String[], "defaultId" String. */
    Bundle listProfiles();

    /** Applique un profil complet. Bundle résultat : voir applyAction. */
    Bundle applyProfile(String profileId);

    /**
     * Exécute une action unitaire du catalogue.
     * @param actionType identifiant stable, ex. "SET_MEDIA_VOLUME"
     * @param params arguments typés ("int", "bool", "string" selon l'action)
     * @return Bundle : "ok" boolean, "verdict" String
     *         (ALLOWED | REFUSED_MOVING | REFUSED_UNKNOWN_SPEED | UNSUPPORTED | ERROR),
     *         "detail" String optionnel.
     */
    Bundle applyAction(String actionType, in Bundle params);
}
