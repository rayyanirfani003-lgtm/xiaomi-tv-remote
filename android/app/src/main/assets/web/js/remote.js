/**
 * TV Remote - JavaScript Controller
 * Mengontrol Xiaomi TV via Wi-Fi (ADB) dan Bluetooth HID
 */

class TVRemote {
    constructor() {
        this.currentMode = 'basic';
        this.connectionType = 'wifi'; // 'wifi' atau 'bluetooth'
        this.tvIp = '';
        this.isConnected = false;
        this.logEntries = [];
        this.voiceRecognition = null;

        this.init();
    }

    init() {
        this.bindEvents();
        this.loadSettings();
        this.initVoiceRecognition();
        this.log('Aplikasi TV Remote dimuat', 'info');
    }

    bindEvents() {
        // Connection type buttons
        document.getElementById('btn-bt').addEventListener('click', () => this.switchConnection('bluetooth'));
        document.getElementById('btn-wifi').addEventListener('click', () => this.switchConnection('wifi'));

        // Mode tabs
        document.querySelectorAll('.tab').forEach(tab => {
            tab.addEventListener('click', (e) => this.switchMode(e.target.dataset.mode));
        });

        // IP Connection
        document.getElementById('btn-connect-ip').addEventListener('click', () => this.connectWifi());
        document.getElementById('btn-scan').addEventListener('click', () => this.scanNetwork());

        // Bluetooth
        document.getElementById('btn-bt-pair').addEventListener('click', () => this.pairBluetooth());

        // All control buttons with data-key
        document.querySelectorAll('[data-key]').forEach(btn => {
            btn.addEventListener('click', (e) => this.sendKey(e.target.closest('[data-key]').dataset.key));
        });

        // App launchers
        document.querySelectorAll('[data-app]').forEach(btn => {
            btn.addEventListener('click', (e) => this.launchApp(e.target.closest('[data-app]').dataset.app));
        });

        // Quick voice commands
        document.querySelectorAll('[data-cmd]').forEach(btn => {
            btn.addEventListener('click', (e) => this.executeQuickCommand(e.target.dataset.cmd));
        });

        // Voice button
        document.getElementById('btn-voice').addEventListener('click', () => this.toggleVoice());

        // Clear log
        document.getElementById('btn-clear-log').addEventListener('click', () => this.clearLog());
    }

    switchConnection(type) {
        this.connectionType = type;
        
        document.getElementById('btn-bt').classList.toggle('active', type === 'bluetooth');
        document.getElementById('btn-wifi').classList.toggle('active', type === 'wifi');
        
        document.getElementById('ip-panel').classList.toggle('hidden', type !== 'wifi');
        document.getElementById('bt-panel').classList.toggle('hidden', type !== 'bluetooth');

        this.log(`Mode koneksi: ${type.toUpperCase()}`, 'info');
    }

    switchMode(mode) {
        this.currentMode = mode;
        
        document.querySelectorAll('.tab').forEach(tab => {
            tab.classList.toggle('active', tab.dataset.mode === mode);
        });
        
        document.querySelectorAll('.mode-content').forEach(content => {
            content.classList.toggle('active', content.id === `mode-${mode}`);
        });
    }

    sendKey(keyCode) {
        const now = Date.now();
        const last = this._lastKeyTime || 0;
        if (now - last < 200 && this._lastKeyCode === keyCode) {
            return; // debounce double-fire
        }
        this._lastKeyTime = now;
        this._lastKeyCode = keyCode;

        this.log(`Mengirim key: ${keyCode}`, 'info');

        if (this.connectionType === 'wifi') {
            this.sendWifiCommand(keyCode);
        } else {
            this.sendBluetoothCommand(keyCode);
        }
    }

    sendWifiCommand(keyCode) {
        if (!this.isConnected && this.connectionType === 'wifi') {
            this.log('Belum terhubung ke TV. Masukkan IP TV terlebih dahulu.', 'error');
            return;
        }

        // Panggil native bridge (Android)
        if (window.AndroidBridge && window.AndroidBridge.sendAdbCommand) {
            window.AndroidBridge.sendAdbCommand(keyCode);
            this.log(`✓ Terkirim via Wi-Fi: ${keyCode}`, 'success');
        } else {
            // Fallback: simpan ke log untuk testing
            this.log(`[SIMULASI] ADB: input keyevent ${this.mapToAdbKey(keyCode)}`, 'info');
        }
    }

    sendBluetoothCommand(keyCode) {
        if (window.AndroidBridge && window.AndroidBridge.sendBluetoothKey) {
            window.AndroidBridge.sendBluetoothKey(keyCode);
            this.log(`✓ Terkirim via Bluetooth: ${keyCode}`, 'success');
        } else {
            this.log(`[SIMULASI] Bluetooth HID: ${keyCode}`, 'info');
        }
    }

