import contextlib
import io
import json
import socket
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch


@contextlib.contextmanager
def server(respond):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_GET(self):
            self.do_POST()

        def do_POST(self):
            size = int(self.headers.get('Content-Length', 0))
            body = self.rfile.read(size)
            status, headers, value = respond(self.command, self.path, dict(self.headers), body)
            if status is None:
                self.connection.shutdown(socket.SHUT_RDWR)
                self.connection.close()
                return
            data = json.dumps(value).encode()
            self.send_response(status)
            for name, content in headers.items():
                self.send_header(name, content)
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            try:
                self.wfile.write(data)
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                pass
    http = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    thread = threading.Thread(target=http.serve_forever, daemon=True)
    thread.start()
    try:
        yield 'http://127.0.0.1:' + str(http.server_port)
    finally:
        http.shutdown()
        http.server_close()
        thread.join()

import payment_simulation as sim


class HttpTests(unittest.TestCase):
    def test_global_rate_is_shared_across_concurrent_requests(self):
        arrivals = []
        def respond(*args):
            arrivals.append(time.monotonic())
            return 200, {}, {'data': {}}
        with server(respond) as origin:
            transport = sim.HttpTransport(origin, [origin], rate=10)
            threads = [threading.Thread(target=transport, args=('GET', '/orders', 'u', None, None))
                       for _ in range(3)]
            for thread in threads:
                thread.start()
            for thread in threads:
                thread.join()
        self.assertEqual(len(arrivals), 3)
        self.assertGreaterEqual(max(arrivals) - min(arrivals), .17)


    def test_redirect_never_sends_authorization_to_second_origin(self):
        leaked = []
        with server(lambda *args: (leaked.append(args) or (200, {}, {'data': {}}))) as other:
            with server(lambda *args: (302, {'Location': other + '/capture'}, {})) as origin:
                transport = sim.HttpTransport(origin, [origin, other], rate=1000)
                with self.assertRaisesRegex(sim.Failure, '^redirect_blocked$'):
                    transport('GET', '/orders/91', 'secret', None, None)
        self.assertEqual(leaked, [])

    def test_order_response_loss_reuses_key_body_and_user_identity(self):
        received = []
        def respond(method, path, headers, body):
            received.append((headers.get('Authorization'), headers.get('Idempotency-Key'), body))
            return (None, {}, None) if len(received) == 1 else (201, {}, {'data': {'orderId': '91'}})
        with server(respond) as origin:
            transport = sim.HttpTransport(origin, [origin], rate=1000)
            self.assertEqual(transport('POST', '/orders', 'user-secret',
                                       {'screeningId': '10', 'screeningSeatIds': ['20']}, 'order-key'),
                             {'orderId': '91'})
        self.assertEqual(len(received), 2)
        self.assertEqual(*received)
        self.assertEqual(received[0][:2], ('Bearer user-secret', 'order-key'))


    def test_timeout_retries_original_event_id_and_body(self):
        received = []
        def respond(method, path, headers, body):
            received.append((headers.get('Authorization'), body))
            if len(received) == 1:
                time.sleep(.12)
            return 200, {}, {'data': {'status': 'SUCCESS'}}
        with server(respond) as origin:
            client = sim.Client(sim.HttpTransport(origin, [origin], rate=1000, timeout=.04), 'u', 'a')
            client.event('P81', 'A1', 'SUCCESS', 0)
        self.assertEqual(len(received), 2)
        self.assertEqual(*received)
        self.assertTrue(json.loads(received[0][1])['eventId'])

    def test_lost_event_response_retries_identical_payload_and_identity(self):
        received = []
        def respond(method, path, headers, body):
            received.append((method, path, headers.get('Authorization'), body))
            return (None, {}, None) if len(received) == 1 else (200, {}, {'data': {'status': 'FAILED'}})
        with server(respond) as origin:
            transport = sim.HttpTransport(origin, [origin], rate=1000, retries=1)
            client = sim.Client(transport, 'user-secret', 'admin-secret')
            client.event('P81', 'A1', 'FAILED', 0)
        self.assertEqual(len(received), 2)
        self.assertEqual(*received)
        self.assertEqual(received[0][2], 'Bearer admin-secret')
        self.assertEqual(json.loads(received[0][3])['result'], 'FAILED')


