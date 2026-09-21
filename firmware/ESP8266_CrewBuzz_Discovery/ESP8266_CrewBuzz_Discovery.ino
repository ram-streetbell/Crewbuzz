#include <ESP8266WiFi.h>
#include <WiFiUdp.h>

const char* WIFI_SSID = "YOUR_WIFI_NAME";
const char* WIFI_PASSWORD = "YOUR_WIFI_PASSWORD";
const char* TABLE_ID = "TABLE_01";
const char* DEVICE_ID = "ESP8266-01";

#define TOUCH_PIN 4   // D2 / GPIO4
#define LED_PIN   2   // D4 / GPIO2

const unsigned int DISCOVERY_PORT = 4001;
const unsigned long DISCOVERY_INTERVAL = 10000;
const unsigned long DEBOUNCE_MS = 500;
const unsigned long DEVICE_ANNOUNCE_INTERVAL = 5000;
unsigned long lastDeviceAnnounce = 0;

String crewbuzzIp = "";
uint16_t crewbuzzPort = 8080;
unsigned long lastDiscovery = 0;
unsigned long lastTouch = 0;
bool lastTouchState = false;

void setLed(bool on) {
  digitalWrite(LED_PIN, on ? LOW : HIGH);
}

bool announceDevice() {
  if (WiFi.status() != WL_CONNECTED) return false;

  WiFiUDP udp;
  if (!udp.begin(0)) return false;

  String message = String("CREWBUZZ_DEVICE|") + TABLE_ID + "|" + DEVICE_ID + "|" + WiFi.localIP().toString();
  udp.beginPacket(IPAddress(255, 255, 255, 255), DISCOVERY_PORT);
  udp.print(message);
  udp.endPacket();
  udp.stop();
  return true;
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
    int size = discovery.parsePacket();

    if (size > 0) {
      char buffer[256];
      int len = discovery.read(buffer, sizeof(buffer) - 1);
      buffer[len] = '\0';

      String response = String(buffer);
      response.trim();

      if (response.startsWith("CREWBUZZ|")) {
        int p1 = response.indexOf('|');
        int p2 = response.indexOf('|', p1 + 1);
        int p3 = response.indexOf('|', p2 + 1);

        if (p1 > 0 && p2 > p1 && p3 > p2) {
          crewbuzzIp = response.substring(p1 + 1, p2);
          crewbuzzPort = (uint16_t)response.substring(p2 + 1, p3).toInt();

          Serial.print("CrewBuzz found: ");
          Serial.print(crewbuzzIp);
          Serial.print(":");
          Serial.println(crewbuzzPort);

          discovery.stop();
          return true;
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

  WiFiClient client;

  if (!client.connect(crewbuzzIp.c_str(), crewbuzzPort)) {
    crewbuzzIp = "";

    if (!discoverCrewBuzz()) return false;

    return sendTableCall();
  }

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

  while (client.connected() && millis() - timeout < 2000) {
    while (client.available()) {
      String line = client.readStringUntil('\n');

      if (line == "\r") {
        client.stop();
        return true;
      }
    }

    delay(1);
  }

  client.stop();
  return false;
}

void setup() {
  Serial.begin(115200);
  delay(300);

  pinMode(TOUCH_PIN, INPUT);
  pinMode(LED_PIN, OUTPUT);
  setLed(false);

  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

  Serial.print("Connecting to Wi-Fi");

  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }

  Serial.println();
  Serial.println("Wi-Fi connected");
  Serial.print("ESP IP: ");
  Serial.println(WiFi.localIP());

  setLed(true);
  announceDevice();
  discoverCrewBuzz();
}

void loop() {
  if (WiFi.status() != WL_CONNECTED) {
    setLed(false);
    delay(500);
    return;
  }

  setLed(true);

  if (millis() - lastDeviceAnnounce >= DEVICE_ANNOUNCE_INTERVAL) {
    lastDeviceAnnounce = millis();
    announceDevice();
  }

  if (millis() - lastDiscovery >= DISCOVERY_INTERVAL ||
      crewbuzzIp.length() == 0) {
    lastDiscovery = millis();
    discoverCrewBuzz();
  }

  bool touched = digitalRead(TOUCH_PIN) == HIGH;

  if (touched &&
      !lastTouchState &&
      millis() - lastTouch >= DEBOUNCE_MS) {

    lastTouch = millis();

    Serial.println("TABLE CALL");

    bool ok = sendTableCall();

    Serial.println(ok ? "Call delivered" : "Call delivery failed");
  }

  lastTouchState = touched;
  delay(20);
}
