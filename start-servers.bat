@echo off
chcp 65001 >nul
echo ===================================================
echo     DANG KHOI DONG HE THONG KEYNET (4 TIEN TRINH)
echo ===================================================

echo [1/4] Khoi dong Registry Server (port 6000)...
start "KeyNet - Registry Server (6000)" cmd /k "java -cp target/classes com.nhom05.cache.registry.RegistryServer 6000"
timeout /t 2 /nobreak >nul

echo [2/4] Khoi dong Cache Server #0 (port 5001)...
start "KeyNet - Cache Server #0 (5001)" cmd /k "java -cp target/classes com.nhom05.cache.server.CacheServer config/config.properties 0"
timeout /t 1 /nobreak >nul

echo [3/4] Khoi dong Cache Server #1 (port 5002)...
start "KeyNet - Cache Server #1 (5002)" cmd /k "java -cp target/classes com.nhom05.cache.server.CacheServer config/config.properties 1"
timeout /t 1 /nobreak >nul

echo [4/4] Khoi dong Cache Server #2 (port 5003)...
start "KeyNet - Cache Server #2 (5003)" cmd /k "java -cp target/classes com.nhom05.cache.server.CacheServer config/config.properties 2"
timeout /t 2 /nobreak >nul

echo.
echo ===================================================
echo   TAT CA SERVER DA KHOI DONG THANH CONG!
echo   Ban co the bat ClientFrame hoac MainAppFrame de test.
echo ===================================================
pause
