# Keep all reflection targets intact (SAIC SDK, android.car.Car, ServiceManager)
-keep class android.car.** { *; }
-keep class android.os.ServiceManager { *; }
-keep class com.saicmotor.** { *; }
-keep class com.mg4.hardware.model.** { *; }
-keep class com.mg4.hardware.MG4Hardware { *; }

# Reflected by Class.forName in MG4Hardware/FirmwareInfo — same treatment as
# ServiceManager, which was already listed.
-keep class android.os.SystemProperties { *; }

# Contrat IPC avec MG4Tasker. Le Stub/Proxy AIDL est résolu par nom côté client :
# renommer ces classes casserait le bind au lieu d'échouer à la compilation.
-keep interface com.mg4.control.tasker.ITaskerBridge { *; }
-keep class com.mg4.control.tasker.ITaskerBridge$* { *; }

# Gson (profils + sauvegarde). Sans Signature, le type générique de
# TypeToken<List<DrivingProfile>> est effacé et la désérialisation rend une liste de
# LinkedTreeMap : les profils disparaîtraient silencieusement au premier lancement d'une
# release minifiée.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes *Annotation*
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Les champs des classes sérialisées sont lus par réflexion : ne pas les renommer.
-keepclassmembers class com.mg4.hardware.model.** {
    <fields>;
    <init>(...);
}

# Stack traces exploitables dans le rapport de crash (CrashLogger) — sans ça les lignes
# obfusquées rendent le rapport inutilisable sans le fichier mapping.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
