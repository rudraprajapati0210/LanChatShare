# Contributing

Start from `main` and create a short-lived branch. For a transport change, describe the failure, phone models, Android versions, route, direction and screen state. Strip peer names, content and credentials from logs. Report security issues privately using [SECURITY.md](SECURITY.md).

Run `./gradlew :app:assembleDebug testDebugUnitTest lintDebug` and `bash tools/check-kotlin.sh` before opening a pull request. The static checks use pinned tools and baselines for pre-existing findings. Fix new findings and keep existing baselines intact. Git Bash or WSL can run the shell checks on Windows.

Use a dedicated, paired set of phones for discovery, hotspot, background and resume changes. Follow the [acceptance checklist](docs/TWO_PHONE_CHECKLIST.md). Record which checks actually ran. Include TalkBack, large text and reduced motion when changing navigation or layout.

Keep protocol changes compatible with existing trust and durable offsets. Use a Conventional Commit subject such as `fix: resume after hotspot rejoin`. Document user-visible changes in the changelog and include screenshots when a screen changes.

Release maintainers tag reviewed commits with `vMAJOR.MINOR.PATCH`. The release workflow publishes a debug APK and generated notes. Installations from different build machines can have different debug signatures. Release signing is outside this workflow.
