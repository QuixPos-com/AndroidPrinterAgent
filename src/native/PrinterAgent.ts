import { NativeEventEmitter, NativeModules } from 'react-native';

export interface PrinterErrors {
  headOpen: boolean;
  pause: boolean;
  other: boolean;
  unreachable: boolean;
}

export interface PrinterInfo {
  ip: string;
  serial: string;
  model: string;
  type: string;
  labelLength: number | null;
  errors?: PrinterErrors;
  ready: boolean;
  status: string;
}

export interface AgentConfig {
  api_url: string;
  printer_ip: string;
  zpl_printers: string[];
  port: string;
  token: string;
  local_addr: string;
  name: string;
  agent_id: number;
}

export interface AgentSnapshot {
  running: boolean;
  agentVersion: string;
  apiUrl: string;
  port: string;
  paired: boolean;
  agentId?: number;
  agentName?: string;
  backendConnected: boolean;
  printerIp: string;
  printerSerial?: string;
  printerModel?: string;
  printerType: string;
  printerReady: boolean;
  printerStatus: string;
  labelLength?: number | null;
  errors?: PrinterErrors;
  fault?: string;
  printers?: PrinterInfo[];
}

export interface ServiceStatus {
  running: boolean;
  background: boolean;
}

interface PrinterAgentNativeModule {
  requestNotificationPermission(): Promise<boolean>;
  startForegroundService(): Promise<string>;
  stopForegroundService(): Promise<string>;
  getServiceStatus(): Promise<ServiceStatus>;
  getConfig(): Promise<AgentConfig>;
  saveConfig(apiUrl: string, zplPrinters: string[], port: string | null): Promise<AgentSnapshot>;
  pairAgent(pin: string, apiUrl: string | null): Promise<{
    success: boolean;
    agentId: number;
    name: string;
    companyId: number;
  }>;
  unpairAgent(): Promise<boolean>;
  scanLocalPrinters(): Promise<PrinterInfo[]>;
  getSnapshot(): Promise<AgentSnapshot>;
  getLogs(): Promise<string[]>;
  clearLogs(): Promise<boolean>;
  sendTestPrint(zpl: string): Promise<string>;
  addListener(eventName: string): void;
  removeListeners(count: number): void;
}

const { PrinterAgentModule } = NativeModules;

export const PrinterAgent = PrinterAgentModule as PrinterAgentNativeModule;
export const PrinterAgentEvents = new NativeEventEmitter(PrinterAgentModule);

