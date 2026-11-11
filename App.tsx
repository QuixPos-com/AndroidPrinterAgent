import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  BackHandler,
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { SafeAreaProvider, useSafeAreaInsets } from 'react-native-safe-area-context';
import {
  AgentSnapshot,
  PrinterAgent,
  PrinterAgentEvents,
  PrinterInfo,
} from './src/native/PrinterAgent';
import {
  Avatar,
  Button,
  Dialog,
  Divider,
  ListItem,
  OverflowMenu,
  PlusIcon,
  SearchIcon,
  SectionHeader,
  Snackbar,
  Switch,
  TextField,
  TopAppBar,
} from './src/ui/components';
import { Tone, toneColors, type, useTheme } from './src/ui/theme';

const DEFAULT_API_URL = 'https://dash.quixpos.com';
const DEFAULT_PORT = '41783';

const isBleAddress = (addr: string) => {
  const l = addr.trim().toLowerCase();
  return (
    l === 'ble' ||
    l === 'bluetooth' ||
    l === 'auto' ||
    l.startsWith('ble:') ||
    l.startsWith('bt:') ||
    /^([0-9a-f]{2}[:-]){5}[0-9a-f]{2}$/i.test(l)
  );
};

const describeAddress = (addr: string) => {
  const l = addr.trim().toLowerCase();
  if (l === 'ble' || l === 'bluetooth' || l === 'auto') return 'Bluetooth (automatisch)';
  return addr.trim();
};

const getPrinterStatusDisplay = (
  info?: PrinterInfo,
): { text: string; tone: Tone; errorAlert: string | null } => {
  if (!info) return { text: 'Status unbekannt', tone: 'neutral', errorAlert: null };
  if (info.errors?.headOpen || info.status === 'headOpen') {
    return {
      text: 'Druckkopf offen',
      tone: 'error',
      errorAlert: 'Druckkopf ist geöffnet. Bitte Klappe schließen.',
    };
  }
  if (info.errors?.pause || info.status === 'pause') {
    return {
      text: 'Pausiert',
      tone: 'warning',
      errorAlert: 'Drucker ist pausiert. Bitte Pause-Taste am Drucker drücken.',
    };
  }
  if (info.errors?.unreachable || info.status === 'offline') {
    return { text: 'Nicht erreichbar', tone: 'error', errorAlert: null };
  }
  if (info.errors?.other || info.status === 'error') {
    return {
      text: 'Druckerfehler',
      tone: 'error',
      errorAlert: 'Drucker meldet einen Fehler (Papier/Farbband prüfen).',
    };
  }
  if (info.ready || info.status === 'online') {
    return { text: 'Bereit', tone: 'success', errorAlert: null };
  }
  return { text: info.status || 'Status unbekannt', tone: 'warning', errorAlert: null };
};

type EditState =
  | { kind: 'api'; value: string }
  | { kind: 'port'; value: string }
  | { kind: 'printer'; index: number | null; value: string };

