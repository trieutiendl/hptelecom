#!/bin/bash
set -euo pipefail
umask 077
test -n "${GITHUB_WORKSPACE:-}" && test -n "${RUNNER_TEMP:-}"
BUILD_HELPERS="$GITHUB_WORKSPACE/ios-screenshots"
BUILD_WORK="$RUNNER_TEMP/hp-ios-screenshot-build"
BUILD_OUTPUT="$RUNNER_TEMP/hp-ios-screenshot-artifacts"
HP_IOS_FULL_REBUILD="${HP_IOS_FULL_REBUILD:-false}"
case "$HP_IOS_FULL_REBUILD" in true|false) ;; *) exit 1 ;; esac
export BUILD_HELPERS BUILD_WORK BUILD_OUTPUT
mkdir -p "$BUILD_WORK/results" "$BUILD_OUTPUT"
if [ -z "${HP_IOS_BUILD_KEY:-}" ]; then
  echo 'The temporary screenshot source key is missing; compilation has not started.'
  exit 1
fi

finish() {
  local result=$?
  local encrypted=0
  set +e
  trap - EXIT
  if [ "$result" -ne 0 ] && [ -n "${BUILD_APP:-}" ] && [ -d "$BUILD_APP" ]; then
    ditto -c -k --keepParent "$BUILD_APP" "$BUILD_WORK/results/simulator-app.zip" >> "$BUILD_WORK/build.log" 2>&1
  fi
  if [ -f "$BUILD_WORK/build.log" ]; then
    cp "$BUILD_WORK/build.log" "$BUILD_WORK/results/build.log"
  fi
  printf '%s\n' "$result" > "$BUILD_WORK/results/exit-code.txt"
  if (cd "$BUILD_WORK/results" && zip -q -r "$BUILD_WORK/screenshot-results.zip" .) && \
     node "$BUILD_HELPERS/crypt.cjs" encrypt "$BUILD_WORK/screenshot-results.zip" "$BUILD_OUTPUT/screenshot-results.zip.enc"; then
    encrypted=1
  else
    echo 'Encrypted result packaging failed; no plaintext artifact will be uploaded.'
  fi
  bash "$BUILD_HELPERS/cleanup.sh"
  if [ "$encrypted" -ne 1 ]; then result=1; fi
  if [ "$result" -ne 0 ]; then
    echo 'Screenshot build stopped. Any available diagnostics are inside the encrypted result.'
  fi
  exit "$result"
}
trap finish EXIT

node "$BUILD_HELPERS/crypt.cjs" decrypt "$BUILD_HELPERS/source.enc" "$BUILD_WORK/source.zip"
node - <<'NODE'
const fs=require('node:fs'), path=require('node:path'), crypto=require('node:crypto');
const release=JSON.parse(fs.readFileSync(path.join(process.env.BUILD_HELPERS,'release.json')));
const source=fs.readFileSync(path.join(process.env.BUILD_WORK,'source.zip'));
if (!/^[0-9a-f]{64}$/.test(release.sourceSha256) || crypto.createHash('sha256').update(source).digest('hex')!==release.sourceSha256) {
  throw Error('Screenshot source integrity failed');
}
NODE
mkdir -p "$BUILD_WORK/app"
unzip -q "$BUILD_WORK/source.zip" -d "$BUILD_WORK/app"
cd "$BUILD_WORK/app"
test -f capture.json
node - <<'NODE'
const fs=require('node:fs'), path=require('node:path');
const release=JSON.parse(fs.readFileSync(path.join(process.env.BUILD_HELPERS,'release.json')));
const config=JSON.parse(fs.readFileSync('app.json')).expo;
if (config.version!==release.version || config.ios.bundleIdentifier!==release.bundleIdentifier || config.ios.buildNumber!==release.build) {
  throw Error('Screenshot app identity mismatch');
}
NODE
if [ "$HP_IOS_FULL_REBUILD" = false ]; then
  # This fingerprint covers every original file, native module, dependency lock,
  # Expo config and entry point. Only these private capture fixtures may change.
  node - <<'NODE' >> "$BUILD_WORK/build.log" 2>&1
