# shellcheck shell=bash
# Sourced by tools/app, tools/dev and tools/emu: where adb is, and waiting for it to settle.

ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"

# The adb server in platform-tools 37 aborts on its own (a panic in its mDNS discovery, or losing
# a start-up race), and a restarted one lists devices over the next second, the emulator as
# `offline` first. Picking a device in that window picks the wrong one or finds none. A server
# that was already up only gets a short wait: a device can stay `offline` for good (a booting
# emulator, a rebooted phone's dead tcpip entry), and that must not stall every command.
adb_settle() {
  local started list prev="" limit
  started=$("$ADB" start-server 2>&1) || true
  case "$started" in
    *"daemon started"*) limit="${LIMENTY_ADB_WAIT:-30}" ;;
    *) adb_transient "$(adb_list)" || return 0
       limit="${LIMENTY_ADB_WAIT:-30}"; [ "$limit" -le 5 ] || limit=5 ;;
  esac
  local deadline=$((SECONDS + limit))
  while [ "$SECONDS" -lt "$deadline" ]; do
    list=$(adb_list)
    if [ "$list" = "$prev" ] && ! adb_transient "$list"; then return 0; fi
    prev="$list"
    sleep 1
  done
  echo "adb: still waiting on these after ${limit}s, going ahead without them:" >&2
  echo "$list" | awk '$2 == "offline" || $2 == "connecting" || $2 == "authorizing" { print "  " $0 }' >&2
}

# The serial of the emulator running AVD $1. Serials follow start order and other emulators are
# often attached too (another AVD, another project), so a serial remembered from an earlier run
# can name a different machine. The console answers the name even while the emulator is offline.
avd_serial() {
  local serial
  for serial in $("$ADB" devices | awk '$1 ~ /^emulator-/ {print $1}'); do
    if [ "$("$ADB" -s "$serial" emu avd name 2>/dev/null | head -1 | tr -d '\r')" = "$1" ]; then
      echo "$serial"; return 0
    fi
  done
  return 1
}

adb_list() {
  local list
  list=$("$ADB" devices 2>/dev/null | awk 'NR > 1 && NF') || true
  echo "${list:-none}"
}

# `unauthorized` is left out: it only changes when someone accepts the prompt on the device.
adb_transient() {
  echo "$1" | awk '$2 == "offline" || $2 == "connecting" || $2 == "authorizing" { found = 1 } END { exit !found }'
}
