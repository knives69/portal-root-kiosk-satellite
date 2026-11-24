#!/system/bin/sh
# portal-autoroot/apply.sh — v5 PRODUCCIÓN (2026-11-24)
#
# Lo ejecuta el helper a través del daemon, como uid 0 en dominio `shell`, en cada
# arranque, justo antes de cerrar el daemon. Nunca debe bloquear el arranque: siempre exit 0.
#
# ── ACTIVO (verificado con efecto medido) ──────────────────────────────────────────
#   * bundle de 3 suelos al HAL `vendor.perfservice` (Binder): KSM off, core_ctl min 0,
#     cpubw min_freq.  Evidencia: apply.log `perf_bundle=… handle=0xNN` + `core_ctl.min_cpus=0`.
#   * 10 tags de log silenciados por liblog (`persist.log.tag.*=S`).  Evidencia: 29→0 líneas/6 s.
#   * watchdog post-boot desacoplado: revierte el revert de `init.qcom.post_boot.sh`.
#   * blindaje de artefactos (permisos root + manifiesto + autorreparación).
#
# ── COMENTADO: verificado SIN efecto (2026-11-24) ─────────────────────────────────
#   El dominio `shell` no puede ni hacer `stat` de esos nodos (MAC manda sobre DAC, también
#   con uid 0 y CAP_DAC_OVERRIDE). Se conservan como historial; NO reactivar sin cambiar de
#   contexto de ejecución:
#     · printk (lo sustituye 6d)      · lowmemorykiller/debug_level
#     · vm/swappiness                 · cnss_diag (el setprop no entra y ctl.stop no es suyo)
#     · thermal-engine renice (rc=1)  · sdm/vendor display.skip_validate
#     · kallsyms + pstore (siempre 0) · sonda probe_write (experimento cerrado, docs/27 §11)
#
# ── SIN VERIFICAR: ftrace instances ──────────────────────────────────────────────
#   `/sys/kernel/debug/tracing/instances` → Permission denied incluso para leer. No se puede
#   afirmar que no sirva, pero tampoco que sirva: comentado hasta poder probarlo en otro dominio.
#
# ── LÍNEAS ROJAS (no tocar) ──────────────────────────────────────────────────────
#   · pm disable/clear com.facebook.portal.aiservice (deja device 0 muerto, pierde mcs_information)
#   · debloat de componentes *WorkerService dentro de aiservice
#   · editar files/settings_config.xml a mano
#   · parar fwanalytics / ping-service / thermal-engine
#   · am force-stop de aiservice

DIR=/data/local/tmp/portal-autoroot
TMP=/data/local/tmp
LOG=$DIR/apply.log
RELEASE=$TMP/portal-release-2026-11-24

# guarda de parada (la crea el host para desactivar el autoroot)
[ -e "$TMP/portal-autoroot.disable" ] && exit 0

: > "$LOG" 2>/dev/null
log() { echo "$*" >> "$LOG" 2>/dev/null; }
state_of() { [ -r "/proc/$1/stat" ] && awk '{print $3}' "/proc/$1/stat" 2>/dev/null; }

log "apply.sh v5 $(date)"
log "boot=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)"
log "uid=$(id -u 2>/dev/null) sid=$(cat /proc/self/attr/current 2>/dev/null)"

# ── 1) BUNDLE DE 3 SUELOS (única optimización de CPU/bus que funciona desde shell) ──
#    IMPORTANTE: re-adquirir sin liberar es un NO-OP (el HAL devuelve el mismo handle y no
#    reescribe el nodo). Por eso se libera primero con la transacción 1.
#    Recursos: 0x42000000 KSM=1 · 0x41000000 (major 4 = core hotplug, min cores)=0
#              0x41800000 (major 6 = cpubw, min_freq)=1
apply_bundle() {
    service call vendor.perfservice 1 >/dev/null 2>&1
    service call vendor.perfservice 4 i32 0 i32 6 \
        i32 1107296256 i32 1 \
        i32 1090519040 i32 0 \
        i32 1098907648 i32 1 2>/dev/null \
        | grep -oE "00000000 [0-9a-f]{8}" | awk '{print $2}'
}

