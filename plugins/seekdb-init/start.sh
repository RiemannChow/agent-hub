#!/bin/bash
# Corrected SeekDB startup script (replaces the image's buggy /root/start.sh).
# Fixes:
#   - data_version.bin lives at /var/lib/seekdb/etc (image ships a wrong /var/lib/oceanbase path)
#   - obshell base-dir must be /var/lib/seekdb
#   - obshell / password steps are non-fatal (observer runs fine without obshell)

set -e

WAIT_FOR_CONFIG_FILE_ATTEMPTS=600
WAIT_FOR_SERVICE_READY_ATTEMPTS=300
WAIT_INTERVAL_SECONDS=1

CONFIG_FILE="/etc/seekdb/seekdb.cnf"

seekdb_set_cnf() {
  local key="$1"
  local value="$2"
  if [ -f "$CONFIG_FILE" ] && grep -qE "^${key}=" "$CONFIG_FILE"; then
    sed -i "s|^${key}=.*|${key}=${value}|" "$CONFIG_FILE"
  else
    echo "${key}=${value}" >> "$CONFIG_FILE"
  fi
}

if [ -n "$DATAFILE_SIZE" ]; then   seekdb_set_cnf datafile_size "$DATAFILE_SIZE"; fi
if [ -n "$DATAFILE_NEXT" ]; then   seekdb_set_cnf datafile_next "$DATAFILE_NEXT"; fi
if [ -n "$DATAFILE_MAXSIZE" ]; then seekdb_set_cnf datafile_maxsize "$DATAFILE_MAXSIZE"; fi
if [ -n "$CPU_COUNT" ]; then      seekdb_set_cnf cpu_count "$CPU_COUNT"; fi
if [ -n "$MEMORY_LIMIT" ]; then    seekdb_set_cnf memory_limit "$MEMORY_LIMIT"; fi
if [ -n "$LOG_DISK_SIZE" ]; then   seekdb_set_cnf log_disk_size "$LOG_DISK_SIZE"; fi

# Start the observer (backgrounds itself)
/usr/libexec/seekdb/scripts/seekdb_systemd_start 2>/dev/null

SEEKDB_CONFIG_FILE="/var/lib/seekdb/etc/seekdb.data_version.bin"
for i in $(seq 1 $WAIT_FOR_CONFIG_FILE_ATTEMPTS); do
  if [ -f "$SEEKDB_CONFIG_FILE" ]; then
    echo "File '$SEEKDB_CONFIG_FILE' found on attempt #$i."
    break
  fi
  if [ $((i % 10)) -eq 0 ]; then echo "seekdb is still not ready."; fi
  sleep $WAIT_INTERVAL_SECONDS
done

# obshell agent (optional management agent) - correct base dir, non-fatal, time-boxed
timeout 30 obshell agent start --seekdb --base-dir=/var/lib/seekdb 2>/dev/null \
  || echo "obshell agent start skipped/failed (non-fatal)."

INITIALIZED_FLAG="/var/lib/seekdb/.initialized"

if [ ! -f "$INITIALIZED_FLAG" ]; then
  MYSQL_OPTS="-h127.0.0.1 -P2881 -uroot"
  if [ -n "$ROOT_PASSWORD" ]; then MYSQL_OPTS="$MYSQL_OPTS -p$ROOT_PASSWORD"; fi

  if [ -n "$SEEKDB_DATABASE" ]; then
    mysql $MYSQL_OPTS -e "CREATE DATABASE IF NOT EXISTS \`$SEEKDB_DATABASE\`;" || echo "warn: create $SEEKDB_DATABASE failed"
    echo "Database $SEEKDB_DATABASE ensured."
    MYSQL_OPTS="$MYSQL_OPTS -D$SEEKDB_DATABASE"
  fi

  if [ -n "$INIT_SCRIPTS_PATH" ]; then
    echo "Executing initialization scripts from $INIT_SCRIPTS_PATH..."
    for sql_file in "$INIT_SCRIPTS_PATH"/*.sql; do
      if [ -f "$sql_file" ]; then
        echo "Executing $sql_file..."
        mysql $MYSQL_OPTS < "$sql_file" || echo "Warning: $sql_file failed"
      fi
    done
    echo "Initialization scripts execution complete."
  fi

  touch "$INITIALIZED_FLAG"
  echo "Initialization complete."
else
  echo "Already initialized. Skipping initialization."
fi

echo "Seekdb started"
echo "Start seekdb health check loop"
while pgrep seekdb > /dev/null; do
  sleep 5
done

echo "Seekdb process not found. Exiting."
exit 1
