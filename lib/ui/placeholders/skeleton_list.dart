import 'package:flutter/material.dart';

class SkeletonList extends StatelessWidget {
  final int count;
  const SkeletonList({super.key, this.count = 8});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return ListView.separated(
      itemCount: count,
      separatorBuilder: (_, __) => const Divider(height: 1),
      itemBuilder: (_, __) => ListTile(
        leading: CircleAvatar(backgroundColor: cs.surfaceVariant, radius: 24),
        title: Container(height: 14, width: 140, color: cs.surfaceVariant),
        subtitle: Padding(
          padding: const EdgeInsets.only(top: 8.0),
          child: Container(height: 12, width: 220, color: cs.surfaceVariant),
        ),
        trailing: Row(mainAxisSize: MainAxisSize.min, children: [
          Container(width: 40, height: 40, decoration: BoxDecoration(color: cs.surfaceVariant, shape: BoxShape.circle)),
          const SizedBox(width: 8),
          Container(width: 40, height: 40, decoration: BoxDecoration(color: cs.surfaceVariant, shape: BoxShape.circle)),
        ]),
      ),
    );
  }
}
