"""外部支付模拟压测；仅标准库，绝不自动准备或修改业务数据。"""

import argparse
import math
import os
import sys
from concurrent.futures import FIRST_COMPLETED, ThreadPoolExecutor, wait
import http.client
import json
import re
import socket
import threading
import time
import uuid
from collections import Counter
from urllib.error import HTTPError, URLError
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener
from urllib.parse import quote, urlencode, urlsplit


class Failure(Exception):
    """只携带本地受控分类，不携带服务端正文或凭据。"""


def validate_origin(target, allowed):
    def valid(value):
        try:
            parsed = urlsplit(value)
            return (parsed.scheme in ('http', 'https') and parsed.hostname
                    and parsed.port != 0 and not parsed.username and not parsed.password
                    and not parsed.path and not parsed.query and not parsed.fragment
                    and not any(c.isspace() for c in value)
                    and '*' not in value and '\\' not in value)
        except ValueError:
            return False
    if not valid(target) or target not in allowed or not all(valid(x) for x in allowed):
        raise Failure('origin_not_allowed')
    return target


def require(condition, category='business_assertion'):
    if not condition:
        raise Failure(category)


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class HttpTransport:
    def __init__(self, origin, allowed, rate=1, timeout=5, retries=1):
        self.origin = validate_origin(origin, allowed)
        self.rate, self.timeout, self.retries = rate, timeout, retries
        self.lock = threading.Lock()
        self.next_request = 0
        self.latencies = []
        self.errors = Counter()
        # 不继承代理环境配置，不跟随任何重定向，避免Authorization被转发。
        self.opener = build_opener(ProxyHandler({}), NoRedirect())

    def __call__(self, method, path, token, body, key):
        require(path.startswith('/') and not path.startswith('//') and not urlsplit(path).netloc,
                'invalid_path')
        payload = None if body is None else json.dumps(body, sort_keys=True, separators=(',', ':')).encode()
        headers = {'Authorization': 'Bearer ' + token, 'Accept': 'application/json'}
        if payload is not None:
            headers['Content-Type'] = 'application/json'
        if key:
            headers['Idempotency-Key'] = key
        for retry in range(self.retries + 1):
            with self.lock:
                wait = max(0, self.next_request - time.monotonic())
                time.sleep(wait)
                self.next_request = time.monotonic() + 1 / self.rate
            start = time.monotonic()
            category = None
            try:
                request = Request(self.origin + path, data=payload, headers=headers, method=method)
                with self.opener.open(request, timeout=self.timeout) as response:
                    require(response.status in (200, 201), 'http_unexpected_success')
                    raw = response.read(1048577)
                    require(len(raw) <= 1048576, 'response_too_large')
                    envelope = json.loads(raw)
                    require(isinstance(envelope, dict) and 'data' in envelope, 'response_contract')
                    return envelope['data']
            except HTTPError as error:
                category = 'redirect_blocked' if 300 <= error.code < 400 else 'http_' + str(error.code)
                error.close()
            except (TimeoutError, socket.timeout, URLError, OSError, http.client.HTTPException):
                category = 'transport_unknown'
            except (ValueError, TypeError):
                category = 'response_contract'
            except Failure as error:
                category = str(error)
            finally:
                with self.lock:
                    self.latencies.append((time.monotonic() - start) * 1000)
                    if category:
                        self.errors[category] += 1
            if category != 'transport_unknown' or retry == self.retries:
                raise Failure(category) from None


class Client:
    def __init__(self, transport, user_token, admin_token):
        self.transport = transport
        self.user_token = user_token
        self.admin_token = admin_token

    def request(self, method, path, body=None, key=None, admin=False):
        return self.transport(method, path, self.admin_token if admin else self.user_token, body, key)

    def attempt(self, payment_no):
        base = '/admin/test/payments/' + quote(payment_no, safe='') + '/attempts?limit=100'
        cursor, seen, current = None, set(), []
        for _ in range(100):
            page = self.request('GET', base + ('' if cursor is None else '&cursor=' + quote(cursor, safe='')),
                                admin=True)
            current.extend(a for a in page['items'] if a['current'] is True and a['paymentNo'] == payment_no)
            cursor = page['nextCursor']
            if cursor is None:
                require(len(current) == 1 and bool(current[0]['attemptId']), 'attempt_contract')
                return current[0]['attemptId']
            require(isinstance(cursor, str) and cursor.isdigit() and cursor not in seen, 'attempt_contract')
            seen.add(cursor)
        raise Failure('attempt_page_limit')

    def event(self, payment_no, attempt, result, delay):
        time.sleep(delay)
        body = dict(paymentNo=payment_no, attemptId=attempt, eventId=uuid.uuid4().hex, result=result)
        self.request('POST', '/admin/test/payments/events', body, admin=True)
        return body


