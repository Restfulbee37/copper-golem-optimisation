# Contributing

## Development setup

Requirements: JDK 21. Everything else is downloaded by Gradle.

```bash
./gradlew build        # builds build/libs/copper_golem_mod-<version>.jar
./gradlew runServer    # dev server in ./run (accept the EULA in run/eula.txt first)
./gradlew genSources   # decompiled, Yarn-mapped Minecraft sources for reading vanilla code
```

## Branches

| Branch | Purpose |
| --- | --- |
| `main` | Development for the newest supported Minecraft version. Protected; always buildable. |
| `mc/<minecraft version>` | Maintenance branch for an older Minecraft version, e.g. `mc/1.21.11`. Created when `main` moves to a new Minecraft version. |
| `feature/<name>`, `fix/<name>` | Short-lived work branches, merged through a merge request. |

Merge requests need a passing pipeline. Squash commits on merge if the branch history is noisy.

## Versioning

Versions are `<mod version>+<Minecraft version>`, for example `0.2.0+1.21.11`.

- The mod version follows [Semantic Versioning](https://semver.org/): MAJOR for config or behavior changes that need user action, MINOR for new features or optimizations, PATCH for fixes.
- The Minecraft version is build metadata. A port to a new Minecraft version with no other changes keeps the mod version: `0.2.0+1.21.11` becomes `0.2.0+1.22`.
- Both parts live in `gradle.properties` (`mod_version`, `minecraft_version`). The jar name and `fabric.mod.json` are generated from them.

## Releasing

1. Make sure the milestone for the version has no open issues.
2. Update `gradle.properties` (`mod_version`, and the Minecraft/Fabric versions if they changed).
3. In `CHANGELOG.md`, rename `## [Unreleased]` to `## [<version>] - <YYYY-MM-DD>` and add a new empty `## [Unreleased]` above it. The heading must match the version exactly, because the pipeline copies that section into the release notes.
4. Commit to `main` (or the `mc/*` branch) and wait for the pipeline to pass.
5. Tag the commit `v<version>` and push the tag:
   ```bash
   git tag -a "v0.2.0+1.21.11" -m "CopperGolemMod 0.2.0 for Minecraft 1.21.11"
   git push origin "v0.2.0+1.21.11"
   ```
6. The tag pipeline checks that the tag matches the version, builds the jar, uploads it to the package registry and creates the GitLab Release with the changelog section as its notes and the milestone attached.
7. Close the milestone.

Tags matching `v*` are protected and can only be created by maintainers.

## Labels

- `type::bug`, `type::feature`, `type::performance`, `type::compatibility`, `type::docs`, `type::maintenance`
- `priority::high`, `priority::medium`, `priority::low`
- `status::needs-info`, `status::confirmed`, `status::blocked`
- `mc::<version>` for the Minecraft version an issue applies to
- `behavior-changing` for anything that changes how golems behave; such changes must stay behind an opt-in config flag
