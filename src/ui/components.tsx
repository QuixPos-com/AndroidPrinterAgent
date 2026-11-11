import React, { useState } from 'react';
import {
  ActivityIndicator,
  Modal,
  Pressable,
  ScrollView,
  StyleProp,
  StyleSheet,
  Switch as RNSwitch,
  Text,
  TextInput,
  TextInputProps,
  View,
  ViewStyle,
  useWindowDimensions,
} from 'react-native';
import { type, useTheme } from './theme';

/* ------------------------------------------------------------------ */
/* Top app bar                                                         */
/* ------------------------------------------------------------------ */

export function TopAppBar({
  title,
  topInset,
  scrolled,
  onMenuPress,
}: {
  title: string;
  topInset: number;
  scrolled: boolean;
  onMenuPress?: () => void;
}) {
  const t = useTheme();
  return (
    <View
      style={{
        paddingTop: topInset,
        backgroundColor: scrolled ? t.surfaceContainer : t.surface,
      }}>
      <View style={styles.appBar}>
        <Text style={[type.titleLarge, styles.appBarTitle, { color: t.onSurface }]} numberOfLines={1}>
          {title}
        </Text>
        {onMenuPress && (
          <IconButton label="Weitere Optionen" onPress={onMenuPress}>
            <View style={styles.overflowIcon}>
              {[0, 1, 2].map(i => (
                <View key={i} style={[styles.overflowDot, { backgroundColor: t.onSurfaceVariant }]} />
              ))}
            </View>
          </IconButton>
        )}
      </View>
    </View>
  );
}

export function IconButton({
  label,
  onPress,
  children,
}: {
  label: string;
  onPress: () => void;
  children: React.ReactNode;
}) {
  const t = useTheme();
  return (
    <View style={styles.iconButtonClip}>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={label}
        onPress={onPress}
        android_ripple={{ color: t.ripple, borderless: false }}
        style={styles.iconButton}>
        {children}
      </Pressable>
    </View>
  );
}

/* ------------------------------------------------------------------ */
/* Overflow menu                                                       */
/* ------------------------------------------------------------------ */

export function OverflowMenu({
  visible,
  top,
  onDismiss,
  items,
}: {
  visible: boolean;
  top: number;
  onDismiss: () => void;
  items: { label: string; onPress: () => void }[];
}) {
  const t = useTheme();
  return (
    <Modal visible={visible} transparent animationType="fade" onRequestClose={onDismiss}>
      <Pressable style={StyleSheet.absoluteFill} onPress={onDismiss}>
        <View style={[styles.menu, { top, backgroundColor: t.surfaceContainer }]}>
          {items.map(item => (
            <Pressable
              key={item.label}
              android_ripple={{ color: t.ripple }}
              style={styles.menuItem}
              onPress={() => {
                onDismiss();
                item.onPress();
              }}>
              <Text style={[type.bodyLarge, { color: t.onSurface }]}>{item.label}</Text>
            </Pressable>
          ))}
        </View>
      </Pressable>
    </Modal>
  );
}

/* ------------------------------------------------------------------ */
/* Lists                                                               */
/* ------------------------------------------------------------------ */

export function SectionHeader({ title }: { title: string }) {
  const t = useTheme();
  return <Text style={[type.titleSmall, styles.sectionHeader, { color: t.primary }]}>{title}</Text>;
}

