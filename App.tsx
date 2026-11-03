import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Platform,
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  View,
} from 'react-native';
import { SafeAreaProvider, useSafeAreaInsets } from 'react-native-safe-area-context';
import {
  AgentSnapshot,
  PrinterAgent,
  PrinterAgentEvents,
  PrinterInfo,
} from './src/native/PrinterAgent';

const getPrinterStatusDisplay = (info?: PrinterInfo) => {
  if (!info) return { text: 'UNBEKANNT', color: '#94a3b8', errorAlert: null };
  if (info.errors?.headOpen || info.status === 'headOpen') {
    return {
      text: 'DRUCKERKOPF OFFEN',
      color: '#ef4444',
      errorAlert: '⚠️ Druckerkopf ist geöffnet! Bitte Klappe schließen.',
    };
  }
  if (info.errors?.pause || info.status === 'pause') {
    return {
      text: 'PAUSIERT',
      color: '#f59e0b',
      errorAlert: '⏸️ Drucker ist pausiert! Bitte Pause-Taste am Drucker drücken.',
    };
  }
  if (info.errors?.unreachable || info.status === 'offline') {
    return {
      text: 'NICHT ERREICHBAR',
      color: '#ef4444',
      errorAlert: null,
    };
  }
  if (info.errors?.other || info.status === 'error') {
    return {
      text: 'DRUCKERFEHLER',
      color: '#ef4444',
      errorAlert: '⚠️ Drucker meldet einen Fehler (Papier/Farbband prüfen).',
    };
  }
  if (info.ready || info.status === 'online') {
    return { text: 'BEREIT / ONLINE', color: '#22c55e', errorAlert: null };
  }
  return {
    text: (info.status || 'UNBEKANNT').toUpperCase(),
    color: '#f59e0b',
    errorAlert: null,
  };
};