class RunnerTests(unittest.TestCase):
    def test_unusable_order_response_never_falls_back_to_preset_order(self):
        for response in ({'orderId': '91'}, {'id': '91', 'status': 'PENDING_PAYMENT'},
                         {'orderId': '../91', 'status': 'PENDING_PAYMENT'}, None):
            calls = []
            def transport(method, path, token, body, key):
                calls.append(path)
                return response
            with self.subTest(response=response), self.assertRaisesRegex(sim.Failure, '^order_dependency_'):
                sim.checkout({'screeningId': '10', 'seatIds': ['20']}, sim.Client(transport, 'u', 'a'))
            self.assertEqual(calls, ['/orders'])

    def test_pending_payment_after_200_success_event_is_business_failure(self):
        def transport(method, path, token, body, key):
            if path == '/orders':
                return {'orderId': '91', 'status': 'PENDING_PAYMENT'}
            if method == 'POST' and path.endswith('/payments'):
                return {'orderId': '91', 'paymentId': '81', 'paymentNo': 'P81', 'status': 'PENDING'}
            if '/attempts?' in path:
                return {'items': [{'paymentNo': 'P81', 'attemptId': 'A1', 'current': True}], 'nextCursor': None}
            if path.endswith('/events'):
                return {'status': 'PENDING'}
            return {'items': [{'paymentNo': 'P81', 'status': 'PENDING'}]}
        with self.assertRaisesRegex(sim.Failure, 'business_assertion'):
            sim.checkout({'screeningId': '10', 'seatIds': ['20']}, sim.Client(transport, 'u', 'a'))

    def test_duration_stops_new_tasks_drains_inflight_and_does_not_recycle_seats(self):
        class Transport:
            errors = {}
            latencies = []
            def __init__(self):
                self.calls = 0
            def __call__(self, *args):
                self.calls += 1
                time.sleep(.02)
                raise sim.Failure('http_503')
        transport = Transport()
        tasks = [{'screeningId': '10', 'seatIds': [str(20 + i)]} for i in range(3)]
        report = sim.run(tasks, transport, {'PAYMENT_USER_TOKEN': 'u'}, 'a', duration=.005)
        self.assertEqual((report['started'], report['skipped'], transport.calls), (1, 2, 1))
        self.assertEqual(report['stopReason'], 'duration_reached')


    def test_order_dependency_failure_blocks_payment_and_returns_nonzero_without_secrets(self):
        calls = []
        def respond(method, path, headers, body):
            calls.append(path)
            return 503, {}, {'message': 'user-secret admin-secret password=private', 'data': None}
        with server(respond) as origin:
            out = io.StringIO()
            code = sim.main(['--target', origin, '--allow-origin', origin, '--tasks', '-'],
                            environ={'PAYMENT_USER_TOKEN': 'user-secret', 'PAYMENT_ADMIN_TOKEN': 'admin-secret'},
                            stdin=io.StringIO('[{"screeningId":"10","seatIds":["20"]}]'), stdout=out)
        self.assertEqual(code, 1)
        self.assertEqual(calls, ['/orders'])
        report = json.loads(out.getvalue())
        self.assertEqual(report['errors'], {'order_dependency_http_503': 1})
        self.assertEqual(report['failed'], 1)
        self.assertEqual(report['stopReason'], 'tasks_exhausted')
        self.assertIn('p95', report['requestLatencyMs'])
        for secret in ('user-secret', 'admin-secret', 'password', 'private'):
            self.assertNotIn(secret, out.getvalue())


