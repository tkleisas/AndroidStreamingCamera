$ErrorActionPreference = "Stop"

# Use Android Studio's bundled JDK 21 (system JDK 25 is too new for Gradle/Kotlin)
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"

$GRADLE = "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.11.1-bin\bpt9gzteqjrbo1mjrsomdt32c\gradle-8.11.1\bin\gradle.bat"
$PROJECT = $PSScriptRoot

Write-Host "=== Build Configuration ===" -ForegroundColor Cyan
Write-Host "JAVA_HOME : $env:JAVA_HOME"
Write-Host "ANDROID_HOME: $env:ANDROID_HOME"
Write-Host "Project     : $PROJECT"

# Build debug APK
Write-Host "`n=== Building Debug APK ===" -ForegroundColor Cyan
& $GRADLE -p $PROJECT assembleDebug 2>&1 | ForEach-Object { Write-Host $_ }

if ($LASTEXITCODE -ne 0) {
    Write-Host "`n=== BUILD FAILED ===" -ForegroundColor Red
    exit $LASTEXITCODE
}

# Find the APK
$apk = Get-ChildItem "$PROJECT\app\build\outputs\apk\debug\*.apk" -ErrorAction SilentlyContinue | Select-Object -First 1
if ($apk) {
    Write-Host "`n=== BUILD SUCCESS ===" -ForegroundColor Green
    Write-Host "APK: $($apk.FullName)"
    Write-Host "Size: $([math]::Round($apk.Length / 1MB, 2)) MB"

    # Deploy to connected device if available
    $adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
    $devices = & $adb devices 2>&1 | Select-String "device$"
    if ($devices) {
        Write-Host "`n=== Deploying to device ===" -ForegroundColor Cyan
        & $adb install -r $apk.FullName 2>&1 | ForEach-Object { Write-Host $_ }
        if ($LASTEXITCODE -eq 0) {
            Write-Host "=== Launching app ===" -ForegroundColor Cyan
            & $adb shell am start -n "com.streamcam.app/.MainActivity" 2>&1 | ForEach-Object { Write-Host $_ }
        }
    } else {
        Write-Host "`nNo device connected. Skipping deploy." -ForegroundColor Yellow
    }
} else {
    Write-Host "`n=== APK not found ===" -ForegroundColor Red
    exit 1
}