SCENARIOS = ('success', 'failed', 'retry', 'old-failure', 'replay', 'duplicate', 'late-success')


def valid_id(value):
    return (isinstance(value, str) and re.fullmatch(r'[1-9][0-9]{0,18}', value) is not None
            and int(value) <= 9223372036854775807)


def validate_tasks(tasks):
    require(isinstance(tasks, list) and bool(tasks), 'invalid_tasks')
    seen = set()
    for task in tasks:
        require(isinstance(task, dict) and set(task) <= {'screeningId', 'seatIds', 'scenario', 'userTokenEnv'},
                'invalid_task')
        require(valid_id(task.get('screeningId')), 'invalid_screening_id')
        seats = task.get('seatIds')
        require(isinstance(seats, list) and 1 <= len(seats) <= 6 and all(valid_id(s) for s in seats),
                'invalid_seats')
        require(len(set(seats)) == len(seats), 'duplicate_seats')
        require(task.get('scenario', 'success') in SCENARIOS, 'invalid_scenario')
        if 'userTokenEnv' in task:
            require(isinstance(task['userTokenEnv'], str) and
                    re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', task['userTokenEnv']), 'invalid_token_env')
        for seat in seats:
            pair = (task['screeningId'], seat)
            require(pair not in seen, 'duplicate_task_seat')
            seen.add(pair)
    return tasks


def checkout(task, client, delay=0):
    try:
        order = client.request('POST', '/orders',
                               {'screeningId': task['screeningId'], 'screeningSeatIds': task['seatIds']},
                               key=uuid.uuid4().hex)
        order_id = order['orderId']
        require(valid_id(order_id) and order['status'] == 'PENDING_PAYMENT', 'contract')
    except Failure as error:
        raise Failure('order_dependency_' + str(error)) from None
    except (KeyError, TypeError):
        raise Failure('order_dependency_contract') from None
    path = '/orders/' + order_id
    scenario = task.get('scenario', 'success')
    key = uuid.uuid4().hex
    payment = client.request('POST', path + '/payments', key=key)
    if scenario == 'replay':
        require(client.request('POST', path + '/payments', key=key) == payment, 'replay_assertion')
    require(payment['orderId'] == order_id and payment['status'] == 'PENDING')
    payment_no = payment['paymentNo']
    attempt = client.attempt(payment_no)
    if scenario == 'late-success':
        before = client.request('GET', path)
        require(before['orderId'] == order_id and before['status'] == 'PENDING_PAYMENT', 'late_initial_state')
        original_items = json.dumps(before['items'], sort_keys=True)
        time.sleep(delay)
        expired = client.request('GET', path)
        require(expired['orderId'] == order_id and expired['status'] == 'EXPIRED', 'late_not_expired')
        require(json.dumps(expired['items'], sort_keys=True) == original_items, 'late_items_changed')
    if scenario in ('failed', 'retry', 'old-failure'):
        client.event(payment_no, attempt, 'FAILED', delay)
        failed = client.request('GET', path + '/payments')
        require(any(p['paymentNo'] == payment_no and p['status'] == 'FAILED' for p in failed['items']))
        if scenario != 'failed':
            retried = client.request('POST', path + '/payments', key=uuid.uuid4().hex)
            require(retried['paymentId'] == payment['paymentId'] and retried['paymentNo'] == payment_no
                    and retried['status'] == 'PENDING', 'retry_assertion')
            new_attempt = client.attempt(payment_no)
            require(new_attempt != attempt, 'retry_assertion')
            if scenario == 'old-failure':
                client.event(payment_no, attempt, 'FAILED', delay)
                pending = client.request('GET', path + '/payments')
                require(any(p['paymentNo'] == payment_no and p['status'] == 'PENDING' for p in pending['items']))
            old_attempt, attempt = attempt, new_attempt
    if scenario != 'failed':
        event = client.event(payment_no, attempt, 'SUCCESS', 0 if scenario == 'late-success' else delay)
        if scenario == 'duplicate':
            client.request('POST', '/admin/test/payments/events', event, admin=True)
        if scenario == 'old-failure':
            client.event(payment_no, old_attempt, 'FAILED', delay)
    expected = 'FAILED' if scenario == 'failed' else 'SUCCESS'
    payments = client.request('GET', path + '/payments')
    require(any(p['paymentNo'] == payment_no and p['status'] == expected for p in payments['items']))
    require(sum(p['status'] == 'SUCCESS' for p in payments['items']) == (expected == 'SUCCESS'))
    current = client.request('GET', path)
    allowed_status = (('REFUNDING', 'REFUNDED') if scenario == 'late-success' else
                      ('PENDING_PAYMENT',) if scenario == 'failed' else ('PAID',))
    require(current['orderId'] == order_id and current['status'] in allowed_status)
    if scenario == 'late-success':
        require(json.dumps(current['items'], sort_keys=True) == original_items, 'late_items_changed')
    require(sorted(i['screeningSeatId'] for i in current['items']) == sorted(task['seatIds']))
    tickets = client.request('GET', '/tickets?' + urlencode(
        {'orderNo': current['orderNo'], 'page': 1, 'pageSize': 50}))
    count = 0 if scenario in ('failed', 'late-success') else len(task['seatIds'])
    require(tickets['total'] == count == len(tickets['items']))
    require(len({t['id'] for t in tickets['items']}) == count)
    require(all(t['orderId'] == order_id and t['status'] == 'VALID' for t in tickets['items']))
    return {'assertions': 'passed', 'seatVerification': 'unverified_no_ownership_api'}


def percentiles(values):
    ordered = sorted(values)
    return {name: (round(ordered[max(0, math.ceil(len(ordered) * fraction) - 1)], 3)
                   if ordered else None)
            for name, fraction in (('p50', .5), ('p95', .95), ('p99', .99))}


def run(tasks, transport, user_tokens, admin_token, concurrency=1, duration=30, delay=0):
    start = time.monotonic()
    deadline = start + duration
    errors, latencies = Counter(), []
    scenario_assertions = {}
    passed = started = 0
    # 同一令牌/场次串行，避免自身触发“同用户同场次一个待付订单”。
    occupied = set()
    pending = list(enumerate(tasks))
    active = {}

    def execute(task):
        before = time.monotonic()
        try:
            checkout(task, Client(transport, user_tokens[task.get('userTokenEnv', 'PAYMENT_USER_TOKEN')],
                                  admin_token), delay)
            return None, (time.monotonic() - before) * 1000
        except Failure as error:
            return str(error), (time.monotonic() - before) * 1000
        except (KeyError, TypeError, ValueError):
            return 'response_contract', (time.monotonic() - before) * 1000
        except Exception:
            return 'unexpected_error', (time.monotonic() - before) * 1000

    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        while pending or active:
            while len(active) < concurrency and time.monotonic() < deadline:
                candidate = next(((i, task, (user_tokens[task.get('userTokenEnv', 'PAYMENT_USER_TOKEN')],
                                             task['screeningId']))
                                  for i, task in pending
                                  if (user_tokens[task.get('userTokenEnv', 'PAYMENT_USER_TOKEN')],
                                      task['screeningId']) not in occupied), None)
                if candidate is None:
                    break
                i, task, identity = candidate
                pending.remove((i, task))
                occupied.add(identity)
                active[pool.submit(execute, task)] = (identity, task.get('scenario', 'success'))
                started += 1
            if not active:
                break
            done, _ = wait(active, return_when=FIRST_COMPLETED)
            for future in done:
                identity, scenario = active.pop(future)
                occupied.remove(identity)
                category, latency = future.result()
                summary = scenario_assertions.setdefault(scenario, {'passed': 0, 'failedOrIncomplete': 0})
                summary['failedOrIncomplete' if category else 'passed'] += 1
                latencies.append(latency)
                if category:
                    errors[category] += 1
                else:
                    passed += 1
    return {'started': started, 'passed': passed, 'failed': sum(errors.values()), 'skipped': len(pending),
            'errors': dict(errors), 'requestErrors': dict(transport.errors),
            'businessAssertions': {'passed': passed, 'failedOrIncomplete': sum(errors.values())},
            'scenarioAssertions': scenario_assertions,
            'seatVerification': 'unverified_no_ownership_api',
            'requestLatencyMs': percentiles(transport.latencies), 'taskLatencyMs': percentiles(latencies),
            'requests': len(transport.latencies), 'elapsedSeconds': round(time.monotonic() - start, 3),
            'stopReason': 'duration_reached' if pending else 'tasks_exhausted',
            'durationMeaning': '仅限制启动新任务的窗口；已启动链路含限速、重试和延迟会排空，可能超时长；任务不循环',
            'limits': ['HTTP契约不证明真实数据库事务/售座并发正确', '每单至多一个成功尝试，重复事件不算新收款',
                       'late-success仅在服务端GET确认EXPIRED后发通知；GET派生过期不证明定时任务已提交',
                       'REFUNDING只证明补偿处理中，不证明退款完成；允许自动执行后的REFUNDED',
                       '座位未恢复及未释放他人座位未验证：现有选座API无订单归属且查询触发过期清理',
                       '未覆盖双成功收款补偿、精确截止边界及全部并发交错；失败订单不自动取消',
                       '越权未验证，未配置错误用户只读探测',
                       '回滚故障注入未验证，不将HTTP失败或伪服务响应作为回滚证据']}


def main(argv=None, environ=None, stdin=None, stdout=None):
    environ = os.environ if environ is None else environ
    stdin = sys.stdin if stdin is None else stdin
    stdout = sys.stdout if stdout is None else stdout
    parser = argparse.ArgumentParser(description=(
        '显式授权测试origin上的有限购票任务。seatIds必须是场次座位ID，映射screeningSeatIds。'
        '任务JSON数组：screeningId、seatIds、可选scenario/userTokenEnv；任务仅执行一次，不循环。'
        'duration仅限制新任务启动，已启动链路排空。会创建真实订单，失败订单不自动取消。'),
        epilog='场景：' + ', '.join(SCENARIOS) + '。token仅从环境变量读取，不接受命令行凭据。')
    parser.add_argument('--target', required=True, help='精确origin，不含路径/凭据/查询/尾斜线')
    parser.add_argument('--allow-origin', action='append', required=True, help='显式精确允许origin，可重复，无隐式本机白名单')
    parser.add_argument('--tasks', required=True, help='任务JSON文件，-表示标准输入')
    parser.add_argument('--admin-token-env', default='PAYMENT_ADMIN_TOKEN')
    parser.add_argument('--concurrency', type=int, default=1)
    parser.add_argument('--rate', type=float, default=1, help='所有线程及重试共享的全局每秒请求数，默认1')
    parser.add_argument('--duration', type=float, default=30, help='新任务启动窗口秒数，默认30')
    parser.add_argument('--delay', type=float, default=0, help=(
        '新通知投递前延迟秒数；late-success先等待一次，再GET确认EXPIRED，未到期则失败且不发成功通知；'
        '须按真实截止设置足够延迟，可能需等待15分钟，不调整服务器时钟或订单时间'))
    parser.add_argument('--timeout', type=float, default=5)
    parser.add_argument('--retries', type=int, default=1, help='传输结果未知时原请求最多重试次数，默认1')
    args = parser.parse_args(argv)
    try:
        validate_origin(args.target, args.allow_origin)
        require(1 <= args.concurrency <= 64 and 0 <= args.retries <= 5, 'invalid_options')
        require(all(math.isfinite(v) and v > 0 for v in (args.rate, args.duration, args.timeout))
                and math.isfinite(args.delay) and args.delay >= 0, 'invalid_options')
        if args.tasks == '-':
            tasks = json.load(stdin)
        else:
            with open(args.tasks, encoding='utf-8') as source:
                tasks = json.load(source)
        validate_tasks(tasks)
        names = {task.get('userTokenEnv', 'PAYMENT_USER_TOKEN') for task in tasks}
        tokens = {name: environ.get(name, '') for name in names}
        admin = environ.get(args.admin_token_env, '')
        require(admin and all(tokens.values()), 'missing_credentials')
        require(all(token != admin for token in tokens.values()), 'separate_identities_required')
        require(all(re.fullmatch(r'[\x21-\x7e]+', token) for token in [admin, *tokens.values()]), 'invalid_credentials')
        transport = HttpTransport(args.target, args.allow_origin, args.rate, args.timeout, args.retries)
        report = run(tasks, transport, tokens, admin, args.concurrency, args.duration, args.delay)
        print(json.dumps(report, ensure_ascii=False), file=stdout)
        return 1 if report['failed'] or report['skipped'] else 0
    except Failure as error:
        print(json.dumps({'error': str(error)}, ensure_ascii=False), file=stdout)
        return 2
    except (OSError, ValueError, TypeError):
        print(json.dumps({'error': 'invalid_input'}, ensure_ascii=False), file=stdout)
        return 2
    except KeyboardInterrupt:
        print(json.dumps({'error': 'interrupted'}), file=stdout)
        return 130


if __name__ == '__main__':
    sys.exit(main())
