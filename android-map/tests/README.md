# Map data regression checks

Run with Java 17, an installed Android SDK platform and SDK Sources:

```powershell
.\tests\check-map-api.ps1 -SdkRoot D:\Android\Sdk -Platform android-35 -Sources android-34
```

The script copies the SDK's real `org.json` implementation into the ignored build directory and removes platform-only annotations from those copies. It does not modify the SDK, contact a server, require credentials or install dependencies. The checks are not included in the APK.

Coverage:

- completed and unfinished reports from different months remain in the same route;
- each patrol report retains its own ordered line;
- maintenance routes without coordinates remain listed;
- repeated incident overlays across monthly tasks appear once;
- MX grouping uses a complete-link 20-metre limit, so nearby points cannot chain into one long-distance cluster;
- route folders and metadata remain isolated across teams;
- labels retain endpoint position names;
- an empty server never produces sample routes.

The production task inclusion policy matches the server's `/tonghop` page: fetch `/api/mobile/cable-tasks`, then include only `work_type === "maintenance"`, without a month or completion filter. The server enforces account/team access for both that request and `/api/mobile/cable-tasks/map`.
