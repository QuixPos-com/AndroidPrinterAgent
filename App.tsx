import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Platform,
  SafeAreaView,
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  View,
} from 'react-native';
import {
  AgentSnapshot,
  PrinterAgent,
  PrinterAgentEvents,
  PrinterInfo,
} from './src/native/PrinterAgent';

export default function App(): React.JSX.Element {
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
      showToast(`Init error: ${e?.message || String(e)}`, 'error');
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
      showToast('QuixPOS API URL is required', 'error');
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
      showToast('Configuration saved & printers reconnected!');
    } catch (e: any) {
      showToast(`Save error: ${e?.message || String(e)}`, 'error');
    }
  };

  const handlePair = async () => {
    const pin = pairPin.trim();
    if (!pin) {
      showToast('Please enter 6-digit pairing PIN', 'error');
      return;
    }
    setIsPairing(true);
    try {
      const res = await PrinterAgent.pairAgent(pin, apiUrl.trim() || null);
      setPairPin('');
      setIsPaired(true);
      setAgentName(res.name || 'android-agent');
      showToast(`Paired successfully as "${res.name || 'agent'}"!`);
    } catch (e: any) {
      showToast(`Pairing failed: ${e?.message || String(e)}`, 'error');
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
      showToast('Agent unpaired (token cleared & stream dropped)');
    } catch (e: any) {
      showToast(`Unpair error: ${e?.message || String(e)}`, 'error');
    }
  };

  const handleScanPrinters = async () => {
    setIsScanning(true);
    try {
      await PrinterAgent.requestBluetoothPermissions();
      const found = await PrinterAgent.scanLocalPrinters();
      if (!Array.isArray(found) || found.length === 0) {
        showToast('No ZPL printers found (BLE or TCP 9100)', 'error');
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
        showToast(`Found ${found[0].model || 'ZPL Printer'} at ${found[0].ip}`);
        setShowDiscovered(false);
      } else {
        setDiscoveredPrinters(found);
        setShowDiscovered(true);
        showToast(`Found ${found.length} ZPL printers (BLE / TCP)`);
      }
    } catch (e: any) {
      showToast(`Scan failed: ${e?.message || String(e)}`, 'error');
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
        showToast('Stopped background notification bar service');
      } else {
        await PrinterAgent.requestNotificationPermission();
        await PrinterAgent.startForegroundService();
        setIsForegroundNotif(true);
        setIsRunning(true);
        showToast('Started background notification bar service');
      }
    } catch (e: any) {
      showToast(`Service error: ${e?.message || String(e)}`, 'error');
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
    <SafeAreaView style={styles.safeArea}>
      <StatusBar barStyle="light-content" />

      {/* Top Header */}
      <View style={styles.header}>
        <View style={styles.headerBrand}>
          <View style={styles.headerIcon}>
            <Text style={styles.headerIconText}>QZ</Text>
          </View>
          <View>
            <Text style={styles.headerTitle}>Zebra Agent Manager</Text>
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
            {backendConnected ? 'SSE ONLINE' : isPaired ? 'CONNECTING' : 'UNPAIRED'}
          </Text>
        </View>
      </View>

      <ScrollView style={styles.scrollArea} contentContainerStyle={styles.scrollContent}>
        {/* Card 1: Connection Settings (API URL & Local Port) */}
        <View style={styles.card}>
          <Text style={styles.cardTitle}>CONNECTION SETTINGS</Text>
          <View style={styles.formGroup}>
            <Text style={styles.label}>QUIXPOS API URL</Text>
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
            <Text style={styles.label}>LOCAL SERVICE PORT</Text>
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
            <Text style={styles.btnPrimaryText}>💾 Save Connection Settings</Text>
          </TouchableOpacity>
        </View>

        {/* Card 2: ZPL Printers (Add IPs / BLE, Scan TCP 9100 & BLE, Live Status) */}
        <View style={styles.card}>
          <View style={styles.cardHeaderRow}>
            <Text style={styles.cardTitle}>ZPL PRINTERS (TCP & BLE)</Text>
            <View style={styles.rowActions}>
              <TouchableOpacity
                style={[styles.btnSecondarySm, isScanning && styles.btnDisabled]}
                onPress={handleScanPrinters}
                disabled={isScanning}>
                {isScanning ? (
                  <View style={styles.scanningRow}>
                    <ActivityIndicator size="small" color="#38bdf8" />
                    <Text style={styles.btnSecondaryText}> Scanning...</Text>
                  </View>
                ) : (
                  <Text style={styles.btnSecondaryText}>🔍 Scan</Text>
                )}
              </TouchableOpacity>
              <TouchableOpacity style={styles.btnOutlineSm} onPress={handleAddBleAutoRow}>
                <Text style={styles.btnOutlineText}>+ BLE</Text>
              </TouchableOpacity>
              <TouchableOpacity style={styles.btnOutlineSm} onPress={handleAddPrinterRow}>
                <Text style={styles.btnOutlineText}>+ Add</Text>
              </TouchableOpacity>
            </View>
          </View>

          {/* Discovered Printers Box */}
          {showDiscovered && discoveredPrinters.length > 0 && (
            <View style={styles.discoveredBox}>
              <View style={styles.discoveredHeader}>
                <Text style={styles.discoveredHeaderText}>
                  Discovered ({discoveredPrinters.length}): tap to add
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
                    showToast(`Added ${p.ip} (${p.model || 'ZPL'})`);
                  }}>
                  <View style={styles.discoveredItemTop}>
                    <Text style={styles.discoveredIp}>{p.ip}</Text>
                    <Text style={styles.discoveredModel}>{p.model || 'ZPL Printer'}</Text>
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
            const isOnline = info?.ready || info?.status === 'online';
            const statusColor = isOnline
              ? '#22c55e'
              : info?.status === 'offline'
                ? '#ef4444'
                : '#f59e0b';

            return (
              <View key={`printer-row-${idx}`} style={styles.printerItemContainer}>
                <View style={styles.printerInputRow}>
                  <TextInput
                    style={[styles.input, styles.printerInput]}
                    value={ipVal}
                    onChangeText={val => handleUpdatePrinterRow(idx, val)}
                    placeholder="e.g. 192.168.1.100 or ble or ble:34:14:B5:..."
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

                {info && (info.model || info.serial || info.status) && (
                  <View style={styles.printerMetaBox}>
                    <View style={[styles.printerDot, { backgroundColor: statusColor }]} />
                    <View style={styles.printerMetaTextCol}>
                      <Text style={styles.printerMetaLine}>
                        <Text style={styles.metaDim}>Status: </Text>
                        <Text style={[styles.metaValue, { color: statusColor }]}>
                          {(info.status || 'unknown').toUpperCase()}
                        </Text>
                        {info.labelLength != null && (
                          <Text style={styles.metaDim}> • Length: {info.labelLength}</Text>
                        )}
                      </Text>
                      <Text style={styles.printerMetaLine}>
                        <Text style={styles.metaDim}>Model: </Text>
                        <Text style={styles.metaValue}>{info.model || '-'}</Text>
                        <Text style={styles.metaDim}> | Serial: </Text>
                        <Text style={styles.metaAccent}>{info.serial || '-'}</Text>
                      </Text>
                    </View>
                  </View>
                )}
              </View>
            );
          })}

          <TouchableOpacity style={styles.btnPrimary} onPress={handleSaveConfig}>
            <Text style={styles.btnPrimaryText}>💾 Save Printers</Text>
          </TouchableOpacity>
        </View>

        {/* Card 3: Pairing & Background Notification Service */}
        <View style={styles.splitRow}>
          {/* Pairing Card */}
          <View style={[styles.card, styles.halfCard]}>
            <Text style={styles.cardTitle}>PAIRING</Text>
            <View style={styles.statusRowBox}>
              <Text style={styles.statusRowLabel}>Paired:</Text>
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
                  {isPaired ? `YES (${agentName || 'agent'})` : 'NO'}
                </Text>
              </View>
            </View>

            {!isPaired ? (
              <View style={styles.pairInputRow}>
                <TextInput
                  style={[styles.input, styles.pinInput]}
                  value={pairPin}
                  onChangeText={setPairPin}
                  placeholder="6-digit PIN"
                  placeholderTextColor="#64748b"
                  autoCapitalize="characters"
                />
                <TouchableOpacity
                  style={[styles.btnPair, isPairing && styles.btnDisabled]}
                  onPress={handlePair}
                  disabled={isPairing}>
                  <Text style={styles.btnPrimaryText}>{isPairing ? '...' : 'Pair'}</Text>
                </TouchableOpacity>
              </View>
            ) : (
              <TouchableOpacity style={styles.btnDanger} onPress={handleUnpair}>
                <Text style={styles.btnDangerText}>Unpair Agent</Text>
              </TouchableOpacity>
            )}
          </View>

          {/* Foreground Notification Bar Service Card */}
          <View style={[styles.card, styles.halfCard]}>
            <Text style={styles.cardTitle}>NOTIFICATION SERVICE</Text>
            <View style={styles.statusRowBox}>
              <Text style={styles.statusRowLabel}>Running:</Text>
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
                  {isRunning ? 'YES' : 'NO'}
                </Text>
              </View>
            </View>
            <View style={styles.statusRowBox}>
              <Text style={styles.statusRowLabel}>Status Bar:</Text>
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
                  {isForegroundNotif ? 'ACTIVE' : 'OFF'}
                </Text>
              </View>
            </View>

            <TouchableOpacity
              style={isForegroundNotif ? styles.btnDanger : styles.btnPrimary}
              onPress={handleToggleForegroundService}>
              <Text style={isForegroundNotif ? styles.btnDangerText : styles.btnPrimaryText}>
                {isForegroundNotif ? 'Stop Bar Service' : 'Start Bar Service'}
              </Text>
            </TouchableOpacity>
          </View>
        </View>

        {/* Card 4: Live Agent Log Terminal */}
        <View style={[styles.card, styles.logCard]}>
          <View style={styles.cardHeaderRow}>
            <Text style={styles.cardTitle}>LIVE AGENT LOGS ({logs.length})</Text>
            <TouchableOpacity style={styles.btnSecondarySm} onPress={handleClearLogs}>
              <Text style={styles.btnSecondaryText}>🗑️ Clear</Text>
            </TouchableOpacity>
          </View>

          <ScrollView
            ref={logScrollRef}
            style={styles.logTerminal}
            nestedScrollEnabled
            onContentSizeChange={() => logScrollRef.current?.scrollToEnd({ animated: true })}>
            {logs.length === 0 ? (
              <Text style={styles.logEmpty}>Waiting for agent output...</Text>
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
            toastMsg.type === 'error' ? styles.toastError : styles.toastSuccess,
          ]}>
          <Text style={styles.toastText}>{toastMsg.text}</Text>
        </View>
      )}
    </SafeAreaView>
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
    paddingHorizontal: 14,
    paddingVertical: 12,
    borderBottomWidth: 1,
    borderBottomColor: '#334155',
    backgroundColor: '#0f172a',
  },
  headerBrand: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    flex: 1,
  },
  headerIcon: {
    width: 36,
    height: 36,
    backgroundColor: '#0284c7',
    alignItems: 'center',
    justifyContent: 'center',
  },
  headerIconText: {
    color: '#ffffff',
    fontWeight: '800',
    fontSize: 15,
  },
  headerTitle: {
    color: '#f8fafc',
    fontSize: 16,
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
    paddingHorizontal: 9,
    paddingVertical: 5,
    borderWidth: 1,
    gap: 6,
  },
  statusPillSm: {
    paddingHorizontal: 8,
    paddingVertical: 3,
    borderWidth: 1,
    maxWidth: 130,
  },
  pillOnline: {
    backgroundColor: 'rgba(34, 197, 94, 0.15)',
    borderColor: 'rgba(34, 197, 94, 0.35)',
  },
  pillWarning: {
    backgroundColor: 'rgba(245, 158, 11, 0.15)',
    borderColor: 'rgba(245, 158, 11, 0.35)',
  },
  pillOffline: {
    backgroundColor: 'rgba(239, 68, 68, 0.15)',
    borderColor: 'rgba(239, 68, 68, 0.35)',
  },
  statusDot: {
    width: 7,
    height: 7,
    borderRadius: 4,
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
    padding: 12,
    gap: 12,
    paddingBottom: 32,
  },
  card: {
    backgroundColor: '#1e293b',
    borderWidth: 1,
    borderColor: '#334155',
    padding: 14,
    gap: 10,
  },
  cardHeaderRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  cardTitle: {
    color: '#94a3b8',
    fontSize: 12,
    fontWeight: '700',
    letterSpacing: 0.6,
  },
  rowActions: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  formGroup: {
    gap: 5,
  },
  label: {
    color: '#94a3b8',
    fontSize: 11,
    fontWeight: '600',
    letterSpacing: 0.4,
  },
  input: {
    backgroundColor: '#0f172a',
    borderWidth: 1,
    borderColor: '#334155',
    color: '#f8fafc',
    paddingHorizontal: 10,
    paddingVertical: 8,
    fontSize: 13,
    fontFamily: monoFont,
  },
  btnPrimary: {
    backgroundColor: '#38bdf8',
    paddingVertical: 10,
    paddingHorizontal: 14,
    alignItems: 'center',
    justifyContent: 'center',
    marginTop: 4,
  },
  btnPrimaryText: {
    color: '#0f172a',
    fontWeight: '700',
    fontSize: 13,
  },
  btnDanger: {
    backgroundColor: 'rgba(239, 68, 68, 0.18)',
    borderWidth: 1,
    borderColor: 'rgba(239, 68, 68, 0.45)',
    paddingVertical: 10,
    paddingHorizontal: 12,
    alignItems: 'center',
    justifyContent: 'center',
    marginTop: 4,
  },
  btnDangerText: {
    color: '#fca5a5',
    fontWeight: '700',
    fontSize: 13,
  },
  btnSecondarySm: {
    backgroundColor: '#334155',
    borderWidth: 1,
    borderColor: '#475569',
    paddingHorizontal: 10,
    paddingVertical: 5,
  },
  btnSecondaryText: {
    color: '#f8fafc',
    fontSize: 12,
    fontWeight: '600',
  },
  btnOutlineSm: {
    borderWidth: 1,
    borderColor: '#475569',
    paddingHorizontal: 10,
    paddingVertical: 5,
  },
  btnOutlineText: {
    color: '#cbd5e1',
    fontSize: 12,
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
    backgroundColor: '#0f172a',
    borderWidth: 1,
    borderColor: '#38bdf8',
    padding: 8,
    gap: 6,
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
    fontSize: 13,
    paddingHorizontal: 4,
  },
  discoveredItem: {
    backgroundColor: '#1e293b',
    borderWidth: 1,
    borderColor: '#334155',
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
    gap: 6,
  },
  printerInputRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  printerInput: {
    flex: 1,
  },
  btnRemovePrinter: {
    borderWidth: 1,
    borderColor: 'rgba(248, 113, 113, 0.4)',
    paddingHorizontal: 11,
    paddingVertical: 8,
  },
  btnRemovePrinterText: {
    color: '#f87171',
    fontSize: 13,
    fontWeight: '700',
  },
  printerMetaBox: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: 'rgba(15, 23, 42, 0.7)',
    borderWidth: 1,
    borderColor: '#334155',
    paddingHorizontal: 10,
    paddingVertical: 6,
    gap: 8,
  },
  printerDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
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
  splitRow: {
    flexDirection: 'row',
    gap: 10,
  },
  halfCard: {
    flex: 1,
    justifyContent: 'space-between',
  },
  statusRowBox: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    backgroundColor: '#0f172a',
    borderWidth: 1,
    borderColor: '#334155',
    paddingHorizontal: 10,
    paddingVertical: 8,
  },
  statusRowLabel: {
    color: '#f8fafc',
    fontSize: 12,
    fontWeight: '600',
  },
  pairInputRow: {
    flexDirection: 'row',
    gap: 6,
    marginTop: 4,
  },
  pinInput: {
    flex: 1,
    paddingVertical: 7,
  },
  btnPair: {
    backgroundColor: '#38bdf8',
    paddingHorizontal: 12,
    justifyContent: 'center',
    alignItems: 'center',
  },
  logCard: {
    minHeight: 220,
  },
  logTerminal: {
    backgroundColor: '#090d16',
    borderWidth: 1,
    borderColor: '#334155',
    padding: 10,
    height: 200,
  },
  logEmpty: {
    color: '#64748b',
    fontFamily: monoFont,
    fontSize: 12,
  },
  logLine: {
    fontFamily: monoFont,
    fontSize: 11,
    lineHeight: 16,
    marginBottom: 2,
  },
  toast: {
    position: 'absolute',
    bottom: 18,
    left: 16,
    right: 16,
    paddingVertical: 11,
    paddingHorizontal: 14,
    borderWidth: 1,
    borderLeftWidth: 4,
    elevation: 6,
  },
  toastSuccess: {
    backgroundColor: '#1e293b',
    borderColor: '#334155',
    borderLeftColor: '#22c55e',
  },
  toastError: {
    backgroundColor: '#1e293b',
    borderColor: '#334155',
    borderLeftColor: '#ef4444',
  },
  toastText: {
    color: '#f8fafc',
    fontSize: 13,
    fontWeight: '600',
  },
});