class InputTests(unittest.TestCase):
    def test_preflight_rejects_missing_and_shared_credentials_without_network(self):
        for environ in ({}, {'PAYMENT_USER_TOKEN': 'same', 'PAYMENT_ADMIN_TOKEN': 'same'}):
            out = io.StringIO()
            code = sim.main(['--target', 'http://127.0.0.1:1', '--allow-origin', 'http://127.0.0.1:1',
                             '--tasks', '-'], environ=environ, stdout=out,
                            stdin=io.StringIO('[{"screeningId":"10","seatIds":["20"]}]'))
            self.assertEqual(code, 2)
            self.assertNotIn('same', out.getvalue())
            self.assertNotIn('transport', out.getvalue())


    def test_tasks_reject_duplicate_seats_invalid_ids_and_unknown_fields(self):
        valid = {'screeningId': '10', 'seatIds': ['20']}
        self.assertEqual(sim.validate_tasks([valid]), [valid])
        for tasks in ([valid, valid], [{'screeningId': '10', 'seatIds': ['20', '20']}],
                      [{'screeningId': 10, 'seatIds': ['20']}],
                      [{'screeningId': '10', 'seatIds': ['0']}],
                      [{'screeningId': '10', 'seatIds': ['9223372036854775808']}],
                      [dict(valid, orderId='91')], [dict(valid, scenario='unknown')], []):
            with self.subTest(tasks=tasks), self.assertRaises(sim.Failure):
                sim.validate_tasks(tasks)


class SafetyTests(unittest.TestCase):
    def test_origin_requires_explicit_exact_allow(self):
        for target, allowed in [
            ('http://localhost:8080', []),
            ('http://localhost:8080', ['http://localhost:8081']),
            ('http://localhost:8080.evil', ['http://localhost:8080']),
            ('http://user:secret@localhost:8080', ['http://localhost:8080']),
            ('http://localhost:8080/api', ['http://localhost:8080']),
        ]:
            with self.subTest(target=target), self.assertRaises(sim.Failure):
                sim.validate_origin(target, allowed)
        self.assertEqual(sim.validate_origin('http://localhost:8080', ['http://localhost:8080']),
                         'http://localhost:8080')


