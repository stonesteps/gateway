#!/bin/sh

### BEGIN INIT INFO
# Provides:          bwg-gateway-agent
# Required-Start:    $local_fs
# Required-Stop:     $local_fs
# Default-Start:     2 3 4 5
# Default-Stop:      0 1 6
# Description: Startup script for IOT Agent
# Short-Description: Startup script for IOT Agent
#
# to add this script to os startup/shutdown
# sudo update-rc.d bwg-gateway-agent defaults
#
### END INIT INFO

JAR_PATH="$(readlink -f $0)"
JAR_BACKUP="${JAR_PATH}_old"
BASEDIR="$(dirname $JAR_PATH)"
JAR_NAME="$(basename -- $0)"
UPGRADE_PACKAGE="${BASEDIR}/upgrade/upgradePackage.tar.gz"

cd "$BASEDIR"
LOGS="$BASEDIR/logs"
LOG_FILE="$LOGS/start.log"
OWNER="$(ls -ld $BASEDIR/$JAR_NAME | awk '{print $3}')"
JAVA_EXEC=java

[ -d "$LOGS" ] || su $OWNER -c "mkdir $LOGS"
[ -d "$BASEDIR/java" ] && JAVA_EXEC="./java/bin/java"

SERVICE_NAME="BWG Agent"
PARAMS="-Djava.library.path=./lib -Djava.security.policy=./dio.policy"

pid_of_jvm() {
    ps -eo pid,args | grep "[j]ava.*$JAR_NAME" | awk '{print $1}'
}

start() {
    echo "Starting $SERVICE_NAME ..."
    PID=`pid_of_jvm`
    if [ "x$PID" = "x" ]; then
        su $OWNER -c "nohup $JAVA_EXEC $PARAMS -jar $JAR_NAME 2>> $LOG_FILE >> $LOG_FILE &"
        echo "$SERVICE_NAME started ..."
    else
        echo "$SERVICE_NAME is already running ..."
    fi
}

stop() {
    PID=`pid_of_jvm`
    if [ "x$PID" != "x" ]; then

        echo "$SERVICE_NAME stopping ..."
        # Number of seconds to wait before using "kill -9"
        WAIT_SECONDS=10

        # Counter to keep count of how many seconds have passed
        count=0

        while kill $PID 2 > /dev/null
        do
            # Wait for one second
            sleep 1
            # Increment the second counter
            count=$((count + 1))

            # Has the process been killed? If so, exit the loop.
            PID=`pid_of_jvm`
            if [ "x$PID" = "x" ]; then
                break
            fi

            # Have we exceeded $WAIT_SECONDS? If so, kill the process with "kill -9"
            # and exit the loop
            if [ $count -gt $WAIT_SECONDS ]; then
                kill -9 $PID 2 > /dev/null
                break
            fi
        done
        echo "$SERVICE_NAME has been stopped after $count seconds."
    else
        echo "$SERVICE_NAME is not running ..."
    fi
}

status() {
    PID=`pid_of_jvm`
    if [ "x$PID" != "x" ]; then
        echo "$SERVICE_NAME is running ..."
    else
        echo "$SERVICE_NAME is stopped ..."
    fi

}

require_file() {
    if [ $# -ne 1 ]; then
        echo 'Usage: require_file <FILENAME>'
    else
        if [ ! -f "${1}" ]; then
            echo "Aborting: required file is not available at ${1}"
            exit 1
        fi
    fi
}

upgrade() {
    require_file "${UPGRADE_PACKAGE}"
    require_file "${JAR_PATH}"

    stop

    echo 'Upgrading.'

    # We are currently in ${BASEDIR}, file will be extracted to it.
    mv "${JAR_PATH}" "${JAR_BACKUP}" && tar -xzf "${UPGRADE_PACKAGE}" "${JAR_NAME}"

    if [ $? -ne 0 ]; then
        echo 'Upgrade failed (could not back up current jar or unpack new one).'
        exit 1
    else
        echo 'Upgrade completed'
    fi

    if start | grep 'started' ; then
        echo 'Successfully started after upgrade'
        status
    else
        echo 'Failed to start after upgrade, rolling back.'
        mv "${JAR_BACKUP}" "${JAR_PATH}"
        if [ $? -ne 0 ]; then
            echo 'Rollback failed (could not restore from back-up).'
            exit 1
        fi
        start
    fi
}

case $1 in
    start)
        start
    ;;
    stop)
        stop
    ;;
    restart)
        stop
        start
    ;;
    status)
        status
    ;;
    upgrade)
        upgrade
    ;;
esac

exit 0
