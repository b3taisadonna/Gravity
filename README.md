# 🪐 Gravity — Moderner Android Browser

**Gravity** ist ein schneller, moderner und datenschutzorientierter Android-Browser mit nativer Unterstützung für **Firefox-Erweiterungen (`.xpi`)**, integriertem Werbeblocker, lokalem DNS-Sinkhole-VPN und modernem Material 3 Design.

---

## 📥 Download

Die vorab kompilierte Debug-APK steht direkt zum Download bereit:
- **Aktuelle APK**: [`app/build/outputs/apk/debug/app-debug.apk`](file:///home/o1623158495/mein-projekt/BlockBrowser/app/build/outputs/apk/debug/app-debug.apk) *(ca. 5,8 MB)*
- **GitHub Releases Download**: Nach dem Push auf GitHub unter `https://github.com/<DEIN-BENUTZERNAME>/Gravity/releases/latest/download/Gravity.apk`

---

## ✨ Hauptfunktionen

### 1. 🧩 Firefox WebExtensions (`.xpi`) & Add-on Engine
* **Volle Unterstützung von Firefox Add-ons**: Lädt und entpackt `.xpi`-Dateien direkt aus Downloads oder von [addons.mozilla.org](https://addons.mozilla.org).
* **Popup & Login Unterstützung**: Unterstützt Browser-Action-Popups (z. B. **Proton Pass** Anmeldefenster und Vault).
* **Optionen & Dashboards**: Voller Zugriff auf Add-on-Einstellungsseiten (z. B. **AdGuard** Dashboard, Filterregeln, Whitelists).
* **WebExtension API Bridge**: Polyfill für `browser.storage.local`, `browser.runtime`, `browser.tabs`, `browser.i18n` und `window.chrome = window.browser`.
* **🧩 Schnellzugriff in der Adressleiste**: Direktes Öffnen von Add-on-Popups per Knopfdruck in der Symbolleiste.

### 2. 🎨 Modernes Material 3 Design
* **Pill-Shape Adressleiste**: Elegantes 24dp-Rundungsdesign mit schnellen Aktions-Icons:
  * 🧩 **Erweiterungen**: Schnellzugriff auf alle installierten Add-ons
  * ⭐ **Favoriten**: Aktuelle Seite mit einem Klick speichern
  * 🌐 **Übersetzen**: Schnelle Übersetzung der aktuellen Seite
  * 🔄 **Neu laden**: Seite aktualisieren
  * ⋮ **Menü**: Alle Browser-Optionen
* **5 integrierte Themes**:
  * ☀️ Hell
  * 🌙 Dunkel
  * 🌌 Mitternachtsblau
  * 🌲 Wald
  * 👁️ Hoher Kontrast

### 3. 🛡️ Werbeblocker & Lokales DNS-VPN
* **In-Page Tracker-Blocker**: Blockiert Werbung und Tracking-Skripte direkt in Webseiten.
* **Systemweites DNS-Sinkhole**: Lokaler Android-`VpnService`, der Werbe- und Tracking-Domains auf Geräte-Ebene ins Leere (`0.0.0.0`) leitet – ohne externe Server oder Datenabfluss.

### 4. 🛠️ Entwicklertools & User-Agent Steuerung
* **Firefox-Identifikation**: Webseiten erkennen Gravity als Firefox (ermöglicht den direkten Download von Firefox-Add-ons ohne Warnungen).
* **Desktop-Modus**: Schalter für Desktop-Versionen von Webseiten.
* **Console-Viewer & Quelltext-Anzeige**: Integrierte DevTools zur Fehlerbehebung.
* **„Öffnen mit…"**: Erkannte `.xpi`-Dateien können wahlweise direkt in Gravity installiert oder mit einer externen App geöffnet werden.

---

## 🏗️ Projekt bauen

### Voraussetzungen
* Android SDK (API Level 34)
* Java 17
* Gradle 8.2+

### Bauen per Kommandozeile
```bash
./gradlew assembleDebug
```
Die fertige APK wird erstellt unter:
```
app/build/outputs/apk/debug/app-debug.apk
```

---

## 🐙 Auf GitHub hochladen & Release erstellen

Um dieses Projekt in dein eigenes GitHub-Konto zu übertragen:

```bash
# 1. Git Repository initialisieren (falls noch nicht geschehen)
git init -b main

# 2. Remote-URL deines GitHub Repositories setzen
git remote add origin https://github.com/<DEIN-BENUTZERNAME>/Gravity.git

# 3. Alle Dateien committen
git add .
git commit -m "Initial commit: Gravity Android Browser"

# 4. Zu GitHub pushen
git push -u origin main
```

### GitHub Release mit APK Download-Link anlegen:
1. Gehe in deinem GitHub-Repository auf **Releases** → **Draft a new release**.
2. Tag eingeben: z. B. `v1.0.0`.
3. Titel: `Gravity v1.0.0`.
4. Ziehe die Datei `app/build/outputs/apk/debug/app-debug.apk` in das Upload-Feld („Attach binaries").
5. Klicke auf **Publish release**.
6. Der direkte Download-Link lautet dann:
   ```
   https://github.com/<DEIN-BENUTZERNAME>/Gravity/releases/download/v1.0.0/app-debug.apk
   ```

---

## 📄 Lizenz
Open Source unter der Apache-2.0 Lizenz.
