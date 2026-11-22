// ANIMORA : pouls (Pulse Sensor) + temperature (LM35) + GPS (NEO-6M) sur ESP32 - version 4 (WiFi + MQTT)
//
// Cablage :
//   Pulse sensor : S -> GPIO34, + -> 3V3, - -> GND
//   LM35         : +Vs -> 5V (VIN), Vout -> GPIO35, GND -> GND
//   GPS NEO-6M   : TX du GPS -> GPIO16 (RX2), RX du GPS -> GPIO17 (TX2), VCC -> 3V3/5V, GND -> GND
//   (GPIO34 et 35 sont sur l'ADC1 : compatibles avec le WiFi)
//
// Topics MQTT : animora/temperature , animora/heartbeat , animora/gps (JSON)
// Serial Monitor : 115200 baud

#include <WiFi.h>
#include <PubSubClient.h>
#include <TinyGPSPlus.h>
#include <HardwareSerial.h>

// ---------- WiFi / MQTT : A MODIFIER ----------
const char* WIFI_SSID   = "SM-A26 5G";   // attention : il y a un ESPACE avant le _
const char* WIFI_PASS   = "1234567890";
const char* MQTT_SERVER = "10.244.236.159";  // IP du PC (ipconfig)
const int   MQTT_PORT   = 1883;
const char* MQTT_USER   = "";               // laisser vide si allow_anonymous true
const char* MQTT_PASS   = "";
const char* TOPIC_TEMP  = "animora/temperature";
const char* TOPIC_BPM   = "animora/heartbeat";
const char* TOPIC_GPS   = "animora/gps";
const unsigned long MQTT_PUBLISH_INTERVAL = 2000;

WiFiClient espClient;
PubSubClient mqtt(espClient);
unsigned long lastMqttPublish = 0, lastWifiTry = 0, lastMqttTry = 0;

#define PIN_PULSE 34
#define PIN_LM35  35

// ---------- GPS ----------
#define GPS_RX   16
#define GPS_TX   17
#define GPS_BAUD 9600

TinyGPSPlus gps;
HardwareSerial GPS_Serial(2);

const bool PLOTTER_MODE = false;

// ---------- Reglages pouls ----------
const float AMP_MIN   = 60.0;
const int   IBI_N     = 8;
const int   MIN_BEATS = 5;
const float BPM_MIN   = 40.0;
const float BPM_MAX   = 220.0;

// ---------- Reglages LM35 ----------
const int   LM35_SAMPLES = 32;
const float OFFSET_C     = 0.0;

const unsigned long PRINT_INTERVAL = 1000;

// ----- Pouls -----
float pulseFilt = 0, pulseBase = 0;
float sigMax = 0, sigMin = 0, amp = 0, rawAvg = 0;
bool pulseAbove = false, pulseInit = false;
unsigned long lastBeat = 0, lastPulseRead = 0;
unsigned long ibiHist[IBI_N];
int ibiCount = 0, ibiIdx = 0;
float meanIbi = 0;
float bpm = 0;
bool pulseValid = false;
bool lowAmp = false;
unsigned long lowAmpSince = 0;

// ----- LM35 -----
long  lm35SumMv = 0;
int   lm35Count = 0;
float tempC = 0, tempMv = 0;
bool  tempReady = false;
unsigned long lastLm35Read = 0;

unsigned long lastPrint = 0;

// ====================== GPS ======================
void readGPS() {
  while (GPS_Serial.available() > 0) {
    gps.encode(GPS_Serial.read());
  }
}

// ====================== POULS ======================
void resetPulse() {
  ibiCount = 0;
  ibiIdx = 0;
  meanIbi = 0;
  bpm = 0;
  pulseValid = false;
}

void computeBpm() {
  unsigned long tmp[IBI_N];
  int n = ibiCount;
  for (int i = 0; i < n; i++) tmp[i] = ibiHist[i];

  for (int i = 1; i < n; i++) {
    unsigned long v = tmp[i];
    int j = i - 1;
    while (j >= 0 && tmp[j] > v) { tmp[j + 1] = tmp[j]; j--; }
    tmp[j + 1] = v;
  }

  float median = tmp[n / 2];
  float sum = 0;
  int k = 0;
  for (int i = 0; i < n; i++) {
    if (fabs((float)tmp[i] - median) <= 0.3f * median) {
      sum += tmp[i];
      k++;
    }
  }

  if (k >= MIN_BEATS - 1) {
    float mean = sum / k;
    float inst = 60000.0f / mean;
    if (inst >= BPM_MIN && inst <= BPM_MAX) {
      meanIbi = mean;
      bpm = inst;
      pulseValid = true;
      return;
    }
  }
  bpm = 0;
  pulseValid = false;
}