const fs=require('node:fs'), path=require('node:path'), crypto=require('node:crypto');
const mutable=new Set(['capture.json','CaptureApp.tsx','CaptureFixtures.ts','CaptureProductionApp.tsx',
  'src/captureState.ts','src/CaptureIncidentEditor.tsx','src/CaptureDossierEditor.tsx']);
const files=[];
function walk(dir='') {
  for (const name of fs.readdirSync(dir || '.')) {
    const relative=dir ? `${dir}/${name}` : name;
    const stat=fs.lstatSync(relative);
    if (stat.isDirectory()) walk(relative);
    else if (!stat.isFile()) throw Error('Unexpected source entry');
    else if (!mutable.has(relative)) files.push(relative);
  }
}
walk();
const hash=bytes=>crypto.createHash('sha256').update(bytes).digest('hex');
const fingerprint=hash(files.sort().map(name=>`${name}\t${hash(fs.readFileSync(name))}\n`).join(''));
if (files.length!==136 || fingerprint!=='f2130e2fde0586fe5eb0b2d85b7afd944cd1731025eadf4194c9300378f88125') {
  throw Error('Source differs from the compiled native baseline; dispatch with full_rebuild enabled');
}
fs.writeFileSync(path.join(process.env.BUILD_WORK,'results/reuse-source-proof.json'), JSON.stringify({
  unchangedFiles:files.length, fingerprint, mutableCaptureFiles:[...mutable]
},null,2));
NODE
fi
echo 'Verified private source. Installing dependencies for unsigned simulator build.'
npm ci --no-audit --no-fund >> "$BUILD_WORK/build.log" 2>&1
npm run check >> "$BUILD_WORK/build.log" 2>&1
python3 "$BUILD_HELPERS/select-simulator.py" >> "$BUILD_WORK/build.log" 2>&1
SIMULATOR="$(cat "$BUILD_WORK/simulator.txt")"

if [ "$HP_IOS_FULL_REBUILD" = false ]; then
  echo 'Restoring the pinned encrypted simulator app and rebuilding its capture bundle.'
  mkdir -p "$BUILD_WORK/reuse" "$BUILD_WORK/rebundle-assets"
  # Fetch the immutable same-repository artifact. Never forward the API token to
  # the signed artifact-storage URL or write that URL/token into diagnostics.
  node - <<'NODE' >> "$BUILD_WORK/build.log" 2>&1
const fs=require('node:fs'), path=require('node:path'), crypto=require('node:crypto');
(async()=>{
  if (process.env.GITHUB_REPOSITORY!=='trieutiendl/hptelecom' || !process.env.GITHUB_TOKEN) {
    throw Error('Pinned artifact access requires the authorized repository token');
  }
  const artifactId=12467353178, runId=39873789044;
  const endpoint=`https://api.github.com/repos/trieutiendl/hptelecom/actions/artifacts/${artifactId}`;
  const headers={Authorization:`Bearer ${process.env.GITHUB_TOKEN}`,Accept:'application/vnd.github+json',
    'X-GitHub-Api-Version':'2022-11-28'};
  const metadataResponse=await fetch(endpoint,{headers,redirect:'error',signal:AbortSignal.timeout(60000)});
  if (!metadataResponse.ok) throw Error(`Pinned artifact metadata unavailable (${metadataResponse.status}); use full_rebuild`);
  const artifact=await metadataResponse.json();
  if (artifact.id!==artifactId || artifact.expired || artifact.workflow_run?.id!==runId ||
      artifact.workflow_run?.head_branch!=='screenshot-ios-418-47' ||
      !artifact.workflow_run.repository_id || artifact.workflow_run.repository_id!==artifact.workflow_run.head_repository_id ||
      artifact.name!==`hp-ios-review-screenshots-${runId}`) throw Error('Pinned artifact provenance mismatch');
  const response=await fetch(`${endpoint}/zip`,{headers,redirect:'manual',signal:AbortSignal.timeout(60000)});
  if (response.status!==302) throw Error(`Pinned artifact download unavailable (${response.status}); use full_rebuild`);
  const location=new URL(response.headers.get('location'));
  if (location.protocol!=='https:' || location.username || location.password) throw Error('Invalid artifact redirect');
  const download=await fetch(location,{redirect:'error',signal:AbortSignal.timeout(180000)});
  if (!download.ok) throw Error(`Artifact storage download failed (${download.status})`);
  const bytes=Buffer.from(await download.arrayBuffer());
  if (artifact.digest && `sha256:${crypto.createHash('sha256').update(bytes).digest('hex')}`!==artifact.digest) {
    throw Error('Artifact archive digest mismatch');
  }
  fs.writeFileSync(path.join(process.env.BUILD_WORK,'reuse/artifact.zip'),bytes);
})().catch(()=>{console.error('Pinned simulator artifact could not be restored; inspect provenance or dispatch full_rebuild.');process.exit(1);});
NODE
  unset GITHUB_TOKEN
  # Read only the expected encrypted member and then the expected inner app zip.
  # Neither GitHub packaging nor old result members are extracted wholesale.
  python3 - <<'PY' >> "$BUILD_WORK/build.log" 2>&1
