"""Preserve bounded app crash reports inside the encrypted diagnostic result only."""
import json, os, pathlib, shutil, subprocess

def collect(work, home):
    output=work/'crash-diagnostics';output.mkdir(exist_ok=True)
    directories=[('host',home/'Library/Logs/DiagnosticReports')]
    selected=work/'selected-simulators.txt'
    if selected.is_file():
        for line in selected.read_text().splitlines():
            parts=line.split()
            if len(parts)==2 and parts[0] in ['iPhone','iPad'] and len(parts[1])==36 and all(c in '0123456789abcdefABCDEF-' for c in parts[1]):
                device=home/'Library/Developer/CoreSimulator/Devices'/parts[1]/'data/Library/Logs'
                directories.extend((parts[0]+'-'+folder,device/folder) for folder in ['CrashReporter','DiagnosticReports'])
                try:
                    result=subprocess.run(['xcrun','simctl','get_app_container',parts[1],'vn.hptelecom.admin.ios','data'],capture_output=True,text=True,timeout=10)
                    if result.returncode==0:
                        phase=pathlib.Path(result.stdout.strip())/'Library/Caches/hp-admin-capture-stage.json'
                        if phase.is_file() and phase.stat().st_size<=4096:
                            value=json.loads(phase.read_text())
                            phases={'preflightStarted','inputRejected','responseAccepted','responseRejected','timedOut','accountRejected','accountAccepted','savingSession','sessionSaved','sessionSaveFailed','installingScripts','navigating','preflightRejected'}
                            if set(value) in [{'phase','at'},{'phase','at','osStatus'}] and value['phase'] in phases and isinstance(value['at'],(int,float)) and ('osStatus' not in value or type(value['osStatus']) is int):
                                (output/(parts[0]+'-capture-stage.json')).write_text(json.dumps(value)+'\n')
                except (OSError,ValueError,subprocess.TimeoutExpired):pass
    copied=[];total=0
    for label,directory in directories:
        if not directory.is_dir():continue
        for source in sorted(directory.glob('HPTelecomAdmin*'),key=lambda p:p.stat().st_mtime,reverse=True):
            if not source.is_file() or source.is_symlink() or source.suffix not in ['.ips','.crash']:continue
            size=source.stat().st_size
            if size>4*1024*1024 or total+size>12*1024*1024 or len(copied)>=12:continue
            name=label+'-'+source.name;shutil.copyfile(source,output/name);total+=size;copied.append(name)
    (output/'inventory.json').write_text(json.dumps({'reports':copied,'bytes':total,'scope':'app process only; encrypted artifact'},indent=2)+'\n')

if __name__=='__main__':collect(pathlib.Path(os.environ['BUILD_WORK']),pathlib.Path.home())
