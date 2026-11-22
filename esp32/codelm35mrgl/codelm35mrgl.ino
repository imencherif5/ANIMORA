// ANIMORA : pouls (Pulse Sensor) seul sur ESP32
//
// Cablage :
//   Pulse sensor : S -> GPIO34, + -> 3V3, - -> GND
//
// Serial Monitor : 115200 baud
// Mettre PLOTTER_MODE a true pour voir la courbe dans le Serial Plotter.

#define PIN_PULSE 34

const bool PLOTTER_MODE = false;

// ---------- Reglages pouls ----------
const float AMP_MIN   = 60.0;    // amplitude minimale du signal pour etre valide
const int   IBI_N     = 8;       // nombre d'intervalles memorises
const int   MIN_BEATS = 5;       // intervalles necessaires avant de calculer le BPM
const float BPM_MIN   = 40.0;
const float BPM_MAX   = 220.0;

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

unsigned long lastPrint = 0;

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

  // tri des intervalles
  for (int i = 1; i < n; i++) {
    unsigned long v = tmp[i];
    int j = i - 1;
    while (j >= 0 && tmp[j] > v) { tmp[j + 1] = tmp[j]; j--; }
    tmp[j + 1] = v;
  }

  // moyenne des intervalles proches de la mediane (elimine les aberrations)
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

  analogRead(PIN_PULSE);                       // lecture a vide (stabilisation)

  long sum = 0;
  for (int i = 0; i < 8; i++) sum += analogRead(PIN_PULSE);
  rawAvg = sum / 8.0f;

  if (!pulseInit) {
    pulseFilt = rawAvg;
    pulseBase = rawAvg;
    pulseInit = true;
  }

  pulseFilt = 0.7f * pulseFilt + 0.3f * rawAvg;          // lissage
  pulseBase = 0.99f * pulseBase + 0.01f * pulseFilt;     // ligne de base lente
  float sig = pulseFilt - pulseBase;

  sigMax = max(sig, sigMax * 0.995f);
  sigMin = min(sig, sigMin * 0.995f);
  amp = sigMax - sigMin;

  float threshold = sigMin + 0.5f * amp;

  if (PLOTTER_MODE) {
    Serial.print("Signal:"); Serial.print(sig, 1);
    Serial.print(",Seuil:"); Serial.println(threshold, 1);
  }

  // signal trop faible : pas de doigt / mauvais contact
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

  // detection d'un battement (front montant)
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

  // aucun battement depuis 4 s : on remet a zero
  if (now - lastBeat > 4000) {
    resetPulse();
  }
}

// ====================== AFFICHAGE ======================
void printData() {
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
}

// ====================== SETUP / LOOP ======================
void setup() {
  Serial.begin(115200);
  delay(500);
  if (!PLOTTER_MODE) Serial.println("\n=== ANIMORA : Pulse Sensor seul ===");

  analogReadResolution(12);
  analogSetPinAttenuation(PIN_PULSE, ADC_11db);
}

void loop() {
  readPulse();
  delay(1);

  if (!PLOTTER_MODE && millis() - lastPrint >= PRINT_INTERVAL) {
    lastPrint = millis();
    printData();
  }
}
