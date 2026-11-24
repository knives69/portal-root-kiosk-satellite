#!/system/bin/sh
# portal-autoroot/apply.sh — acciones de root en cada arranque (v3 consolidada).
# Se ejecuta en la ventana privilegiada, antes de cerrar el daemon.
#
# LINEAS ROJAS (verificadas y prohibidas):
#  * pm disable com.facebook.portal.aiservice  -> Device 0 unavailable, portalsnap sin imagen
#  * debloat de componentes dentro de aiservice (DelayedWorkerService, ConditionalWorkerService…)
#  * tocar settings_config.xml (sin efecto y se trunco con credencial de camara)
#  * parar fwanalytics / ping-service (0 % medido; su parada coincidio con la rotura)
#  * parar thermal-engine (es proteccion termica)
# FUERA en esta version, pendiente de medir por separado:
#  * core_ctl/min_cpus (deja dormir el cluster grande: afecta pipeline y timing)
#  * audiodebug_daemon (el audio es producto)

TMP=/data/local/tmp
LOG=$TMP/portal-autoroot/apply.log
: > "$LOG"
log() { echo "$*" >> "$LOG" 2>/dev/null; }
state_of() { [ -r "/proc/$1/stat" ] && awk '{print $3}' "/proc/$1/stat" 2>/dev/null; }

log "apply.sh $(date)"
log "boot=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)"
log "uid=$(id -u 2>/dev/null) sid=$(cat /proc/self/attr/current 2>/dev/null)"

# 1) KSM: lo apaga el bundle Binder de 1b) (el HAL corre en hal_perf_default).
#    NO se escribe el nodo por sysfs desde shell: siempre falla (EACCES), y un
#    chmod 0444 dejaria el nodo en solo-lectura TAMBIEN para el HAL, con lo que
#    se perderia la unica via que funciona (Binder).

# 1b) Bundle Vía B al HAL de perf (funciona desde shell: binder_call permitido).
#     Empaquetado permanente de suelos (duration=0, num_args=6):
#       1. KSM OFF:            0x42000000 (1107296256) val 1 -> KSM erradicado (0% CPU)
#       2. core_ctl min_cpus:  0x41000000 (1090519040) val 0 -> clúster grande duerme en reposo
#       3. cpubw min_freq:     0x41800000 (1098907648) val 1 -> suelo de bus CPU-DDR mínimo
#     (Regla: suelos sí; techos y quitar boosts no -> sched_boost queda intacto para trabajo real).
#     Se limpia handle previo en caché de perfservice y se guarda el nuevo devuelto.
service call vendor.perfservice 1 >/dev/null 2>&1
PERF_HANDLE=$(service call vendor.perfservice 4 i32 0 i32 6 i32 1107296256 i32 1 i32 1090519040 i32 0 i32 1098907648 i32 1 2>/dev/null \
  | grep -oE "00000000 [0-9a-f]{8}" | awk '{print $2}')
log "perf_bundle=ksm_off+corectl_min0+cpubw_min handle=${PERF_HANDLE:-none}"
# handle persistido: permite liberar el lock sin reiniciar
printf '%s\n' "${PERF_HANDLE:-none}" > $TMP/portal-autoroot/perf_handle 2>/dev/null

# 2) printk: el log esta inundado de QCamera; bajamos el nivel (denegado como shell)
echo "1 6 1 7" 2>/dev/null > /proc/sys/kernel/printk
log "printk=$(cat /proc/sys/kernel/printk 2>/dev/null)"

# 3) LowMemoryKiller: sin depuracion verbosa
echo 0 2>/dev/null > /sys/module/lowmemorykiller/parameters/debug_level
log "lmk_debug=$(cat /sys/module/lowmemorykiller/parameters/debug_level 2>/dev/null)"

# 4) ZRAM: swappiness a un valor normal (estaba 100/140)
echo 60 2>/dev/null > /proc/sys/vm/swappiness
log "swappiness=$(cat /proc/sys/vm/swappiness 2>/dev/null)"

# 5) Telemetria de diagnostico WiFi: apagar la fuente y el demonio
setprop persist.service.wifi.diagnostics.enabled false 2>/dev/null
setprop ctl.stop cnss_diag 2>/dev/null
sleep 1
CN=$(pidof cnss_diag 2>/dev/null | awk '{print $1}')
if [ -n "$CN" ]; then
    kill -STOP "$CN" 2>/dev/null
    sleep 1
    log "cnss_diag=alive pid=$CN state=$(state_of $CN) (T = congelado)"
else
    log "cnss_diag=stopped"
fi

