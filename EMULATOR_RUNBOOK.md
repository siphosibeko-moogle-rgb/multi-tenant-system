# Running the app — emulator runbook

Everything needed to go from a cold machine to the app on screen, plus the
failures you have already hit and what they mean.

Paths assume the SDK is at `~/Android/Sdk` and the repo at
`~/Desktop/multi-tenant-system`.

---

## One-time setup

Add the SDK tools to your PATH so `adb` and `emulator` are always available:

```bash
echo 'export PATH="$PATH:$HOME/Android/Sdk/platform-tools:$HOME/Android/Sdk/emulator"' >> ~/.bashrc
```

Parrot ships `podman-docker`, which sets `DOCKER_HOST` to a Podman socket at
login. Testcontainers and `docker compose` both read it and fail. Override it:

```bash
echo 'unset DOCKER_HOST' >> ~/.bashrc
```

`~/.bashrc` runs after `/etc/profile.d/`, so it wins. Open a new terminal and
check both took effect:

```bash
which adb emulator      # both should resolve
env | grep -i docker    # should print nothing
```

`android/local.properties` holds the SDK path and is gitignored, so it does not
survive a fresh clone. If Gradle says "SDK location not found":

```bash
echo "sdk.dir=$HOME/Android/Sdk" > ~/Desktop/multi-tenant-system/android/local.properties
```

---

## Every session — three terminals

### Terminal 1: database and backend

```bash
cd ~/Desktop/multi-tenant-system
docker compose up -d db
cd inventory-backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Leave it running. Confirm from another terminal:

```bash
curl -s http://localhost:8080/api/v1/actuator/health
```

Want `"status":"UP"`, including all three pools (`appDataSource`,
`appConnectionPool`, `loginDataSource`).

### Terminal 2: emulator

```bash
nohup emulator -avd Pixel_7 -no-snapshot-load > /tmp/emulator.log 2>&1 &
```

`nohup` matters. Launching with a bare `&` ties the emulator to the shell, and
it segfaults when that shell moves on — which is exactly what happened the
first time.

Wait until the OS has actually finished booting, not merely until the device
appears:

```bash
adb wait-for-device
adb shell getprop sys.boot_completed   # want: 1
adb devices                            # want: emulator-5554   device
```

`adb devices` reports the device well before boot completes. Installing in that
window fails in confusing ways, so wait for the `1`.

Studio's Device Manager (▶ next to Pixel 7) does the same thing and manages the
process lifecycle for you. Either is fine.

### Terminal 3: build and install

```bash
cd ~/Desktop/multi-tenant-system/android
./gradlew installDebug
```

Launch it:

```bash
adb shell monkey -p com.example.inventory.mobile -c android.intent.category.LAUNCHER 1
```

Or swipe up in the emulator and tap **Inventory**.

---

## Useful commands

```bash
# Reinstall after a code change
./gradlew installDebug

# Clean restart of the app (fresh state, forces a re-fetch)
adb shell am force-stop com.example.inventory.mobile
adb shell monkey -p com.example.inventory.mobile -c android.intent.category.LAUNCHER 1

# Logs for this app only
adb logcat --pid=$(adb shell pidof -s com.example.inventory.mobile)

# Wipe app data (clears stored tokens — back to a logged-out state)
adb shell pm clear com.example.inventory.mobile

# Unit tests, no emulator needed
./gradlew testDebugUnitTest

# Shut the emulator down
adb emu kill
```

---

## Test data

Register a tenant:

```bash
curl -s -X POST http://localhost:8080/api/v1/auth/register-tenant \
  -H 'Content-Type: application/json' \
  -d '{"businessName":"Test Shop","slug":"testshop","ownerEmail":"you@example.test","ownerPassword":"correct-horse-battery","ownerName":"You"}'
```

Log in and keep the token:

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"you@example.test","password":"correct-horse-battery","tenantSlug":"testshop"}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["accessToken"])')

echo "${TOKEN:0:20}"    # non-empty means it worked
```

Create products:

```bash
for i in $(seq 1 30); do
  curl -s -X POST http://localhost:8080/api/v1/products \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d "{\"sku\":\"SKU-$i\",\"name\":\"Product $i\",\"sellingPrice\":$((i*10))}" > /dev/null
done

# ALWAYS verify — the loop above hides every error
curl -s "http://localhost:8080/api/v1/products?limit=5" -H "Authorization: Bearer $TOKEN" | head -c 300
```

Access tokens last 15 minutes. When calls start returning 401, re-run the login.

---

## Troubleshooting

| Symptom | Cause |
|---|---|
| `No connected devices!` | Emulator not booted, or `sys.boot_completed` is not yet `1` |
| `Unable to access jarfile gradle-wrapper.jar` | Wrapper jar missing — should be committed; was gitignored once already |
| `SDK location not found` | `local.properties` missing (gitignored, so absent on a fresh clone) |
| `adb: command not found` | PATH not set — see one-time setup |
| Emulator segfaults after a moment | Started with `&` instead of `nohup ... &` |
| `CLEARTEXT communication not permitted` | Debug network security config not applied |
| App shows "Can't reach the server" while backend is up | Base URL must be `http://10.0.2.2:8080/api/v1/` — `localhost` inside the emulator means the emulator |
| 404 on every call | Backend context path is `/api/v1`; a client pointed at the root will 404 |
| `docker compose` cannot reach the daemon | `DOCKER_HOST` set to Podman by `/etc/profile.d/podman-docker.sh` |

**The single most important line:** inside the emulator, your host machine is
`10.0.2.2`. `localhost` there refers to the emulated phone itself, so a request
to `http://localhost:8080` goes nowhere and looks exactly like the backend
being down.

---

## Verification checklist

Worth re-running after any auth or networking change:

1. Sign in with valid credentials → tenant name shown.
2. Wrong password → "Your email or password was not recognised."
3. Backend stopped → "Can't reach the server…" with a retry, not a crash.
4. Empty catalogue → "No products yet", not a spinner or a blank screen.
5. 30+ products → scroll → page two appends without jumping to the top.
6. Sign in as a second tenant → a completely different catalogue.

Number 6 is the one that matters most. It exercises RLS, the JWT `tid` claim,
and per-connection tenant binding through the UI — proving the thing the whole
architecture exists to guarantee, rather than trusting a test that could itself
be wrong.
