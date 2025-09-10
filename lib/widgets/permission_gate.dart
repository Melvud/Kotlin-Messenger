import 'package:flutter/material.dart';

class PermissionGate extends StatelessWidget {
  final String message;
  final VoidCallback onRetry;
  const PermissionGate({super.key, required this.message, required this.onRetry});

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24.0),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.lock, size: 64, color: Theme.of(context).colorScheme.outline),
            const SizedBox(height: 16),
            Text(message, textAlign: TextAlign.center, style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 12),
            FilledButton(onPressed: onRetry, child: Text(MaterialLocalizations.of(context).okButtonLabel)),
          ],
        ),
      ),
    );
  }
}
