#include <ESP8266WiFi.h>
#include <ESP8266WebServer.h>
#include <WiFiUdp.h>

// CHANGE THESE 3 VALUES
const char* WIFI_SSID = "YOUR_WIFI_NAME";
const char* WIFI_PASSWORD = "YOUR_WIFI_PASSWORD";
const char* TABLE_ID = "TABLE_01";
const char* DEVICE_ID = "ESP8266-01";

#define TOUCH_PIN 4   // D2 / GPIO4 - TTP223 OUT
#define LED_PIN   2   // D4 / GPIO2 - built-in LED (active LOW)

const unsigned int DISCOVERY_PORT = 4001;
const unsigned long DISCOVERY_INTERVAL = 10000;
const unsigned long DEVICE_ANNOUNCE_INTERVAL = 5000;
const unsigned long DEBOUNCE_MS = 500;

WiFiUDP deviceUdp;
ESP8266WebServer webServer(80);
String crewbuzzIp = "";
uint16_t crewbuzzPort = 8080;
unsigned long lastDiscovery = 0;
unsigned long lastDeviceAnnounce = 0;
unsigned long lastTouch = 0;
bool lastTouchState = false;

void setLed(bool on) {
  digitalWrite(LED_PIN, on ? LOW : HIGH);
}

void handleDeviceInfo() {
  String json = String("{\"type\":\"CREWBUZZ_DEVICE\",\"table_id\":\"") + TABLE_ID +
                "\",\"device_id\":\"" + DEVICE_ID +
                "\",\"ip\":\"" + WiFi.localIP().toString() +
                "\",\"tablet_ip\":\"" + crewbuzzIp +
                "\",\"tablet_port\":" + String(crewbuzzPort) + "}";
  webServer.send(200, "application/json", json);
}

void handleHealth() {
  webServer.send(200, "text/plain", "CREWBUZZ_OK");
}

void handleConfigure() {
  if (!webServer.hasArg("ip")) {
    webServer.send(400, "text/plain", "Missing ip");
    return;
  }

  String ip = webServer.arg("ip");
  ip.trim();
  uint16_t port = webServer.hasArg("port") ? (uint16_t)webServer.arg("port").toInt() : 8080;

  IPAddress parsed;
  if (ip.length() == 0 || !parsed.fromString(ip) || port == 0) {
    webServer.send(400, "text/plain", "Invalid tablet address");
    return;
  }

  crewbuzzIp = ip;
  crewbuzzPort = port;

  Serial.print("CrewBuzz configured: ");
  Serial.print(crewbuzzIp);
  Serial.print(":");
  Serial.println(crewbuzzPort);

  webServer.send(200, "application/json", "{\"ok\":true}");
}

void handleDeviceDiscovery() {
  int packetSize = deviceUdp.parsePacket();
  if (packetSize <= 0) return;

  char buffer[128];
  int len = deviceUdp.read(buffer, sizeof(buffer) - 1);
  if (len < 0) return;
  buffer[len] = '\0';

  String message = String(buffer);
  message.trim();

  if (message == "CREWBUZZ_DEVICE_DISCOVER") {
    String response = String("CREWBUZZ_DEVICE|") + TABLE_ID + "|" + DEVICE_ID + "|" + WiFi.localIP().toString();
    deviceUdp.beginPacket(deviceUdp.remoteIP(), deviceUdp.remotePort());
    deviceUdp.print(response);
    deviceUdp.endPacket();
  }
}

void announceDevice() {
  if (WiFi.status() != WL_CONNECTED) return;

  WiFiUDP udp;
  if (!udp.begin(0)) return;

  String message = String("CREWBUZZ_DEVICE|") + TABLE_ID + "|" + DEVICE_ID + "|" + WiFi.localIP().toString();
  udp.beginPacket(IPAddress(255, 255, 255, 255), DISCOVERY_PORT);
  udp.print(message);
  udp.endPacket();
  udp.stop();
}

