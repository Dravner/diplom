#include <WiFi.h>
#include <HTTPClient.h>
#include <Adafruit_MPU6050.h>
#include <Adafruit_Sensor.h>
#include <Wire.h>

const char* ssid = "AP";  // Укажи свою Wi-Fi сеть
const char* password = "88888888";
const char* serverUrl = "http://192.168.136.122:5000/data";  // IP ноутбука

WiFiClient client;
Adafruit_MPU6050 mpu;

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

void connectToWiFi() {
    Serial.print("Подключение к WiFi...");
    WiFi.begin(ssid, password);

    int attempts = 0;
    while (WiFi.status() != WL_CONNECTED) {
        delay(1000);
        Serial.print(".");
        attempts++;
        if (attempts > 20) {  // Если не подключилось за 20 секунд
            Serial.println("\nНе удалось подключиться к WiFi! Перезагрузка...");
            ESP.restart();  // Перезагрузка ESP
        }
    }

    Serial.println("\nWiFi подключен!");
    Serial.print("IP-адрес: ");
    Serial.println(WiFi.localIP());
}

void setup() {
    Serial.begin(115200);
    Wire.begin(21, 22);

    connectToWiFi();

    if (!mpu.begin()) {
        Serial.println("Ошибка MPU6050");
        while (1);
    }
}

void loop() {
    sensors_event_t a, g, temp;
    mpu.getEvent(&a, &g, &temp);
    
    unsigned long now = millis();
    float dt = (now - lastTime) / 1000.0;
    lastTime = now;

    float accelRoll = atan2(-a.acceleration.x, a.acceleration.z) * 180.0 / PI;
    roll = kalmanRoll.update(accelRoll, g.gyro.y * 180.0 / PI, dt);

    HTTPClient http;
    http.begin(serverUrl);
    http.addHeader("Content-Type", "application/json");

    String json = "{\"roll\": " + String(roll, 2) + "}";
    int httpResponseCode = http.POST(json);

    if (httpResponseCode > 0) {
        Serial.print("Сервер ответил: ");
        Serial.println(httpResponseCode);
    } else {
        Serial.print("Ошибка HTTP: ");
        Serial.println(http.errorToString(httpResponseCode));
    }

    http.end();
    delay(500);
}