export function ListItem({
  headline,
  supporting,
  supportingColor,
  extra,
  extraColor,
  leading,
  trailing,
  onPress,
  disabled,
}: {
  headline: string;
  supporting?: string;
  supportingColor?: string;
  extra?: string | null;
  extraColor?: string;
  leading?: React.ReactNode;
  trailing?: React.ReactNode;
  onPress?: () => void;
  disabled?: boolean;
}) {
  const t = useTheme();
  const content = (
    <>
      {leading && <View style={styles.listLeading}>{leading}</View>}
      <View style={styles.listText}>
        <Text style={[type.bodyLarge, { color: disabled ? t.outline : t.onSurface }]} numberOfLines={1}>
          {headline}
        </Text>
        {!!supporting && (
          <Text
            style={[type.bodyMedium, { color: supportingColor || t.onSurfaceVariant }]}
            numberOfLines={2}>
            {supporting}
          </Text>
        )}
        {!!extra && (
          <Text style={[type.bodyMedium, { color: extraColor || t.onSurfaceVariant }]}>{extra}</Text>
        )}
      </View>
      {trailing && <View style={styles.listTrailing}>{trailing}</View>}
    </>
  );

  if (!onPress) {
    return <View style={styles.listItem}>{content}</View>;
  }
  return (
    <Pressable
      onPress={onPress}
      disabled={disabled}
      android_ripple={{ color: t.ripple }}
      style={styles.listItem}>
      {content}
    </Pressable>
  );
}

export function Avatar({
  label,
  background,
  color,
}: {
  label: string;
  background: string;
  color: string;
}) {
  return (
    <View style={[styles.avatar, { backgroundColor: background }]}>
      <Text style={[type.labelLarge, { color }]}>{label}</Text>
    </View>
  );
}

/** Minimal 24dp icons drawn with views (no icon font dependency). */
export function SearchIcon({ color }: { color: string }) {
  return (
    <View style={styles.icon24}>
      <View style={[styles.searchRing, { borderColor: color }]} />
      <View style={[styles.searchHandle, { backgroundColor: color }]} />
    </View>
  );
}

export function PlusIcon({ color }: { color: string }) {
  return (
    <View style={[styles.icon24, styles.centered]}>
      <View style={[styles.plusH, { backgroundColor: color }]} />
      <View style={[styles.plusV, { backgroundColor: color }]} />
    </View>
  );
}

export function Divider({ inset = 0 }: { inset?: number }) {
  const t = useTheme();
  return <View style={[styles.divider, { marginLeft: inset, backgroundColor: t.outlineVariant }]} />;
}

export function Switch({ value, onValueChange }: { value: boolean; onValueChange: (v: boolean) => void }) {
  const t = useTheme();
  return (
    <RNSwitch
      value={value}
      onValueChange={onValueChange}
      trackColor={{ false: t.surfaceContainerHighest, true: t.primary }}
      thumbColor={value ? t.onPrimary : t.outline}
    />
  );
}

/* ------------------------------------------------------------------ */
/* Buttons                                                             */
/* ------------------------------------------------------------------ */

type ButtonVariant = 'filled' | 'tonal' | 'outlined' | 'text';

export function Button({
  label,
  onPress,
  variant = 'filled',
  disabled,
  loading,
  destructive,
  style,
}: {
  label: string;
  onPress: () => void;
  variant?: ButtonVariant;
  disabled?: boolean;
  loading?: boolean;
  destructive?: boolean;
  style?: StyleProp<ViewStyle>;
}) {
  const t = useTheme();
  const inactive = disabled || loading;

  let bg = 'transparent';
  let fg = destructive ? t.error : t.primary;
  let border: string | undefined;
  if (variant === 'filled') {
    bg = destructive ? t.error : t.primary;
    fg = destructive ? t.onError : t.onPrimary;
  } else if (variant === 'tonal') {
    bg = t.secondaryContainer;
    fg = t.onSecondaryContainer;
  } else if (variant === 'outlined') {
    border = t.outline;
  }
  if (inactive) {
    fg = t.dark ? 'rgba(225,226,232,0.38)' : 'rgba(25,28,32,0.38)';
    if (variant === 'filled' || variant === 'tonal') {
      bg = t.dark ? 'rgba(225,226,232,0.12)' : 'rgba(25,28,32,0.12)';
    }
    if (variant === 'outlined') {
      border = t.dark ? 'rgba(225,226,232,0.12)' : 'rgba(25,28,32,0.12)';
    }
  }

  return (
    <View
      style={[
        styles.buttonClip,
        { backgroundColor: bg },
        border ? [styles.buttonBorder, { borderColor: border }] : null,
        style,
      ]}>
      <Pressable
        accessibilityRole="button"
        onPress={onPress}
        disabled={inactive}
        android_ripple={{ color: variant === 'filled' ? 'rgba(255,255,255,0.16)' : t.ripple }}
        style={[styles.button, variant === 'text' && styles.buttonText]}>
        {loading && <ActivityIndicator size="small" color={fg} style={styles.buttonSpinner} />}
        <Text style={[type.labelLarge, { color: fg }]}>{label}</Text>
      </Pressable>
    </View>
  );
}