    mapToAdbKey(keyCode) {
        const keyMap = {
            'KEYCODE_POWER': '26',
            'KEYCODE_DPAD_UP': '19',
            'KEYCODE_DPAD_DOWN': '20',
            'KEYCODE_DPAD_LEFT': '21',
            'KEYCODE_DPAD_RIGHT': '22',
            'KEYCODE_DPAD_CENTER': '23',
            'KEYCODE_BACK': '4',
            'KEYCODE_HOME': '3',
            'KEYCODE_MENU': '82',
            'KEYCODE_VOLUME_UP': '24',
            'KEYCODE_VOLUME_DOWN': '25',
            'KEYCODE_MUTE': '164',
            'KEYCODE_CHANNEL_UP': '166',
            'KEYCODE_CHANNEL_DOWN': '167',
            'KEYCODE_TV_INPUT': '178',
            'KEYCODE_SETTINGS': '176',
            'KEYCODE_GUIDE': '172',
            'KEYCODE_INFO': '165',
            'KEYCODE_0': '7',
            'KEYCODE_1': '8',
            'KEYCODE_2': '9',
            'KEYCODE_3': '10',
            'KEYCODE_4': '11',
            'KEYCODE_5': '12',
            'KEYCODE_6': '13',
            'KEYCODE_7': '14',
            'KEYCODE_8': '15',
            'KEYCODE_9': '16',
            'KEYCODE_PROG_RED': '183',
            'KEYCODE_PROG_GREEN': '184',
            'KEYCODE_PROG_YELLOW': '185',
            'KEYCODE_PROG_BLUE': '186',
        };
        return keyMap[keyCode] || keyCode;
    }

    connectWifi() {
        const ipInput = document.getElementById('tv-ip');
        const ip = ipInput.value.trim();

        if (!ip) {
            this.log('Masukkan IP address TV Xiaomi', 'error');
            return;
        }

        if (!this.isValidIp(ip)) {
            this.log('Format IP tidak valid. Contoh: 192.168.1.100', 'error');
            return;
        }

        this.tvIp = ip;
        this.log(`Menghubungkan ke ${ip}:5555...`, 'info');

        if (window.AndroidBridge && window.AndroidBridge.connectAdb) {
            window.AndroidBridge.connectAdb(ip);
        } else {
            // Simulasi
            setTimeout(() => {
                this.isConnected = true;
                this.updateConnectionStatus(true);
                this.log(`✓ Terhubung ke TV di ${ip}`, 'success');
            }, 1000);
        }

        this.saveSettings();
    }

    disconnectWifi() {
        this.isConnected = false;
        this.updateConnectionStatus(false);
        
        if (window.AndroidBridge && window.AndroidBridge.disconnectAdb) {
            window.AndroidBridge.disconnectAdb();
        }
        
        this.log('Terputus dari TV', 'info');
    }

    updateConnectionStatus(connected) {
        this.isConnected = connected;
        const status = document.getElementById('connection-status');
        status.textContent = connected ? 'Terhubung' : 'Terputus';
        status.className = `status ${connected ? 'connected' : 'disconnected'}`;
    }

    scanNetwork() {
        this.log('Memindai jaringan lokal...', 'info');

        if (window.AndroidBridge && window.AndroidBridge.scanNetwork) {
            window.AndroidBridge.scanNetwork();
        } else {
            this.log('[SIMULASI] Pindai: mencari port 5555 di subnet 192.168.1.x', 'info');
        }
    }

    pairBluetooth() {
        this.log('Memulai pairing Bluetooth...', 'info');

        if (window.AndroidBridge && window.AndroidBridge.startBluetoothPairing) {
            window.AndroidBridge.startBluetoothPairing();
        } else {
            this.log('[SIMULASI] Bluetooth: membuka pengaturan pairing', 'info');
        }
    }

    launchApp(appName) {
        const apps = {
            'netflix': 'com.netflix.ninja',
            'youtube': 'com.google.android.youtube.tv',
            'spotify': 'com.spotify.tv.android',
            'disney': 'com.disney.disneyplus',
            'vidio': 'com.vidio.android',
            'prime': 'com.amazon.amazonvideo.livingroom'
        };

        const pkg = apps[appName];
        if (!pkg) return;

        this.log(`Membuka aplikasi: ${appName}`, 'info');

        if (window.AndroidBridge && window.AndroidBridge.launchApp) {
            window.AndroidBridge.launchApp(pkg);
        } else {
            this.log(`[SIMULASI] Launch: am start -n ${pkg}`, 'info');
        }
    }

    executeQuickCommand(cmd) {
        const cmdMap = {
            'netflix': () => this.launchApp('netflix'),
            'youtube': () => this.launchApp('youtube'),
            'volume_up': () => this.sendKey('KEYCODE_VOLUME_UP'),
            'volume_down': () => this.sendKey('KEYCODE_VOLUME_DOWN'),
            'mute': () => this.sendKey('KEYCODE_MUTE'),
            'power': () => this.sendKey('KEYCODE_POWER')
        };

        const action = cmdMap[cmd];
        if (action) {
            this.log(`Perintah cepat: ${cmd}`, 'info');
            action();
        }
    }