void readPulse() {
  unsigned long now = millis();
  if (now - lastPulseRead < 10) return;
  lastPulseRead = now;

  analogRead(PIN_PULSE);

  long sum = 0;
  for (int i = 0; i < 8; i++) sum += analogRead(PIN_PULSE);
  rawAvg = sum / 8.0f;

  if (!pulseInit) {
    pulseFilt = rawAvg;
    pulseBase = rawAvg;
    pulseInit = true;
  }

  pulseFilt = 0.7f * pulseFilt + 0.3f * rawAvg;
  pulseBase = 0.99f * pulseBase + 0.01f * pulseFilt;
  float sig = pulseFilt - pulseBase;

  sigMax = max(sig, sigMax * 0.995f);
  sigMin = min(sig, sigMin * 0.995f);
  amp = sigMax - sigMin;

  float threshold = sigMin + 0.5f * amp;

  if (PLOTTER_MODE) {
    Serial.print("Signal:"); Serial.print(sig, 1);
    Serial.print(",Seuil:"); Serial.println(threshold, 1);
  }

  if (amp < AMP_MIN) {
    if (!lowAmp) {
      lowAmp = true;
      lowAmpSince = now;
    } else if (now - lowAmpSince > 1500) {
      resetPulse();
    }
    pulseAbove = false;
    return;
  }
  lowAmp = false;

  bool above = sig > threshold;

  unsigned long minGap = 300;
  if (meanIbi > 0 && 0.5f * meanIbi > minGap) minGap = (unsigned long)(0.5f * meanIbi);

  if (above && !pulseAbove && (now - lastBeat) > minGap) {
    unsigned long ibi = now - lastBeat;
    lastBeat = now;

    if (ibi <= 1800) {
      ibiHist[ibiIdx] = ibi;
      ibiIdx = (ibiIdx + 1) % IBI_N;
      if (ibiCount < IBI_N) ibiCount++;
      if (ibiCount >= MIN_BEATS) computeBpm();
    }
  }
  pulseAbove = above;

  if (now - lastBeat > 4000) {
    resetPulse();
  }
}

// ====================== LM35 ======================
void readLM35() {
  unsigned long now = millis();
  if (now - lastLm35Read < 25) return;
  lastLm35Read = now;

  analogReadMilliVolts(PIN_LM35);
  lm35SumMv += analogReadMilliVolts(PIN_LM35);
  lm35Count++;

  if (lm35Count >= LM35_SAMPLES) {
    tempMv = lm35SumMv / (float)lm35Count;
    tempC = tempMv / 10.0f + OFFSET_C;
    tempReady = true;
    lm35SumMv = 0;
    lm35Count = 0;
  }
}

// ====================== WIFI + MQTT (non bloquant) ======================
void maintainConnections() {
  unsigned long now = millis();

  if (WiFi.status() != WL_CONNECTED) {
    if (now - lastWifiTry > 15000 || lastWifiTry == 0) {
      lastWifiTry = now;
      Serial.print("WiFi : connexion a '");
      Serial.print(WIFI_SSID);
      Serial.print("' (statut ");
      Serial.print(WiFi.status());
      Serial.println(")");
      WiFi.begin(WIFI_SSID, WIFI_PASS);
    }
    return;
  }

  if (!mqtt.connected()) {
    if (now - lastMqttTry > 5000 || lastMqttTry == 0) {
      lastMqttTry = now;
      Serial.print("MQTT : connexion a ");
      Serial.print(MQTT_SERVER);
      Serial.print("... ");
      bool ok;
      if (strlen(MQTT_USER) > 0) ok = mqtt.connect("esp32-animora", MQTT_USER, MQTT_PASS);
      else                       ok = mqtt.connect("esp32-animora");
      if (ok) Serial.println("OK");
      else { Serial.print("echec (code "); Serial.print(mqtt.state()); Serial.println(")"); }
    }
    return;
  }
  mqtt.loop();
}

void publishMqtt() {
  if (!mqtt.connected()) return;
  unsigned long now = millis();
  if (now - lastMqttPublish < MQTT_PUBLISH_INTERVAL) return;
  lastMqttPublish = now;

  if (tempReady) {
    mqtt.publish(TOPIC_TEMP, String(tempC, 1).c_str());
  }
  // BPM = 0 si aucun pouls valide
  mqtt.publish(TOPIC_BPM, String(pulseValid ? (int)(bpm + 0.5f) : 0).c_str());

  // GPS : JSON
  char buf[160];
  int sat = gps.satellites.isValid() ? (int)gps.satellites.value() : 0;
  if (gps.location.isValid()) {
    snprintf(buf, sizeof(buf),
             "{\"fix\":true,\"lat\":%.6f,\"lon\":%.6f,\"alt\":%.1f,\"speed\":%.1f,\"sat\":%d}",
             gps.location.lat(), gps.location.lng(),
             gps.altitude.isValid() ? gps.altitude.meters() : 0.0,
             gps.speed.isValid() ? gps.speed.kmph() : 0.0,
             sat);
  } else {
    snprintf(buf, sizeof(buf), "{\"fix\":false,\"sat\":%d}", sat);
  }
  mqtt.publish(TOPIC_GPS, buf);
}