class LateSuccessTests(unittest.TestCase):
    def late_transport(self, final_status='REFUNDING', expired_status='EXPIRED', changed=False, tickets=0):
        self.calls = []
        self.events = []
        self.details = 0
        items = [{'orderItemId': '31', 'screeningSeatId': '20', 'seatId': '21',
                  'rowNumber': 1, 'seatNumber': 2, 'unitPrice': '39.90'}]
        def transport(method, path, token, body, key):
            self.calls.append((method, path, token))
            if path == '/orders':
                return {'orderId': '91', 'status': 'PENDING_PAYMENT'}
            if method == 'POST' and path == '/orders/91/payments':
                return {'orderId': '91', 'paymentId': '81', 'paymentNo': 'P81', 'status': 'PENDING'}
            if '/attempts?' in path:
                return {'items': [{'paymentNo': 'P81', 'attemptId': 'A1', 'current': True}], 'nextCursor': None}
            if path == '/admin/test/payments/events':
                self.events.append(body)
                return {'status': 'SUCCESS'}
            if path == '/orders/91/payments':
                return {'items': [{'paymentNo': 'P81', 'status': 'SUCCESS'}]}
            if path == '/orders/91':
                self.details += 1
                status = ('PENDING_PAYMENT' if self.details == 1 else
                          expired_status if not self.events else final_status)
                detail_items = [dict(i) for i in items]
                if changed and self.events:
                    detail_items[0]['unitPrice'] = '0.01'
                return {'orderId': '91', 'orderNo': 'O91', 'status': status, 'items': detail_items}
            if path.startswith('/tickets?'):
                return {'total': tickets, 'items': [{'id': '71', 'orderId': '91', 'status': 'VALID'}] * tickets}
            self.fail('不允许额外写入或时钟修改接口')
        return transport

    def test_insufficient_delay_blocks_success_notification_without_changing_deadline(self):
        transport = self.late_transport(expired_status='PENDING_PAYMENT')
        with self.assertRaisesRegex(sim.Failure, '^late_not_expired$'):
            sim.checkout({'screeningId': '10', 'seatIds': ['20'], 'scenario': 'late-success'},
                         sim.Client(transport, 'u', 'a'), delay=0)
        self.assertEqual(self.events, [])
        self.assertEqual([path for method, path, _ in self.calls if method == 'POST'],
                         ['/orders', '/orders/91/payments'])

    def test_delay_occurs_once_before_server_expiry_check_not_after_notification(self):
        transport = self.late_transport()
        observed = []
        def sleep(seconds):
            observed.append((seconds, self.details, len(self.events)))
        with patch.object(sim.time, 'sleep', side_effect=sleep):
            sim.checkout({'screeningId': '10', 'seatIds': ['20'], 'scenario': 'late-success'},
                         sim.Client(transport, 'u', 'a'), delay=901)
        self.assertEqual([entry for entry in observed if entry[0] > 0], [(901, 1, 0)])

    def test_late_success_rejects_paid_order_changed_item_price_or_any_ticket(self):
        for options in ({'final_status': 'PAID'}, {'changed': True}, {'tickets': 1}):
            with self.subTest(options=options), self.assertRaises(sim.Failure):
                sim.checkout({'screeningId': '10', 'seatIds': ['20'], 'scenario': 'late-success'},
                             sim.Client(self.late_transport(**options), 'u', 'a'))

    def test_late_success_requires_current_payment_success_not_event_acknowledgement(self):
        boundary = self.late_transport()
        def transport(method, path, token, body, key):
            if method == 'GET' and path == '/orders/91/payments':
                return {'items': [{'paymentNo': 'P81', 'status': 'PENDING'}]}
            return boundary(method, path, token, body, key)
        with self.assertRaisesRegex(sim.Failure, '^business_assertion$'):
            sim.checkout({'screeningId': '10', 'seatIds': ['20'], 'scenario': 'late-success'},
                         sim.Client(transport, 'u', 'a'))

    def test_local_http_insufficient_delay_is_nonzero_and_sends_no_event(self):
        boundary = self.late_transport(expired_status='PENDING_PAYMENT')
        def respond(method, path, headers, body):
            value = boundary(method, path, headers['Authorization'].removeprefix('Bearer '),
                             json.loads(body) if body else None, headers.get('Idempotency-Key'))
            return 200, {}, {'data': value}
        with server(respond) as origin:
            out = io.StringIO()
            code = sim.main(['--target', origin, '--allow-origin', origin, '--tasks', '-', '--rate', '1000'],
                            environ={'PAYMENT_USER_TOKEN': 'u', 'PAYMENT_ADMIN_TOKEN': 'a'},
                            stdin=io.StringIO('[{"screeningId":"10","seatIds":["20"],"scenario":"late-success"}]'),
                            stdout=out)
        self.assertEqual(code, 1)
        report = json.loads(out.getvalue())
        self.assertEqual(report['errors'], {'late_not_expired': 1})
        self.assertEqual(report['scenarioAssertions']['late-success'], {'passed': 0, 'failedOrIncomplete': 1})
        self.assertEqual(self.events, [])

    def test_local_http_late_success_cli_reports_evidence_and_honest_limits(self):
        boundary = self.late_transport(final_status='REFUNDED')
        def respond(method, path, headers, body):
            token = headers['Authorization'].removeprefix('Bearer ')
            value = boundary(method, path, token, json.loads(body) if body else None,
                             headers.get('Idempotency-Key'))
            return (201 if path == '/orders' else 200), {}, {'data': value}
        with server(respond) as origin:
            out = io.StringIO()
            code = sim.main(['--target', origin, '--allow-origin', origin, '--tasks', '-', '--rate', '1000'],
                            environ={'PAYMENT_USER_TOKEN': 'u', 'PAYMENT_ADMIN_TOKEN': 'a'},
                            stdin=io.StringIO('[{"screeningId":"10","seatIds":["20"],"scenario":"late-success"}]'),
                            stdout=out)
        self.assertEqual(code, 0)
        report = json.loads(out.getvalue())
        self.assertEqual(report['scenarioAssertions']['late-success'], {'passed': 1, 'failedOrIncomplete': 0})
        self.assertEqual(report['seatVerification'], 'unverified_no_ownership_api')
        self.assertIn('回滚故障注入未验证，不将HTTP失败或伪服务响应作为回滚证据', report['limits'])
        self.assertIn('越权未验证，未配置错误用户只读探测', report['limits'])

    def test_late_success_accepts_refunding_or_automatically_refunded_without_tickets(self):
        for status in ('REFUNDING', 'REFUNDED'):
            with self.subTest(status=status):
                transport = self.late_transport(final_status=status)
                task = {'screeningId': '10', 'seatIds': ['20'], 'scenario': 'late-success'}
                self.assertEqual(sim.validate_tasks([task]), [task])
                result = sim.checkout(task, sim.Client(transport, 'u', 'a'), delay=0)
                self.assertEqual(result['assertions'], 'passed')
                self.assertEqual(len(self.events), 1)
                self.assertEqual(self.events[0]['result'], 'SUCCESS')
                self.assertEqual(self.details, 3)
                self.assertEqual(result['seatVerification'], 'unverified_no_ownership_api')


