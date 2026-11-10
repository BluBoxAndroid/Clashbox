import 'dart:async';

import 'package:clashbox/core/controller.dart';
import 'package:clashbox/core/lib.dart';
import 'package:clashbox/core/method.dart';
import 'package:clashbox/l10n/l10n.dart';
import 'package:clashbox/models/models.dart';
import 'package:clashbox/pages/home.dart';
import 'package:clashbox/plugins/service.dart';
import 'package:clashbox/providers/providers.dart';
import 'package:clashbox/state.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import '../helpers/test_app.dart';
import '../helpers/test_profiles.dart';

// A Service stand-in at the platform-channel seam: [silent] never answers,
// which is the failure the first frame must survive.
class _FakeService implements Service {
  _FakeService({this.silent = false});

  final bool silent;
  final invokedMethods = <String>[];

  @override
  Future<String> init() {
    if (silent) {
      return Completer<String>().future;
    }
    return Future.value('');
  }

  @override
  Future<String> syncState(SharedState state) async => '';

  @override
  Future<bool> shutdown() async => true;

  @override
  Future<bool> start() async => true;

  @override
  Future<bool> stop() async => true;

  @override
  Future<DateTime?> getRunTime() async => null;

  @override
  Future<CoreMethodResponse> invokeMethod(CoreMethodCall call) {
    invokedMethods.add(call.method.wireName);
    if (silent) {
      return Completer<CoreMethodResponse>().future;
    }
    final result = switch (call.method) {
      CoreMethod.proxyList => {'proxies': <String, dynamic>{}, 'all': <String>[]},
      CoreMethod.trafficCurrent || CoreMethod.trafficTotal => {
        'up': 0,
        'down': 0,
      },
      CoreMethod.connectionList => {'connections': <dynamic>[]},
      CoreMethod.connectionCount => 0,
      CoreMethod.routeWatch => {'core-epoch': 1, 'route-version': 1},
      _ => null,
    };
    return Future.value(
      CoreMethodResponse(id: call.id, result: result),
    );
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

Future<ProviderContainer> _bootContainer(_FakeService service) async {
  final container = ProviderContainer(
    overrides: [
      coreHandlerProvider.overrideWithValue(
        CoreController.scoped(CoreLib.scoped(service)),
      ),
      profilesProvider.overrideWith(() => TestProfiles(const [])),
    ],
  );
  globalState.container = container;
  container
      .read(viewSizeProvider.notifier)
      .update((_) => const Size(1200, 900));
  return container;
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUpAll(() async {
    await AppLocalizations.load(const Locale('en'));
  });

  tearDown(() {
    CoreLib.resetInstance();
  });

  testWidgets('home renders when the core answers normally', (tester) async {
    final service = _FakeService();
    final container = await _bootContainer(service);
    addTearDown(container.dispose);

    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: const TestApp(child: HomePage()),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(seconds: 2));

    expect(tester.takeException(), isNull);
    expect(find.byType(HomePage), findsOneWidget);
    expect(tester.getSize(find.byType(HomePage)).height, greaterThan(0));
    expect(find.byType(WidgetsApp), findsOneWidget);

    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('first frame renders even when the core never answers', (
    tester,
  ) async {
    final service = _FakeService(silent: true);
    final container = await _bootContainer(service);
    addTearDown(container.dispose);

    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: const TestApp(child: HomePage()),
      ),
    );
    // A single pump is the first frame: it must already contain the shell.
    await tester.pump();

    expect(tester.takeException(), isNull);
    expect(find.byType(HomePage), findsOneWidget);
    expect(tester.getSize(find.byType(HomePage)).height, greaterThan(0));

    // Let every core timeout fire; the shell must stay up and error-free.
    await tester.pump(const Duration(seconds: 30));
    expect(tester.takeException(), isNull);
    expect(find.byType(HomePage), findsOneWidget);

    await tester.pumpWidget(const SizedBox.shrink());
  });
}
