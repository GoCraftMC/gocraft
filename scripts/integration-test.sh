#!/bin/sh
# Runs the integration gomod inside a real Minecraft server using the game's GameTest mode, which
# is Fabric's headless CI runner: a temporary test world, with no eula.txt involved. Fails unless every check in testmod passes.
set -eu

here="$(cd "$(dirname "$0")/.." && pwd)"
example="$here/../gocraft-example"
run="${1:-$here/build/it-run}"

"$here/gradlew" -p "$here" --no-configuration-cache -q build :go-jar:jar -x test
cp "$here/go-jar/build/libs/go.jar" "$here/testmod/go.jar"
cp "$here/go-jar/build/libs/go.jar" "$example/go.jar"
(cd "$example" && java -jar go.jar gomod >/dev/null)
# genBindings has to run first: go mod tidy needs the generated package to exist.
(cd "$here/testmod" && java -jar go.jar genBindings >/dev/null && go mod tidy && java -jar go.jar gomod >/dev/null)

rm -rf "$run"
mkdir -p "$run/mods/gomods"
cp "$here/build/libs/gocraft-0.1.0.jar" "$run/mods/"
cp "$example"/build/libs/*.gomod "$here"/testmod/build/libs/*.gomod "$run/mods/gomods/"

mc=26.3 loader=0.20.1 installer=1.1.2 api=0.165.0+26.3
cache="$HOME/.gocraft/cache"
mkdir -p "$cache/servers" "$cache/mods"
launcher="$cache/servers/fabric-server-$mc-$loader-$installer.jar"
[ -f "$launcher" ] || curl -fsSL -o "$launcher" "https://meta.fabricmc.net/v2/versions/loader/$mc/$loader/$installer/server/jar"
apijar="$cache/mods/fabric-api-$api.jar"
[ -f "$apijar" ] || curl -fsSL -o "$apijar" "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/${api%+*}%2B${api#*+}/fabric-api-$api.jar"
cp "$apijar" "$run/mods/"

# Fabric API's published jar leaves out its test runner, so add it the way Loom does for test runs.
gametest=4.0.35+3434d6d95d
gametestjar="$cache/mods/fabric-gametest-api-v1-$gametest.jar"
[ -f "$gametestjar" ] || curl -fsSL -o "$gametestjar" "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-gametest-api-v1/${gametest%+*}%2B${gametest#*+}/fabric-gametest-api-v1-$gametest.jar"
cp "$gametestjar" "$run/mods/"

# macOS has no timeout command; there, CI's own job timeout is the backstop.
limit=""
if command -v timeout >/dev/null; then limit="timeout ${IT_TIMEOUT:-400}"; fi

cd "$run"
$limit java --enable-native-access=ALL-UNNAMED -Xmx2G -Dfabric-api.gametest=true \
	-jar "$launcher" nogui 2>&1 | tee it.log | grep -E 'IT (PASS|FAIL)|GOCRAFT-IT|GoCraft ready|FATAL|panicked|Could not load' || true

result="$(grep -o 'GOCRAFT-IT RESULT passed=[0-9]* failed=[0-9]*' it.log | tail -1 || true)"
echo "${result:-NO RESULT LINE: see $run/it.log}"
grep -q 'fib(10) = 55' it.log || { echo "FAIL: /gopher fib did not reply through Brigadier"; exit 1; }
case "$result" in
	*passed=*failed=0) exit 0 ;;
	*) exit 1 ;;
esac