bool discoverCrewBuzz() {
  if (WiFi.status() != WL_CONNECTED) return false;

  WiFiUDP discovery;
  if (!discovery.begin(0)) return false;

  discovery.beginPacket(IPAddress(255, 255, 255, 255), DISCOVERY_PORT);
  discovery.print("CREWBUZZ_DISCOVER");
  discovery.endPacket();

  unsigned long start = millis();

  while (millis() - start < 1500) {
    int packetSize = discovery.parsePacket();

    if (packetSize > 0) {
      char buffer[256];
      int len = discovery.read(buffer, sizeof(buffer) - 1);

      if (len > 0) {
        buffer[len] = '\0';
        String response = String(buffer);
        response.trim();

        if (response.startsWith("CREWBUZZ|")) {
          int p1 = response.indexOf('|');
          int p2 = response.indexOf('|', p1 + 1);
          int p3 = response.indexOf('|', p2 + 1);

          if (p1 > 0 && p2 > p1 && p3 > p2) {
            String newIp = response.substring(p1 + 1, p2);
            uint16_t newPort = (uint16_t)response.substring(p2 + 1, p3).toInt();

            if (newIp.length() > 0 && newPort > 0) {
              crewbuzzIp = newIp;
              crewbuzzPort = newPort;

              Serial.print("CrewBuzz found: ");
              Serial.print(crewbuzzIp);
              Serial.print(":");
              Serial.println(crewbuzzPort);

              discovery.stop();
              return true;
            }
          }
        }
      }
    }

    delay(10);
  }

  discovery.stop();
  return false;
}

bool sendTableCall() {
  if (crewbuzzIp.length() == 0) {
    if (!discoverCrewBuzz()) return false;
  }

  for (int attempt = 0; attempt < 2; attempt++) {
    WiFiClient client;
    client.setTimeout(1200);

    if (client.connect(crewbuzzIp.c_str(), crewbuzzPort)) {
      String json = String("{\"type\":\"TABLE_CALL\",\"table_id\":\"") + TABLE_ID + "\",\"request\":\"WAITER\"}";

      client.println("POST /request HTTP/1.1");
      client.print("Host: ");
      client.println(crewbuzzIp);
      client.println("Content-Type: application/json");
      client.print("Content-Length: ");
      client.println(json.length());
      client.println("Connection: close");
      client.println();
      client.print(json);

      unsigned long timeout = millis();
      while (millis() - timeout < 2500) {
        if (client.available()) {
          String line = client.readStringUntil('\n');
          line.trim();
          if (line.startsWith("HTTP/") && line.indexOf(" 200 ") >= 0) {
            client.stop();
            return true;
          }
          if (line.startsWith("HTTP/")) {
            client.stop();
            break;
          }
        }

        if (!client.connected()) break;
        delay(2);
      }

      client.stop();
    }

    crewbuzzIp = "";
    if (attempt == 0 && !discoverCrewBuzz()) return false;
  }

  return false;
}

void setup() {
  Serial.begin(115200);
  delay(300);

  pinMode(TOUCH_PIN, INPUT);
  pinMode(LED_PIN, OUTPUT);
  setLed(false);

  Serial.println();
  Serial.println("================================");
  Serial.println("CrewBuzz ESP8266 TABLE DEVICE");
  Serial.println("================================");
  Serial.print("Table: ");
  Serial.println(TABLE_ID);
  Serial.print("Device: ");
  Serial.println(DEVICE_ID);

  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
  WiFi.persistent(false);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

  Serial.print("Connecting to Wi-Fi");
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print('.');
  }

  Serial.println();
  Serial.println("Wi-Fi connected");
  Serial.print("ESP IP: ");
  Serial.println(WiFi.localIP());

  deviceUdp.begin(DISCOVERY_PORT);

  webServer.on("/crewbuzz", HTTP_GET, handleDeviceInfo);
  webServer.on("/health", HTTP_GET, handleHealth);
  webServer.on("/configure", HTTP_GET, handleConfigure);
  webServer.begin();
  Serial.println("HTTP device server: port 80");

  setLed(true);
  announceDevice();
  discoverCrewBuzz();
}

void loop() {
  if (WiFi.status() != WL_CONNECTED) {
    setLed(false);
    WiFi.reconnect();
    delay(500);
    return;
  }

  setLed(true);
  webServer.handleClient();
  handleDeviceDiscovery();

  if (millis() - lastDeviceAnnounce >= DEVICE_ANNOUNCE_INTERVAL) {
    lastDeviceAnnounce = millis();
    announceDevice();
  }

  if (crewbuzzIp.length() == 0 || millis() - lastDiscovery >= DISCOVERY_INTERVAL) {
    lastDiscovery = millis();
    discoverCrewBuzz();
  }

  bool touched = (digitalRead(TOUCH_PIN) == HIGH);

  if (touched && !lastTouchState && millis() - lastTouch >= DEBOUNCE_MS) {
    lastTouch = millis();
    Serial.println("TABLE CALL");

    bool ok = sendTableCall();
    Serial.println(ok ? "Call delivered" : "Call delivery failed");
  }

  lastTouchState = touched;
  delay(20);
}
