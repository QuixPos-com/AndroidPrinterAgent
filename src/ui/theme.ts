import { useColorScheme } from 'react-native';

/**
 * Material 3 colour roles (baseline blue tonal palette).
 * Follows the system light / dark setting.
 */
const light = {
  dark: false,
  primary: '#0061A4',
  onPrimary: '#FFFFFF',
  primaryContainer: '#D1E4FF',
  onPrimaryContainer: '#001D36',
  secondaryContainer: '#D7E3F7',
  onSecondaryContainer: '#101C2B',
  surface: '#F8F9FF',
  onSurface: '#191C20',
  onSurfaceVariant: '#43474E',
  surfaceContainerLow: '#F2F3FA',
  surfaceContainer: '#ECEEF4',
  surfaceContainerHigh: '#E6E8EE',
  surfaceContainerHighest: '#E0E2E8',
  outline: '#73777F',
  outlineVariant: '#C3C7CF',
  error: '#BA1A1A',
  onError: '#FFFFFF',
  errorContainer: '#FFDAD6',
  onErrorContainer: '#410002',
  success: '#1B6D2F',
  successContainer: '#A6F5A8',
  onSuccessContainer: '#002108',
  warning: '#7D5800',
  warningContainer: '#FFDEA6',
  onWarningContainer: '#271900',
  inverseSurface: '#2E3135',
  inverseOnSurface: '#EFF0F7',
  inversePrimary: '#9ECAFF',
  scrim: 'rgba(0, 0, 0, 0.32)',
  ripple: 'rgba(25, 28, 32, 0.12)',
};

export type Theme = typeof light;

const dark: Theme = {
  dark: true,
  primary: '#9ECAFF',
  onPrimary: '#003258',
  primaryContainer: '#00497D',
  onPrimaryContainer: '#D1E4FF',
  secondaryContainer: '#3B4858',
  onSecondaryContainer: '#D7E3F7',
  surface: '#111418',
  onSurface: '#E1E2E8',
  onSurfaceVariant: '#C3C7CF',
  surfaceContainerLow: '#191C20',
  surfaceContainer: '#1D2024',
  surfaceContainerHigh: '#272A2F',
  surfaceContainerHighest: '#32353A',
  outline: '#8D9199',
  outlineVariant: '#43474E',
  error: '#FFB4AB',
  onError: '#690005',
  errorContainer: '#93000A',
  onErrorContainer: '#FFDAD6',
  success: '#8BD88E',
  successContainer: '#005319',
  onSuccessContainer: '#A6F5A8',
  warning: '#F8BD48',
  warningContainer: '#5F4100',
  onWarningContainer: '#FFDEA6',
  inverseSurface: '#E1E2E8',
  inverseOnSurface: '#2E3135',
  inversePrimary: '#0061A4',
  scrim: 'rgba(0, 0, 0, 0.32)',
  ripple: 'rgba(225, 226, 232, 0.12)',
};

export const useTheme = (): Theme => (useColorScheme() === 'dark' ? dark : light);

export type Tone = 'success' | 'warning' | 'error' | 'neutral';

export const toneColors = (t: Theme, tone: Tone) => {
  switch (tone) {
    case 'success':
      return { fg: t.success, container: t.successContainer, onContainer: t.onSuccessContainer };
    case 'warning':
      return { fg: t.warning, container: t.warningContainer, onContainer: t.onWarningContainer };
    case 'error':
      return { fg: t.error, container: t.errorContainer, onContainer: t.onErrorContainer };
    default:
      return {
        fg: t.onSurfaceVariant,
        container: t.surfaceContainerHighest,
        onContainer: t.onSurface,
      };
  }
};

/** Material 3 type scale (Roboto is the Android system default). */
export const type = {
  headlineSmall: { fontSize: 24, lineHeight: 32, fontWeight: '400' as const },
  titleLarge: { fontSize: 22, lineHeight: 28, fontWeight: '400' as const },
  titleMedium: { fontSize: 16, lineHeight: 24, fontWeight: '500' as const, letterSpacing: 0.15 },
  titleSmall: { fontSize: 14, lineHeight: 20, fontWeight: '500' as const, letterSpacing: 0.1 },
  bodyLarge: { fontSize: 16, lineHeight: 24, fontWeight: '400' as const, letterSpacing: 0.5 },
  bodyMedium: { fontSize: 14, lineHeight: 20, fontWeight: '400' as const, letterSpacing: 0.25 },
  bodySmall: { fontSize: 12, lineHeight: 16, fontWeight: '400' as const, letterSpacing: 0.4 },
  labelLarge: { fontSize: 14, lineHeight: 20, fontWeight: '500' as const, letterSpacing: 0.1 },
  labelMedium: { fontSize: 12, lineHeight: 16, fontWeight: '500' as const, letterSpacing: 0.5 },
};
