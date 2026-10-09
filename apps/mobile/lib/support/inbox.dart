import 'dart:async';
import 'package:flutter/material.dart';
import 'package:qingjing_customer_support/customer_support.dart';

class CustomerSupportInbox extends ChangeNotifier with WidgetsBindingObserver {
  CustomerSupportInbox(this.api);
  final SupportApi api;
  int unread = 0;
  bool started = false, active = true, running = false, alive = true;
  Timer? timer;
  int delay = 2;
  void start() {
    if (started) return;
    started = true;
    WidgetsBinding.instance.addObserver(this);
    unawaited(refresh());
  }

  Future<void> refresh() async {
    timer?.cancel();
    if (!alive || !started || !active || running) return;
    running = true;
    try {
      final conversation = await api.conversation();
      final value = (conversation['unreadCount'] as num).toInt();
      if (alive && unread != value) {
        unread = value;
        notifyListeners();
      }
      delay = 2;
    } catch (_) {
      delay = (delay * 2).clamp(2, 30);
    } finally {
      running = false;
      if (alive && active) timer = Timer(Duration(seconds: delay), refresh);
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    active = state == AppLifecycleState.resumed;
    timer?.cancel();
    if (active) {
      delay = 2;
      unawaited(refresh());
    }
  }

  @override
  void dispose() {
    alive = false;
    timer?.cancel();
    if (started) WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }
}

class CustomerSupportScope extends InheritedWidget {
  const CustomerSupportScope({
    super.key,
    required this.api,
    this.inbox,
    required super.child,
  });
  final SupportApi api;
  final CustomerSupportInbox? inbox;
  static SupportApi? of(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<CustomerSupportScope>()?.api;
  static CustomerSupportInbox? inboxOf(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<CustomerSupportScope>()?.inbox;
  @override
  bool updateShouldNotify(CustomerSupportScope old) =>
      api != old.api || inbox != old.inbox;
}

class CustomerSupportUnreadBadge extends StatelessWidget {
  const CustomerSupportUnreadBadge({super.key, required this.child});
  final Widget child;
  @override
  Widget build(BuildContext context) {
    final inbox = CustomerSupportScope.inboxOf(context);
    if (inbox == null) return child;
    return AnimatedBuilder(
      animation: inbox,
      builder: (_, _) => Badge(
        isLabelVisible: inbox.unread > 0,
        label: Text(inbox.unread > 99 ? '99+' : '${inbox.unread}'),
        child: child,
      ),
    );
  }
}