PERF_HANDLE=$(apply_bundle)
printf '%s\n' "${PERF_HANDLE:-none}" > "$DIR/perf_handle" 2>/dev/null
log "perf_bundle=ksm_off+corectl_min0+cpubw_min handle=${PERF_HANDLE:-none}"
# verificación indirecta de KSM (el nodo /sys/kernel/mm/ksm/run no es legible desde shell):
# si ksmd no consume jiffies en 2 s, no está fusionando páginas. 0 = apagado efectivo.
KSM_PID=$(pidof ksmd 2>/dev/null | awk '{print $1}')
if [ -n "$KSM_PID" ]; then
    K0=$(awk '{print $14+$15}' /proc/$KSM_PID/stat 2>/dev/null)
    sleep 2
    K1=$(awk '{print $14+$15}' /proc/$KSM_PID/stat 2>/dev/null)
    log "ksmd_pid=$KSM_PID ksmd_delta=$(( ${K1:-0} - ${K0:-0} )) jiffies/2s"
else
    log "ksmd_pid=none"
fi

CC=/sys/devices/system/cpu/cpu4/core_ctl/min_cpus
[ -r "$CC" ] && log "core_ctl.min_cpus=$(cat "$CC" 2>/dev/null)"
log "cpus_online=$(cat /sys/devices/system/cpu/online 2>/dev/null)"

# ── 1c) WATCHDOG POST-BOOT (subproceso desacoplado, muere solo) ───────────────────
#    Causa raíz del revert del suelo: `init.qcom.post_boot.sh` vuelve a escribir
#    /sys/devices/system/cpu/cpu4/core_ctl/min_cpus = 2 DESPUÉS de boot_completed.
#    Este subproceso espera de forma no bloqueante a `vendor.post_boot.parsed = 1`
#    (máx 90 s), comprueba el nodo y, si volvió a 2, RE-ADQUIERE el bundle.
#    No deja demonios residentes: termina en cuanto hace su trabajo.
WLOG=$DIR/watchdog.log
(
    exec </dev/null >/dev/null 2>&1
    W=0
    while [ "$(getprop vendor.post_boot.parsed 2>/dev/null)" != "1" ] && [ "$W" -lt 90 ]; do
        sleep 3
        W=$((W + 3))
    done
    sleep 5
    MIN=$(cat "$CC" 2>/dev/null)
    echo "$(date) post_boot_parsed=$(getprop vendor.post_boot.parsed 2>/dev/null) waited=${W}s min_cpus=$MIN" >> "$WLOG" 2>/dev/null
    if [ -n "$MIN" ] && [ "$MIN" != "0" ]; then
        NEWH=$(apply_bundle)
        sleep 2
        echo "$(date) reacquired handle=${NEWH:-none} min_cpus=$(cat "$CC" 2>/dev/null)" >> "$WLOG" 2>/dev/null
        printf '%s\n' "${NEWH:-none}" > "$DIR/perf_handle" 2>/dev/null
    else
        echo "$(date) min_cpus ya en 0, sin re-adquirir" >> "$WLOG" 2>/dev/null
    fi
) &
log "watchdog=spawned pid=$! log=$WLOG"

# ── 2) RUIDO DE LOG: silenciar por liblog (esto SÍ entra desde shell) ─────────────
#    Reversible: setprop persist.log.tag.<TAG> ""
#    Se mantiene visible: aloha.PLUserInit* ya silenciado a petición; NO silenciar
#    ia_voice_processing_hal / KnowlesParamReader (voz), mm-camera / CameraAnalytics /
#    AlohaCameraPipeline (cámara), crash-uploader (tombstones).
TAGS="QCamera ImmortalSensors aloha.AlohaUsersIncomingMessageHandler CollectPingData \
AshmemAllocator PhoneStatusViewGroup aloha.PLUserInitImpl aloha.ProxyUserInitImpl \
aloha.OwnerAccountsRefreshHelper aloha.TrackAndHoldAiDirector"
for T in $TAGS; do
    setprop "persist.log.tag.$T" S 2>/dev/null
done
log "log_tags=$(for T in $TAGS; do printf '%s ' "$(getprop "persist.log.tag.$T" 2>/dev/null)"; done)"

