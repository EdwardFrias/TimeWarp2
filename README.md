# TimeWarp para LDPlayer

Cada **10 segundos reales** avanza el reloj del sistema Android del emulador **1 día** (configurable, también hacia atrás).

## Requisitos en LDPlayer
Activa root: **Ajustes → Otros ajustes → Permiso root** (el nombre puede variar según la versión) y reinicia el emulador.

## Compilar el APK
Opción A, Android Studio: abre esta carpeta y usa *Build → Build APK(s)*.
Opción B, terminal (JDK 17 + Android SDK con plataforma 34): `./gradlew assembleDebug`.
Opción C, sin instalar nada: sube la carpeta a un repositorio de GitHub; el workflow *Build APK* deja el APK como artefacto.

El APK queda en `app/build/outputs/apk/debug/app-debug.apk`.

## Instalar y usar
Arrastra el APK a la ventana de LDPlayer (o *Instalar APK*), abre **TimeWarp**, pulsa *Comprobar root* y luego *Iniciar TimeWarp*.
Al pulsar *Detener* el reloj se queda en la fecha en la que estaba. Si marcas *Al detener, devolver la hora real*, se restaura la hora real.

## Cómo funciona
- Hora virtual = hora de inicio + segundos reales × días/segundo × 86400, calculada con el reloj monotónico (`elapsedRealtime`), sin deriva.
- Al iniciar prueba varios métodos (`AlarmManager.setTime`, `date` toybox/toolbox, `service call alarm`) y usa el primero que verifica que cambió la hora.
- Desactiva `auto_time` mientras corre. Solo lo restaura si detienes con la opción de devolver la hora real; si no, queda desactivado para que el sistema no vuelva a sincronizar la hora por su cuenta.
- Límite 1971–2037: el `time_t` de 32 bits de Android desborda en 2038. Al llegar, el reloj se detiene (o vuelve al inicio si activas el bucle).

## Avisos
- Con la fecha saltando, HTTPS, inicios de sesión y Google Play Services suelen fallar (certificados "caducados"/"aún no válidos").
- Los juegos que validan la hora en su servidor no se dejan engañar por el reloj local.
- Sin root, solo funciona si el APK se instala como app de sistema (`/system/priv-app`), donde `AlarmManager.setTime` tiene permiso.