class ContractTests(unittest.TestCase):
    def test_attempt_query_follows_cursor_not_latest_guess(self):
        paths = []
        def transport(method, path, token, body, key):
            paths.append(path)
            if len(paths) == 1:
                return {'items': [{'paymentNo': 'P1', 'attemptId': 'old', 'current': False}], 'nextCursor': '42'}
            return {'items': [{'paymentNo': 'P1', 'attemptId': 'new', 'current': True}], 'nextCursor': None}
        self.assertEqual(sim.Client(transport, 'u', 'a').attempt('P1'), 'new')
        self.assertEqual(paths[-1], '/admin/test/payments/P1/attempts?limit=100&cursor=42')


    def test_failure_retry_old_event_replay_and_duplicates_preserve_business_state(self):
        for scenario in ('failed', 'retry', 'old-failure', 'replay', 'duplicate'):
            with self.subTest(scenario=scenario):
                state = {'status': 'PENDING', 'attempt': 'A1', 'events': [], 'keys': []}
                def transport(method, path, token, body, key):
                    if path == '/orders':
                        return {'orderId': '91', 'status': 'PENDING_PAYMENT'}
                    if method == 'POST' and path.endswith('/payments'):
                        state['keys'].append(key)
                        if state['status'] == 'FAILED':
                            state.update(status='PENDING', attempt='A2')
                        return {'orderId': '91', 'paymentId': '81', 'paymentNo': 'P81', 'status': 'PENDING'}
                    if '/attempts?' in path:
                        return {'items': [{'paymentNo': 'P81', 'attemptId': state['attempt'], 'current': True}],
                                'nextCursor': None}
                    if path.endswith('/events'):
                        state['events'].append(body.copy())
                        if body['attemptId'] == state['attempt'] and state['status'] != 'SUCCESS':
                            state['status'] = body['result']
                        return {'status': state['status']}
                    if path.endswith('/payments'):
                        return {'items': [{'paymentId': '81', 'paymentNo': 'P81', 'status': state['status']}]}
                    if path == '/orders/91':
                        return {'orderId': '91', 'orderNo': 'O91',
                                'status': 'PAID' if state['status'] == 'SUCCESS' else 'PENDING_PAYMENT',
                                'items': [{'screeningSeatId': '20'}]}
                    if path.startswith('/tickets?'):
                        items = [{'id': '71', 'orderId': '91', 'status': 'VALID'}] if state['status'] == 'SUCCESS' else []
                        return {'items': items, 'total': len(items)}
                    self.fail('未预期接口')
                self.assertEqual(sim.checkout({'screeningId': '10', 'seatIds': ['20'], 'scenario': scenario},
                                             sim.Client(transport, 'u', 'a'))['assertions'], 'passed')
                if scenario == 'failed':
                    self.assertEqual(state['status'], 'FAILED')
                elif scenario in ('retry', 'old-failure'):
                    self.assertEqual(state['keys'].__len__(), 2)
                    self.assertNotEqual(*state['keys'])
                    self.assertEqual(state['events'][0]['result'], 'FAILED')
                    self.assertTrue(any(e['attemptId'] == 'A2' and e['result'] == 'SUCCESS' for e in state['events']))
                    if scenario == 'old-failure':
                        self.assertEqual(state['events'][1]['attemptId'], 'A1')
                        self.assertEqual(state['events'][-1]['attemptId'], 'A1')
                        self.assertNotEqual(state['events'][1]['eventId'], state['events'][0]['eventId'])
                elif scenario == 'replay':
                    self.assertEqual(len(state['keys']), 2)
                    self.assertEqual(*state['keys'])
                elif scenario == 'duplicate':
                    self.assertEqual(len(state['events']), 2)
                    self.assertEqual(*state['events'])


    def test_checkout_uses_created_order_and_separate_admin_identity(self):
        exchanges = [
            ('POST', '/orders', 'user-token', {'screeningId': '10', 'screeningSeatIds': ['20']},
             {'orderId': '91', 'orderNo': 'O91', 'status': 'PENDING_PAYMENT'}),
            ('POST', '/orders/91/payments', 'user-token', None,
             {'paymentId': '81', 'paymentNo': 'P81', 'orderId': '91', 'status': 'PENDING'}),
            ('GET', '/admin/test/payments/P81/attempts?limit=100', 'admin-token', None,
             {'items': [{'paymentNo': 'P81', 'attemptId': 'A1', 'current': True}], 'nextCursor': None}),
            ('POST', '/admin/test/payments/events', 'admin-token', 'event', {'status': 'SUCCESS'}),
            ('GET', '/orders/91/payments', 'user-token', None,
             {'orderId': '91', 'items': [{'paymentId': '81', 'paymentNo': 'P81', 'status': 'SUCCESS'}]}),
            ('GET', '/orders/91', 'user-token', None,
             {'orderId': '91', 'orderNo': 'O91', 'status': 'PAID', 'items': [{'screeningSeatId': '20'}]}),
            ('GET', '/tickets?orderNo=O91&page=1&pageSize=50', 'user-token', None,
             {'items': [{'id': '71', 'orderId': '91', 'status': 'VALID'}], 'total': 1}),
        ]
        def transport(method, path, token, body, key):
            expected = exchanges.pop(0)
            self.assertEqual((method, path, token), expected[:3])
            if expected[3] == 'event':
                self.assertEqual({k: body[k] for k in ('paymentNo', 'attemptId', 'result')},
                                 {'paymentNo': 'P81', 'attemptId': 'A1', 'result': 'SUCCESS'})
                self.assertTrue(body['eventId'])
            else:
                self.assertEqual(body, expected[3])
            if method == 'POST' and path != '/admin/test/payments/events':
                self.assertTrue(key)
            return expected[4]
        result = sim.checkout({'screeningId': '10', 'seatIds': ['20']},
                              sim.Client(transport, 'user-token', 'admin-token'), delay=0)
        self.assertEqual(result['assertions'], 'passed')
        self.assertEqual(exchanges, [])


if __name__ == '__main__':
    unittest.main()
