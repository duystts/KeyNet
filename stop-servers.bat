@echo off
chcp 65001 >nul
echo Dang tat cac tien trinh Java tren cac cong 5001, 5002, 5003, 6000...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":5001 :5002 :5003 :6000 :6001 :6002 :6003" ^| findstr "LISTENING"') do (
    taskkill /F /PID %%a >nul 2>&1
)
echo Da giai phong toan bo cong!
timeout /t 2 /nobreak >nul