# ── 3) BLINDAJE: que nada pise el estado bueno por accidente ─────────────────────
#    Un `adb push` (uid 2000) FALLA con EACCES en vez de sobrescribir en silencio.
#    Actualizaciones legítimas: desde este mismo script (root) o desde el payload/daemon.
if [ -d "$DIR" ]; then
    mkdir -p "$RELEASE" 2>/dev/null
    for f in run.sh env-odd.sh env-even.sh autoroot.conf apply.sh; do
        [ -f "$DIR/$f" ] || continue
        [ -f "$RELEASE/$f" ] || cp -f "$DIR/$f" "$RELEASE/$f" 2>/dev/null
        chown root:root "$DIR/$f" "$RELEASE/$f" 2>/dev/null
        chmod 0555 "$RELEASE/$f" 2>/dev/null
        chmod 0555 "$DIR/$f" 2>/dev/null
    done
    if [ -f "$TMP/preload-portal.so" ] && [ ! -f "$RELEASE/preload-portal.so" ]; then
        cp -f "$TMP/preload-portal.so" "$RELEASE/preload-portal.so" 2>/dev/null
    fi
    chown root:root "$TMP/preload-portal.so" "$RELEASE/preload-portal.so" 2>/dev/null
    chmod 0555 "$TMP/preload-portal.so" "$RELEASE/preload-portal.so" 2>/dev/null
    # actualización INTENCIONADA de artefactos: crear $DIR/.release-refresh y el
    # siguiente apply.sh regenera el manifiesto en vez de restaurar lo viejo.
    if [ -e "$DIR/.release-refresh" ]; then
        rm -f "$RELEASE/MANIFEST.sha256" 2>/dev/null
        rm -f "$DIR/.release-refresh" 2>/dev/null
        log "release_refresh=1"
    fi
    # manifiesto: se crea una vez y no se pisa (es la referencia)
    if [ ! -f "$RELEASE/MANIFEST.sha256" ]; then
        ( cd "$RELEASE" && sha256sum preload-portal.so run.sh env-odd.sh env-even.sh \
            autoroot.conf apply.sh 2>/dev/null > MANIFEST.sha256 )
        chown root:root "$RELEASE/MANIFEST.sha256" 2>/dev/null
        chmod 0444 "$RELEASE/MANIFEST.sha256" 2>/dev/null
    fi
    # verificación: si algo no cuadra con el manifiesto, se restaura desde $RELEASE
    BAD=0
    while read -r sum name; do
        [ -n "$name" ] || continue
        case "$name" in preload-portal.so) LIVE=$TMP/$name ;; *) LIVE=$DIR/$name ;; esac
        [ -f "$LIVE" ] || continue
        NOW=$(sha256sum "$LIVE" 2>/dev/null | awk '{print $1}')
        if [ "$NOW" != "$sum" ]; then
            BAD=1
            cp -f "$RELEASE/$name" "$LIVE" 2>/dev/null
            chown root:root "$LIVE" 2>/dev/null
            chmod 0555 "$LIVE" 2>/dev/null
            log "release_restored=$name"
        fi
    done < "$RELEASE/MANIFEST.sha256"
    log "release_verify=$([ "$BAD" = 0 ] && echo OK || echo RESTORED)"
    # directorio pegajoso: el helper sigue escribiendo state/result/logs, pero no puede
    # borrar ni sustituir los ficheros de root
    chown root:root "$DIR" 2>/dev/null
    chmod 1777 "$DIR" 2>/dev/null
    # el directorio de referencia NO debe ser escribible: si podemos, 0555 (root lo sí)
    chmod 0555 "$RELEASE" 2>/dev/null
    log "dir_perms=$(ls -ld "$DIR" 2>/dev/null | awk '{print $1, $3, $4}')"
    log "release_perms=$(ls -ld "$RELEASE" 2>/dev/null | awk '{print $1, $3}')"
    # si no corremos como root, chown falla: dejarlo visible
    if [ "$(stat -c %U "$RELEASE/preload-portal.so" 2>/dev/null)" = "root" ]; then
        log "perm_mode=root"
    else
        log "perm_mode=nonroot (chown no aplicable; 0555 sigue bloqueando adb push)"
    fi
fi

