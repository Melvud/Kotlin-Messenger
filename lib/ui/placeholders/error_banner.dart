import 'package:flutter/material.dart';

class ErrorBanner extends StatelessWidget {
  final String message;
  final VoidCallback? onRetry;
  const ErrorBanner({super.key, required this.message, this.onRetry});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return MaterialBanner(
      backgroundColor: cs.errorContainer,
      leading: Icon(Icons.error_outline, color: cs.onErrorContainer),
      content: Text(message, style: TextStyle(color: cs.onErrorContainer)),
      actions: [
        if (onRetry != null)
          TextButton(onPressed: onRetry, child: Text(MaterialLocalizations.of(context).viewLicensesButtonLabel)),
      ],
    );
  }
}
