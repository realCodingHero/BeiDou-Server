#!/bin/sh
set -e

working_dir=/opt/server
working_dir_bak=/opt/server_backup
marker_file="$working_dir/.initialized"

mkdir -p $working_dir

if [ ! -f "$marker_file" ] && [ ! -f "$working_dir/BeiDou.jar" ]; then
    echo "First run - initializing volume from image backup..."
    cp -r $working_dir_bak/* $working_dir/
    touch $marker_file
    echo "Initialization complete."
fi

cd $working_dir

exec java ${JAVA_OPTS} -jar ./BeiDou.jar --spring.config.location=./application.yml "$@"
