import 'package:flutter/material.dart';
import 'package:flutter_colorpicker/flutter_colorpicker.dart';

/// Minimal colour picker field that pops a Material 3 dialog hosting the
/// `flutter_colorpicker` HSV picker. Used for primary/accent/background
/// theme overrides + the floating-overlay alpha tint.
class ColorPickerField extends StatelessWidget {
  const ColorPickerField({
    super.key,
    required this.label,
    required this.color,
    required this.onColorChanged,
  });

  final String label;
  final Color color;
  final ValueChanged<Color> onColorChanged;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Row(
        children: [
          Expanded(child: Text(label)),
          GestureDetector(
            onTap: () => _open(context),
            child: Container(
              width: 40,
              height: 28,
              decoration: BoxDecoration(
                color: color,
                borderRadius: BorderRadius.circular(8),
                border: Border.all(
                  // ignore: deprecated_member_use
                  color: Colors.white.withOpacity(0.2),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _open(BuildContext context) async {
    Color current = color;
    final picked = await showDialog<Color>(
      context: context,
      builder: (_) {
        return AlertDialog(
          title: Text('Pick $label'),
          content: SingleChildScrollView(
            child: ColorPicker(
              pickerColor: current,
              onColorChanged: (c) => current = c,
              enableAlpha: false,
              labelTypes: const [],
              pickerAreaHeightPercent: 0.6,
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context),
              child: const Text('Cancel'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(context, current),
              child: const Text('Use'),
            ),
          ],
        );
      },
    );
    if (picked != null) onColorChanged(picked);
  }
}
