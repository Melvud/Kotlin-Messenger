import 'package:flutter/material.dart';

class UserAvatar extends StatelessWidget {
  final String displayName;
  final double radius;
  final String? email;
  const UserAvatar({super.key, required this.displayName, this.email, this.radius = 24});

  static Color _colorFrom(String input, ColorScheme scheme) {
    final hash = input.codeUnits.fold<int>(0, (p, c) => p + c);
    final colors = [
      scheme.primary, scheme.secondary, scheme.tertiary,
      scheme.primaryContainer, scheme.secondaryContainer, scheme.tertiaryContainer
    ];
    return colors[hash % colors.length];
  }

  @override
  Widget build(BuildContext context) {
    final initials = displayName.trim().isEmpty
        ? '?'
        : displayName.trim().split(RegExp(r'\s+')).map((s) => s[0]).take(2).join().toUpperCase();
    final bg = _colorFrom(displayName + (email ?? ''), Theme.of(context).colorScheme);
    final onBg = ThemeData.estimateBrightnessForColor(bg) == Brightness.dark
        ? Colors.white
        : Colors.black87;

    return CircleAvatar(
      radius: radius,
      backgroundColor: bg,
      child: Text(initials, style: TextStyle(color: onBg, fontWeight: FontWeight.w700)),
    );
  }
}