import os, zipfile
from pathlib import Path
work = Path(os.environ['BUILD_WORK'])
with zipfile.ZipFile(work / 'reuse/artifact.zip') as archive:
    if archive.namelist() != ['screenshot-results.zip.enc']:
        raise RuntimeError('Unexpected encrypted artifact members')
    (work / 'reuse/screenshot-results.zip.enc').write_bytes(archive.read('screenshot-results.zip.enc'))
PY
  node "$BUILD_HELPERS/crypt.cjs" decrypt "$BUILD_WORK/reuse/screenshot-results.zip.enc" "$BUILD_WORK/reuse/results.zip" >> "$BUILD_WORK/build.log" 2>&1
  python3 - <<'PY' >> "$BUILD_WORK/build.log" 2>&1
import hashlib, io, os, zipfile
from pathlib import Path, PurePosixPath
work = Path(os.environ['BUILD_WORK'])
with zipfile.ZipFile(work / 'reuse/results.zip') as archive:
    if archive.namelist().count('simulator-app.zip') != 1:
        raise RuntimeError('Missing or duplicate compiled app')
    data = archive.read('simulator-app.zip')
if hashlib.sha256(data).hexdigest() != 'ff7b6ca71d1f15b127c5baf91e73d27762ce9f10cabcbd3beace78aff3f15716':
    raise RuntimeError('Pinned simulator app integrity failed')
with zipfile.ZipFile(io.BytesIO(data)) as archive:
    for entry in archive.infolist():
        name = PurePosixPath(entry.filename)
        if name.is_absolute() or '..' in name.parts or name.parts[0] not in ('HPTelecomServer.app', '__MACOSX'):
            raise RuntimeError('Unexpected compiled app archive path')
(work / 'reuse/simulator-app.zip').write_bytes(data)
PY
  ditto -x -k "$BUILD_WORK/reuse/simulator-app.zip" "$BUILD_WORK/reuse" >> "$BUILD_WORK/build.log" 2>&1
  BUILD_APP="$BUILD_WORK/reuse/HPTelecomServer.app"
  export BUILD_APP
  # Record all native contents (including frameworks, plist and permissions).
  node - <<'NODE' >> "$BUILD_WORK/build.log" 2>&1
