import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:qingjing_design_tokens/qingjing_design_tokens.dart';

typedef T = QingjingWallpaperTokens;

const qjSystemUiOverlayStyle = SystemUiOverlayStyle(
  statusBarColor: Colors.transparent,
  statusBarBrightness: Brightness.light,
  statusBarIconBrightness: Brightness.dark,
  systemStatusBarContrastEnforced: false,
  systemNavigationBarColor: T.colorSurface,
  systemNavigationBarDividerColor: Colors.transparent,
  systemNavigationBarIconBrightness: Brightness.dark,
  systemNavigationBarContrastEnforced: false,
);

abstract final class QjTheme {
  static TextStyle type(
    double size,
    FontWeight weight,
    double height, [
    Color color = T.colorInk,
  ]) => TextStyle(
    fontSize: size,
    fontWeight: weight,
    height: height,
    color: color,
  );

  static ThemeData get light => ThemeData(
    useMaterial3: true,
    brightness: Brightness.light,
    scaffoldBackgroundColor: T.colorSurface,
    colorScheme: const ColorScheme.light(
      primary: T.colorNavigation,
      onPrimary: T.colorInverseInk,
      primaryContainer: T.colorAccentSoft,
      onPrimaryContainer: T.colorInk,
      secondary: T.colorAccent,
      onSecondary: T.colorInk,
      secondaryContainer: T.colorAccentSoft,
      onSecondaryContainer: T.colorInk,
      tertiary: T.colorSuccess,
      onTertiary: T.colorInverseInk,
      error: T.colorDanger,
      onError: T.colorInverseInk,
      errorContainer: T.colorDangerSoft,
      onErrorContainer: T.colorDanger,
      surface: T.colorSurface,
      onSurface: T.colorInk,
      onSurfaceVariant: T.colorMutedInk,
      surfaceContainerLowest: T.colorSurface,
      surfaceContainerLow: T.colorSurfaceMuted,
      surfaceContainer: T.colorSurfaceMuted,
      surfaceContainerHigh: T.colorSurfaceStrong,
      surfaceContainerHighest: T.colorSurfaceStrong,
      outline: T.colorOutline,
      outlineVariant: T.colorOutlineStrong,
      scrim: T.colorScrim,
      surfaceTint: Colors.transparent,
    ),
    textTheme: TextTheme(
      displaySmall: type(
        T.fontSizeDisplay,
        FontWeight.w800,
        T.lineHeightDisplay,
      ),
      headlineMedium: type(
        T.fontSizePageTitle,
        FontWeight.w800,
        T.lineHeightTitle,
      ),
      titleLarge: type(
        T.fontSizeSectionTitle,
        FontWeight.w700,
        T.lineHeightSection,
      ),
      titleMedium: type(
        T.fontSizeCardTitle,
        FontWeight.w700,
        T.lineHeightSection,
      ),
      bodyLarge: type(T.fontSizeBodyLarge, FontWeight.w400, T.lineHeightBody),
      bodyMedium: type(T.fontSizeBody, FontWeight.w400, T.lineHeightBody),
      bodySmall: type(
        T.fontSizeCaption,
        FontWeight.w500,
        T.lineHeightCaption,
        T.colorMutedInk,
      ),
      labelLarge: type(
        T.fontSizeBodyLarge,
        FontWeight.w700,
        T.lineHeightSection,
      ),
      labelMedium: type(
        T.fontSizeCaptionLarge,
        FontWeight.w500,
        T.lineHeightCaption,
      ),
      labelSmall: type(T.fontSizeMicro, FontWeight.w700, T.lineHeightCaption),
    ),
    appBarTheme: AppBarTheme(
      backgroundColor: T.colorSurface,
      foregroundColor: T.colorInk,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      scrolledUnderElevation: 0,
      systemOverlayStyle: qjSystemUiOverlayStyle,
      toolbarHeight: T.sizeTopBar,
      titleTextStyle: type(
        T.fontSizePageTitle,
        FontWeight.w800,
        T.lineHeightTitle,
      ),
    ),
    filledButtonTheme: FilledButtonThemeData(style: action()),
    elevatedButtonTheme: ElevatedButtonThemeData(style: action()),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        foregroundColor: T.colorInk,
        backgroundColor: T.colorSurface,
        minimumSize: const Size(0, T.sizeTouchTargetMin),
        shape: const StadiumBorder(),
        side: const BorderSide(color: T.colorOutlineStrong),
        textStyle: type(T.fontSizeBody, FontWeight.w700, T.lineHeightSection),
      ),
    ),
    textButtonTheme: TextButtonThemeData(
      style: TextButton.styleFrom(
        foregroundColor: T.colorInk,
        minimumSize: const Size(0, T.sizeTouchTargetMin),
      ),
    ),
    cardTheme: CardThemeData(
      color: T.colorSurface,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      margin: EdgeInsets.zero,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(T.radiusCard),
      ),
    ),
    bottomSheetTheme: const BottomSheetThemeData(
      backgroundColor: T.colorSurface,
      surfaceTintColor: Colors.transparent,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(
          top: Radius.circular(T.radiusSheet),
        ),
      ),
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: T.colorSurfaceMuted,
      contentPadding: const EdgeInsets.symmetric(horizontal: 15, vertical: 15),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(T.radiusControl),
        borderSide: const BorderSide(color: T.colorOutlineStrong),
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(T.radiusControl),
        borderSide: const BorderSide(color: T.colorAccentStrong),
      ),
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(T.radiusControl),
      ),
      hintStyle: type(
        T.fontSizeBody,
        FontWeight.w400,
        T.lineHeightBody,
        T.colorMutedInk,
      ),
    ),
    dividerColor: T.colorOutline,
  );

  static ButtonStyle action({bool accent = false}) => FilledButton.styleFrom(
    backgroundColor: accent ? T.colorAccent : T.colorNavigation,
    foregroundColor: accent ? T.colorInk : T.colorInverseInk,
    disabledBackgroundColor: T.colorSurfaceStrong,
    disabledForegroundColor: T.colorSubtleInk,
    minimumSize: const Size(0, T.sizePrimaryControl),
    padding: const EdgeInsets.symmetric(horizontal: T.space6),
    shape: const StadiumBorder(),
    elevation: 0,
    animationDuration: T.durationFast,
    textStyle: type(T.fontSizeBodyLarge, FontWeight.w700, T.lineHeightSection),
  );
}
