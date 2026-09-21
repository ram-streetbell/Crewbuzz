# CrewBuzz

CrewBuzz is an Android restaurant waitstaff paging terminal with **automatic local-network discovery**.

## Network architecture

The Android tablet runs:
- HTTP table-call receiver on port **8080**
- UDP discovery responder on port **4001**

ESP8266 call buttons do **not** store the tablet IP.

1. ESP8266 joins the restaurant Wi-Fi.
2. ESP broadcasts `CREWBUZZ_DISCOVER` on UDP port 4001.
3. CrewBuzz replies with its current IP and HTTP port.
4. ESP sends the table call to `POST /request`.
5. If the tablet IP changes, the ESP discovers it again automatically.
6. If a request fails, the ESP clears the old address and re-discovers.

So the system no longer depends on a fixed Android tablet IP.

## Android app

- Staff login with demo PIN `1234`
- Dashboard with active calls
- Real HTTP table-call receiver
- UDP automatic discovery
- Device registration/status UI
- Call history
- Settings showing local receiver configuration
- No internet service is required for table calls; the tablet and ESP devices only need to be on the same local Wi-Fi/LAN.

## ESP8266 firmware

Firmware:
`firmware/ESP8266_CrewBuzz_Discovery/ESP8266_CrewBuzz_Discovery.ino`

Before uploading, change:
- `WIFI_SSID`
- `WIFI_PASSWORD`
- `TABLE_01` if this button belongs to another table

Connections:
- TTP223 VCC -> 3.3V
- TTP223 GND -> GND
- TTP223 OUT -> D2 / GPIO4
- NodeMCU built-in LED -> D4 / GPIO2

## Important

The Android tablet and ESP8266 must be on the same local Wi-Fi network. If the router has **AP/client isolation** enabled, devices may not be able to discover or connect to each other.

## Build

Open the repository in Android Studio and run the `app` configuration, or run:

```
gradle :app:assembleRelease
```

GitHub Actions builds the release APK automatically on pushes to `main`.


Release pipeline updated for installable CrewBuzz Terminal builds.
