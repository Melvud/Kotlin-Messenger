import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

class CallActionButton extends StatelessWidget {
  final IconData icon;
  final VoidCallback onPressed;
  final bool active;
  final bool destructive;
  final String semanticsLabel;

  const CallActionButton({
    super.key,
    required this.icon,
    required this.onPressed,
    required this.semanticsLabel,
    this.active = true,
    this.destructive = false,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final bg = destructive
        ? cs.error
        : (active ? cs.primary : cs.surfaceVariant);
    final fg = destructive
        ? cs.onError
        : (active ? cs.onPrimary : cs.onSurfaceVariant);

    return Semantics(
      button: true,
      enabled: true,
      label: semanticsLabel,
      child: Material(
        color: bg,
        shape: const CircleBorder(),
        elevation: 2,
        child: InkWell(
          customBorder: const CircleBorder(),
          onTap: () {
            HapticFeedback.selectionClick();
            onPressed();
          },
          child: Padding(
            padding: const EdgeInsets.all(18.0),
            child: Icon(icon, color: fg, size: 28),
          ),
        ),
      ),
    );
  }
}
