# GoCraft

A Fabric mod that loads full Minecraft mods written in Go, the way fabric-language-kotlin does for Kotlin.
Go mods are native code with the whole Go runtime, and to the game they are ordinary mods.

**Go mods are not sandboxed.** They run as native code with the same access to your computer as the game
itself, so only install ones from people you trust. GoCraft says so in the log on every launch, and on screen
when the set of installed Go mods changes.

This repo has two parts:

- `src/`: the Fabric mod (`com.go.craft`), which finds `.gomod` files in `mods/gomods` and runs them.
- `go-jar/`: `go.jar`, the build tool Go mods use instead of Gradle.

Download the mod and `go.jar` from the [releases](https://github.com/GoCraftMC/gocraft/releases).

Read the [documentation](https://github.com/GoCraftMC/gocraft-docs), or see
[gocraft-example](https://github.com/GoCraftMC/gocraft-example) for a complete mod.

## Building

```sh
./gradlew build :go-jar:jar
```

This produces `build/libs/gocraft-<version>.jar` and `go-jar/build/libs/go.jar`.

## Testing

```sh
scripts/integration-test.sh
```

This runs `testmod/` inside a real Minecraft server, using Fabric's headless test mode. It expects
`gocraft-sdk` and `gocraft-example` checked out next to this repo.

## License

BSD-4-Clause. This product includes software developed by The GoCraft Authors.
