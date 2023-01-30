#!/bin/sh

#docker container stop vscode-docker-vivado
#docker container rm -v vscode-docker-vivado

CONTAINER_DISPLAY="0"
CONTAINER_HOSTNAME="vivado-container"

X11TMPDIR=/tmp/vscode-docker-x11-remote

HOSTNAME=`hostname`

if [ ! -d ${X11TMPDIR} ]; then
    # Create a directory for the socket
    rm -rf ${X11TMPDIR}
    mkdir -p ${X11TMPDIR}/socket
    touch ${X11TMPDIR}/Xauthority

    # Get the DISPLAY slot
    DISPLAY_NUMBER=$(echo $DISPLAY | cut -d. -f1 | cut -d: -f2)
    echo "DISPLAY_NUMBER=$DISPLAY_NUMBER"

    # Extract current authentication cookie
    AUTH_COOKIE=$(xauth list | grep "^$(HOSTNAME)/unix:${DISPLAY_NUMBER} " | awk '{print $3}')
    echo "AUTH_COOKIE=$AUTH_COOKIE"

    # Create the new X Authority file
    xauth -f ${X11TMPDIR}/Xauthority add ${CONTAINER_HOSTNAME}/unix:${CONTAINER_DISPLAY} MIT-MAGIC-COOKIE-1 ${AUTH_COOKIE}

    # Proxy with the :0 DISPLAY
    socat UNIX-LISTEN:${X11TMPDIR}/socket/X${CONTAINER_DISPLAY},fork TCP4:localhost:60${DISPLAY_NUMBER} &

    # if user id inside docker container differs from host id
    # we need to provide access for this other user
    # inspired by https://jtreminio.com/blog/running-docker-containers-as-current-host-user/
    chmod ugo+rwx -R ${X11TMPDIR}
    # not sure why this is ALSO needed
    setfacl -R -m user:1000:rwx ${X11TMPDIR}
fi
