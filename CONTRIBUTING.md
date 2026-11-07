# Contributing

Thanks for helping. Bug reports, compatibility reports, ideas and pull requests all go to [GitHub](https://github.com/Restfulbee37/copper-golem-optimisation).

## Reporting a problem

Open an [issue](https://github.com/Restfulbee37/copper-golem-optimisation/issues/new/choose) and pick the form that fits:

- **Bug report**: something breaks, crashes, or golems behave differently with the mod.
- **Compatibility report**: a problem only when another mod is installed.
- **Feature request**: an idea or an optimisation you'd like to see.

The output of `/cgp status`, your Minecraft and mod versions, and the server log help most. For performance problems, a `/cgp bench ab` JSON file (from `copper_golem_mod/benchmarks/`) or a [Spark](https://spark.lucko.me/) profile is ideal.

## Pull requests

1. Fork the repository and branch from the branch for your Minecraft version: `main` for the newest version, `mc/<version>` for older ones (see [Branches](#branches)).
2. Keep each pull request to one change. Add an entry under `## [Unreleased]` in `CHANGELOG.md` if players or server owners would notice it.
3. Run `./gradlew build runGameTest -Pgametest.filter='copper_golem_mod_test:correctness_tests_*'` before you push. GitHub runs the same checks on every pull request.
4. **Golem behavior must not change.** The default optimizations must give exactly vanilla's results; in-game tests in `src/gametest` compare them with vanilla. Anything that changes behavior goes behind an opt-in config option that is off by default, like the stationary sorter.

Before a pull request is merged, the maintainer also runs it through the full pipeline, which includes the workload benchmark on a copy of a real 865-golem farm (see [TESTING.md](TESTING.md)). Once it passes, your pull request is merged and shows as merged on GitHub; you don't need to do anything extra. Your commits keep your name.

By contributing, you agree that your contribution is licensed under the project's [MIT licence](LICENSE).

## Development setup

Requirements: JDK 21. Everything else is downloaded by Gradle.

```bash
./gradlew build        # builds build/libs/copper_golem_mod-<version>.jar and runs the tests
./gradlew test         # JUnit tests only (run inside a Fabric environment via fabric-loader-junit)
./gradlew runGameTest  # in-game tests and the workload benchmark (see TESTING.md)
./gradlew runServer    # dev server in ./run (accept the EULA in run/eula.txt first)
./gradlew genSources   # decompiled, Yarn-mapped Minecraft sources for reading vanilla code
```

## For maintainers

The rest of this file is the maintainer's process. The GitHub repository is a mirror of the maintainer's private GitLab repository, where the full pipeline (including the workload benchmark) runs and releases are made. Pull requests from GitHub are brought into GitLab as merge requests, merged there, and the mirror then updates GitHub, which marks the pull request as merged.

### Porting to a new Minecraft version

1. Create the maintenance branch for the version `main` currently targets, e.g. `git branch mc/1.21.11 main && git push origin mc/1.21.11`.
2. On a `port/mc-<version>` branch, update `gradle.properties` (Minecraft, Loader, Fabric API, Loom), plus the Gradle wrapper and Java version if the new Minecraft needs them. Check [fabricmc.net/develop](https://fabricmc.net/develop) and the matching branch of [fabric-example-mod](https://github.com/FabricMC/fabric-example-mod).
3. Run `./gradlew genSources` and compare the vanilla code each mixin targets with the previous version. The optimizations assume vanilla behavior (see the comments in `GolemOptimizations`), so check the logic, not just the names.
4. Port the in-game tests in `src/gametest` too: the GameTest API and its names change between versions. `TestSupport.isStationary` reads the field `copperGolemMod$stationary` that `CopperGolemMixin` adds, so keep that name or update both. `./gradlew runGameTest` then confirms every mixin applies (they are `require = 1`) and that golems still behave like vanilla.
5. Update [Supported versions](README.md#supported-versions) in the README: the new version becomes the feature version and the previous one moves to bug fixes.
6. Add a `mc::<version>` label and open the merge request.

### Branches

| Branch | Purpose |
| --- | --- |
| `main` | Development for the newest supported Minecraft version. Protected; always buildable. |
| `mc/<minecraft version>` | Maintenance branch for an older Minecraft version, e.g. `mc/1.21.11`. Created when `main` moves to a new Minecraft version. |
| `feature/<name>`, `fix/<name>` | Short-lived work branches, merged through a merge request. |

Merge requests need a passing pipeline. Only `main`, `mc/*` and the version tags are mirrored to GitHub.

### Versioning

Versions are `<mod version>+<Minecraft version>`, for example `0.2.0+1.21.11`.

- The mod version follows [Semantic Versioning](https://semver.org/): MAJOR for config or behavior changes that need user action, MINOR for new features or optimizations, PATCH for fixes.
- The Minecraft version is build metadata. A port to a new Minecraft version with no other changes keeps the mod version: `0.2.0+1.21.11` becomes `0.2.0+1.22`.
- Both parts live in `gradle.properties` (`mod_version`, `minecraft_version`). The jar name and `fabric.mod.json` are generated from them.

### Releasing

1. Make sure the milestone for the version has no open issues.
2. Update `gradle.properties` (`mod_version`, and the Minecraft/Fabric versions if they changed).
3. In `CHANGELOG.md`, rename `## [Unreleased]` to `## [<version>] - <YYYY-MM-DD>` and add a new empty `## [Unreleased]` above it. The heading must match the version exactly, because the pipeline copies that section into the release notes.
4. Commit to `main` (or the `mc/*` branch) and wait for the pipeline to pass.
5. Tag the commit `v<version>` and push the tag:
   ```bash
   git tag -a "v0.2.0+1.21.11" -m "Copper Golem Optimisation 0.2.0 for Minecraft 1.21.11"
   git push origin "v0.2.0+1.21.11"
   ```
6. The tag pipeline checks that the tag matches the version, builds the jar, uploads it to the package registry and creates the GitLab Release with the changelog section as its notes and the milestone attached. Then the `modrinth` job uploads the jar to Modrinth with the same notes, as a beta for 0.x and a release from 1.0.0 on. It only runs when the CI/CD variables `MODRINTH_PROJECT_ID` and `MODRINTH_TOKEN` are set (masked and protected). The notes go public there, so keep them free of private details. `MODRINTH_DRY_RUN=1 MODRINTH_PROJECT_ID=<id> tools/modrinth_upload.sh <jar> <version> <notes file>` shows what would be uploaded.
7. Close the milestone.

Tags matching `v*` are protected and can only be created by maintainers.

### Labels

- `type::bug`, `type::feature`, `type::performance`, `type::compatibility`, `type::docs`, `type::maintenance`
- `priority::high`, `priority::medium`, `priority::low`
- `status::needs-info`, `status::confirmed`, `status::blocked`
- `mc::<version>` for the Minecraft version an issue applies to
- `behavior-changing` for anything that changes how golems behave; such changes must stay behind an opt-in config flag

These are the GitLab labels. GitHub issues use the labels its forms add (`bug`, `compatibility`, `enhancement`).