// ====================== AFFICHAGE ======================
void printGPS() {
  Serial.println("GPS   :");
  if (gps.location.isValid()) {
    Serial.print("        Latitude  : "); Serial.println(gps.location.lat(), 6);
    Serial.print("        Longitude : "); Serial.println(gps.location.lng(), 6);
  } else {
    Serial.println("        Position : En attente du GPS Fix");
  }
  if (gps.altitude.isValid()) {
    Serial.print("        Altitude  : "); Serial.print(gps.altitude.meters()); Serial.println(" m");
  }
  if (gps.speed.isValid()) {
    Serial.print("        Vitesse   : "); Serial.print(gps.speed.kmph()); Serial.println(" km/h");
  }
  if (gps.satellites.isValid()) {
    Serial.print("        Satellites: "); Serial.println(gps.satellites.value());
  }
  if (gps.date.isValid()) {
    Serial.print("        Date      : ");
    Serial.print(gps.date.day()); Serial.print("/");
    Serial.print(gps.date.month()); Serial.print("/");
    Serial.println(gps.date.year());
  }
  if (gps.time.isValid()) {
    Serial.print("        Heure UTC : ");
    if (gps.time.hour() < 10) Serial.print("0");
    Serial.print(gps.time.hour()); Serial.print(":");
    if (gps.time.minute() < 10) Serial.print("0");
    Serial.print(gps.time.minute()); Serial.print(":");
    if (gps.time.second() < 10) Serial.print("0");
    Serial.println(gps.time.second());
  }
  Serial.print("        Caracteres recus : "); Serial.println(gps.charsProcessed());
  if (millis() > 5000 && gps.charsProcessed() < 10) {
    Serial.println("        ERREUR : Aucune donnee GPS recue ! Verifie le cablage TX/RX.");
  }
}

void printData() {
  Serial.println("----------------------------------------");

  Serial.print("WiFi  : ");
  if (WiFi.status() == WL_CONNECTED) { Serial.print(WiFi.localIP()); }
  else Serial.print("deconnecte");
  Serial.print(" | MQTT : ");
  Serial.println(mqtt.connected() ? "connecte" : "deconnecte");

  Serial.print("TEMP  : ");
  if (!tempReady) {
    Serial.println("mesure en cours...");
  } else {
    Serial.print(tempC, 1);
    Serial.print(" C (");
    Serial.print(tempMv, 0);
    Serial.println(" mV)");

    if (tempMv < 50) {
      Serial.println("        Attention : tension tres basse. Verifie l'alimentation 5 V, le GND et Vout -> GPIO35.");
    } else if (tempMv > 1000) {
      Serial.println("        Attention : tension trop haute pour un LM35. Verifie le branchement.");
    }
  }

  Serial.print("POULS : ");
  if (pulseValid) {
    Serial.print(bpm, 0);
    Serial.print(" bpm (amplitude = ");
    Serial.print(amp, 0);
    Serial.println(")");
  } else if (amp < AMP_MIN) {
    Serial.print("Signal non valide (amplitude = ");
    Serial.print(amp, 0);
    Serial.print(", min requis = ");
    Serial.print(AMP_MIN, 0);
    Serial.println(")");
  } else {
    Serial.print("mesure en cours (intervalles : ");
    Serial.print(ibiCount);
    Serial.print("/");
    Serial.print(MIN_BEATS);
    Serial.print(", amplitude = ");
    Serial.print(amp, 0);
    Serial.println(")");
  }

  printGPS();
}

// ====================== SETUP / LOOP ======================
void setup() {
  Serial.begin(115200);
  delay(500);
  if (!PLOTTER_MODE) Serial.println("\n=== ANIMORA : pouls + LM35 + GPS + MQTT (v4) ===");

  analogReadResolution(12);
  analogSetPinAttenuation(PIN_PULSE, ADC_11db);
  analogSetPinAttenuation(PIN_LM35, ADC_11db);

  // GPS : buffer agrandi pour ne pas perdre de caracteres pendant les connexions reseau
  GPS_Serial.setRxBufferSize(1024);
  GPS_Serial.begin(GPS_BAUD, SERIAL_8N1, GPS_RX, GPS_TX);

  // Connexion WiFi identique au programme de test (qui fonctionne)
  WiFi.mode(WIFI_STA);
  WiFi.persistent(false);
  WiFi.disconnect(true);
  delay(500);
  Serial.printf("WiFi : connexion a '%s'...\n", WIFI_SSID);
  WiFi.begin(WIFI_SSID, WIFI_PASS);
  unsigned long t0 = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - t0 < 20000) {
    delay(500);
    readGPS();
    Serial.printf("  statut %d\n", WiFi.status());
  }
  if (WiFi.status() == WL_CONNECTED) {
    Serial.print("WiFi OK, IP ESP32 : ");
    Serial.println(WiFi.localIP());
  } else {
    Serial.println("WiFi : echec, nouvelle tentative en arriere-plan");
  }
  lastWifiTry = millis();

  WiFi.setAutoReconnect(true);
  mqtt.setServer(MQTT_SERVER, MQTT_PORT);
}

void loop() {
  readGPS();
  readPulse();
  readLM35();
  maintainConnections();
  publishMqtt();
  delay(1);

  if (!PLOTTER_MODE && millis() - lastPrint >= PRINT_INTERVAL) {
    lastPrint = millis();
    printData();
  }
}
