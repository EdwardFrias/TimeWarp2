# TimeWarp para LDPlayer

Cada **10 segundos reales** avanza el reloj del sistema Android del emulador **1 día** (configurable, también hacia atrás).

## Requisitos en LDPlayer
Activa root: **Ajustes → Otros ajustes → Permiso root** (el nombre puede variar según la versión) y reinicia el emulador.

## Compilar el APK
Opción A, Android Studio: abre esta carpeta y usa *Build → Build APK(s)*.
Opción B, terminal (JDK 17 + Android SDK con plataforma 34): `./gradlew assembleRelease` (APK optimizado con R8 y firmado con la clave de depuración).
Opción C, sin instalar nada: sube la carpeta a un repositorio de GitHub; el workflow *Build APK* deja el APK como artefacto.

El APK optimizado queda en `app/build/outputs/apk/release/app-release.apk`. En Android Studio elige la variante *release* en *Build Variants*; con `assembleDebug` sale la versión debug en `debug/app-debug.apk`.

## Si la instalación falla
1. Desinstala TimeWarp del emulador si ya estaba (otra firma o versión provoca el fallo).
2. Usa `app-debug.apk` en vez del release.
3. Para ver el error real de Android, desde la carpeta de LDPlayer: `adb devices` y luego `adb install -r ruta\app-debug.apk` (el mensaje `INSTALL_FAILED_...` dice la causa exacta).

## Instalar y usar
Arrastra el APK a la ventana de LDPlayer (o *Instalar APK*), abre **TimeWarp**, pulsa *Comprobar root* y luego *Iniciar TimeWarp*.
Al pulsar *Detener* el reloj se queda en la fecha en la que estaba. Si marcas *Al detener, devolver la hora real*, se restaura la hora real.

## Ventana flotante
Pulsa *Abrir ventana flotante*. La primera vez hace falta el permiso «Mostrar sobre otras apps»: con root, la app lo concede sola (`appops`); si no, abre la pantalla de permisos para que lo actives tú.
La ventana se arrastra, muestra la hora del sistema y tiene *Iniciar/Detener*, *App* (abre la pantalla principal) y *–* (la minimiza a una burbuja «TW»; tócala para expandirla). *X* cierra la ventana sin detener TimeWarp. Usa los ajustes guardados en la pantalla principal.

## Cómo funciona
- Hora virtual = hora de inicio + segundos reales × días/segundo × 86400, calculada con el reloj monotónico (`elapsedRealtime`), sin deriva.
- Al iniciar prueba varios métodos (`AlarmManager.setTime`, `date` toybox/toolbox, `service call alarm`) y usa el primero que verifica que cambió la hora.
- Desactiva `auto_time` mientras corre. Solo lo restaura si detienes con la opción de devolver la hora real; si no, queda desactivado para que el sistema no vuelva a sincronizar la hora por su cuenta.
- Límite por defecto 1971–2037: el `time_t` de 32 bits de Android desborda en 2038. Al llegar, el reloj se detiene (o vuelve al inicio si activas el bucle).
- Casilla *Quitar el límite de 2037*: permite llegar hasta el año 9999. Pasada la fecha 2038-01-19 la app verifica en cada cambio que la hora realmente quedó puesta y, si el método actual falla, prueba los demás (`service call alarm` usa milisegundos de 64 bits). Si ninguno funciona, el reloj se detiene justo antes de 2038. Depende del Android del emulador (en LDPlayer de 64 bits suele funcionar).

## Optimización
- Sin dependencias externas; en release, R8 y `shrinkResources`.
- Una sola shell `su` persistente, y los comandos de hora se construyen con aritmética (sin `SimpleDateFormat`) para no generar basura cada segundo.
- El texto de estado solo se reconstruye cuando cambia.
- La interfaz flotante se actualiza 1 vez por segundo, y se pausa si está minimizada o la pantalla se apaga.
- Se mantiene un wake lock parcial mientras TimeWarp corre, para que el reloj siga avanzando con la pantalla apagada.

## Avisos
- Con la fecha saltando, HTTPS, inicios de sesión y Google Play Services suelen fallar (certificados "caducados"/"aún no válidos").
- Los juegos que validan la hora en su servidor no se dejan engañar por el reloj local.
- Sin root, solo funciona si el APK se instala como app de sistema (`/system/priv-app`), donde `AlarmManager.setTime` tiene permiso.