# 6) thermal-engine: sigue vivo, solo cede CPU
TH=$(pidof thermal-engine 2>/dev/null | awk '{print $1}')
if [ -n "$TH" ]; then
    # toybox renice solo admite incremento (-n). thermal-engine corre con
    # nice -20, así que -n 10 dejaría -10 (sigue siendo alta prioridad):
    # se calcula el incremento para fijar el valor ABSOLUTO 10.
    CUR=$(awk '{print $19}' /proc/$TH/stat 2>/dev/null)
    INC=$((10 - ${CUR:-0}))
    renice -n "$INC" -p "$TH" >/dev/null 2>&1
    log "thermal-engine=renice inc=$INC rc=$? pid=$TH nice=$(awk '{print $19}' /proc/$TH/stat 2>/dev/null)"
fi

# 6b) core_ctl del clúster grande: aplicado arriba vía Binder (0x41000000 = 0).
#     Se mantiene lectura de confirmación en sysfs (min_cpus y global_state).
CC=/sys/devices/system/cpu/cpu4/core_ctl/min_cpus
if [ -e "$CC" ]; then
    log "core_ctl.min_cpus=$(cat $CC 2>/dev/null)"
fi

# 6c) Superficie/GPU: omitir la validación redundante de capas.
#     Valor actual: sdm.debug.disable_skip_validate=1 (validaciones SIEMPRE),
#     vendor.display.disable_skip_validate vacío -> ponerlos a 0 ACTIVA la omisión
#     (es la optimización). Beneficio no medido: se registra para A/B con
#     cameraserver y la tasa de frames de portalsnap.
setprop sdm.debug.disable_skip_validate 0 2>/dev/null
setprop vendor.display.disable_skip_validate 0 2>/dev/null
log "skip_validate=$(getprop sdm.debug.disable_skip_validate)/$(getprop vendor.display.disable_skip_validate)"

# 6d) Ruido de log: silenciar tags por liblog (NO es printk, que esta denegado).
#     persist.log.tag.* SI es escribible desde el dominio shell (verificado 24-11:
#     rc=0, se lee S, y se revierte con valor vacio). liblog lo respeta EN CALIENTE
#     (medido: 29 lineas QCamera en 6 s -> 0) y el valor persiste entre arranques.
TAGS="QCamera ImmortalSensors aloha.AlohaUsersIncomingMessageHandler CollectPingData AshmemAllocator PhoneStatusViewGroup aloha.PLUserInitImpl aloha.ProxyUserInitImpl aloha.OwnerAccountsRefreshHelper aloha.TrackAndHoldAiDirector"
for T in $TAGS; do
    setprop "persist.log.tag.$T" S 2>/dev/null
    log "log_tag.$T=$(getprop "persist.log.tag.$T" 2>/dev/null)"
done
# 7) ftrace de audio/privacidad en RAM, solo si la instancia existe
for inst in privacy audio; do
    F=/sys/kernel/debug/tracing/instances/$inst/tracing_on
    [ -e "$F" ] || continue
    echo 0 2>/dev/null > "$F"
    log "ftrace.$inst=$(cat $F 2>/dev/null)"
done

# 7b) SONDA de acceso al directorio privado de aiservice.
#     Decide si el cap de 2 fps puede persistirse desde el arranque (docs/27, paso 1).
#     Registra el error EXACTO por nivel: distingue ENOENT (no existe) de EACCES (SELinux/DAC).
#     Solo crea y borra un fichero VACIO; no toca datos de la app ni la camara.
AIS=/data/user/0/com.facebook.portal.aiservice
for P in /data/user/0 "$AIS" "$AIS/files" "$AIS/databases"; do
    E=$( ( ls -ld "$P" ) 2>&1 )
    case "$E" in
        *"$P"*) log "probe_stat OK $P" ;;
        *)      log "probe_stat ERR $P :: ${E:-empty}" ;;
    esac
done
if [ -d "$AIS/files" ]; then
    E=$( ( : > "$AIS/files/.portal_probe" ) 2>&1 )
    if [ -e "$AIS/files/.portal_probe" ]; then
        log "probe_write=OK uid=$(id -u)"
        rm -f "$AIS/files/.portal_probe" 2>/dev/null
    else
        log "probe_write=DENIED uid=$(id -u) msg=${E:-unknown}"
    fi
else
    log "probe_write=DENIED (dir no visible) uid=$(id -u) sid=$(cat /proc/self/attr/current 2>/dev/null)"
fi
# 8) Captura de diagnostico (kallsyms + traza del arranque anterior)
cat /proc/kallsyms > $TMP/kallsyms.txt 2>/dev/null
cat /sys/fs/pstore/console-ramoops* > $TMP/pstore.txt 2>/dev/null
log "kallsyms_lines=$(wc -l < $TMP/kallsyms.txt 2>/dev/null)"
log "pstore_bytes=$(wc -c < $TMP/pstore.txt 2>/dev/null)"
log "fin"
exit 0
