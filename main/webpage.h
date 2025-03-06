#ifndef WEBPAGE_H
#define WEBPAGE_H

#include <Arduino.h>

String generateWebPage() {
    String page = "<!DOCTYPE html><html lang='ru'>";
    page += "<head><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1.5'>";
    page += "<title>ESP32 MPU6050</title>";
    page += "<style>";
    page += "body{text-align:center;font-size:28px;background:#2c3e50;color:white;margin:0;padding:0;display:flex;flex-direction:column;align-items:center;justify-content:center;height:100vh;}";
    page += ".container{max-width:600px;padding:20px;background:#34495e;border-radius:15px;box-shadow:0px 0px 20px rgba(0,0,0,0.5);}";
    page += ".button{font-size:24px;padding:15px;background:#3498db;color:#fff;text-decoration:none;border:none;border-radius:10px;cursor:pointer;margin-top:10px;display:inline-block;}";
    page += ".box{border:2px solid #fff;padding:15px;margin:10px;font-size:24px;border-radius:10px;background:#1abc9c;}";
    page += "</style></head>";
    page += "<body><div class='container'>";
    page += "<h1>Мониторинг углов наклона</h1>";
    page += "<div class='box'>Pitch (наклон): <span id='pitch'>0.00</span>°</div>";
    page += "<div class='box'>Roll (крен): <span id='roll'>0.00</span>°</div>";
    page += "<button class='button' onclick='calibrate()'>Калибровать</button>";
    page += "</div>";

    page += "<script>";
    page += "function updateData(){";
    page += "fetch('/data').then(response => response.json()).then(data => {";
    page += "document.getElementById('pitch').innerText = data.pitch;";
    page += "document.getElementById('roll').innerText = data.roll;";
    page += "});}";
    page += "setInterval(updateData, 500);";  
    page += "function calibrate(){";
    page += "fetch('/calibrate').then(() => {";
    page += "document.body.innerHTML='<h2>Калибровка...</h2>'; setTimeout(()=>window.location='/', 1000);";
    page += "});}";
    page += "</script>";

    page += "</body></html>";
    return page;
}

#endif
