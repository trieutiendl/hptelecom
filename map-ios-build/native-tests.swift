import Foundation

var checks = 0
func check(_ condition: @autoclosure () -> Bool, _ name: String) {
    checks += 1
    guard condition() else { fatalError("Native regression failed: \(name)") }
}
func url(_ value: String) -> URL { URL(string: value)! }
let api = "https://hptelecom.id.vn"
var connectivity = MapConnectionState()
check(!connectivity.known && !connectivity.available, "startup does not invent connectivity")
check(connectivity.update(available: false), "first real offline observation is published")
check(!connectivity.update(available: false), "unchanged offline path does not emit duplicate event")
check(connectivity.update(available: true), "network recovery emits a change")
check(!connectivity.update(available: true), "unchanged online path does not emit duplicate event")
let onlineState = connectivity.payload(sequence: 9)
check(onlineState["networkAvailable"] as? Bool == true && onlineState["offline"] as? Bool == false, "online payload follows OS path")
check(onlineState["cacheOnly"] as? Bool == false && onlineState["sequence"] as? Int == 9, "online does not claim cache isolation")
check(connectivity.update(available: false), "network loss emits a change")
let offlineState = connectivity.payload(sequence: 10)
check(offlineState["networkAvailable"] as? Bool == false && offlineState["offline"] as? Bool == true && offlineState["cacheOnly"] as? Bool == false, "offline path does not promise cached remote imagery")
for (code, expected) in [(NSURLErrorTimedOut,"ETIMEDOUT"),(NSURLErrorDNSLookupFailed,"ENOTFOUND"),(NSURLErrorNetworkConnectionLost,"NETWORK_ERROR"),(NSURLErrorServerCertificateUntrusted,"CERT_ERROR"),(NSURLErrorSecureConnectionFailed,"CERT_ERROR")] {
    let failure = MapSecurity.nativeErrorResult(NSError(domain: NSURLErrorDomain, code: code, userInfo: [NSLocalizedDescriptionKey:"Bearer private-token https://private.invalid/?key=secret"]))
    check(failure["code"] as? String == expected, "native connection error category \(expected)")
    check(!(failure["error"] as? String ?? "").contains("private"), "native failure omits raw secret description")
}
check(MapSecurity.nativeErrorResult(MapFailure.message("Phản hồi vượt giới hạn tải."))["code"] as? String == "NATIVE_ERROR", "native response validation is not a connection failure")
check(MapSecurity.trusted(MapSecurity.startURL), "local app origin")
check(!MapSecurity.trusted(url(api + "/hpmap/index.html")), "remote frame rejected")
check(!MapSecurity.trusted(url("hpmap://app/hpmap/index.html?frame=other")), "unexpected query rejected")
check(MapSecurity.allowedAPI(url(api + "/api/me"), method:"GET"), "profile read")
check(MapSecurity.allowedAPI(url(api + "/api/login"), method:"POST"), "login method")
check(!MapSecurity.allowedAPI(url(api + "/api/login"), method:"GET"), "login GET rejected")
check(!MapSecurity.allowedAPI(url("http://hptelecom.id.vn/api/me"), method:"GET"), "TLS required")
check(!MapSecurity.allowedAPI(url("https://hptelecom.id.vn.evil.invalid/api/me"), method:"GET"), "spoof host rejected")
check(!MapSecurity.allowedAPI(url("https://user:password@hptelecom.id.vn/api/me"), method:"GET"), "userinfo rejected")
check(!MapSecurity.allowedAPI(url(api + ":8443/api/me"), method:"GET"), "custom port rejected")
check(!MapSecurity.allowedAPI(url(api + "/api/users"), method:"POST"), "unrelated endpoint rejected")
check(MapSecurity.allowedAPI(url(api + "/api/map-routes?task_id=catalog-123"), method:"GET"), "route read")
check(!MapSecurity.allowedAPI(url(api + "/api/map-routes?task_id=../other"), method:"GET"), "route traversal rejected")
check(!MapSecurity.allowedAPI(url(api + "/api/map-routes?task_id=a&task_id=b"), method:"GET"), "ambiguous route query")
check(MapSecurity.allowedAPI(url(api + "/api/map-basemap?client=android"), method:"GET"), "shared mobile key")
check(!MapSecurity.allowedAPI(url(api + "/api/map-basemap?client=other"), method:"GET"), "unknown client rejected")
check(MapSecurity.allowedAPI(url("https://api.maptiler.com/maps/satellite-v4/256/tiles.json?key=unit_key_12345"), method:"GET"), "tile metadata")
check(!MapSecurity.allowedAPI(url("https://api.maptiler.com/maps/satellite-v4/256/tiles.json?key=unit&extra=bad"), method:"GET"), "metadata query restricted")
check(MapSecurity.allowedTile(url("https://tile.openstreetmap.org/1/1/1.png")), "OSM tile")
check(MapSecurity.allowedTile(url("https://a.tile.opentopomap.org/4/10/12.png")), "terrain tile")
check(MapSecurity.allowedTile(url("https://api.maptiler.com/maps/hybrid-v4/256/4/10/12.png?key=unit_key_12345")), "hybrid tile")
check(!MapSecurity.allowedTile(url("https://tile.openstreetmap.org/1/2/1.png")), "out of range x")
check(!MapSecurity.allowedTile(url("https://tile.openstreetmap.org/1/1/2.png")), "out of range y")
check(!MapSecurity.allowedTile(url("https://tile.openstreetmap.org/30/1/1.png")), "excess zoom rejected")
check(!MapSecurity.allowedTile(url("https://api.maptiler.com/maps/other/256/4/10/12.png?key=unit")), "style allowlist")
check(!MapSecurity.allowedTile(url("https://tile.openstreetmap.org/1/1/1.png?token=private")), "extra tile query rejected")
check(!MapSecurity.externalAllowed(url("file:///etc/passwd")), "file URLs rejected")
check(MapSecurity.externalAllowed(url("https://maps.apple.com/?daddr=12,108")), "Apple Maps link")
let root = URL(fileURLWithPath:NSTemporaryDirectory()).appendingPathComponent("hp-map-test-assets",isDirectory:true)
check(MapSecurity.assetURL(url("hpmap://app/hpmap/index.html"),root:root)?.lastPathComponent == "index.html", "bundle asset")
check(MapSecurity.assetURL(url("hpmap://app/%2e%2e/private.txt"),root:root) == nil, "encoded asset traversal")
check(MapSecurity.assetURL(url("hpmap://app/hpmap/index.html?extra=1"),root:root) == nil, "asset query rejected")
check(MapSecurity.fileName("../My:Route.kmz") == "_My_Route.kmz", "safe export name")
check(MapSecurity.fileName("...") == "HP-Map.kmz", "empty export name")
let kml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><name>HP Hà Phong</name></Document></kml>"
let zip = try MapKMZ.make(kml)
check(zip.prefix(4) == Data([0x50,0x4b,0x03,0x04]), "KMZ ZIP signature")
check(zip.count > kml.utf8.count, "KMZ ZIP directory present")
do { _ = try MapKMZ.make("<!DOCTYPE kml [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><kml/>"); fatalError("DTD was accepted") } catch { checks += 1 }
do { _ = try MapKMZ.make("<kml>" + String(repeating:"x",count:MapSecurity.maxMapFile) + "</kml>"); fatalError("Oversize was accepted") } catch { checks += 1 }
check(CommandLine.arguments.count == 2, "fixture output supplied")
try zip.write(to:URL(fileURLWithPath:CommandLine.arguments[1]),options:.atomic)
print("\(checks) native security/KMZ checks passed.")
