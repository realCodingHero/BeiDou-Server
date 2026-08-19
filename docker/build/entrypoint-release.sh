#!/bin/sh
set -e

working_dir=/opt/server
working_dir_bak=/opt/server_backup
marker_file="$working_dir/.initialized"

mkdir -p $working_dir

if [ ! -f "$marker_file" ] && [ ! -f "$working_dir/BeiDou.jar" ]; then
    echo "First run - initializing volume..."
    cp -r $working_dir_bak/* $working_dir/
    touch $marker_file
    echo "Initialization complete. Backup kept for future recovery."
fi

cd $working_dir

JAVA_EXEC=$(find . -type f -name java -path "*/bin/java" | head -1)

if [ -z "$JAVA_EXEC" ]; then
    JAVA_EXEC="java"
else
    chmod +x "$JAVA_EXEC"
fi

exec "$JAVA_EXEC" ${JAVA_OPTS} -jar ./BeiDou.jar --spring.config.location=./application.yml "$@"
