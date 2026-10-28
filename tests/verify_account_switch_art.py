#!/usr/bin/env python3
"""Run the real APK account-switch regression on Android, without launching Music."""
import argparse
from pathlib import Path
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--before-apk', type=Path, required=True)
    parser.add_argument('--work-dir', type=Path, required=True)
    parser.add_argument('--d8-jar', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent
    work = args.work_dir.resolve()
    work.mkdir(parents=True, exist_ok=False)
    classes = ['VerifyAccountSwitchAudio', 'VerifyBackgroundPlayback']
    subprocess.run(['javac', '--release', '8', '-Xlint:-options', '-d', str(work),
                    *[str(root / (name + '.java')) for name in classes]], check=True)
    subprocess.run(['java', '-cp', str(args.d8_jar), 'com.android.tools.r8.D8', '--min-api', '32',
                    '--output', str(work), *[str(work / (name + '.class')) for name in classes]], check=True)
    dex = work / 'classes.dex'
    dex.chmod(0o444)
    for name, source in [('before.apk', args.before_apk), ('after.apk', args.apk)]:
        target = work / name
        shutil.copyfile(source, target)
        target.chmod(0o444)
    def run(name, apk, label):
        result = subprocess.run(['/system/bin/app_process', '-Xmx192m', '-Djava.class.path=' + str(dex),
                                 '/system/bin', name, str(apk), str(work)], text=True, capture_output=True)
        output = result.stdout + result.stderr
        (work / (label + '.txt')).write_text(output)
        return result.returncode, output
    code, output = run('VerifyAccountSwitchAudio', work / 'before.apk', 'before')
    if code != 1 or 'Account/entitlement change erased audio-only preference for fixture-B' not in output:
        raise AssertionError('Before APK failed for an unexpected reason: ' + output)
    print('PASS: before APK reproduces preference loss on the actual account-change callback')
    code, output = run('VerifyAccountSwitchAudio', work / 'after.apk', 'after')
    if code != 0 or 'PASS: actual account and entitlement listeners preserve audio-only preferences' not in output:
        raise AssertionError('Account-switch repair failed: ' + output)
    print(output, end='')
    code, output = run('VerifyBackgroundPlayback', work / 'after.apk', 'background')
    if code != 0 or 'PASS: actual APK background eligibility stays enabled' not in output:
        raise AssertionError('Background eligibility failed: ' + output)
    print(output, end='')

if __name__ == '__main__':
    main()
