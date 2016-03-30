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
BASEDIR="$(dirname $JAR_PATH)"
JAR_NAME="$(basename -- $0)"
cd "$BASEDIR"
LOGS="$BASEDIR/logs"
[ -d "$LOGS" ] || mkdir "$LOGS"
LOG_FILE="$LOGS/start.log"
OWNER="$(ls -ld $BASEDIR/$JAR_NAME | awk '{print $3}')"

SERVICE_NAME="BWG Agent"
PID_PATH_NAME="$BASEDIR/bwg-agent-pid"
PARAMS="-Djava.library.path=./lib -Djava.security.policy=./dio.policy"

start() {
    echo "Starting $SERVICE_NAME ..."
    if [ ! -f $PID_PATH_NAME ]; then
        su $OWNER -c "nohup java $PARAMS -jar $JAR_NAME 2>> $LOG_FILE >> $LOG_FILE &"
        echo $! > $PID_PATH_NAME
        echo "$SERVICE_NAME started ..."
    else
        echo "$SERVICE_NAME is already running ..."
    fi
}

stop() {
    if [ -f $PID_PATH_NAME ]; then
        PID=$(cat $PID_PATH_NAME);

        echo "$SERVICE_NAME stopping ..."
        # Number of seconds to wait before using "kill -9"
        WAIT_SECONDS=10

        # Counter to keep count of how many seconds have passed
        count=0

        while kill $PID 2>> $LOG_FILE >> $LOG_FILE
        do
            # Wait for one second
            sleep 1
            # Increment the second counter
            ((count++))

            # Has the process been killed? If so, exit the loop.
            if ! ps -p $PID > /dev/null ; then
                break
            fi

            # Have we exceeded $WAIT_SECONDS? If so, kill the process with "kill -9"
            # and exit the loop
            if [ $count -gt $WAIT_SECONDS ]; then
                kill -9 $PID 2>> $LOG_FILE >> $LOG_FILE
                break
            fi
        done
        echo "$SERVICE_NAME has been stopped after $count seconds."
        rm $PID_PATH_NAME > /dev/null
    else
        echo "$SERVICE_NAME is not running ..."
    fi
}

status() {
    if [ -f $PID_PATH_NAME ]; then
        echo "$SERVICE_NAME is running ..."
    else
        echo "$SERVICE_NAME is stopped ..."
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
esac

exit 0