function PrinterAgentMain(): React.JSX.Element {
  const insets = useSafeAreaInsets();
  const [apiUrl, setApiUrl] = useState<string>('https://dash.quixpos.com');
  const [port, setPort] = useState<string>('41783');
  const [printerRows, setPrinterRows] = useState<string[]>(['']);
  const [printerInfoByIp, setPrinterInfoByIp] = useState<Record<string, PrinterInfo>>({});
  const [discoveredPrinters, setDiscoveredPrinters] = useState<PrinterInfo[]>([]);
  const [showDiscovered, setShowDiscovered] = useState<boolean>(false);
  const [isScanning, setIsScanning] = useState<boolean>(false);

  const [isPaired, setIsPaired] = useState<boolean>(false);
  const [agentName, setAgentName] = useState<string>('');
  const [backendConnected, setBackendConnected] = useState<boolean>(false);
  const [pairPin, setPairPin] = useState<string>('');
  const [isPairing, setIsPairing] = useState<boolean>(false);

  const [isRunning, setIsRunning] = useState<boolean>(false);
  const [isForegroundNotif, setIsForegroundNotif] = useState<boolean>(false);

  const [logs, setLogs] = useState<string[]>([]);
  const [toastMsg, setToastMsg] = useState<{ text: string; type: 'success' | 'error' } | null>(
    null,
  );
  const logScrollRef = useRef<any>(null);
  const toastTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const showToast = useCallback((text: string, type: 'success' | 'error' = 'success') => {
    if (toastTimerRef.current) {
      clearTimeout(toastTimerRef.current);
    }
    setToastMsg({ text, type });
    toastTimerRef.current = setTimeout(() => {
      setToastMsg(null);
    }, 3500);
  }, []);

  const applySnapshot = useCallback((snap: AgentSnapshot | null) => {
    if (!snap) return;
    setIsRunning(!!snap.running);
    setIsPaired(!!snap.paired);
    setAgentName(snap.agentName || '');
    setBackendConnected(!!snap.backendConnected);

    if (Array.isArray(snap.printers)) {
      setPrinterInfoByIp(prev => {
        const next = { ...prev };
        snap.printers?.forEach(p => {
          if (p && p.ip) {
            next[p.ip.trim()] = p;
          }
        });
        return next;
      });
    }
  }, []);

  const loadInitialState = useCallback(async () => {
    try {
      await PrinterAgent.requestNotificationPermission();
      await PrinterAgent.startForegroundService();

      const cfg = await PrinterAgent.getConfig();
      setApiUrl(cfg.api_url || 'https://dash.quixpos.com');
      setPort(cfg.port || '41783');
      const initialPrinters =
        cfg.zpl_printers && cfg.zpl_printers.length > 0
          ? cfg.zpl_printers
          : cfg.printer_ip
            ? [cfg.printer_ip]
            : [''];
      setPrinterRows(initialPrinters);

      const svc = await PrinterAgent.getServiceStatus();
      setIsRunning(svc.running);
      setIsForegroundNotif(svc.background);

      const snap = await PrinterAgent.getSnapshot();
      applySnapshot(snap);

      const initialLogs = await PrinterAgent.getLogs();
      if (Array.isArray(initialLogs)) {
        setLogs(initialLogs.slice(-300));
      }
    } catch (e: any) {
      showToast(`Initialisierungsfehler: ${e?.message || String(e)}`, 'error');
    }
  }, [applySnapshot, showToast]);

  useEffect(() => {
    loadInitialState();

    const logSub = PrinterAgentEvents.addListener('agent-log', (rawLine: any) => {
      const line = String(rawLine || '');
      setLogs(prev => {
        const next = [...prev, line];
        return next.length > 400 ? next.slice(next.length - 400) : next;
      });
    });

    const stateSub = PrinterAgentEvents.addListener('agent-state', (rawSnap: any) => {
      const snap = rawSnap as AgentSnapshot;
      applySnapshot(snap);
      PrinterAgent.getServiceStatus()
        .then(st => {
          setIsRunning(st.running);
          setIsForegroundNotif(st.background);
        })
        .catch(() => { });
    });

    const statusPoll = setInterval(() => {
      PrinterAgent.getServiceStatus()
        .then(st => {
          setIsRunning(st.running);
          setIsForegroundNotif(st.background);
        })
        .catch(() => { });
    }, 3000);

    return () => {
      logSub.remove();
      stateSub.remove();
      clearInterval(statusPoll);
    };
  }, [applySnapshot, loadInitialState]);

  const handleSaveConfig = async () => {
    const cleanApi = apiUrl.trim();
    if (!cleanApi) {
      showToast('QuixPOS-API-URL ist erforderlich', 'error');
      return;
    }
    const cleanPrinters = printerRows.map(r => r.trim()).filter(Boolean);
    const hasBle = cleanPrinters.some(p => {
      const l = p.toLowerCase();
      return (
        l === 'ble' ||
        l === 'bluetooth' ||
        l === 'auto' ||
        l.startsWith('ble:') ||
        l.startsWith('bt:') ||
        /^([0-9a-f]{2}[:-]){5}[0-9a-f]{2}$/i.test(l)
      );
    });
    try {
      if (hasBle) {
        await PrinterAgent.requestBluetoothPermissions();
      }
      const snap = await PrinterAgent.saveConfig(cleanApi, cleanPrinters, port.trim() || '41783');
      applySnapshot(snap);
      showToast('Konfiguration gespeichert & Drucker neu verbunden!');
    } catch (e: any) {
      showToast(`Fehler beim Speichern: ${e?.message || String(e)}`, 'error');
    }
  };

  const handlePair = async () => {
    const pin = pairPin.trim();
    if (!pin) {
      showToast('Bitte 6-stellige Kopplungs-PIN eingeben', 'error');
      return;
    }
    setIsPairing(true);
    try {
      const res = await PrinterAgent.pairAgent(pin, apiUrl.trim() || null);
      setPairPin('');
      setIsPaired(true);
      setAgentName(res.name || 'android-agent');
      showToast(`Erfolgreich gekoppelt als „${res.name || 'Agent'}“!`);
    } catch (e: any) {
      showToast(`Kopplung fehlgeschlagen: ${e?.message || String(e)}`, 'error');
    } finally {
      setIsPairing(false);
    }
  };

  const handleUnpair = async () => {
    try {
      await PrinterAgent.unpairAgent();
      setIsPaired(false);
      setAgentName('');
      setBackendConnected(false);
      showToast('Agent getrennt (Token gelöscht & Verbindung beendet)');
    } catch (e: any) {
      showToast(`Fehler beim Entkoppeln: ${e?.message || String(e)}`, 'error');
    }
  };

  const handleScanPrinters = async () => {
    setIsScanning(true);
    try {
      await PrinterAgent.requestBluetoothPermissions();
      const found = await PrinterAgent.scanLocalPrinters();
      if (!Array.isArray(found) || found.length === 0) {
        showToast('Keine Drucker gefunden (BLE oder TCP 9100)', 'error');
        setDiscoveredPrinters([]);
        setShowDiscovered(false);
        return;
      }

      setPrinterInfoByIp(prev => {
        const next = { ...prev };
        found.forEach(p => {
          if (p && p.ip) next[p.ip.trim()] = p;
        });
        return next;
      });

      if (found.length === 1) {
        selectDiscoveredPrinter(found[0]);
        showToast(`${found[0].model || 'Drucker'} unter ${found[0].ip} gefunden`);
        setShowDiscovered(false);
      } else {
        setDiscoveredPrinters(found);
        setShowDiscovered(true);
        showToast(`${found.length} Drucker gefunden (BLE / TCP)`);
      }
    } catch (e: any) {
      showToast(`Suchlauf fehlgeschlagen: ${e?.message || String(e)}`, 'error');
    } finally {
      setIsScanning(false);
    }
  };

  const selectDiscoveredPrinter = (info: PrinterInfo) => {
    const ip = info.ip.trim();
    setPrinterInfoByIp(prev => ({ ...prev, [ip]: info }));
    setPrinterRows(prev => {
      if (prev.some(r => r.trim() === ip)) {
        return prev;
      }
      const emptyIdx = prev.findIndex(r => r.trim() === '');
      if (emptyIdx !== -1) {
        const copy = [...prev];
        copy[emptyIdx] = ip;
        return copy;
      }
      return [...prev, ip];
    });
  };

  const handleAddPrinterRow = () => {
    setPrinterRows(prev => [...prev, '']);
  };

  const handleAddBleAutoRow = async () => {
    try {
      await PrinterAgent.requestBluetoothPermissions();
    } catch {}
    setPrinterRows(prev => {
      if (prev.some(r => r.trim().toLowerCase() === 'ble')) {
        return prev;
      }
      const emptyIdx = prev.findIndex(r => r.trim() === '');
      if (emptyIdx !== -1) {
        const copy = [...prev];
        copy[emptyIdx] = 'ble';
        return copy;
      }
      return [...prev, 'ble'];
    });
  };

  const handleUpdatePrinterRow = (index: number, value: string) => {
    setPrinterRows(prev => {
      const copy = [...prev];
      copy[index] = value;
      return copy;
    });
  };

  const handleRemovePrinterRow = (index: number) => {
    setPrinterRows(prev => {
      const next = prev.filter((_, idx) => idx !== index);
      return next.length > 0 ? next : [''];
    });
  };

  const handleToggleForegroundService = async () => {
    try {
      if (isForegroundNotif) {
        await PrinterAgent.stopForegroundService();
        setIsForegroundNotif(false);
        setIsRunning(false);
        showToast('Hintergrund-Statusleistendienst gestoppt');
      } else {
        await PrinterAgent.requestNotificationPermission();
        await PrinterAgent.startForegroundService();
        setIsForegroundNotif(true);
        setIsRunning(true);
        showToast('Hintergrund-Statusleistendienst gestartet');
      }
    } catch (e: any) {
      showToast(`Dienstfehler: ${e?.message || String(e)}`, 'error');
    }
  };

  const handleClearLogs = async () => {
    await PrinterAgent.clearLogs();
    setLogs([]);
  };

  const connectedPrinterCount = printerRows
    .map(ip => ip.trim())
    .filter(ip => ip && (printerInfoByIp[ip]?.ready || printerInfoByIp[ip]?.status === 'online'))
    .length;

  const totalConfiguredPrinters = printerRows.map(ip => ip.trim()).filter(Boolean).length;

  return (
    <View
      style={[
        styles.safeArea,
        {
          paddingTop: insets.top,
          paddingBottom: insets.bottom,
          paddingLeft: insets.left,
          paddingRight: insets.right,
        },
      ]}>
      <StatusBar barStyle="light-content" />

      {/* Top Header */}
      <View style={styles.header}>
        <View style={styles.headerBrand}>
          <View style={styles.headerIcon}>
            <Text style={styles.headerIconText}>QP</Text>
          </View>
          <View>
            <Text style={styles.headerTitle}>QuixPosPrinterAgent</Text>
          </View>
        </View>
        <View
          style={[
            styles.statusPill,
            backendConnected
              ? styles.pillOnline
              : isPaired
                ? styles.pillWarning
                : styles.pillOffline,
          ]}>
          <View
            style={[
              styles.statusDot,
              backendConnected
                ? styles.dotOnline
                : isPaired
                  ? styles.dotWarning
                  : styles.dotOffline,
            ]}
          />
          <Text
            style={[
              styles.statusPillText,
              backendConnected
                ? styles.textOnline
                : isPaired
                  ? styles.textWarning
                  : styles.textOffline,
            ]}>
            {backendConnected ? 'ONLINE' : isPaired ? 'VERBINDUNG...' : 'NICHT GEKOPPELT'}
          </Text>
        </View>
      </View>

      <ScrollView style={styles.scrollArea} contentContainerStyle={styles.scrollContent}>
        {/* Card 1: Connection Settings (API URL & Local Port) */}
        <View style={styles.card}>
          <Text style={styles.cardTitle}>VERBINDUNGSEINSTELLUNGEN</Text>
          <View style={styles.formGroup}>
            <Text style={styles.label}>QUIXPOS API-URL</Text>
            <TextInput
              style={styles.input}
              value={apiUrl}
              onChangeText={setApiUrl}
              placeholder="https://dash.quixpos.com"
              placeholderTextColor="#64748b"
              autoCapitalize="none"
              autoCorrect={false}
            />
          </View>
          <View style={styles.formGroup}>
            <Text style={styles.label}>LOKALER SERVICE-PORT</Text>
            <TextInput
              style={styles.input}
              value={port}
              onChangeText={setPort}
              placeholder="41783"
              placeholderTextColor="#64748b"
              keyboardType="numeric"
            />
          </View>
          <TouchableOpacity style={styles.btnPrimary} onPress={handleSaveConfig}>
            <Text style={styles.btnPrimaryText}>💾 Verbindungseinstellungen speichern</Text>
          </TouchableOpacity>
        </View>

        {/* Card 2: ZPL Printers (Add IPs / BLE, Scan TCP 9100 & BLE, Live Status) */}
        <View style={styles.card}>
          <View style={styles.cardHeaderRow}>
            <Text style={styles.cardTitle}>DRUCKER (TCP & BLE)</Text>
            <View style={styles.rowActions}>
              <TouchableOpacity
                style={[styles.btnSecondarySm, isScanning && styles.btnDisabled]}
                onPress={handleScanPrinters}
                disabled={isScanning}>
                {isScanning ? (
                  <View style={styles.scanningRow}>
                    <ActivityIndicator size="small" color="#38bdf8" />
                    <Text style={styles.btnSecondaryText}> Sucht...</Text>
                  </View>
                ) : (
                  <Text style={styles.btnSecondaryText}>🔍 Suchen</Text>
                )}
              </TouchableOpacity>
              <TouchableOpacity style={styles.btnOutlineSm} onPress={handleAddBleAutoRow}>
                <Text style={styles.btnOutlineText}>+ BLE</Text>
              </TouchableOpacity>
              <TouchableOpacity style={styles.btnOutlineSm} onPress={handleAddPrinterRow}>
                <Text style={styles.btnOutlineText}>+ Hinzufügen</Text>
              </TouchableOpacity>
            </View>
          </View>

          {/* Discovered Printers Box */}
          {showDiscovered && discoveredPrinters.length > 0 && (
            <View style={styles.discoveredBox}>
              <View style={styles.discoveredHeader}>
                <Text style={styles.discoveredHeaderText}>
                  Gefunden ({discoveredPrinters.length}): Zum Hinzufügen tippen
                </Text>
                <TouchableOpacity onPress={() => setShowDiscovered(false)}>
                  <Text style={styles.closeBtnText}>✕</Text>
                </TouchableOpacity>
              </View>
              {discoveredPrinters.map(p => (
                <TouchableOpacity
                  key={p.ip}
                  style={styles.discoveredItem}
                  onPress={() => {
                    selectDiscoveredPrinter(p);
                    showToast(`${p.ip} (${p.model || 'Drucker'}) hinzugefügt`);
                  }}>
                  <View style={styles.discoveredItemTop}>
                    <Text style={styles.discoveredIp}>{p.ip}</Text>
                    <Text style={styles.discoveredModel}>{p.model || 'Drucker'}</Text>
                  </View>
                  {!!p.serial && <Text style={styles.discoveredSerial}>SN: {p.serial}</Text>}
                </TouchableOpacity>
              ))}
            </View>
          )}

          {/* Printer IP Inputs */}
          {printerRows.map((ipVal, idx) => {
            const cleanIp = ipVal.trim();
            const info = cleanIp ? printerInfoByIp[cleanIp] : undefined;
            const statusDisplay = getPrinterStatusDisplay(info);

            return (
              <View key={`printer-row-${idx}`} style={styles.printerItemContainer}>
                <View style={styles.printerInputRow}>
                  <TextInput
                    style={[styles.input, styles.printerInput]}
                    value={ipVal}
                    onChangeText={val => handleUpdatePrinterRow(idx, val)}
                    placeholder="z. B. 192.168.1.100 oder ble oder ble:34:14:B5:..."
                    placeholderTextColor="#64748b"
                    autoCapitalize="none"
                    autoCorrect={false}
                  />
                  <TouchableOpacity
                    style={styles.btnRemovePrinter}
                    onPress={() => handleRemovePrinterRow(idx)}>
                    <Text style={styles.btnRemovePrinterText}>✕</Text>
                  </TouchableOpacity>
                </View>

                {info && (info.model || info.serial || info.status || info.labelLength != null) && (
                  <View style={styles.printerMetaBox}>
                    <View style={[styles.printerDot, { backgroundColor: statusDisplay.color }]} />
                    <View style={styles.printerMetaTextCol}>
                      <Text style={styles.printerMetaLine}>
                        <Text style={styles.metaDim}>Status: </Text>
                        <Text style={[styles.metaValue, { color: statusDisplay.color }]}>
                          {statusDisplay.text}
                        </Text>
                        {info.labelLength != null && (
                          <Text style={styles.metaDim}> • Länge: {info.labelLength} Dots</Text>
                        )}
                      </Text>
                      <Text style={styles.printerMetaLine}>
                        <Text style={styles.metaDim}>Modell: </Text>
                        <Text style={styles.metaValue}>{info.model || '-'}</Text>
                        <Text style={styles.metaDim}> | Seriennr.: </Text>
                        <Text style={styles.metaAccent}>{info.serial || '-'}</Text>
                      </Text>
                      {statusDisplay.errorAlert && (
                        <Text style={styles.printerAlertText}>
                          {statusDisplay.errorAlert}
                        </Text>
                      )}
                    </View>
                  </View>
                )}
              </View>
            );
          })}

          <TouchableOpacity style={styles.btnPrimary} onPress={handleSaveConfig}>
            <Text style={styles.btnPrimaryText}>💾 Drucker speichern</Text>
          </TouchableOpacity>
        </View>

        {/* Card 3: Pairing & Background Notification Service */}
        <View style={styles.splitRow}>
          {/* Pairing Card */}
          <View style={[styles.card, styles.halfCard]}>
            <Text style={styles.cardTitle}>KOPPLUNG</Text>
            <View style={styles.statusRowBox}>
              <Text style={styles.statusRowLabel}>Gekoppelt:</Text>
              <View
                style={[
                  styles.statusPillSm,
                  isPaired ? styles.pillOnline : styles.pillOffline,
                ]}>
                <Text
                  style={[
                    styles.statusPillTextSm,
                    isPaired ? styles.textOnline : styles.textOffline,
                  ]}
                  numberOfLines={1}>
                  {isPaired ? `JA (${agentName || 'Agent'})` : 'NEIN'}
                </Text>
              </View>
            </View>

            {!isPaired ? (
              <View style={styles.pairInputRow}>
                <TextInput
                  style={[styles.input, styles.pinInput]}
                  value={pairPin}
                  onChangeText={setPairPin}
                  placeholder="6-stellige PIN"
                  placeholderTextColor="#64748b"
                  autoCapitalize="characters"
                />
                <TouchableOpacity
                  style={[styles.btnPair, isPairing && styles.btnDisabled]}
                  onPress={handlePair}
                  disabled={isPairing}>
                  <Text style={styles.btnPrimaryText}>{isPairing ? '...' : 'Koppeln'}</Text>
                </TouchableOpacity>
              </View>
            ) : (
              <TouchableOpacity style={styles.btnDanger} onPress={handleUnpair}>
                <Text style={styles.btnDangerText}>Agent trennen</Text>
              </TouchableOpacity>
            )}
          </View>

          {/* Foreground Notification Bar Service Card */}
          <View style={[styles.card, styles.halfCard]}>
            <Text style={styles.cardTitle}>HINTERGRUNDDIENST</Text>
            <View style={styles.statusRowBox}>
              <Text style={styles.statusRowLabel}>Aktiv:</Text>
              <View
                style={[
                  styles.statusPillSm,
                  isRunning ? styles.pillOnline : styles.pillOffline,
                ]}>
                <Text
                  style={[
                    styles.statusPillTextSm,
                    isRunning ? styles.textOnline : styles.textOffline,
                  ]}>
                  {isRunning ? 'JA' : 'NEIN'}
                </Text>
              </View>
            </View>
            <View style={styles.statusRowBox}>
              <Text style={styles.statusRowLabel}>Statusleiste:</Text>
              <View
                style={[
                  styles.statusPillSm,
                  isForegroundNotif ? styles.pillOnline : styles.pillOffline,
                ]}>
                <Text
                  style={[
                    styles.statusPillTextSm,
                    isForegroundNotif ? styles.textOnline : styles.textOffline,
                  ]}>
                  {isForegroundNotif ? 'AKTIV' : 'AUS'}
                </Text>
              </View>
            </View>

            <TouchableOpacity
              style={isForegroundNotif ? styles.btnDanger : styles.btnPrimary}
              onPress={handleToggleForegroundService}>
              <Text style={isForegroundNotif ? styles.btnDangerText : styles.btnPrimaryText}>
                {isForegroundNotif ? 'Dienst stoppen' : 'Dienst starten'}
              </Text>
            </TouchableOpacity>
          </View>
        </View>

        {/* Card 4: Live Agent Log Terminal */}
        <View style={[styles.card, styles.logCard]}>
          <View style={styles.cardHeaderRow}>
            <Text style={styles.cardTitle}>LIVE-AGENT-PROTOKOLL ({logs.length})</Text>
            <TouchableOpacity style={styles.btnSecondarySm} onPress={handleClearLogs}>
              <Text style={styles.btnSecondaryText}>🗑️ Leeren</Text>
            </TouchableOpacity>
          </View>

          <ScrollView
            ref={logScrollRef}
            style={styles.logTerminal}
            nestedScrollEnabled
            onContentSizeChange={() => logScrollRef.current?.scrollToEnd({ animated: true })}>
            {logs.length === 0 ? (
              <Text style={styles.logEmpty}>Warte auf Agent-Ausgabe...</Text>
            ) : (
              logs.map((line, i) => {
                let color = '#cbd5e1';
                if (line.includes('online') || line.includes('Successfully')) {
                  color = '#4ade80';
                } else if (line.includes('error') || line.includes('fail') || line.includes('unreachable')) {
                  color = '#f87171';
                } else if (line.includes('Local monitor') || line.includes('SSE connected')) {
                  color = '#38bdf8';
                }
                return (
                  <Text key={`log-${i}`} style={[styles.logLine, { color }]}>
                    {line}
                  </Text>
                );
              })
            )}
          </ScrollView>
        </View>
      </ScrollView>

      {/* Toast Banner */}
      {toastMsg && (
        <View
          style={[
            styles.toast,
            { bottom: Math.max(insets.bottom, 14) + 8 },
            toastMsg.type === 'error' ? styles.toastError : styles.toastSuccess,
          ]}>
          <Text style={styles.toastText}>{toastMsg.text}</Text>
        </View>
      )}
    </View>
  );
}

