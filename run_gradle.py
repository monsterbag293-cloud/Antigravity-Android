import subprocess
import os
import sys

env = os.environ.copy()
env['JAVA_HOME'] = r'C:\Users\NEW0033\.jdk\jdk-17.0.13+11'
env['ANDROID_HOME'] = os.path.expandvars(r'%LOCALAPPDATA%\Android\Sdk')
env['PATH'] = env['JAVA_HOME'] + r'\bin;' + env.get('PATH', '')

args = sys.argv[1:] if len(sys.argv) > 1 else [':core:network:testDebugUnitTest', '--tests', '*DesktopHubConnectionTest*', '--console=plain']

cmd = ['cmd.exe', '/c', r'.\gradlew.bat'] + args
print("Executing:", " ".join(cmd), flush=True)

proc = subprocess.Popen(
    cmd,
    env=env,
    stdin=subprocess.DEVNULL,
    stdout=subprocess.PIPE,
    stderr=subprocess.STDOUT,
    text=True,
    bufsize=1
)

for line in iter(proc.stdout.readline, ''):
    print(line, end='', flush=True)

proc.stdout.close()
returncode = proc.wait()
print("\nProcess finished with exit code:", returncode, flush=True)
sys.exit(returncode)