    initVoiceRecognition() {
        const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
        
        if (SpeechRecognition) {
            this.voiceRecognition = new SpeechRecognition();
            this.voiceRecognition.lang = 'id-ID';
            this.voiceRecognition.continuous = false;
            this.voiceRecognition.interimResults = false;

            this.voiceRecognition.onresult = (event) => {
                const transcript = event.results[0][0].transcript.toLowerCase();
                this.log(`Terdeteksi: "${transcript}"`, 'success');
                this.processVoiceCommand(transcript);
            };

            this.voiceRecognition.onerror = (event) => {
                this.log(`Error suara: ${event.error}`, 'error');
                document.getElementById('voice-status').textContent = 'Error: ' + event.error;
                document.getElementById('btn-voice').classList.remove('recording');
            };

            this.voiceRecognition.onend = () => {
                document.getElementById('btn-voice').classList.remove('recording');
            };
        } else {
            this.log('Browser tidak mendukung Speech Recognition', 'error');
        }
    }

    toggleVoice() {
        if (!this.voiceRecognition) {
            this.log('Fitur suara tidak tersedia', 'error');
            return;
        }

        const btn = document.getElementById('btn-voice');
        
        if (btn.classList.contains('recording')) {
            this.voiceRecognition.stop();
            btn.classList.remove('recording');
            document.getElementById('voice-status').textContent = '';
        } else {
            this.voiceRecognition.start();
            btn.classList.add('recording');
            document.getElementById('voice-status').textContent = 'Mendengarkan...';
        }
    }

    processVoiceCommand(text) {
        const commands = [
            { pattern: /netflix|netplix/, action: () => this.launchApp('netflix') },
            { pattern: /youtube|youtub/, action: () => this.launchApp('youtube') },
            { pattern: /spotify/, action: () => this.launchApp('spotify') },
            { pattern: /volume naik|keraskan|loud/, action: () => this.sendKey('KEYCODE_VOLUME_UP') },
            { pattern: /volume turun|pelan/, action: () => this.sendKey('KEYCODE_VOLUME_DOWN') },
            { pattern: /bisu|senyap|mute/, action: () => this.sendKey('KEYCODE_MUTE') },
            { pattern: /mati|power off/, action: () => this.sendKey('KEYCODE_POWER') },
            { pattern: /home|beranda/, action: () => this.sendKey('KEYCODE_HOME') },
            { pattern: /back|kembali/, action: () => this.sendKey('KEYCODE_BACK') },
            { pattern: /buka .+/, action: () => this.log(`Mencoba membuka aplikasi`, 'info') },
        ];

        for (const cmd of commands) {
            if (cmd.pattern.test(text)) {
                cmd.action();
                document.getElementById('voice-status').textContent = `✓ Dieksekusi`;
                return;
            }
        }

        document.getElementById('voice-status').textContent = `Tidak dikenali: "${text}"`;
        this.log(`Perintah suara tidak dikenali: ${text}`, 'error');
    }

    isValidIp(ip) {
        const regex = /^(\d{1,3}\.){3}\d{1,3}$/;
        if (!regex.test(ip)) return false;
        return ip.split('.').every(part => parseInt(part) >= 0 && parseInt(part) <= 255);
    }

    saveSettings() {
        const settings = {
            tvIp: this.tvIp,
            connectionType: this.connectionType,
            currentMode: this.currentMode
        };
        localStorage.setItem('tvRemoteSettings', JSON.stringify(settings));
    }

    loadSettings() {
        const saved = localStorage.getItem('tvRemoteSettings');
        if (saved) {
            const settings = JSON.parse(saved);
            this.tvIp = settings.tvIp || '';
            this.connectionType = settings.connectionType || 'wifi';
            this.currentMode = settings.currentMode || 'basic';

            document.getElementById('tv-ip').value = this.tvIp;
            this.switchConnection(this.connectionType);
            this.switchMode(this.currentMode);
        }
    }

    log(message, type = 'info') {
        const timestamp = new Date().toLocaleTimeString('id-ID');
        const entry = { timestamp, message, type };
        this.logEntries.push(entry);

        const logContent = document.getElementById('log-content');
        const div = document.createElement('div');
        div.className = `log-entry ${type}`;
        div.textContent = `[${timestamp}] ${message}`;
        logContent.appendChild(div);
        logContent.scrollTop = logContent.scrollHeight;
    }

    clearLog() {
        this.logEntries = [];
        document.getElementById('log-content').innerHTML = '';
        this.log('Log dikosongkan', 'info');
    }
}

// Initialize when DOM is ready
document.addEventListener('DOMContentLoaded', () => {
    window.tvRemote = new TVRemote();
});
