# Raspberry Pi camera setup

The app uses the Pi's camera like this:

1. The app turns on a Wi-Fi network from the phone (see step 1 below). The Pi joins it automatically.
2. The app finds the Pi on the hotspot. It tries the address that worked last time, then `PiConfig.FIXED_HOST`, then every address on the hotspot that answers on the SSH port (about 1 s).
3. It logs in over SSH and runs `rpicam-vid`, which serves an MJPEG stream on TCP port 8888.
4. It reads the stream and runs detection only on the newest frame, dropping older ones, so delay never builds up.
5. When the app closes, the SSH session closes and `rpicam-vid` stops with it.

**Why MJPEG over plain TCP:** it has the lowest delay of the usual options. Each frame is a complete JPEG, so the phone can decode any frame on its own. Frames never wait in a video decoder's buffer, and frames are skipped freely when detection is slower than the camera. H.264, RTSP and WebRTC all add decoder or jitter buffering, which costs roughly 100–500 ms. The cost is bandwidth: about 1–1.5 MB/s at 640×480 / 30 fps, which a hotspot handles easily.

Nothing needs to be installed on the Pi beyond the standard Raspberry Pi OS.

---

## 1. Phone network (automatic)

With `AUTO_HOTSPOT = true` in `PiConfig.kt` (the default), the app turns on its own Wi-Fi network when it starts, using `HOTSPOT_NAME` and `HOTSPOT_PASSWORD`. Android doesn't let apps switch on the normal hotspot, so the app uses whichever of these the phone supports:

| Phone | Network the app creates | Name the Pi sees |
|---|---|---|
| Android 16 QPR2 or newer | Local-only hotspot | exactly `HOTSPOT_NAME`, e.g. `picamera` |
| Android 10 to 16 | Wi-Fi Direct group | `DIRECT-SV-` + `HOTSPOT_NAME`, e.g. `DIRECT-SV-picamera`. Android requires the `DIRECT-xy` prefix for these |
| Older | none | turn on the normal hotspot by hand |

Things to know:

- The network is **2.4 GHz with WPA2**, which every Pi supports. It has **no internet**: it only links the phone and the Pi.
- It stays on while the app is running and turns off when the app is closed.
- **Wi-Fi Direct needs the phone's Wi-Fi switched on.** The phone doesn't have to be connected to anything. If Wi-Fi is off, the app says *"Turn on Wi-Fi so the phone can start the Pi network."*
- **Turn the normal hotspot off.** Most phones can't run it alongside the app's network.
- The second status line in the app shows the network, e.g. `Phone network: "DIRECT-SV-picamera" on`.

**Doing it by hand instead** (`AUTO_HOTSPOT = false`): in Settings → Hotspot, name the hotspot `HOTSPOT_NAME` and set its password to `HOTSPOT_PASSWORD`. Use WPA2-Personal and 2.4 GHz, and turn off "Turn off hotspot automatically". Then switch it on from Quick Settings before using the Pi camera.

## 2. Raspberry Pi (one time)

### Easiest: let the app set up the Pi's Wi-Fi

1. In `PiConfig.kt`, set `HOTSPOT_NAME` and `HOTSPOT_PASSWORD`.
2. Get the Pi onto **any** network the phone is also on: your home Wi-Fi via Raspberry Pi Imager (see below), or an Ethernet cable to your router. Connect the phone to the same Wi-Fi.
3. Open the app. It finds the Pi on that network, logs in, and saves both possible names from step 1 on the Pi: `sva-hotspot` (`picamera`) and `sva-direct` (`DIRECT-SV-picamera`). The Pi will join either one automatically, preferring them over other networks, with power saving off.

After that, the Pi joins the phone's network by itself. It can take up to about 2 minutes to notice a network that has just appeared; the app keeps looking for 3 minutes after it starts. If you change the name or password later, update `PiConfig` and connect the app to the Pi once more over another network. The app only rewrites the Pi's settings when they differ.

Requirements: the Pi uses NetworkManager (Raspberry Pi OS Bookworm or newer), and the Pi user has sudo rights. The user created by Imager does.

To check it on the Pi: `nmcli connection show sva-hotspot`. In Android Studio's Logcat, the tag `PiHotspot` shows `added`, `updated` or `unchanged`.

### New SD card