/* ------------------------------------------------------------------ */
/* Outlined text field                                                 */
/* ------------------------------------------------------------------ */

export function TextField({
  label,
  helper,
  containerColor,
  style,
  ...inputProps
}: TextInputProps & {
  label: string;
  helper?: string;
  containerColor: string;
}) {
  const t = useTheme();
  const [focused, setFocused] = useState(false);
  return (
    <View style={style}>
      <View
        style={[
          styles.field,
          focused ? styles.fieldFocused : styles.fieldIdle,
          { borderColor: focused ? t.primary : t.outline },
        ]}>
        <TextInput
          {...inputProps}
          onFocus={e => {
            setFocused(true);
            inputProps.onFocus?.(e);
          }}
          onBlur={e => {
            setFocused(false);
            inputProps.onBlur?.(e);
          }}
          placeholderTextColor={t.onSurfaceVariant}
          cursorColor={t.primary}
          selectionColor={t.primary + '55'}
          style={[type.bodyLarge, styles.fieldInput, { color: t.onSurface }]}
        />
        <Text
          style={[
            type.bodySmall,
            styles.fieldLabel,
            { backgroundColor: containerColor, color: focused ? t.primary : t.onSurfaceVariant },
          ]}>
          {label}
        </Text>
      </View>
      {!!helper && (
        <Text style={[type.bodySmall, styles.fieldHelper, { color: t.onSurfaceVariant }]}>{helper}</Text>
      )}
    </View>
  );
}

/* ------------------------------------------------------------------ */
/* Dialog                                                              */
/* ------------------------------------------------------------------ */

export function Dialog({
  visible,
  title,
  onDismiss,
  children,
  actions,
  scrollable,
}: {
  visible: boolean;
  title: string;
  onDismiss: () => void;
  children?: React.ReactNode;
  actions: React.ReactNode;
  scrollable?: boolean;
}) {
  const t = useTheme();
  const { width, height } = useWindowDimensions();
  return (
    <Modal
      visible={visible}
      transparent
      animationType="fade"
      statusBarTranslucent
      navigationBarTranslucent
      onRequestClose={onDismiss}>
      <View style={[styles.dialogBackdrop, { backgroundColor: t.scrim }]}>
        <Pressable style={StyleSheet.absoluteFill} onPress={onDismiss} />
        <View
          style={[
            styles.dialog,
            {
              backgroundColor: t.surfaceContainerHigh,
              width: Math.min(560, width - 48),
              maxHeight: height * 0.8,
            },
          ]}>
          <Text style={[type.headlineSmall, styles.dialogTitle, { color: t.onSurface }]}>{title}</Text>
          {scrollable ? (
            <ScrollView style={styles.dialogScroll}>{children}</ScrollView>
          ) : (
            <View style={styles.dialogBody}>{children}</View>
          )}
          <View style={styles.dialogActions}>{actions}</View>
        </View>
      </View>
    </Modal>
  );
}

/* ------------------------------------------------------------------ */
/* Snackbar                                                            */
/* ------------------------------------------------------------------ */

export function Snackbar({ message, bottom }: { message: string | null; bottom: number }) {
  const t = useTheme();
  if (!message) return null;
  return (
    <View
      pointerEvents="none"
      style={[styles.snackbar, { bottom, backgroundColor: t.inverseSurface }]}>
      <Text style={[type.bodyMedium, { color: t.inverseOnSurface }]}>{message}</Text>
    </View>
  );
}

/* ------------------------------------------------------------------ */

