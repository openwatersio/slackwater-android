# Licensing and our CLA

Slackwater for Android is licensed under [GPL-3.0](../LICENSE). Contributors sign the same [Contributor License Agreement](../CLA.md) as [Slackwater for iOS](https://github.com/openwatersio/slackwater-ios), which grants Open Water Software, LLC a broad, sublicensable copyright and patent license over each contribution. Contributors keep full copyright in their work, and the project commits to releasing contributions under an OSI-approved open source license.

The iOS app needs that grant to ship on the App Store, whose terms conflict with GPLv3; [slackwater-ios `docs/licensing.md`](https://github.com/openwatersio/slackwater-ios/blob/main/docs/licensing.md) explains the conflict. The Android app shares code, design and contributors with it, so one agreement covers both apps and lets work move between them. It also covers distribution through app stores, Google Play included, under the store's own terms.

## CLA automation

The CLA workflow records signatures in `.cla.json` on `main` using the organization-owned Open Waters CLA App. The App has Single file write permission for `.cla.json` and Metadata read permission. Its ruleset bypass permits signature commits without giving the ordinary GitHub Actions token a bypass. The Android workflow skips pushes to `main` that change only `.cla.json`.

Maintainers configure the organization variable `CLA_APP_CLIENT_ID` and encrypted Actions secret `CLA_APP_PRIVATE_KEY`, restricted to the participating repositories. The workflow creates a short-lived token scoped to this repository. Never check out or execute pull-request code in the privileged CLA workflow. See the [shared setup guide](https://github.com/openwatersio/cla-github-action/blob/master/docs/open-waters.md).

Each pull request runs the secret-free `CLA trigger` workflow. When it completes, the CLA workflow runs from `main`, checks the open pull request whose head is that commit, and sets a `cla` commit status on it. Human comments mentioning the CLA or `recheck` reach the action's signing-text validation and update the status. Claude co-author metadata is exempt, while human contributors must sign. If storage rejects a write, the `cla` status reports an error; comment `recheck` to retry. The `cla` status and `.cla.json` are authoritative even if a bot comment has already acknowledged signing. An unsigned result shows only in the status, not as a failed CLA workflow run.