In **Raspberry Pi Imager**, choose Raspberry Pi OS (Lite is fine). Open *Edit settings / OS customisation* and set:

- **Username and password**: put the same values in `PiConfig.USERNAME` / `PiConfig.PASSWORD` in the app.
- **Wireless LAN**: the hotspot name and password from step 1, and your Wi-Fi country. If you use the app setup above, enter your home Wi-Fi here instead.
- **Services** tab: enable **SSH** with **password authentication**.

After the first boot, turn off Wi-Fi power saving. Power saving adds random 50–300 ms delays to the stream:

```bash
nmcli -t -f NAME,TYPE connection show            # find the Wi-Fi connection (Imager names it "preconfigured")
sudo nmcli connection modify preconfigured 802-11-wireless.powersave 2
```

### Pi that's already set up

```bash
# Join the hotspot automatically, preferred over other networks, with power saving off
sudo nmcli connection add type wifi con-name phone-hotspot ifname wlan0 ssid "HOTSPOT_NAME" \
    wifi-sec.key-mgmt wpa-psk wifi-sec.psk "HOTSPOT_PASSWORD" \
    connection.autoconnect yes connection.autoconnect-priority 100 \
    802-11-wireless.powersave 2

# SSH on at every boot
sudo systemctl enable --now ssh
```

### Check the camera

```bash
rpicam-hello --list-cameras      # should list your camera module (libcamera-hello on older OS)
```

## 3. App

Edit `app/src/main/java/com/example/myapplication/pi/PiConfig.kt`:

| Setting | What it does |
|---|---|
| `USERNAME`, `PASSWORD` | Pi SSH login |
| `HOTSPOT_NAME`, `HOTSPOT_PASSWORD` | Name and password of the phone's network for the Pi. Leave the name empty to set up the Pi's Wi-Fi yourself |
| `AUTO_HOTSPOT` | `true`: the app turns the network on itself (step 1). `false`: you turn on the normal hotspot by hand |
| `FIXED_HOST` | Optional IP/hostname to try before scanning |
| `WIDTH`, `HEIGHT`, `FPS` | Stream size. 640×480 matches the YOLO input well |
| `JPEG_QUALITY` | Lower it (e.g. 50) if the picture lags on weak Wi-Fi |
| `ROTATE_180` | Set `true` if the camera is mounted upside down |

When the app opens it says *"Looking for Pi camera"* and keeps searching until the Pi is found. The status line at the top shows each step. Once video arrives it says *"Pi camera connected"*. If the Pi can't be reached it says *"Can't reach the Pi camera. Still trying."* once and keeps looking. If the Pi stream drops, the app says *"Pi camera disconnected. Reconnecting."* and keeps retrying.

On Android 17+ the app asks for the **Nearby devices** permission. It is needed to connect to the Pi over the hotspot.

## Testing the stream without the app

With a laptop on the same hotspot:

```bash
# on the Pi
rpicam-vid -t 0 -n --codec mjpeg --width 640 --height 480 --framerate 30 -q 70 --listen -o tcp://0.0.0.0:8888
# on the laptop
ffplay -fflags nobuffer -flags low_delay -f mjpeg tcp://PI_IP:8888
```

## Troubleshooting

The status line at the top of the app shows what is happening.

| Status message | Fix |
|---|---|
| `Phone network: Wi-Fi is off` | Switch the phone's Wi-Fi on (needed for Wi-Fi Direct). It doesn't have to connect to anything. |
| `Phone network: Wi-Fi Direct busy` | Turn off the phone's normal hotspot, or any other Wi-Fi Direct connection. |
| `no Pi found on the hotspot` | Wait up to 2 minutes for the Pi to notice the network. If it still doesn't join, the Pi doesn't have this network name saved yet: connect once over home Wi-Fi or Ethernet (step 2). On the Pi, `nmcli connection show` should list `sva-hotspot` and `sva-direct`. |
| `SSH login to … rejected` | `PiConfig` username/password don't match the Pi. |
| `Pi camera didn't start: …` | Usually the ribbon cable or another program using the camera. Run `rpicam-hello --list-cameras` on the Pi. |
| `Pi video port didn't open` | A firewall on the Pi is blocking TCP 8888 (e.g. `sudo ufw allow 8888/tcp`). |
| `no video for 3 s` | Wi-Fi dropped or the Pi lost power. The app reconnects on its own. |