const fs=require('node:fs'), path=require('node:path'), crypto=require('node:crypto');
const root=process.env.BUILD_APP, entries=[];
function walk(dir='') {
  for (const name of fs.readdirSync(path.join(root,dir)).sort()) {
    const relative=dir ? `${dir}/${name}` : name;
    if (relative==='main.jsbundle' || relative==='assets') continue;
    const full=path.join(root,relative), stat=fs.lstatSync(full);
    const entry={path:relative,mode:stat.mode};
    if (stat.isSymbolicLink()) {
      const resolved=fs.realpathSync(full), boundary=fs.realpathSync(root)+path.sep;
      if (!resolved.startsWith(boundary)) throw Error('Native app symlink escapes the app');
      entry.link=fs.readlinkSync(full);
    }
    else if (stat.isFile()) entry.sha256=crypto.createHash('sha256').update(fs.readFileSync(full)).digest('hex');
    else if (!stat.isDirectory()) throw Error('Unexpected native app entry');
    entries.push(entry);
    if (stat.isDirectory()) walk(relative);
  }
}
walk();
fs.writeFileSync(path.join(process.env.BUILD_WORK,'reuse/native-before.json'),JSON.stringify(entries));
fs.copyFileSync(path.join(root,'main.jsbundle'),path.join(process.env.BUILD_WORK,'reuse/original.jsbundle'));
NODE
  # Match the original Xcode Release bundle invocation and Hermes compiler.
  NODE_ENV=production npx --no-install expo export:embed --entry-file "$BUILD_WORK/app/index.ts" \
    --platform ios --dev false --reset-cache --bundle-output "$BUILD_WORK/reuse/main.js" \
    --assets-dest "$BUILD_WORK/rebundle-assets" --minify false \
    --config-cmd "'$(command -v node)' '$BUILD_WORK/app/node_modules/react-native/cli.js' config" >> "$BUILD_WORK/build.log" 2>&1
  node_modules/hermes-compiler/hermesc/osx-bin/hermesc -emit-binary -max-diagnostic-width=80 -O \
    -out "$BUILD_WORK/reuse/main.jsbundle" "$BUILD_WORK/reuse/main.js" >> "$BUILD_WORK/build.log" 2>&1
  node - <<'NODE' >> "$BUILD_WORK/build.log" 2>&1
const fs=require('node:fs'), path=require('node:path'), crypto=require('node:crypto');
const work=process.env.BUILD_WORK, root=process.env.BUILD_APP;
const original=fs.readFileSync(path.join(work,'reuse/original.jsbundle'));
const replacement=fs.readFileSync(path.join(work,'reuse/main.jsbundle'));
if (replacement.length<16 || !original.subarray(0,12).equals(replacement.subarray(0,12))) {
  throw Error('Hermes bytecode magic/version changed');
}
const generated=path.join(work,'rebundle-assets');
if (fs.readdirSync(generated).some(name=>name!=='assets') || !fs.lstatSync(path.join(generated,'assets')).isDirectory()) {
  throw Error('Bundle export attempted to replace non-asset app contents');
}
function checkAssets(dir) {
  for (const name of fs.readdirSync(dir)) {
    const full=path.join(dir,name), stat=fs.lstatSync(full);
    if (stat.isDirectory()) checkAssets(full);
    else if (!stat.isFile()) throw Error('Unexpected generated asset entry');
  }
}
checkAssets(generated);
fs.rmSync(path.join(root,'assets'),{recursive:true,force:true});
fs.cpSync(path.join(generated,'assets'),path.join(root,'assets'),{recursive:true});
fs.copyFileSync(path.join(work,'reuse/main.jsbundle'),path.join(root,'main.jsbundle'));
const entries=[];
function walk(dir='') {
  for (const name of fs.readdirSync(path.join(root,dir)).sort()) {
    const relative=dir ? `${dir}/${name}` : name;
    if (relative==='main.jsbundle' || relative==='assets') continue;
    const full=path.join(root,relative), stat=fs.lstatSync(full);
    const entry={path:relative,mode:stat.mode};
    if (stat.isSymbolicLink()) entry.link=fs.readlinkSync(full);
    else if (stat.isFile()) entry.sha256=crypto.createHash('sha256').update(fs.readFileSync(full)).digest('hex');
    else if (!stat.isDirectory()) throw Error('Unexpected native app entry');
    entries.push(entry);
    if (stat.isDirectory()) walk(relative);
  }
}
walk();
if (JSON.stringify(entries)!==fs.readFileSync(path.join(work,'reuse/native-before.json'),'utf8')) {
  throw Error('Compiled native app changed during bundle replacement');
}
fs.writeFileSync(path.join(work,'results/reuse-app-proof.json'),JSON.stringify({
  sourceRun:39873789044, sourceArtifact:12467353178,
  originalAppZipSha256:'ff7b6ca71d1f15b127c5baf91e73d27762ce9f10cabcbd3beace78aff3f15716',
  nativeEntriesVerifiedUnchanged:entries.length,
  bundleSha256:crypto.createHash('sha256').update(replacement).digest('hex'),
  hermesHeader:replacement.subarray(0,12).toString('hex')
},null,2));
NODE
else
unset GITHUB_TOKEN
echo 'Generating the native iPhone project.'
npx --no-install expo prebuild --platform ios --clean --no-install >> "$BUILD_WORK/build.log" 2>&1
(cd ios && pod install) >> "$BUILD_WORK/build.log" 2>&1
ruby -rxcodeproj -e '
  project_path = Dir.glob(File.join(ENV.fetch("BUILD_WORK"), "app/ios/*.xcodeproj")).first
  raise "Missing iOS project" unless project_path
  project = Xcodeproj::Project.open(project_path)
  targets = project.native_targets.select { |t| t.product_type == "com.apple.product-type.application" }
  raise "Expected one app target" unless targets.length == 1
  File.write(File.join(ENV.fetch("BUILD_WORK"), "scheme.txt"), targets.first.name)