# ── 4) COMENTADO: sin efecto desde el dominio shell (historial, ver cabecera) ─────
# # 2) printk: el log esta inundado de QCamera; bajamos el nivel (denegado como shell)
# echo "1 6 1 7" 2>/dev/null > /proc/sys/kernel/printk
# log "printk=$(cat /proc/sys/kernel/printk 2>/dev/null)"
#
# # 3) LowMemoryKiller: sin depuracion verbosa
# echo 0 2>/dev/null > /sys/module/lowmemorykiller/parameters/debug_level
# log "lmk_debug=$(cat /sys/module/lowmemorykiller/parameters/debug_level 2>/dev/null)"
#
# # 4) ZRAM: swappiness a un valor normal (estaba 100/140)
# echo 60 2>/dev/null > /proc/sys/vm/swappiness
# log "swappiness=$(cat /proc/sys/vm/swappiness 2>/dev/null)"
#
# # 5) Telemetria de diagnostico WiFi: apagar la fuente y el demonio
# #    Medido 2026-11-24: el setprop NO entra (getprop sigue a true) y `ctl.stop` no es suyo.
# setprop persist.service.wifi.diagnostics.enabled false 2>/dev/null
# setprop ctl.stop cnss_diag 2>/dev/null
# sleep 1
# CN=$(pidof cnss_diag 2>/dev/null | awk '{print $1}')
# if [ -n "$CN" ]; then
#     kill -STOP "$CN" 2>/dev/null
#     sleep 1
#     log "cnss_diag=alive pid=$CN state=$(state_of $CN) (T = congelado)"
# else
#     log "cnss_diag=stopped"
# fi
#
# # 6) thermal-engine: sigue vivo, solo cede CPU  (SELinux: setsched/kill denegados, rc=1)
# TH=$(pidof thermal-engine 2>/dev/null | awk '{print $1}')
# if [ -n "$TH" ]; then
#     CUR=$(awk '{print $19}' /proc/$TH/stat 2>/dev/null)
#     INC=$((10 - ${CUR:-0}))
#     renice -n "$INC" -p "$TH" >/dev/null 2>&1
#     log "thermal-engine=renice inc=$INC rc=$? pid=$TH nice=$(awk '{print $19}' /proc/$TH/stat 2>/dev/null)"
# fi
#
# # 6c) Superficie/GPU: omitir la validacion redundante de capas.
# #     Medido 2026-11-24: getprop sigue a 1 (prop de sistema, no escribible desde shell).
# setprop sdm.debug.disable_skip_validate 0 2>/dev/null
# setprop vendor.display.disable_skip_validate 0 2>/dev/null
# log "skip_validate=$(getprop sdm.debug.disable_skip_validate)/$(getprop vendor.display.disable_skip_validate)"
#
# # 7) ftrace de audio/privacidad en RAM — SIN VERIFICAR (debugfs oculto a shell)
# for inst in privacy audio; do
#     F=/sys/kernel/debug/tracing/instances/$inst/tracing_on
#     [ -e "$F" ] || continue
#     echo 0 2>/dev/null > "$F"
#     log "ftrace.$inst=$(cat $F 2>/dev/null)"
# done
#
# # 7b) SONDA de acceso al directorio privado de aiservice (experimento cerrado, docs/27 §11)
# #     probe_stat OK x4 · probe_write=DENIED -> el dominio shell no atraviesa app_data_file.
# AIS=/data/user/0/com.facebook.portal.aiservice
# for P in /data/user/0 "$AIS" "$AIS/files" "$AIS/databases"; do
#     E=$( ( ls -ld "$P" ) 2>&1 )
#     case "$E" in
#         *"$P"*) log "probe_stat OK $P" ;;
#         *)      log "probe_stat ERR $P :: ${E:-empty}" ;;
#     esac
# done
# if [ -d "$AIS/files" ]; then
#     E=$( ( : > "$AIS/files/.portal_probe" ) 2>&1 )
#     if [ -e "$AIS/files/.portal_probe" ]; then
#         log "probe_write=OK uid=$(id -u)"
#         rm -f "$AIS/files/.portal_probe" 2>/dev/null
#     else
#         log "probe_write=DENIED uid=$(id -u) msg=${E:-unknown}"
#     fi
# else
#     log "probe_write=DENIED (dir no visible) uid=$(id -u) sid=$(cat /proc/self/attr/current 2>/dev/null)"
# fi
#
# # 8) Captura de diagnostico (kallsyms + traza del arranque anterior) — siempre 0 lineas
# cat /proc/kallsyms > $TMP/kallsyms.txt 2>/dev/null
# cat /sys/fs/pstore/console-ramoops* > $TMP/pstore.txt 2>/dev/null
# log "kallsyms_lines=$(wc -l < $TMP/kallsyms.txt 2>/dev/null)"
# log "pstore_bytes=$(wc -c < $TMP/pstore.txt 2>/dev/null)"

log "fin"
exit 0
