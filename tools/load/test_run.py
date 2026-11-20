"""Offline regression tests for load evidence gates; no HTTP, DB, Docker or Gradle."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]


class LoadExitTests(unittest.TestCase):
    def run_case(self, mode, acceptance, complete, expected_exit):
        # Replace only the external, minutes-long workload. Real Recorder writes
        # evidence and calculates completeness, and real main decides the exit.
        child = r'''
import sys
sys.path.insert(0, str(__import__('pathlib').Path.cwd() / 'tools/load'))
import run
mode, acceptance, complete, output = sys.argv[1:]
async def synthetic(args, rec):
    if complete == 'true':
        phase = 'diagnostic' if mode == 'diagnostic' else 'measurement'
        rec.phase_windows = [{'phase':phase,'startNs':0,'endNs':1000000000}]
        for stream in rec.samples: rec.samples[stream] = [0,1000000000]
        rec.sql_snapshots[(phase,'start')] = []
        rec.sql_snapshots[(phase,'end')] = []
    rec.row(metricId='synthetic',phase='measurement',sampleId=0,status='ERROR' if acceptance=='FAIL' else 'OK',dispatchNs=1,endNs=2)
    return {'acceptance':acceptance}
run.capacity = run.bbox = run.chat = run.telemetry = synthetic
# The fixture has no asynchronous I/O. Avoid opening an event-loop wakeup
# socket in this offline test (Windows sandbox may deny socketpair startup).
def run_synthetic(coroutine):
    try:
        coroutine.send(None)
    except StopIteration as result:
        return result.value
    raise AssertionError('Synthetic workload must not perform I/O')
run.asyncio.run = run_synthetic
sys.argv = ['run.py','--mode',mode,'--output',output]
raise SystemExit(run.main())
'''
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'evidence'
            process = subprocess.run(
                [sys.executable, '-B', '-c', child, mode, acceptance, str(complete).lower(), str(output)],
                cwd=ROOT, capture_output=True, text=True, encoding='utf-8',
                env={**os.environ, 'PYTHONIOENCODING':'utf-8'}, timeout=30,
            )
            summary = json.loads((output / 'summary.json').read_text(encoding='utf-8'))
            self.assertEqual(summary['metricAcceptance'], acceptance)
            self.assertEqual(summary['evidenceCompleteness'], 'COMPLETE' if complete or mode == 'capacity' else 'PARTIAL')
            self.assertTrue((output / 'raw.csv').is_file())
            self.assertEqual(process.returncode, expected_exit, process.stderr)

    def test_failed_capacity_exits_nonzero_after_saving_evidence(self):
        self.run_case('capacity', 'FAIL', True, 1)

    def test_failed_bbox_exits_nonzero_with_complete_telemetry(self):
        self.run_case('bbox', 'FAIL', True, 1)

    def test_failed_chat_exits_nonzero_with_complete_telemetry(self):
        self.run_case('chat', 'FAIL', True, 1)

    def test_metric_pass_with_missing_telemetry_exits_nonzero(self):
        self.run_case('chat', 'PASS', False, 1)

    def test_complete_metric_pass_exits_zero(self):
        self.run_case('bbox', 'PASS', True, 0)

    def test_complete_preflight_is_success_without_claiming_load_pass(self):
        self.run_case('telemetry', 'PREFLIGHT_ONLY', True, 0)

    def test_incomplete_preflight_exits_nonzero(self):
        self.run_case('telemetry', 'PREFLIGHT_ONLY', False, 1)

    def test_complete_diagnostic_is_success_without_claiming_load_pass(self):
        self.run_case('diagnostic', 'DIAGNOSTIC_ONLY', True, 0)


class OrchestratorGateTests(unittest.TestCase):
    def run_case(self, acceptance, completeness, exit_code, should_fail):
        # Evaluate the actual function AST without executing the script's Docker
        # setup. The Python process boundary is replaced by a summary fixture.
        script = r'''
param($BackendRoot,$OutputRoot,$Acceptance,$Completeness,[int]$PythonExit)
$ErrorActionPreference='Stop'
$tokens=$null; $errors=$null
$ast=[System.Management.Automation.Language.Parser]::ParseFile((Join-Path $BackendRoot 'tools/b6/load-validation.ps1'),[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'Invalid script'}
$function=$ast.Find({param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Run-Load'},$true)
. ([scriptblock]::Create($function.Extent.Text))
function Assert-Candidate {}
function python {
    New-Item -ItemType Directory -Path "$OutputRoot/chat" -Force | Out-Null
    @{metricAcceptance=$Acceptance;evidenceCompleteness=$Completeness} | ConvertTo-Json | Set-Content "$OutputRoot/chat/summary.json"
    $global:LASTEXITCODE=$PythonExit
}
$server=[pscustomobject]@{Id=1}; $jar='unused-fixture.jar'
Run-Load 'chat'
'''
        with tempfile.TemporaryDirectory() as directory:
            harness = Path(directory) / 'harness.ps1'
            harness.write_text(script, encoding='utf-8')
            process = subprocess.run(
                ['pwsh','-NoProfile','-File',str(harness),str(ROOT),directory,
                 acceptance,completeness,str(exit_code)],
                capture_output=True, text=True, encoding='utf-8', timeout=30,
            )
            self.assertTrue((Path(directory) / 'exit-codes.jsonl').exists(), process.stderr)
            self.assertEqual(process.returncode != 0, should_fail, process.stderr)

    def test_orchestrator_rejects_failed_metrics_even_with_zero_exit(self):
        self.run_case('FAIL', 'COMPLETE', 0, True)

    def test_orchestrator_rejects_partial_evidence_even_with_zero_exit(self):
        self.run_case('PASS', 'PARTIAL', 0, True)

    def test_orchestrator_rejects_nonzero_exit_even_with_pass_summary(self):
        self.run_case('PASS', 'COMPLETE', 1, True)

    def test_orchestrator_accepts_complete_success(self):
        self.run_case('PASS', 'COMPLETE', 0, False)


class DistributionPreflightTests(unittest.TestCase):
    def run_case(self, dirty=False, ignored=False):
        # Execute the real orchestration preflight through its compiler setup
        # boundary. Only Git responses and the external Gradle build are fixtures.
        source = (ROOT / 'tools/b6/load-validation.ps1').read_text(encoding='utf-8')
        preflight = source[:source.index('$monitorClasses =')]
        with tempfile.TemporaryDirectory() as directory:
            workspace = Path(directory)
            scripts = workspace / 'tools/b6'
            scripts.mkdir(parents=True)
            for name in ('distribution.ps1', 'local-docker.ps1'):
                shutil.copyfile(ROOT / 'tools/b6' / name, scripts / name)
            (scripts / 'preflight.ps1').write_text(preflight, encoding='utf-8')
            jar = workspace / 'build/install/roy-backend/lib/roy-backend-fixture.jar'
            jar.parent.mkdir(parents=True)
            jar.write_text('stale artifact', encoding='utf-8')
            (workspace / 'gradlew.bat').write_text(
                '@echo off\r\necho rebuilt artifact>build\\install\\roy-backend\\lib\\roy-backend-fixture.jar\r\nexit /b 0\r\n',
                encoding='ascii',
            )
            harness = workspace / 'harness.ps1'
            harness.write_text(r'''
param([string]$Dirty,[string]$Ignored)
$ErrorActionPreference='Stop'
$env:JAVA_HOME='synthetic-jdk-not-executed'
function docker {
    $global:LASTEXITCODE=0
    if ($args[1] -eq 'show') { 'local-fixture' } else { 'npipe:////./pipe/docker_engine' }
}
function git {
    $global:LASTEXITCODE=0
    switch ($args[0]) {
        'rev-parse' { '1111111111111111111111111111111111111111' }
        'status' { if($Dirty -eq 'true') { ' M src/main/kotlin/Changed.kt' } }
        'ls-files' { if($Ignored -eq 'true') { 'src/main/kotlin/Ignored.kt' } }
        default { throw 'Unexpected Git fixture request' }
    }
}
& ./tools/b6/preflight.ps1 -OutputRoot './evidence'
''', encoding='utf-8')
            process = subprocess.run(
                ['pwsh','-NoProfile','-File',str(harness),str(dirty).lower(),str(ignored).lower()],
                cwd=workspace, capture_output=True, text=True, encoding='utf-8', timeout=30,
            )
            if dirty or ignored:
                self.assertNotEqual(process.returncode, 0, process.stdout)
                self.assertEqual(jar.read_text(encoding='utf-8'), 'stale artifact')
            else:
                self.assertEqual(process.returncode, 0, process.stderr)
                self.assertEqual(jar.read_text(encoding='utf-8').strip(), 'rebuilt artifact')

    def test_dirty_source_is_rejected_before_build_or_database(self):
        self.run_case(dirty=True)

    def test_ignored_source_is_rejected_before_build_or_database(self):
        self.run_case(ignored=True)

    def test_clean_source_rebuilds_stale_distribution_before_measurement(self):
        self.run_case()


if __name__ == '__main__':
    unittest.main()