' >> "$BUILD_WORK/build.log" 2>&1
BUILD_SCHEME="$(cat "$BUILD_WORK/scheme.txt")"
BUILD_WORKSPACE="$(find "$BUILD_WORK/app/ios" -maxdepth 1 -name '*.xcworkspace' -print -quit)"
test -n "$BUILD_WORKSPACE"
echo 'Compiling the real iPhone interface without Apple signing credentials.'
xcodebuild -workspace "$BUILD_WORKSPACE" -scheme "$BUILD_SCHEME" -configuration Release \
  -sdk iphonesimulator -destination "platform=iOS Simulator,id=$SIMULATOR" \
  -derivedDataPath "$BUILD_WORK/DerivedData" -jobs 3 \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO build >> "$BUILD_WORK/build.log" 2>&1
BUILD_APP="$(find "$BUILD_WORK/DerivedData/Build/Products/Release-iphonesimulator" -maxdepth 1 -name '*.app' -print -quit)"
test -n "$BUILD_APP"
export BUILD_APP
fi
python3 - <<'PY' >> "$BUILD_WORK/build.log" 2>&1
import json, os, plistlib, subprocess
from pathlib import Path
app = Path(os.environ['BUILD_APP'])
release = json.loads((Path(os.environ['BUILD_HELPERS']) / 'release.json').read_text())
with (app / 'Info.plist').open('rb') as source:
    info = plistlib.load(source)
if (info.get('CFBundleIdentifier') != release['bundleIdentifier'] or
        info.get('CFBundleShortVersionString') != release['version'] or
        info.get('CFBundleVersion') != release['build'] or
        info.get('CFBundleSupportedPlatforms') != ['iPhoneSimulator'] or
        info.get('DTPlatformName') != 'iphonesimulator' or info.get('HPCaptureOnly') is not True):
    raise RuntimeError('Simulator app identity/platform verification failed')
executable_name = info.get('CFBundleExecutable', '')
if not executable_name or Path(executable_name).name != executable_name:
    raise RuntimeError('Invalid simulator executable name')
executable = app / executable_name
if not executable.is_file() or not os.access(executable, os.X_OK):
    raise RuntimeError('Missing simulator executable')
architecture = subprocess.check_output(['xcrun', 'lipo', '-archs', str(executable)], text=True).split()
if 'arm64' not in architecture:
    raise RuntimeError('Compiled app does not support this Apple Silicon simulator runner')
build_commands = subprocess.check_output(['xcrun', 'vtool', '-show-build', str(executable)], text=True)
if not any(line.split() == ['platform', 'IOSSIMULATOR'] for line in build_commands.splitlines()):
    raise RuntimeError('Compiled executable is not an iOS simulator binary')
if not any('hpcapture' in item.get('CFBundleURLSchemes', []) for item in info.get('CFBundleURLTypes', [])):
    raise RuntimeError('Compiled app is missing its capture-only URL scheme')
PY
echo 'Launching isolated iPhone simulators and capturing the application screens.'
python3 -u "$BUILD_HELPERS/capture.py" 2>> "$BUILD_WORK/build.log"
echo 'Screenshots captured. Packaging the encrypted result and removing temporary files.'
