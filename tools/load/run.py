"""Local synthetic load runner. Read methodology.json before running. No dotenv use.

Required DB env: LOAD_DB_NAME (roy_m1t2_step9load or roy_load_chat),
LOAD_DB_PORT, DATABASE_USER, DATABASE_PASSWORD, TEST_DATABASE_DISPOSABLE=true.
The operator must independently verify the newly created disposable container.
Output must be a fresh directory. Tokens and message text are never persisted.
"""
import argparse
import asyncio
import base64
import collections
import csv
from email.utils import parsedate_to_datetime
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import re
import subprocess
import sys
import threading
import time
import uuid
from urllib.parse import urlparse

import httpx
import psutil
import psycopg2
import websocket

ROOT = Path(__file__).resolve().parents[2]
METHOD = json.loads(Path(__file__).with_name('methodology.json').read_text())
FIELDS = ['runId','metricId','phase','sampleId','plannedNs','dispatchNs','endNs','durationMs','status','errorClass','recipientLabel','messageId','messageSeq','inFlight','lagMs','httpStatus']


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def save(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')


def db():
    if os.environ.get('TEST_DATABASE_DISPOSABLE') != 'true':
        raise RuntimeError('Disposable confirmation missing')
    name = os.environ.get('LOAD_DB_NAME')
    if name not in ('roy_m1t2_step9load', 'roy_load_chat'):
        raise RuntimeError('Unexpected load fixture alias')
    return psycopg2.connect(host='127.0.0.1', port=int(os.environ['LOAD_DB_PORT']), dbname=name,
                            user=os.environ['DATABASE_USER'], password=os.environ['DATABASE_PASSWORD'],
                            connect_timeout=5, application_name='load-observer')


def percentile(values, q):
    values = sorted(values)
    return values[math.ceil(q * len(values))-1] if values else None


def cgroup_numeric(raw):
    result={'ioReadBytes':0,'ioWriteBytes':0,'ioReadOps':0,'ioWriteOps':0}
    current=[]
    keys={'usage_usec':'cpuUsageUsec','user_usec':'cpuUserUsec','system_usec':'cpuSystemUsec','anon':'memoryAnonBytes'}
    io_keys={'rbytes':'ioReadBytes','wbytes':'ioWriteBytes','rios':'ioReadOps','wios':'ioWriteOps'}
    for line in raw.splitlines():
        fields=line.split()
        if not fields: continue
        if len(fields)==1 and fields[0].isdigit(): current.append(int(fields[0]))
        elif re.fullmatch(r'\d+:\d+',fields[0]):
            for item in fields[1:]:
                key,value=item.split('=',1)
                if not value.isdigit(): raise ValueError('Invalid cgroup IO counter')
                if key in io_keys: result[io_keys[key]]+=int(value)
        elif len(fields)==2 and fields[1].isdigit():
            if fields[0] in keys: result[keys[fields[0]]]=int(fields[1])
        else: raise ValueError('Unexpected cgroup counter line')
    if len(current)!=1 or not all(value in result for value in keys.values()):
        raise ValueError('Required cgroup counters missing')
    result['memoryCurrentBytes']=current[0]
    return result


class Recorder:
    def __init__(self, args):
        self.args = args
        self.out = Path(args.output)
        self.out.mkdir(parents=True, exist_ok=False)
        self.run_id = self.out.name
        self.file = (self.out/'raw.csv').open('w', newline='', encoding='utf-8')
        self.writer = csv.DictWriter(self.file, fieldnames=FIELDS)
        self.writer.writeheader()
        self.rows = []
        self.lock = threading.Lock()
        self.stop = threading.Event()
        self.resource_thread = None
        self.resource_errors = []
        self.monitor_processes=[]
        self.monitor_threads=[]
        self.monitor_ready={key:threading.Event() for key in ('resources','pool','docker','heap')}
        self.samples={key:[] for key in ('resources','pool','docker','heap')}
        self.phase_windows=[]
        self.sql_snapshots={}
        self.sql_deltas=[]
        passport = {'runId': self.run_id, 'utc': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
                    'methodology': METHOD, 'methodologySha256': sha(Path(__file__).with_name('methodology.json')),
                    'generatorSha256': sha(__file__), 'python': platform.python_version(),
                    'capacityEndpointSha256':sha(Path(__file__).with_name('echo.py')),
                    'poolSamplerSha256':sha(Path(__file__).with_name('PoolSampler.java')),
                    'chatSetupPolicy':{'deadlineSeconds':360,'registrationRetries':'Only HTTP 429; obey maximum valid error.retryAfterSeconds and Retry-After, fallback 1s; no transport retries',
                                       'measurementRetries':False,'evidence':'setup-registration.jsonl and setup-summary.json'},
                    'httpx': httpx.__version__, 'websocket_client': websocket.__version__,
                    'psutil': psutil.__version__, 'os': platform.platform(),
                    'cpuLogical': psutil.cpu_count(), 'cpuPhysical': psutil.cpu_count(logical=False),
                    'cpuModel':platform.processor(), 'disk':psutil.disk_usage(str(ROOT))._asdict(),
                    'gitSha':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
                    'jvm':{'javaHome':os.environ.get('JAVA_HOME'),
                           'note':'Exact JVM flags/runtime and container hardware are also recorded by orchestration'},
                    'databaseConfig':{'maximumPoolSize':os.environ.get('DATABASE_MAX_POOL_SIZE','5'),
                                      'connectionTimeoutMs':os.environ.get('DATABASE_CONNECTION_TIMEOUT_MS','5000'),
                                      'statementTimeoutMs':os.environ.get('DATABASE_STATEMENT_TIMEOUT_MS','5000'),
                                      'lockTimeoutMs':os.environ.get('DATABASE_LOCK_TIMEOUT_MS','1000')},
                    'sharedHostContention': 'Concurrent Android build/emulator may run; other user processes are not stopped',
                    'ramBytes': psutil.virtual_memory().total, 'mode': args.mode,
                    'serverPid': args.server_pid, 'baseUrl': args.base_url,
                    'poolTelemetry': 'Local Attach/JMX, four Hikari gauges; completeness determined from actual stream coverage',
                    'artifactSha256': sha(args.jar) if args.jar else None,
                    'fixtureSqlSha256': sha(ROOT/'src/test/resources/step9-local-fixture.sql')}
        save(self.out/'passport.json', passport)

    def row(self, **r):
        r['runId'] = self.run_id
        if r.get('endNs') and r.get('dispatchNs'):
            r['durationMs'] = (r['endNs']-r['dispatchNs'])/1e6
        if r.get('dispatchNs') and r.get('plannedNs'):
            r['lagMs'] = (r['dispatchNs']-r['plannedNs'])/1e6
        with self.lock:
            self.writer.writerow(r)
            self.rows.append(r)
            if len(self.rows) % 100 == 0: self.file.flush()

    def resources(self):
        def collect():
            generator = psutil.Process()
            server = psutil.Process(self.args.server_pid) if self.args.server_pid else None
            generator.cpu_percent()
            if server:
                server.cpu_percent()
            with db() as conn, (self.out/'resources.jsonl').open('w', encoding='utf-8') as f:
                conn.autocommit = True
                with conn.cursor() as c: c.execute("SET statement_timeout='3000ms'")
                while not self.stop.is_set():
                    r = {'monoNs': time.perf_counter_ns(), 'generatorCpuPercent': generator.cpu_percent(),
                         'generatorRssBytes': generator.memory_info().rss, 'systemCpuPercent':psutil.cpu_percent(),
                         'systemAvailableRamBytes':psutil.virtual_memory().available}
                    if server:
                        r.update(serverCpuPercent=server.cpu_percent(), serverRssBytes=server.memory_info().rss)
                    with conn.cursor() as c:
                        c.execute("SELECT state,wait_event_type,wait_event,count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() GROUP BY 1,2,3")
                        r['dbActivityProxy'] = c.fetchall()
                        c.execute("SELECT mode,granted,count(*) FROM pg_locks WHERE database=(SELECT oid FROM pg_database WHERE datname=current_database()) GROUP BY 1,2")
                        r['dbLocks'] = c.fetchall()
                    f.write(json.dumps(r)+'\n'); f.flush()
                    self.samples['resources'].append(r['monoNs'])
                    self.monitor_ready['resources'].set()
                    self.stop.wait(1)
        def guarded_collect():
            try: collect()
            except Exception as error: self.resource_errors.append(type(error).__name__)
        self.resource_thread = threading.Thread(target=guarded_collect, daemon=True)
        self.resource_thread.start()
        self.start_heap_monitor()
        self.start_stream_monitors()
        deadline=time.monotonic()+10
        for key,event in self.monitor_ready.items():
            if not event.wait(max(0,deadline-time.monotonic())):
                self.resource_errors.append(key+'MonitorStartupTimeout')

    def start_stream_monitors(self):
        def start(kind,command,convert):
            try:
                process=subprocess.Popen(command,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True,
                    encoding='utf-8',creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
                self.monitor_processes.append(process)
                def read():
                    try:
                        with (self.out/(kind+'.jsonl')).open('w',encoding='utf-8') as output:
                            for line in process.stdout:
                                stamp=time.perf_counter_ns()
                                if self.stop.is_set(): break
                                line=re.sub(r'\x1b\[[0-?]*[ -/]*[@-~]','',line).strip()
                                if not line: continue
                                raw=json.loads(line)
                                if 'errorClass' in raw:
                                    error=raw['errorClass']
                                    self.resource_errors.append(kind+':'+(error if isinstance(error,str) and re.fullmatch(r'[A-Za-z0-9_]+',error) else 'InvalidErrorClass'))
                                    break
                                row={'monoNs':stamp,**convert(raw)}
                                output.write(json.dumps(row)+'\n'); output.flush()
                                self.samples[kind].append(stamp)
                                self.monitor_ready[kind].set()
                        if not self.stop.is_set(): self.resource_errors.append(kind+'MonitorEnded')
                    except Exception as error: self.resource_errors.append(kind+':'+type(error).__name__)
                    finally: process.stdout.close()
                thread=threading.Thread(target=read,daemon=True)
                self.monitor_threads.append(thread); thread.start()
            except Exception as error: self.resource_errors.append(kind+':'+type(error).__name__)
        def pool_values(raw):
            keys=('active','idle','total','pending')
            if set(raw)!=set(keys) or any(type(raw[k]) is not int or raw[k]<0 for k in keys):
                raise ValueError('Invalid pool gauges')
            return {k:raw[k] for k in keys}
        if self.args.server_pid and os.environ.get('JAVA_HOME'):
            start('pool',[str(Path(os.environ['JAVA_HOME'])/'bin/java.exe'),'--add-modules','jdk.attach',
                          '-cp',str(ROOT/'build/load-monitor'),'PoolSampler',str(self.args.server_pid)],pool_values)
        else: self.resource_errors.append('PoolMonitorConfigurationMissing')
        try:
            container=os.environ.get('LOAD_CONTAINER_ID','')
            if not re.fullmatch(r'[0-9a-f]{64}',container): raise ValueError('Full owned container ID required')
            local=r'^(?:npipe:/{4}\./pipe/[^/]+|unix:///(?!/).+)$'
            host=os.environ.get('DOCKER_HOST')
            if host and not re.fullmatch(local,host): raise ValueError('Docker host is not local')
            context=os.environ.get('DOCKER_CONTEXT') or subprocess.check_output(['docker','context','show'],text=True).strip()
            endpoint=subprocess.check_output(['docker','context','inspect','--format','{{.Endpoints.docker.Host}}','--',context],text=True).strip()
            if not re.fullmatch(local,endpoint): raise ValueError('Docker context is not local')
            self.start_cgroup_monitor(container)
        except Exception as error: self.resource_errors.append('docker:'+type(error).__name__)

    def start_heap_monitor(self):
        try:
            if not self.args.server_pid or not os.environ.get('JAVA_HOME'):
                raise ValueError('Heap monitor configuration missing')
            process=subprocess.Popen([str(Path(os.environ['JAVA_HOME'])/'bin/jstat.exe'),'-gc',str(self.args.server_pid),'1000'],
                stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True,encoding='utf-8',
                creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
            self.monitor_processes.append(process)
            def collect():
                known={'S0C','S1C','S0U','S1U','EC','EU','OC','OU','MC','MU','CCSC','CCSU','YGC','YGCT','FGC','FGCT','CGC','CGCT','GCT'}
                header=None
                try:
                    with (self.out/'heap.jsonl').open('w',encoding='utf-8') as output:
                        for line in process.stdout:
                            stamp=time.perf_counter_ns()
                            if self.stop.is_set(): break
                            fields=line.split()
                            if not fields: continue
                            if header is None:
                                if not {'EC','EU','OC','OU','YGC','YGCT','FGC','FGCT','GCT'}.issubset(fields) or not set(fields).issubset(known) or len(set(fields))!=len(fields):
                                    raise ValueError('Unexpected jstat header')
                                header=fields
                                continue
                            if len(fields)!=len(header): raise ValueError('Unexpected jstat numeric row')
                            gauges={}
                            for key,value in zip(header,fields):
                                # Some collectors do not expose concurrent-collection gauges.
                                if value=='-' and key in ('CGC','CGCT'): continue
                                number=float(value.replace(',','.'))
                                if not math.isfinite(number) or number<0: raise ValueError('Invalid jstat gauge')
                                gauges[key]=number
                            output.write(json.dumps({'monoNs':stamp,'gauges':gauges})+'\n'); output.flush()
                            self.samples['heap'].append(stamp); self.monitor_ready['heap'].set()
                    if not self.stop.is_set(): self.resource_errors.append('HeapMonitorEnded')
                except Exception as error: self.resource_errors.append('heap:'+type(error).__name__)
                finally: process.stdout.close()
            thread=threading.Thread(target=collect,daemon=True)
            self.monitor_threads.append(thread); thread.start()
        except Exception as error: self.resource_errors.append('heap:'+type(error).__name__)

    def start_cgroup_monitor(self,container):
        def collect():
            command=['docker','exec',container,'cat','/sys/fs/cgroup/cpu.stat','/sys/fs/cgroup/memory.current',
                     '/sys/fs/cgroup/memory.stat','/sys/fs/cgroup/io.stat']
            previous=None; planned=time.perf_counter_ns()
            try:
                with (self.out/'docker.jsonl').open('w',encoding='utf-8') as output:
                    while not self.stop.is_set():
                        dispatch=time.perf_counter_ns()
                        # Only fixed numeric cgroup files in the independently verified owned container.
                        raw=subprocess.check_output(command,stderr=subprocess.DEVNULL,text=True,encoding='utf-8',timeout=3,
                            creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
                        stamp=time.perf_counter_ns()
                        if self.stop.is_set(): break
                        row={'monoNs':stamp,'pollDurationMs':(stamp-dispatch)/1e6,'pollLagMs':max(0,(dispatch-planned)/1e6),
                             **cgroup_numeric(raw)}
                        if previous:
                            delta=row['cpuUsageUsec']-previous['cpuUsageUsec']
                            if delta<0 or stamp<=previous['monoNs']: raise ValueError('Cgroup CPU counter reset')
                            row['cpuPercent']=delta*100_000/(stamp-previous['monoNs'])
                        output.write(json.dumps(row)+'\n'); output.flush()
                        self.samples['docker'].append(stamp); self.monitor_ready['docker'].set()
                        previous=row; planned+=1_000_000_000
                        self.stop.wait(max(0,(planned-time.perf_counter_ns())/1e9))
            except Exception as error:
                if not self.stop.is_set(): self.resource_errors.append('docker:'+type(error).__name__)
        thread=threading.Thread(target=collect,daemon=True)
        self.monitor_threads.append(thread); thread.start()

    def sql_snapshot(self,phase,edge):
        stamp=time.perf_counter_ns()
        try:
            with db() as conn,conn.cursor() as c:
                c.execute("SET statement_timeout='3000ms'")
                c.execute("SELECT queryid,sum(calls)::bigint,sum(rows)::bigint FROM pg_stat_statements WHERE dbid=(SELECT oid FROM pg_database WHERE datname=current_database()) GROUP BY queryid ORDER BY queryid")
                snapshot=[{'queryid':r[0],'calls':r[1],'rows':r[2]} for r in c.fetchall()]
            self.sql_snapshots[(phase,edge)]=snapshot
            save(self.out/f'sql-{phase}-{edge}.json',{'monoNs':stamp,'phase':phase,'edge':edge,'statistics':snapshot,
                'scope':'Current database; includes observer and background queries; no exact SQL-per-REST attribution'})
            if edge=='end' and (phase,'start') in self.sql_snapshots:
                before={r['queryid']:r for r in self.sql_snapshots[(phase,'start')]}
                if set(before)-{r['queryid'] for r in snapshot}:
                    self.resource_errors.append('SqlStatisticsRowsEvictedOrReset')
                delta=[]
                for r in snapshot:
                    old=before.get(r['queryid'],{'calls':0,'rows':0})
                    delta.append({'queryid':r['queryid'],'calls':r['calls']-old['calls'],'rows':r['rows']-old['rows']})
                if any(r['calls']<0 or r['rows']<0 for r in delta): self.resource_errors.append('SqlStatisticsCounterReset')
                self.sql_deltas.append({'phase':phase,'deltas':delta})
        except Exception as error:
            self.resource_errors.append('sql:'+type(error).__name__)
            save(self.out/f'sql-{phase}-{edge}.json',{'monoNs':stamp,'phase':phase,'edge':edge,'errorClass':type(error).__name__})

    def end_phase(self,name,start,seconds):
        self.phase_windows.append({'phase':name,'startNs':start,'endNs':start+round(seconds*1e9),'drainEndNs':time.perf_counter_ns()})
        self.sql_snapshot(name,'end')

    def coverage(self):
        result=[]
        for phase in self.phase_windows:
            for stream,samples in self.samples.items():
                selected=[s for s in samples if phase['startNs']<=s<=phase['endNs']]
                gaps=[(b-a)/1e9 for a,b in zip(selected,selected[1:])]
                start_gap=(selected[0]-phase['startNs'])/1e9 if selected else None
                end_gap=(phase['endNs']-selected[-1])/1e9 if selected else None
                result.append({'phase':phase['phase'],'stream':stream,'samples':len(selected),'startGapSeconds':start_gap,
                    'endGapSeconds':end_gap,'maxGapSeconds':max(gaps,default=None),
                    'complete':len(selected)>=2 and start_gap<=2 and end_gap<=2 and max(gaps,default=math.inf)<=2})
        return result

    def finish(self, extra=None):
        self.stop.set()
        for process in self.monitor_processes:
            if process.poll() is None:
                process.terminate()
                try: process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill(); process.wait(timeout=5)
                    self.resource_errors.append('MonitorForcedKill')
        for thread in self.monitor_threads:
            thread.join(5)
            if thread.is_alive(): self.resource_errors.append('MonitorReaderStillAlive')
        if self.resource_thread:
            self.resource_thread.join(7)
            if self.resource_thread.is_alive(): self.resource_errors.append('ResourceReaderStillAlive')
        self.file.flush(); self.file.close()
        groups = collections.defaultdict(list)
        for r in self.rows:
            groups[(r['phase'], r['metricId'])].append(r)
        summary = {'runId': self.run_id, 'rawSha256': sha(self.out/'raw.csv'), 'groups': [], 'resourceErrors':self.resource_errors}
        for (phase, metric), rows in groups.items():
            good = [r for r in rows if r['status'] == 'OK']
            values = [r['durationMs'] for r in good]
            summary['groups'].append({'phase': phase, 'metric': metric, 'scheduled': len(rows), 'success':len(good),
                'errors':len(rows)-len(good), 'errorRate':(len(rows)-len(good))/len(rows),
                'errorClasses':dict(collections.Counter(r.get('errorClass','') for r in rows if r['status'] != 'OK')),
                'httpStatuses':dict(collections.Counter(r.get('httpStatus',0) for r in rows if 'httpStatus' in r)),
                'p50':percentile(values,.5),'p95':percentile(values,.95),'p99':percentile(values,.99),
                'max':max(values) if values else None, 'maxLagMs':max((r.get('lagMs',0) for r in rows),default=0)})
        summary.update(extra or {})
        summary['metricAcceptance']=summary.get('acceptance','NOT_RUN')
        coverage=self.coverage()
        required_phase='diagnostic' if self.args.mode=='diagnostic' else 'measurement'
        selected=[row for row in coverage if row['phase']==required_phase]
        complete=self.args.mode=='capacity' or (len(selected)==4 and all(r['complete'] for r in selected)
            and not self.resource_errors and (required_phase,'start') in self.sql_snapshots and (required_phase,'end') in self.sql_snapshots)
        summary['resourceCoverage']=coverage
        summary['evidenceCompleteness']='COMPLETE' if complete else 'PARTIAL'
        summary['evidenceLimitations']=[] if complete else ['Required resource/SQL/heap evidence missing, interrupted, or gaps exceed 2s; see resourceCoverage and resourceErrors']
        save(self.out/'phase-windows.json',self.phase_windows)
        save(self.out/'sql-deltas.json',{'phases':self.sql_deltas,'attribution':'Includes background and observer queries; not exact SQL-per-REST counts'})
        save(self.out/'summary.json',summary)
        print(json.dumps(summary, ensure_ascii=False), flush=True)


async def schedule(seconds, rate, callback):
    start = time.perf_counter_ns()
    tasks = set()
    for i in range(int(seconds*rate)):
        planned = start + round(i*1e9/rate)
        await asyncio.sleep(max(0,(planned-time.perf_counter_ns())/1e9))
        if len(tasks) >= 5000:
            raise RuntimeError('Generator overload: 5000 outstanding operations')
        task = asyncio.create_task(callback(i,planned,len(tasks)))
        tasks.add(task); task.add_done_callback(tasks.discard)
    await asyncio.gather(*tasks)
    await asyncio.sleep(max(0,(start+round(seconds*1e9)-time.perf_counter_ns())/1e9))
    return start


def bbox_params():
    cycle=[]
    for i in range(20):
        k=i%4
        p={'west':37.55+.012*k,'south':55.70+.004*k,'east':37.586+.012*k,'north':55.72+.004*k,'limit':10}
        if 12<=i<16: p['topicKey']='football'
        if 16<=i<18: p.update(peopleMin=2,peopleMax=2)
        if i==18: p['topicKey']='volleyball'
        if i==19:
            p={'west':37.55,'south':55.70,'east':37.586,'north':55.72,'limit':10}
        cycle.append(p)
    return cycle


async def bbox(args, rec):
    with db() as conn,conn.cursor() as c:
        counts={}
        for table in ('users','points','point_topics','chats','chat_memberships','messages'):
            c.execute('SELECT count(*) FROM '+table); counts[table]=c.fetchone()[0]
        if list(counts.values()) != [100,350,350,350,700,2000]:
            raise RuntimeError('Unexpected bbox fixture cardinality')
        c.execute("SELECT current_setting('server_version'),postgis_lib_version(),max(version) FROM flyway_schema_history WHERE success")
        save(rec.out/'fixture.json',{'counts':counts,'database':c.fetchone()})
    cycle=bbox_params()
    async with httpx.AsyncClient(base_url=args.base_url,timeout=5,trust_env=False,limits=httpx.Limits(max_connections=1000,max_keepalive_connections=200)) as client:
        first=await client.get('/api/v1/points',params=cycle[19]); first.raise_for_status()
        cursor=first.json()['nextCursor']
        if not cursor: raise RuntimeError('Fixture requires pagination')
        cycle[19]['cursor']=cursor
        expected=[]
        for p in cycle:
            r=await client.get('/api/v1/points',params=p); r.raise_for_status()
            body=r.json()
            if not isinstance(body.get('items'),list) or ('nextCursor' not in body): raise RuntimeError('Invalid bbox shape')
            if p.get('topicKey')=='volleyball':
                if body['items']: raise RuntimeError('Empty fixture query returned points')
            elif not body['items']: raise RuntimeError('Nonempty fixture query returned empty page')
            expected.append(body)
        save(rec.out/'schedule.json',cycle)
        save(rec.out/'expected-responses.json',expected)
        rec.resources()
        async def phase(name, seconds, rate):
            rec.sql_snapshot(name,'start')
            async def one(i,planned,inflight):
                dispatch=time.perf_counter_ns(); status='ERROR'; error=''; httpstatus=0
                try:
                    response=await asyncio.wait_for(client.get('/api/v1/points',params=cycle[i%20]),5)
                    httpstatus=response.status_code
                    if httpstatus!=200: error='HTTP_'+str(httpstatus)
                    elif response.json()!=expected[i%20]: error='INVALID_RESPONSE'
                    else: status='OK'
                except Exception as e: error=type(e).__name__
                rec.row(metricId='bbox',phase=name,sampleId=i,plannedNs=planned,dispatchNs=dispatch,endNs=time.perf_counter_ns(),status=status,errorClass=error,inFlight=inflight,httpStatus=httpstatus)
            start=await schedule(seconds,rate,one)
            rec.end_phase(name,start,seconds)
            return start
        if args.mode=='diagnostic':
            await phase('diagnostic',60,150)
            return {'acceptance':'DIAGNOSTIC_ONLY'}
        await phase('baseline',10,1)
        await phase('warmup',300,100)
        start=await phase('measurement',600,100)
        rows=[r for r in rec.rows if r['phase']=='measurement']
        cohorts=[]
        for minute in range(10):
            rr=[r for r in rows if (r['plannedNs']-start)//60_000_000_000==minute]
            good=sum(r['status']=='OK' for r in rr)
            cohorts.append({'minute':minute,'offered':len(rr)/60,'achieved':good/60,'errors':len(rr)-good,
                'dispatchedInWallMinute':sum(start+minute*60_000_000_000<=r['dispatchNs']<start+(minute+1)*60_000_000_000 for r in rows),
                'maxInFlight':max((r['inFlight'] for r in rr),default=0),
                'maxLagMs':max((r['lagMs'] for r in rr),default=0),
                'httpStatuses':dict(collections.Counter(r.get('httpStatus',0) for r in rr))})
        valid=all(r['status']=='OK' and r['lagMs']<=100 for r in rows) and len(rows)==60000
        p95=percentile([r['durationMs'] for r in rows if r['status']=='OK'],.95)
        return {'cohorts':cohorts,'achievedRps':sum(r['status']=='OK' for r in rows)/600,
                'acceptance':'PASS' if valid and p95 is not None and p95<=500 else 'FAIL'}


async def chat(args,rec):
    setup_started=time.perf_counter_ns()
    setup_deadline=time.monotonic()+360
    with db() as conn,conn.cursor() as c:
        c.execute('SELECT count(*) FROM users'); assert c.fetchone()[0]==0,'Chat database must be empty'
    tokens=[]; actors=[]; sockets=[]; readers=[]; events=[]; event_lock=threading.Lock(); disconnected=[]; done=threading.Event()
    event_file=(rec.out/'ws-received.jsonl').open('w',encoding='utf-8')
    async with httpx.AsyncClient(base_url=args.base_url,timeout=5,trust_env=False,limits=httpx.Limits(max_connections=100)) as client:
        async def request(method,path,token=None,body=None,key=False,setup=False):
            headers={}
            if token: headers['Authorization']='Bearer '+token
            if key: headers['Idempotency-Key']=str(uuid.uuid4())
            timeout=min(5,setup_deadline-time.monotonic()) if setup else 5
            if timeout<=0: raise RuntimeError('Six-minute chat setup deadline exceeded')
            r=await asyncio.wait_for(client.request(method,path,headers=headers,json=body),timeout)
            r.raise_for_status(); return r.json()
        setup_attempts=0; setup_limited=0; setup_wait=0.0
        with (rec.out/'setup-registration.jsonl').open('w',encoding='utf-8') as setup_log:
            for i in range(50):
                attempt=0
                while True:
                    remaining=setup_deadline-time.monotonic()
                    if remaining<=0: raise RuntimeError('Six-minute chat setup deadline exceeded')
                    attempt+=1; setup_attempts+=1; dispatch=time.perf_counter_ns()
                    try:
                        response=await asyncio.wait_for(client.post('/api/v1/auth/register',json={
                            'phone':f'+1202555{i:04d}','password':'Synthetic load password 42!',
                            'displayName':f'Load participant {i}','ageYears':25,
                            'acceptedPrivacyPolicyVersion':'draft-2026-11-01.2'}),min(5,remaining))
                    except Exception as error:
                        setup_log.write(json.dumps({'participantLabel':i,'attempt':attempt,'dispatchNs':dispatch,
                            'endNs':time.perf_counter_ns(),'httpStatus':0,'errorClass':type(error).__name__,'waitSeconds':0})+'\n')
                        setup_log.flush()
                        raise
                    wait=0.0
                    if response.status_code==429:
                        setup_limited+=1
                        hints=[]
                        try:
                            value=response.json().get('error',{}).get('retryAfterSeconds')
                            if isinstance(value,(int,float)) and not isinstance(value,bool) and math.isfinite(value) and value>0:
                                hints.append(float(value))
                        except (ValueError,AttributeError): pass
                        header=response.headers.get('Retry-After')
                        if header:
                            try:
                                value=float(header)
                            except ValueError:
                                try: value=parsedate_to_datetime(header).timestamp()-time.time()
                                except (ValueError,TypeError,OverflowError): value=0
                            if math.isfinite(value) and value>0: hints.append(value)
                        wait=max(hints,default=1.0)
                    setup_log.write(json.dumps({'participantLabel':i,'attempt':attempt,'dispatchNs':dispatch,
                        'endNs':time.perf_counter_ns(),'httpStatus':response.status_code,'waitSeconds':wait})+'\n')
                    setup_log.flush()
                    if response.status_code==429:
                        if wait>=setup_deadline-time.monotonic(): raise RuntimeError('Rate-limit wait exceeds chat setup deadline')
                        setup_wait+=wait
                        await asyncio.sleep(wait)
                        continue
                    response.raise_for_status()
                    if response.status_code!=201: raise RuntimeError('Registration did not return 201')
                    r=response.json()
                    tokens.append(r['accessToken']); actors.append(r['user']['id'])
                    break
        created=await request('POST','/api/v1/points',tokens[0],{'position':{'lat':55.75,'lon':37.62},'topic':{'presetKey':'football'},
                    'address':'Synthetic load point','addressSource':'manual','confirmNearby':False},key=True,setup=True)
        chat_id=created['chat']['id']
        for token in tokens[1:]: await request('PUT',f'/api/v1/chats/{chat_id}/membership',token,setup=True)
        # Setup plus 12-minute load and reconciliation must not cross access expiry.
        for token in tokens:
            segment=token.split('.')[1]
            claims=json.loads(base64.urlsafe_b64decode(segment+'='*((-len(segment))%4)))
            if claims.get('exp',0)<time.time()+900:
                raise RuntimeError('Chat JWT lifetime must cover setup, measurement and reconciliation; set JWT_TTL_SECONDS=3600')
        with db() as conn,conn.cursor() as c:
            c.execute('SELECT participant_count,last_message_seq FROM chats WHERE id=%s',(chat_id,)); count,initial_seq=c.fetchone()
            c.execute("SELECT count(*) FROM messages WHERE chat_id=%s AND type='TEXT'",(chat_id,)); assert c.fetchone()[0]==0
            assert count==50
        save(rec.out/'fixture.json',{'participants':50,'chatId':chat_id,'initialSeq':initial_seq,'initialTextCount':0,'creation':'REST register/create/join'})
        def reader(i,sock):
            while not done.is_set():
                try:
                    raw=sock.recv(); stamp=time.perf_counter_ns()
                    if not raw: raise RuntimeError('closed')
                    event=json.loads(raw)
                    if event.get('type')=='chat.message.created':
                        with event_lock:
                            events.append((i,stamp,event))
                            event_file.write(json.dumps({'recipientLabel':i,'receivedNs':stamp,'messageId':event['payload'].get('id'),
                                'messageSeq':event['payload'].get('messageSeq'),'eventId':event.get('eventId')})+'\n')
                            event_file.flush()
                except websocket.WebSocketTimeoutException: continue
                except Exception as e:
                    if not done.is_set(): disconnected.append({'recipient':i,'error':type(e).__name__,'monoNs':time.perf_counter_ns()})
                    return
        for i,token in enumerate(tokens):
            remaining=setup_deadline-time.monotonic()
            if remaining<=0: raise RuntimeError('Six-minute chat setup deadline exceeded')
            sock=websocket.create_connection(args.base_url.replace('http://','ws://')+'/api/v1/ws',header=['Authorization: Bearer '+token],timeout=min(5,remaining),http_proxy_host=None)
            assert json.loads(sock.recv())['type']=='hello'
            sock.send(json.dumps({'v':1,'type':'subscribe','requestId':str(uuid.uuid4()),'chatIds':[chat_id]}))
            subscribed=json.loads(sock.recv()); assert subscribed.get('acceptedChatIds')==[chat_id]
            sockets.append(sock); sock.settimeout(1)
            thread=threading.Thread(target=reader,args=(i,sock),daemon=True)
            readers.append(thread); thread.start()
            if time.monotonic()>=setup_deadline: raise RuntimeError('Six-minute chat setup deadline exceeded')
        save(rec.out/'setup-summary.json',{'registrationAttempts':setup_attempts,'rateLimitedAttempts':setup_limited,
            'registeredParticipants':len(tokens),'subscribedParticipants':len(sockets),'rateLimitWaitSeconds':setup_wait,
            'elapsedSeconds':(time.perf_counter_ns()-setup_started)/1e9,'measurementRetries':False})
        rec.resources(); sends=[]
        async def phase(name,seconds):
            rec.sql_snapshot(name,'start')
            async def one(i,planned,inflight):
                actor=i%50; dispatch=time.perf_counter_ns(); status='ERROR'; error=''; message={}; code=0
                try:
                    response=await asyncio.wait_for(client.post(f'/api/v1/chats/{chat_id}/messages',headers={'Authorization':'Bearer '+tokens[actor],'Idempotency-Key':str(uuid.uuid4())},json={'text':f'Synthetic load message {name} {i}'}),5)
                    code=response.status_code
                    if code!=201: error='HTTP_'+str(code)
                    else:
                        message=response.json()
                        if not message.get('id') or not isinstance(message.get('messageSeq'),int): error='INVALID_RESPONSE'
                        else: status='OK'
                except Exception as e: error=type(e).__name__
                end=time.perf_counter_ns()
                row=dict(metricId='restAck',phase=name,sampleId=i,plannedNs=planned,dispatchNs=dispatch,endNs=end,status=status,errorClass=error,
                         messageId=message.get('id',''),messageSeq=message.get('messageSeq',''),httpStatus=code,inFlight=inflight)
                rec.row(**row); sends.append((actor,row))
            start=await schedule(seconds,2,one)
            rec.end_phase(name,start,seconds)
        try:
            await phase('warmup',120); await phase('measurement',600); await asyncio.sleep(5)
            done.set()
            for sock in sockets: sock.close()
            join_deadline=time.monotonic()+5
            for thread in readers: thread.join(max(0,join_deadline-time.monotonic()))
            if any(thread.is_alive() for thread in readers):
                raise RuntimeError('WS reader did not stop; correlation snapshot is unsafe')
            by_id=collections.defaultdict(list)
            for recipient,stamp,event in events:
                payload=event['payload']; by_id[payload.get('id')].append((recipient,stamp,payload.get('messageSeq')))
            duplicates=0; missing=0; wrong_seq=0; known=set(); seqs=[]
            for sender,row in sends:
                mid=row['messageId']; known.add(mid)
                if mid: seqs.append(row['messageSeq'])
                matches=by_id.get(mid,[])
                for recipient in range(50):
                    received=[x for x in matches if x[0]==recipient]
                    metric='senderEcho' if recipient==sender else 'wsDelivery'
                    if len(received)>1: duplicates+=len(received)-1
                    if not received:
                        if recipient!=sender: missing+=1
                        rec.row(metricId=metric,phase=row['phase'],sampleId=row['sampleId'],plannedNs=row['plannedNs'],dispatchNs=row['dispatchNs'],endNs=row['dispatchNs']+5_000_000_000,
                                status='ERROR',errorClass='MISSING' if mid else 'REST_FAILED',recipientLabel=recipient,messageId=mid,messageSeq=row['messageSeq'])
                    for j,(_,stamp,seq) in enumerate(received):
                        error='DUPLICATE' if j else 'SEQUENCE' if seq!=row['messageSeq'] else 'TIMEOUT' if stamp-row['dispatchNs']>5_000_000_000 else ''
                        if seq!=row['messageSeq']: wrong_seq+=1
                        rec.row(metricId=metric,phase=row['phase'],sampleId=row['sampleId'],plannedNs=row['plannedNs'],dispatchNs=row['dispatchNs'],endNs=stamp,
                                status='ERROR' if error else 'OK',errorClass=error,recipientLabel=recipient,messageId=mid,messageSeq=seq)
            unknown=[mid for mid in by_id if mid not in known]
            socket_sequence_errors=0
            for recipient in range(50):
                delivered=[event['payload'].get('messageSeq') for who,_,event in events if who==recipient]
                socket_sequence_errors+=sum(a>=b for a,b in zip(delivered,delivered[1:]))
            # REST delta pagination and persisted sequence validate final delivery identities.
            delta=[]; after=initial_seq
            while True:
                page=await request('GET',f'/api/v1/chats/{chat_id}/messages?afterSeq={after}&limit=100',tokens[0])
                items=page['items']; delta.extend(items)
                if not items: break
                next_after=max(x['messageSeq'] for x in items)
                if next_after<=after: raise RuntimeError('Delta did not advance')
                after=next_after
            with db() as conn,conn.cursor() as c:
                c.execute("SELECT id::text,message_seq FROM messages WHERE chat_id=%s AND type='TEXT' ORDER BY message_seq",(chat_id,)); persisted=c.fetchall()
            save(rec.out/'correlation.json',{'disconnects':disconnected,'unknownMessageIds':unknown,'dbMessages':persisted,
                'restDelta':[[x['id'],x['messageSeq']] for x in delta], 'duplicates':duplicates,'missing':missing,'wrongSequence':wrong_seq,
                'socketSequenceErrors':socket_sequence_errors})
            identities={(r['messageId'],r['messageSeq']) for _,r in sends if r['status']=='OK'}
            reconciled=identities==set(persisted)=={(x['id'],x['messageSeq']) for x in delta}
            reconciled=reconciled and len(identities)==1440 and sorted(seqs)==list(range(initial_seq+1,initial_seq+1441))
            rows=[r for r in rec.rows if r['phase']=='measurement']; deliveries=[r for r in rows if r['metricId']=='wsDelivery']
            p95=percentile([r['durationMs'] for r in deliveries if r['status']=='OK'],.95)
            valid=all(r['status']=='OK' for r in rows) and not disconnected and not unknown and not socket_sequence_errors and reconciled and len(deliveries)==58800 and p95 is not None and p95<=400
            valid=valid and all(r.get('lagMs',0)<=100 for r in rows if r['metricId']=='restAck')
            return {'acceptance':'PASS' if valid else 'FAIL','participants':50,'sendRate':2,'expectedMeasurementDeliveries':58800,
                    'duplicates':duplicates,'missing':missing,'wrongSequence':wrong_seq,'reconciled':reconciled,'disconnects':len(disconnected)}
        finally:
            done.set()
            for sock in sockets: sock.close()
            with event_lock: event_file.close()


async def capacity(args,rec):
    rate=METHOD['generator']['capacity_rate']
    seconds=METHOD['generator']['capacity_seconds']
    server=subprocess.Popen([sys.executable,'-u',str(Path(__file__).with_name('echo.py'))],stdout=subprocess.PIPE,
                            stderr=subprocess.DEVNULL,text=True,creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
    try:
        ready=await asyncio.wait_for(asyncio.to_thread(server.stdout.readline),10)
        port=json.loads(ready)['port']
    except BaseException:
        server.terminate(); server.wait(timeout=5); raise
    async with httpx.AsyncClient(timeout=5,trust_env=False,limits=httpx.Limits(max_connections=1000,max_keepalive_connections=200)) as client:
        async def one(i,planned,inflight):
            dispatch=time.perf_counter_ns(); status='ERROR'; error=''
            try:
                r=await asyncio.wait_for(client.get(f'http://127.0.0.1:{port}/'),5)
                if r.status_code==200 and r.json()=={'ok':True}: status='OK'
                else: error='INVALID_RESPONSE'
            except Exception as e: error=type(e).__name__
            rec.row(metricId='generatorCapacity',phase='capacity',sampleId=i,plannedNs=planned,dispatchNs=dispatch,endNs=time.perf_counter_ns(),status=status,errorClass=error,inFlight=inflight)
        started=time.perf_counter_ns()
        try: await schedule(seconds,rate,one)
        finally: server.terminate(); server.wait(timeout=5)
        elapsed=(time.perf_counter_ns()-started)/1e9
    return {'acceptance':'PASS' if len(rec.rows)==seconds*rate and all(r['status']=='OK' and r['lagMs']<=100 for r in rec.rows) else 'FAIL',
            'offeredRps':rate,'achievedCohortRps':sum(r['status']=='OK' for r in rec.rows)/seconds,
            'elapsedSeconds':elapsed,'achievedWallRps':sum(r['status']=='OK' for r in rec.rows)/elapsed}


async def telemetry(args,rec):
    rec.resources()
    rec.sql_snapshot('measurement','start')
    started=time.perf_counter_ns()
    await asyncio.sleep(10)
    rec.end_phase('measurement',started,10)
    return {'acceptance':'PREFLIGHT_ONLY','scope':'Ten-second resource collection; no HTTP load or latency acceptance'}


def main():
    p=argparse.ArgumentParser()
    p.add_argument('--mode',choices=['capacity','bbox','chat','diagnostic','telemetry'],required=True)
    p.add_argument('--output',required=True); p.add_argument('--base-url',default='http://127.0.0.1:8080')
    p.add_argument('--server-pid',type=int); p.add_argument('--jar')
    args=p.parse_args()
    parsed=urlparse(args.base_url)
    if parsed.scheme!='http' or parsed.hostname!='127.0.0.1' or parsed.username or parsed.query or parsed.fragment or parsed.path not in ('','/'):
        p.error('Only plain loopback HTTP origin is allowed')
    rec=Recorder(args)
    try:
        result=asyncio.run(capacity(args,rec) if args.mode=='capacity' else telemetry(args,rec) if args.mode=='telemetry' else chat(args,rec) if args.mode=='chat' else bbox(args,rec))
    except Exception as e:
        rec.finish({'acceptance':'FAIL','fatalClass':type(e).__name__})
        raise
    else: rec.finish(result)


if __name__=='__main__': main()