const styles = StyleSheet.create({
  appBar: {
    height: 64,
    flexDirection: 'row',
    alignItems: 'center',
    paddingLeft: 16,
    paddingRight: 4,
  },
  appBarTitle: {
    flex: 1,
  },
  iconButtonClip: {
    width: 48,
    height: 48,
    borderRadius: 24,
    overflow: 'hidden',
  },
  iconButton: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  overflowIcon: {
    height: 18,
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  overflowDot: {
    width: 4,
    height: 4,
    borderRadius: 2,
  },
  menu: {
    position: 'absolute',
    right: 12,
    minWidth: 160,
    borderRadius: 4,
    paddingVertical: 8,
    elevation: 3,
  },
  menuItem: {
    height: 48,
    justifyContent: 'center',
    paddingHorizontal: 12,
  },
  sectionHeader: {
    paddingHorizontal: 16,
    paddingTop: 24,
    paddingBottom: 8,
  },
  listItem: {
    flexDirection: 'row',
    alignItems: 'center',
    minHeight: 56,
    paddingVertical: 12,
    paddingLeft: 16,
    paddingRight: 24,
  },
  listLeading: {
    marginRight: 16,
    minWidth: 40,
    alignItems: 'center',
  },
  listText: {
    flex: 1,
  },
  listTrailing: {
    marginLeft: 16,
  },
  avatar: {
    width: 40,
    height: 40,
    borderRadius: 20,
    alignItems: 'center',
    justifyContent: 'center',
  },
  icon24: {
    width: 24,
    height: 24,
  },
  centered: {
    alignItems: 'center',
    justifyContent: 'center',
  },
  searchRing: {
    position: 'absolute',
    left: 3,
    top: 3,
    width: 13,
    height: 13,
    borderRadius: 7,
    borderWidth: 2,
  },
  searchHandle: {
    position: 'absolute',
    left: 13.5,
    top: 16,
    width: 8,
    height: 2,
    borderRadius: 1,
    transform: [{ rotate: '45deg' }],
  },
  plusH: {
    position: 'absolute',
    width: 14,
    height: 2,
  },
  plusV: {
    position: 'absolute',
    width: 2,
    height: 14,
  },
  divider: {
    height: StyleSheet.hairlineWidth,
  },
  buttonClip: {
    borderRadius: 20,
    overflow: 'hidden',
  },
  button: {
    minHeight: 40,
    paddingHorizontal: 24,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
  },
  buttonText: {
    paddingHorizontal: 12,
  },
  buttonSpinner: {
    marginRight: 8,
  },
  field: {
    minHeight: 56,
    borderRadius: 4,
    justifyContent: 'center',
  },
  fieldIdle: {
    borderWidth: 1,
    paddingHorizontal: 16,
  },
  fieldFocused: {
    borderWidth: 2,
    paddingHorizontal: 15,
  },
  buttonBorder: {
    borderWidth: 1,
  },
  fieldInput: {
    paddingVertical: 12,
    paddingHorizontal: 0,
  },
  fieldLabel: {
    position: 'absolute',
    top: -9,
    left: 12,
    paddingHorizontal: 4,
  },
  fieldHelper: {
    paddingHorizontal: 16,
    paddingTop: 4,
  },
  dialogBackdrop: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  dialog: {
    borderRadius: 28,
    paddingTop: 24,
    paddingBottom: 20,
    elevation: 6,
  },
  dialogTitle: {
    paddingHorizontal: 24,
    marginBottom: 16,
  },
  dialogBody: {
    paddingHorizontal: 24,
  },
  dialogScroll: {
    flexGrow: 0,
  },
  dialogActions: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
    alignItems: 'center',
    gap: 8,
    paddingHorizontal: 24,
    marginTop: 24,
  },
  snackbar: {
    position: 'absolute',
    left: 12,
    right: 12,
    minHeight: 48,
    borderRadius: 4,
    paddingHorizontal: 16,
    paddingVertical: 14,
    justifyContent: 'center',
    elevation: 6,
  },
});