export default function App(): React.JSX.Element {
  return (
    <SafeAreaProvider>
      <PrinterAgentMain />
    </SafeAreaProvider>
  );
}

const monoFont = Platform.select({ ios: 'Menlo', android: 'monospace', default: 'monospace' });

const styles = StyleSheet.create({
  safeArea: {
    flex: 1,
    backgroundColor: '#0f172a',
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 12,
    paddingVertical: 8,
    backgroundColor: '#0f172a',
  },
  headerBrand: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    flex: 1,
  },
  headerIcon: {
    width: 32,
    height: 32,
    borderRadius: 8,
    backgroundColor: '#0284c7',
    alignItems: 'center',
    justifyContent: 'center',
  },
  headerIconText: {
    color: '#ffffff',
    fontWeight: '800',
    fontSize: 14,
  },
  headerTitle: {
    color: '#f8fafc',
    fontSize: 15,
    fontWeight: '700',
  },
  headerSubtitle: {
    color: '#94a3b8',
    fontSize: 11,
    marginTop: 1,
  },
  statusPill: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 8,
    paddingVertical: 3,
    borderRadius: 14,
    gap: 5,
  },
  statusPillSm: {
    paddingHorizontal: 6,
    paddingVertical: 2,
    borderRadius: 10,
    maxWidth: 130,
  },
  pillOnline: {
    backgroundColor: 'rgba(34, 197, 94, 0.15)',
  },
  pillWarning: {
    backgroundColor: 'rgba(245, 158, 11, 0.15)',
  },
  pillOffline: {
    backgroundColor: 'rgba(239, 68, 68, 0.15)',
  },
  statusDot: {
    width: 6,
    height: 6,
    borderRadius: 3,
  },
  dotOnline: {
    backgroundColor: '#22c55e',
  },
  dotWarning: {
    backgroundColor: '#f59e0b',
  },
  dotOffline: {
    backgroundColor: '#ef4444',
  },
  statusPillText: {
    fontSize: 11,
    fontWeight: '700',
  },
  statusPillTextSm: {
    fontSize: 10,
    fontWeight: '700',
  },
  textOnline: {
    color: '#86efac',
  },
  textWarning: {
    color: '#fde047',
  },
  textOffline: {
    color: '#fca5a5',
  },
  scrollArea: {
    flex: 1,
  },
  scrollContent: {
    paddingHorizontal: 12,
    paddingVertical: 6,
    gap: 8,
    paddingBottom: 20,
  },
  card: {
    paddingVertical: 2,
    gap: 6,
  },
  cardHeaderRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  cardTitle: {
    color: '#94a3b8',
    fontSize: 11,
    fontWeight: '700',
    letterSpacing: 0.5,
  },
  rowActions: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
  },
  formGroup: {
    gap: 2,
  },
  label: {
    color: '#94a3b8',
    fontSize: 10,
    fontWeight: '600',
    letterSpacing: 0.3,
  },
  input: {
    backgroundColor: '#1e293b',
    borderRadius: 8,
    color: '#f8fafc',
    paddingHorizontal: 10,
    paddingVertical: 6,
    fontSize: 13,
    fontFamily: monoFont,
  },
  btnPrimary: {
    backgroundColor: '#38bdf8',
    borderRadius: 8,
    paddingVertical: 8,
    paddingHorizontal: 12,
    alignItems: 'center',
    justifyContent: 'center',
    marginTop: 2,
  },
  btnPrimaryText: {
    color: '#0f172a',
    fontWeight: '700',
    fontSize: 12,
  },
  btnDanger: {
    backgroundColor: 'rgba(239, 68, 68, 0.22)',
    borderRadius: 8,
    paddingVertical: 8,
    paddingHorizontal: 12,
    alignItems: 'center',
    justifyContent: 'center',
    marginTop: 2,
  },
  btnDangerText: {
    color: '#fca5a5',
    fontWeight: '700',
    fontSize: 12,
  },
  btnSecondarySm: {
    backgroundColor: '#1e293b',
    borderRadius: 6,
    paddingHorizontal: 8,
    paddingVertical: 4,
  },
  btnSecondaryText: {
    color: '#f8fafc',
    fontSize: 11,
    fontWeight: '600',
  },
  btnOutlineSm: {
    backgroundColor: '#1e293b',
    borderRadius: 6,
    paddingHorizontal: 8,
    paddingVertical: 4,
  },
  btnOutlineText: {
    color: '#cbd5e1',
    fontSize: 11,
    fontWeight: '600',
  },
  btnDisabled: {
    opacity: 0.55,
  },
  scanningRow: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  discoveredBox: {
    paddingVertical: 2,
    gap: 4,
  },
  discoveredHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  discoveredHeaderText: {
    color: '#94a3b8',
    fontSize: 11,
  },
  closeBtnText: {
    color: '#94a3b8',
    fontSize: 12,
    paddingHorizontal: 4,
  },
  discoveredItem: {
    backgroundColor: '#1e293b',
    borderRadius: 8,
    padding: 8,
  },
  discoveredItemTop: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  discoveredIp: {
    color: '#38bdf8',
    fontFamily: monoFont,
    fontWeight: '700',
    fontSize: 12,
  },
  discoveredModel: {
    color: '#f8fafc',
    fontSize: 12,
    fontWeight: '600',
  },
  discoveredSerial: {
    color: '#94a3b8',
    fontFamily: monoFont,
    fontSize: 11,
    marginTop: 2,
  },
  printerItemContainer: {
    gap: 4,
  },
  printerInputRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
  },
  printerInput: {
    flex: 1,
  },
  btnRemovePrinter: {
    backgroundColor: 'rgba(239, 68, 68, 0.15)',
    borderRadius: 8,
    paddingHorizontal: 10,
    paddingVertical: 6,
    alignItems: 'center',
    justifyContent: 'center',
  },
  btnRemovePrinterText: {
    color: '#f87171',
    fontSize: 12,
    fontWeight: '700',
  },
  printerMetaBox: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 2,
    paddingVertical: 2,
    gap: 6,
  },
  printerDot: {
    width: 6,
    height: 6,
    borderRadius: 3,
  },
  printerMetaTextCol: {
    flex: 1,
    gap: 2,
  },
  printerMetaLine: {
    fontSize: 11,
    fontFamily: monoFont,
  },
  metaDim: {
    color: '#64748b',
  },
  metaValue: {
    color: '#f8fafc',
    fontWeight: '600',
  },
  metaAccent: {
    color: '#38bdf8',
    fontWeight: '600',
  },
  printerAlertText: {
    color: '#fca5a5',
    fontSize: 11,
    fontWeight: '700',
    marginTop: 2,
  },
  splitRow: {
    flexDirection: 'row',
    gap: 8,
  },
  halfCard: {
    flex: 1,
    justifyContent: 'space-between',
  },
  statusRowBox: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: 2,
  },
  statusRowLabel: {
    color: '#f8fafc',
    fontSize: 11,
    fontWeight: '600',
  },
  pairInputRow: {
    flexDirection: 'row',
    gap: 6,
    marginTop: 2,
  },
  pinInput: {
    flex: 1,
    paddingVertical: 6,
  },
  btnPair: {
    backgroundColor: '#38bdf8',
    borderRadius: 8,
    paddingHorizontal: 10,
    justifyContent: 'center',
    alignItems: 'center',
  },
  logCard: {
    minHeight: 160,
  },
  logTerminal: {
    backgroundColor: '#090d16',
    borderRadius: 8,
    padding: 8,
    height: 160,
  },
  logEmpty: {
    color: '#64748b',
    fontFamily: monoFont,
    fontSize: 11,
  },
  logLine: {
    fontFamily: monoFont,
    fontSize: 10,
    lineHeight: 15,
    marginBottom: 2,
  },
  toast: {
    position: 'absolute',
    bottom: 14,
    left: 14,
    right: 14,
    paddingVertical: 8,
    paddingHorizontal: 12,
    borderRadius: 8,
    elevation: 6,
  },
  toastSuccess: {
    backgroundColor: '#15803d',
  },
  toastError: {
    backgroundColor: '#b91c1c',
  },
  toastText: {
    color: '#f8fafc',
    fontSize: 12,
    fontWeight: '600',
  },
});
