#!/bin/bash
# Haven Linux VM Setup
# Run this in the Android Terminal app
#
# Usage:
#   Download and run:  curl -sLO https://raw.githubusercontent.com/GlassOnTin/Haven/main/scripts/haven-vm-setup.sh && bash haven-vm-setup.sh
#   Or pipe (skips VNC password prompt):  curl -sL ... | bash

set -e

echo "=== Updating packages ==="
sudo apt update && sudo apt upgrade -y

echo "=== Installing SSH server ==="
sudo apt install -y openssh-server
sudo sed -i 's/^#\?Port .*/Port 8022/' /etc/ssh/sshd_config
sudo sed -i 's/^#\?PasswordAuthentication .*/PasswordAuthentication yes/' /etc/ssh/sshd_config
sudo systemctl enable --now ssh
echo "SSH ready on port 8022"

echo "=== Installing VNC server and desktop ==="
sudo apt install -y tigervnc-standalone-server tigervnc-common \
    xfce4 xfce4-terminal dbus-x11 xfonts-base

echo "=== Setting VNC password ==="
if [ -t 0 ]; then
    # Interactive — prompt for password
    vncpasswd
else
    # Piped — set a default password, user can change later with vncpasswd
    echo -e "haven\nhaven\nn" | vncpasswd >/dev/null 2>&1
    echo "VNC password set to 'haven' — change with: vncpasswd"
fi

echo "=== Creating VNC startup config ==="
mkdir -p ~/.vnc
cat > ~/.vnc/xstartup << 'XSTARTUP'
#!/bin/sh
unset SESSION_MANAGER
unset DBUS_SESSION_BUS_ADDRESS
export XDG_SESSION_TYPE=x11
exec startxfce4
XSTARTUP
chmod +x ~/.vnc/xstartup

echo "=== Starting VNC server ==="
vncserver -kill :1 2>/dev/null || true
vncserver :1 -localhost no -geometry 1920x1080 -depth 24

echo ""
echo "=== Done ==="
echo "SSH:  localhost:8022"
echo "VNC:  localhost:5901"
echo ""
echo "In Haven, tap the Linux VM card on the Connect tab."