function PrinterAgentMain(): React.JSX.Element {
  const t = useTheme();
  const insets = useSafeAreaInsets();

  // Persisted configuration
  const [apiUrl, setApiUrl] = useState<string>(DEFAULT_API_URL);
  const [port, setPort] = useState<string>(DEFAULT_PORT);
  const [printers, setPrinters] = useState<string[]>([]);

  const [printerInfoByIp, setPrinterInfoByIp] = useState<Record<string, PrinterInfo>>({});
  const [discoveredPrinters, setDiscoveredPrinters] = useState<PrinterInfo[]>([]);
  const [showDiscovered, setShowDiscovered] = useState<boolean>(false);
  const [isScanning, setIsScanning] = useState<boolean>(false);

  const [isPaired, setIsPaired] = useState<boolean>(false);
  const [agentName, setAgentName] = useState<string>('');
  const [backendConnected, setBackendConnected] = useState<boolean>(false);
  const [pairPin, setPairPin] = useState<string>('');
  const [isPairing, setIsPairing] = useState<boolean>(false);
  const [confirmUnpair, setConfirmUnpair] = useState<boolean>(false);

  const [isRunning, setIsRunning] = useState<boolean>(false);
  const [isForegroundNotif, setIsForegroundNotif] = useState<boolean>(false);

  const [edit, setEdit] = useState<EditState | null>(null);
  const [isSaving, setIsSaving] = useState<boolean>(false);
  const [menuOpen, setMenuOpen] = useState<boolean>(false);
  const [scrolled, setScrolled] = useState<boolean>(false);

  const [snackbar, setSnackbar] = useState<string | null>(null);
  const snackbarTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const handleClose = useCallback(() => {
    try {
      PrinterAgent.closeApp?.();
    } catch { }
    BackHandler.exitApp();
  }, []);

  const showToast = useCallback((text: string, _type: 'success' | 'error' = 'success') => {
    if (snackbarTimerRef.current) {
      clearTimeout(snackbarTimerRef.current);
    }
    setSnackbar(text);
    snackbarTimerRef.current = setTimeout(() => setSnackbar(null), 4000);
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
      setApiUrl(cfg.api_url || DEFAULT_API_URL);
      setPort(cfg.port || DEFAULT_PORT);
      const initialPrinters =
        cfg.zpl_printers && cfg.zpl_printers.length > 0
          ? cfg.zpl_printers
          : cfg.printer_ip
            ? [cfg.printer_ip]
            : [];
      setPrinters(initialPrinters.map(p => p.trim()).filter(Boolean));

      const svc = await PrinterAgent.getServiceStatus();
      setIsRunning(svc.running);
      setIsForegroundNotif(svc.background);

      const snap = await PrinterAgent.getSnapshot();
      applySnapshot(snap);
    } catch (e: any) {
      showToast(`Initialisierungsfehler: ${e?.message || String(e)}`, 'error');
    }
  }, [applySnapshot, showToast]);

  useEffect(() => {
    loadInitialState();

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
      stateSub.remove();
      clearInterval(statusPoll);
    };
  }, [applySnapshot, loadInitialState]);

  /** Saves the configuration (with the given changes applied) and reconnects printers. */
  const persistConfig = async (
    patch: { apiUrl?: string; printers?: string[]; port?: string },
    successMsg: string,
  ): Promise<boolean> => {
    const nextApi = (patch.apiUrl ?? apiUrl).trim();
    const nextPrinters = (patch.printers ?? printers).map(r => r.trim()).filter(Boolean);
    const nextPort = (patch.port ?? port).trim() || DEFAULT_PORT;
    if (!nextApi) {
      showToast('QuixPOS-API-URL ist erforderlich', 'error');
      return false;
    }
    setIsSaving(true);
    try {
      if (nextPrinters.some(isBleAddress)) {
        await PrinterAgent.requestBluetoothPermissions();
      }
      const snap = await PrinterAgent.saveConfig(nextApi, nextPrinters, nextPort);
      setApiUrl(nextApi);
      setPrinters(nextPrinters);
      setPort(nextPort);
      applySnapshot(snap);
      showToast(successMsg);
      return true;
    } catch (e: any) {
      showToast(`Fehler beim Speichern: ${e?.message || String(e)}`, 'error');
      return false;
    } finally {
      setIsSaving(false);
    }
  };

  const addPrinter = async (info: PrinterInfo) => {
    const ip = info.ip.trim();
    setPrinterInfoByIp(prev => ({ ...prev, [ip]: info }));
    if (printers.some(r => r.trim() === ip)) {
      showToast(`${ip} ist bereits eingerichtet`);
      return;
    }
    await persistConfig(
      { printers: [...printers, ip] },
      `${info.model || 'Drucker'} hinzugefügt`,
    );
  };

  const handleSaveEdit = async () => {
    if (!edit) return;
    const value = edit.value.trim();

    if (edit.kind === 'api') {
      if (!value) {
        showToast('QuixPOS-API-URL ist erforderlich', 'error');
        return;
      }
      if (await persistConfig({ apiUrl: value }, 'API-URL gespeichert')) setEdit(null);
      return;
    }

    if (edit.kind === 'port') {
      const n = Number(value);
      if (!Number.isInteger(n) || n < 1 || n > 65535) {
        showToast('Ungültiger Port (1–65535)', 'error');
        return;
      }
      if (await persistConfig({ port: String(n) }, 'Port gespeichert')) setEdit(null);
      return;
    }

    if (!value) {
      showToast('Bitte eine Druckeradresse eingeben', 'error');
      return;
    }
    const duplicate = printers.some(
      (p, i) => i !== edit.index && p.trim().toLowerCase() === value.toLowerCase(),
    );
    if (duplicate) {
      showToast('Dieser Drucker ist bereits eingerichtet', 'error');
      return;
    }
    const next =
      edit.index === null
        ? [...printers, value]
        : printers.map((p, i) => (i === edit.index ? value : p));
    if (await persistConfig({ printers: next }, 'Drucker gespeichert & neu verbunden')) {
      setEdit(null);
    }
  };

  const handleRemovePrinter = async (index: number) => {
    const next = printers.filter((_, i) => i !== index);
    if (await persistConfig({ printers: next }, 'Drucker entfernt')) setEdit(null);
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
      showToast(`Gekoppelt als „${res.name || 'Agent'}“`);
    } catch (e: any) {
      showToast(`Kopplung fehlgeschlagen: ${e?.message || String(e)}`, 'error');
    } finally {
      setIsPairing(false);
    }
  };

  const handleUnpair = async () => {
    setConfirmUnpair(false);
    try {
      await PrinterAgent.unpairAgent();
      setIsPaired(false);
      setAgentName('');
      setBackendConnected(false);
      showToast('Agent getrennt');
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
        showToast('Keine Drucker gefunden (Bluetooth oder TCP 9100)', 'error');
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

      setDiscoveredPrinters(found);
      setShowDiscovered(true);
    } catch (e: any) {
      showToast(`Suchlauf fehlgeschlagen: ${e?.message || String(e)}`, 'error');
    } finally {
      setIsScanning(false);
    }
  };

  const handleToggleForegroundService = async () => {
    try {
      if (isForegroundNotif) {
        await PrinterAgent.stopForegroundService();
        setIsForegroundNotif(false);
        setIsRunning(false);
        showToast('Hintergrunddienst gestoppt');
      } else {
        await PrinterAgent.requestNotificationPermission();
        await PrinterAgent.startForegroundService();
        setIsForegroundNotif(true);
        setIsRunning(true);
        showToast('Hintergrunddienst gestartet');
      }
    } catch (e: any) {
      showToast(`Dienstfehler: ${e?.message || String(e)}`, 'error');
    }
  };

  /* ---------------------------------------------------------------- */

  const readyCount = printers.filter(p => printerInfoByIp[p.trim()]?.ready).length;
  const overall: { title: string; body: string; tone: Tone } = backendConnected
    ? {
        title: 'Verbunden',
        body: `Gekoppelt als „${agentName || 'Agent'}“ · ${readyCount} von ${printers.length} Drucker bereit`,
        tone: 'success',
      }
    : isPaired
      ? {
          title: 'Verbindung wird hergestellt…',
          body: `Gekoppelt als „${agentName || 'Agent'}“`,
          tone: 'warning',
        }
      : {
          title: 'Nicht gekoppelt',
          body: 'Kopplungs-PIN aus dem QuixPOS-Dashboard eingeben, um Druckaufträge zu empfangen.',
          tone: 'neutral',
        };
  const overallColors = toneColors(t, overall.tone);

  const editTitle =
    edit?.kind === 'api'
      ? 'QuixPOS-API-URL'
      : edit?.kind === 'port'
        ? 'Lokaler Service-Port'
        : edit?.index === null
          ? 'Drucker hinzufügen'
          : 'Drucker bearbeiten';

  return (
    <View style={[styles.root, { backgroundColor: t.surface }]}>
      <StatusBar barStyle={t.dark ? 'light-content' : 'dark-content'} />

      <TopAppBar
        title="QuixPOS Printer Agent"
        topInset={insets.top}
        scrolled={scrolled}
        onMenuPress={() => setMenuOpen(true)}
      />

      <ScrollView
        style={styles.flex}
        contentContainerStyle={{
          paddingBottom: insets.bottom + 24,
          paddingLeft: insets.left,
          paddingRight: insets.right,
        }}
        keyboardShouldPersistTaps="handled"
        scrollEventThrottle={16}
        onScroll={e => setScrolled(e.nativeEvent.contentOffset.y > 0)}>
        {/* Status card */}
        <View style={[styles.statusCard, { backgroundColor: overallColors.container }]}>
          <View style={[styles.statusIndicator, { borderColor: overallColors.onContainer }]}>
            <View style={[styles.statusIndicatorDot, { backgroundColor: overallColors.onContainer }]} />
          </View>
          <View style={styles.flex}>
            <Text style={[type.titleMedium, { color: overallColors.onContainer }]}>
              {overall.title}
            </Text>
            <Text style={[type.bodyMedium, { color: overallColors.onContainer }]}>
              {overall.body}
            </Text>
          </View>
        </View>

        {/* Pairing */}
        <SectionHeader title="Kopplung" />
        {isPaired ? (
          <ListItem
            headline={agentName || 'Agent'}
            supporting={backendConnected ? 'Mit QuixPOS verbunden' : 'Verbindung wird hergestellt…'}
            trailing={
              <Button
                variant="text"
                destructive
                label="Trennen"
                onPress={() => setConfirmUnpair(true)}
              />
            }
          />
        ) : (
          <View style={styles.pairRow}>
            <TextField
              style={styles.flex}
              label="Kopplungs-PIN"
              containerColor={t.surface}
              value={pairPin}
              onChangeText={setPairPin}
              placeholder="6-stellig"
              autoCapitalize="characters"
              autoCorrect={false}
              maxLength={12}
              onSubmitEditing={handlePair}
              returnKeyType="done"
            />
            <Button label="Koppeln" onPress={handlePair} loading={isPairing} />
          </View>
        )}

        <Divider />

        {/* Printers */}
        <SectionHeader title="Drucker" />
        {printers.length === 0 && (
          <ListItem
            headline="Keine Drucker eingerichtet"
            supporting="Suche nach Druckern im Netzwerk und per Bluetooth oder füge einen manuell hinzu."
          />
        )}
        {printers.map((addr, idx) => {
          const info = printerInfoByIp[addr.trim()];
          const status = getPrinterStatusDisplay(info);
          const colors = toneColors(t, status.tone);
          const ble = isBleAddress(addr);
          const details = [describeAddress(addr), info?.serial ? `SN ${info.serial}` : null]
            .filter(Boolean)
            .join(' · ');
          return (
            <ListItem
              key={`${addr}-${idx}`}
              headline={info?.model || describeAddress(addr)}
              supporting={info?.model ? details : status.text}
              supportingColor={info?.model ? undefined : colors.fg}
              extra={
                info?.model
                  ? status.errorAlert || status.text
                  : status.errorAlert
              }
              extraColor={colors.fg}
              leading={
                <Avatar
                  label={ble ? 'BT' : 'IP'}
                  background={colors.container}
                  color={colors.onContainer}
                />
              }
              onPress={() => setEdit({ kind: 'printer', index: idx, value: addr })}
            />
          );
        })}
        <ListItem
          headline={isScanning ? 'Suche läuft…' : 'Nach Druckern suchen'}
          supporting="Bluetooth und Netzwerk (TCP 9100)"
          disabled={isScanning}
          leading={
            isScanning ? (
              <ActivityIndicator color={t.primary} />
            ) : (
              <SearchIcon color={t.onSurfaceVariant} />
            )
          }
          onPress={handleScanPrinters}
        />
        <ListItem
          headline="Drucker manuell hinzufügen"
          leading={<PlusIcon color={t.onSurfaceVariant} />}
          onPress={() => setEdit({ kind: 'printer', index: null, value: '' })}
        />

        <Divider />

        {/* Background service */}
        <SectionHeader title="Hintergrunddienst" />
        <ListItem
          headline="In Statusleiste ausführen"
          supporting={
            isForegroundNotif
              ? 'Aktiv – Druckaufträge werden auch im Hintergrund empfangen'
              : isRunning
                ? 'Agent läuft, aber ohne Benachrichtigung'
                : 'Gestoppt'
          }
          trailing={
            <Switch value={isForegroundNotif} onValueChange={handleToggleForegroundService} />
          }
          onPress={handleToggleForegroundService}
        />

        <Divider />

        {/* Connection */}
        <SectionHeader title="Verbindung" />
        <ListItem
          headline="QuixPOS-API-URL"
          supporting={apiUrl}
          onPress={() => setEdit({ kind: 'api', value: apiUrl })}
        />
        <ListItem
          headline="Lokaler Service-Port"
          supporting={port}
          onPress={() => setEdit({ kind: 'port', value: port })}
        />
      </ScrollView>

      <Snackbar message={snackbar} bottom={insets.bottom + 12} />

      <OverflowMenu
        visible={menuOpen}
        top={insets.top + 8}
        onDismiss={() => setMenuOpen(false)}
        items={[
          { label: 'Nach Druckern suchen', onPress: handleScanPrinters },
          { label: 'Beenden', onPress: handleClose },
        ]}
      />

      {/* Edit dialog (API URL / port / printer) */}
      <Dialog
        visible={!!edit}
        title={editTitle}
        onDismiss={() => !isSaving && setEdit(null)}
        actions={
          <>
            {edit?.kind === 'printer' && edit.index !== null && (
              <Button
                variant="text"
                destructive
                label="Entfernen"
                disabled={isSaving}
                style={styles.dialogLeadingAction}
                onPress={() => edit.index !== null && handleRemovePrinter(edit.index)}
              />
            )}
            <Button
              variant="text"
              label="Abbrechen"
              disabled={isSaving}
              onPress={() => setEdit(null)}
            />
            <Button variant="text" label="Speichern" loading={isSaving} onPress={handleSaveEdit} />
          </>
        }>
        {edit && (
          <>
            <TextField
              label={
                edit.kind === 'api' ? 'URL' : edit.kind === 'port' ? 'Port' : 'Adresse'
              }
              containerColor={t.surfaceContainerHigh}
              value={edit.value}
              onChangeText={value => setEdit({ ...edit, value })}
              autoFocus
              autoCapitalize="none"
              autoCorrect={false}
              keyboardType={
                edit.kind === 'port' ? 'number-pad' : edit.kind === 'api' ? 'url' : 'default'
              }
              placeholder={
                edit.kind === 'api'
                  ? DEFAULT_API_URL
                  : edit.kind === 'port'
                    ? DEFAULT_PORT
                    : 'z. B. 192.168.1.100'
              }
              helper={
                edit.kind === 'printer'
                  ? 'IP-Adresse, „ble“ für automatische Bluetooth-Suche oder ble:<MAC-Adresse>'
                  : edit.kind === 'port'
                    ? 'Port des lokalen Monitor-Servers auf diesem Gerät'
                    : undefined
              }
              onSubmitEditing={handleSaveEdit}
            />
            {edit.kind === 'printer' && (
              <View style={styles.chipRow}>
                <Button
                  variant="outlined"
                  label="Bluetooth automatisch"
                  onPress={() => setEdit({ ...edit, value: 'ble' })}
                />
              </View>
            )}
          </>
        )}
      </Dialog>

      {/* Scan results */}
      <Dialog
        visible={showDiscovered}
        title={`${discoveredPrinters.length} Drucker gefunden`}
        onDismiss={() => setShowDiscovered(false)}
        scrollable
        actions={
          <Button variant="text" label="Schließen" onPress={() => setShowDiscovered(false)} />
        }>
        {discoveredPrinters.map(p => {
          const added = printers.some(r => r.trim() === p.ip.trim());
          return (
            <ListItem
              key={p.ip}
              headline={p.model || 'Drucker'}
              supporting={[p.ip, p.serial ? `SN ${p.serial}` : null]
                .filter(Boolean)
                .join(' · ')}
              extra={added ? 'Bereits eingerichtet' : null}
              disabled={added || isSaving}
              leading={
                <Avatar
                  label={isBleAddress(p.ip) ? 'BT' : 'IP'}
                  background={t.secondaryContainer}
                  color={t.onSecondaryContainer}
                />
              }
              onPress={async () => {
                setShowDiscovered(false);
                await addPrinter(p);
              }}
            />
          );
        })}
      </Dialog>

      {/* Unpair confirmation */}
      <Dialog
        visible={confirmUnpair}
        title="Agent trennen?"
        onDismiss={() => setConfirmUnpair(false)}
        actions={
          <>
            <Button variant="text" label="Abbrechen" onPress={() => setConfirmUnpair(false)} />
            <Button variant="text" destructive label="Trennen" onPress={handleUnpair} />
          </>
        }>
        <Text style={[type.bodyMedium, { color: t.onSurfaceVariant }]}>
          Das Zugangstoken wird gelöscht und die Verbindung zu QuixPOS beendet. Zum erneuten
          Koppeln wird eine neue PIN benötigt.
        </Text>
      </Dialog>
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

const styles = StyleSheet.create({
  root: {
    flex: 1,
  },
  flex: {
    flex: 1,
  },
  statusCard: {
    flexDirection: 'row',
    alignItems: 'center',
    marginHorizontal: 16,
    marginTop: 8,
    padding: 16,
    borderRadius: 12,
    gap: 16,
  },
  statusIndicator: {
    width: 24,
    height: 24,
    borderRadius: 12,
    borderWidth: 2,
    alignItems: 'center',
    justifyContent: 'center',
  },
  statusIndicatorDot: {
    width: 10,
    height: 10,
    borderRadius: 5,
  },
  pairRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    paddingHorizontal: 16,
    paddingTop: 8,
    paddingBottom: 8,
  },
  chipRow: {
    flexDirection: 'row',
    marginTop: 16,
  },
  dialogLeadingAction: {
    marginRight: 'auto',
  },
});
