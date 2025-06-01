#include <WiFi.h>
#include <WebServer.h>
#include <WebSocketsServer.h>
#include <Adafruit_MPU6050.h>
#include <Adafruit_Sensor.h>
#include <Wire.h>
#include "alert_mp3.h"

Adafruit_MPU6050 mpu;
WebServer server(80);
WebSocketsServer webSocket = WebSocketsServer(81);

const char *ssid = "TrailerMonitor";
const char *password = "12345678";

float criticalAngle = 15.0; // критический угол

// === Kalman-фильтр ===
class Kalman {
public:
  Kalman() : angle(0), bias(0), P{{1, 0}, {0, 1}} {}
  float update(float newAngle, float newRate, float dt) {
    float rate = newRate - bias;
    angle += dt * rate;

    P[0][0] += dt * (dt * P[1][1] - P[0][1] - P[1][0] + Q_angle);
    P[0][1] -= dt * P[1][1];
    P[1][0] -= dt * P[1][1];
    P[1][1] += Q_bias * dt;

    float S = P[0][0] + R_measure;
    float K[2] = { P[0][0] / S, P[1][0] / S };
    float y = newAngle - angle;
    angle += K[0] * y;
    bias += K[1] * y;

    P[0][0] -= K[0] * P[0][0];
    P[0][1] -= K[0] * P[0][1];
    P[1][0] -= K[1] * P[0][0];
    P[1][1] -= K[1] * P[0][1];

    return angle;
  }
private:
  float angle, bias;
  float P[2][2];
  float Q_angle = 0.001, Q_bias = 0.003, R_measure = 0.03;
};

Kalman kalmanRoll;
float roll = 0.0;
unsigned long lastTime = 0;

const char MAIN_page[] PROGMEM = R"rawliteral(
<!DOCTYPE html>
<html lang="ru">
<head>
  <meta charset="UTF-8">
  <title>Контроль наклона прицепа</title>
  <style>
    body { font-family: sans-serif; text-align: center; background: #f0f0f0; font-size: 1.5em; }
    h1 { margin-top: 40px; font-size: 2.5em; }
    #angle { font-size: 4em; margin: 30px 0; }
    #warning { color: red; font-weight: bold; font-size: 2em; }
    .card { background: white; margin: auto; padding: 30px; border-radius: 20px; width: 90%; max-width: 500px; box-shadow: 0 6px 12px rgba(0,0,0,0.3); }
    button, input { font-size: 1.5em; padding: 15px; margin: 15px; width: 80%; max-width: 300px; }
    label { display: block; margin: 20px 0; }
  </style>
</head>
<body>
  <div class="card">
    <h1>Угол наклона</h1>
    <div id="angle">0°</div>
    <div id="warning"></div>
    <label>Критический угол: <input type="number" id="threshold" min="1" max="90"></label>
    <button onclick="startApp()" id="startBtn">Начать</button>
    <audio id="alarm" src="/alert.mp3"></audio>
  </div>
  <script>
    let audio = document.getElementById("alarm");
    let ws;
    let threshold = localStorage.getItem("criticalAngle") ? parseFloat(localStorage.getItem("criticalAngle")) : 15;

    document.getElementById("threshold").value = threshold;

    document.getElementById("threshold").addEventListener("change", function() {
      threshold = parseFloat(this.value);
      localStorage.setItem("criticalAngle", threshold);
    });

    function startApp() {
      document.getElementById("startBtn").style.display = "none";
      threshold = parseFloat(document.getElementById("threshold").value);
      localStorage.setItem("criticalAngle", threshold);
      ws = new WebSocket("ws://" + location.hostname + ":81/");
      ws.onmessage = function(event) {
        let roll = parseFloat(event.data);
        document.getElementById("angle").innerText = roll.toFixed(2) + "°";
        if (Math.abs(roll) > threshold) {
          document.getElementById("warning").innerText = "ОПАСНЫЙ УГОЛ!";
          audio.play();
        } else {
          document.getElementById("warning").innerText = "";
          audio.pause();
          audio.currentTime = 0;
        }
      };
    }
  </script>
</body>
</html>
)rawliteral";

void setup() {
  Serial.begin(115200);
  Wire.begin();

  WiFi.softAP(ssid, password);
  Serial.println("Точка доступа запущена. IP: 192.168.4.1");

  if (!mpu.begin()) {
    Serial.println("Ошибка инициализации MPU6050");
    while (1);
  }

  server.on("/", []() {
    server.send_P(200, "text/html", MAIN_page);
  });

  server.on("/alert.mp3", []() {
    server.sendHeader("Content-Type", "audio/mpeg");
    server.send_P(200, "audio/mpeg", (const char*)alert_mp3, alert_mp3_len);
  });

  server.begin();
  webSocket.begin();
  webSocket.onEvent([](uint8_t, WStype_t type, uint8_t *payload, size_t) {});
}

void loop() {
  server.handleClient();
  webSocket.loop();

  sensors_event_t a, g, temp;
  mpu.getEvent(&a, &g, &temp);

  unsigned long now = millis();
  float dt = (now - lastTime) / 1000.0;
  lastTime = now;

  float accelRoll = atan2(-a.acceleration.x, a.acceleration.z) * 180.0 / PI;
  roll = kalmanRoll.update(accelRoll, g.gyro.y * 180.0 / PI, dt);

  String data = String(roll, 2);
  webSocket.broadcastTXT(data);
  delay(300);
}
