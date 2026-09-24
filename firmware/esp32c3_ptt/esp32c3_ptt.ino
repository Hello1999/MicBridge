// MicBridge push-to-talk button for Seeed XIAO ESP32C3.
//
// Wiring: momentary button between D1 (GPIO3) and GND. No resistor needed (internal pull-up).
// The on-board BOOT button (GPIO9) also works as a talk button, handy before wiring anything.
// Attach the U.FL antenna that ships with the board, otherwise BLE range is only ~1 m.
//
// Board: "XIAO_ESP32C3" (esp32 core 3.x, built-in BLE library, no extra dependency).
//
// GATT contract (must match app/src/main/java/com/jack/micbridge/ble/ButtonProtocol.kt):
//   service  8f1d0001-6b5c-4c3e-9a2e-5f3b7d9c0a11
//   state    8f1d0002-6b5c-4c3e-9a2e-5f3b7d9c0a11  read + notify, payload [state, seq]
//            state 1 = pressed, 0 = released; seq wraps 0..255
//   While pressed the packet repeats every HEARTBEAT_MS; the phone mutes if it hears
//   nothing for 1.5 s, so a dead button can never leave the mic open.

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLE2902.h>

static const int BUTTON_PIN = D1;              // GPIO3. Avoid strapping pins 2, 8, 9 for wiring.
static const int BOOT_KEY_PIN = 9;                 // on-board BOOT button, read only after boot
static const uint32_t LOCKOUT_MS = 25;         // ignore contact bounce after an accepted edge
static const uint32_t HEARTBEAT_MS = 400;
static const char *DEVICE_NAME = "MicBridge-BTN";

#define SERVICE_UUID "8f1d0001-6b5c-4c3e-9a2e-5f3b7d9c0a11"
#define STATE_UUID   "8f1d0002-6b5c-4c3e-9a2e-5f3b7d9c0a11"

static BLECharacteristic *stateChar = nullptr;
static volatile bool connected = false;
static bool pressed = false;
static uint8_t seq = 0;
static uint32_t lastEdgeMs = 0;
static uint32_t lastSentMs = 0;

static bool buttonDown() {
  return digitalRead(BUTTON_PIN) == LOW || digitalRead(BOOT_KEY_PIN) == LOW;
}

static void publish() {
  uint8_t payload[2] = {static_cast<uint8_t>(pressed ? 1 : 0), seq++};
  stateChar->setValue(payload, sizeof(payload));  // also what a fresh client reads
  if (connected) stateChar->notify();
  lastSentMs = millis();
}

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *server) override {
    connected = true;
  }
  void onDisconnect(BLEServer *server) override {
    connected = false;
    BLEDevice::startAdvertising();  // be discoverable again right away
  }
};

void setup() {
  pinMode(BUTTON_PIN, INPUT_PULLUP);
  pinMode(BOOT_KEY_PIN, INPUT_PULLUP);
  Serial.begin(115200);
  // USB-CDC blocks on a full TX buffer when no host is reading. Never let a debug print
  // stall button handling: drop output instead of waiting.
  Serial.setTxTimeoutMs(0);

  BLEDevice::init(DEVICE_NAME);
  BLEServer *server = BLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());

  BLEService *service = server->createService(SERVICE_UUID);
  stateChar = service->createCharacteristic(
      STATE_UUID, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
#if defined(CONFIG_BLUEDROID_ENABLED)
  stateChar->addDescriptor(new BLE2902());  // NimBLE adds the CCCD automatically
#endif
  pressed = buttonDown();
  uint8_t initial[2] = {static_cast<uint8_t>(pressed ? 1 : 0), seq};
  stateChar->setValue(initial, sizeof(initial));
  service->start();

  // 128-bit UUID fills most of the 31-byte advert, so the name goes in the scan response.
  BLEAdvertising *adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(SERVICE_UUID);
  adv->setScanResponse(true);
  BLEDevice::startAdvertising();
  Serial.println("MicBridge button advertising");
}

void loop() {
  const uint32_t now = millis();
  const bool down = buttonDown();

  // React on the first edge (lowest latency), then ignore bounce for LOCKOUT_MS.
  if (down != pressed && now - lastEdgeMs >= LOCKOUT_MS) {
    pressed = down;
    lastEdgeMs = now;
    publish();
    if (Serial) Serial.println(pressed ? "pressed" : "released");
  } else if (pressed && connected && now - lastSentMs >= HEARTBEAT_MS) {
    publish();
  }
  delay(2);
}
