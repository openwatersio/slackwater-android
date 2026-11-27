# Releasing to Google Play

The [Play upload workflow](../.github/workflows/release.yml) builds a signed release Android App Bundle from `main` and uploads it to a Google Play testing track. It runs every night and on request. It never publishes to production: `scripts/play-upload.mjs` refuses the production track, and the Play service account is not given the permission.

## One-time setup

The workflow needs three things it cannot create for itself: the upload key, a first bundle uploaded by hand, and permission for GitHub Actions to call the Google Play Developer API. Do the steps in order.

### 1. Create the upload key

Play App Signing holds the key that signs what testers install. The upload key only proves to Google that a bundle comes from this project, and Google can reset it if it is lost.

1. Create a PKCS12 keystore holding one RSA 2048 key with the alias `upload`. Run this outside the repository, and choose a store password when asked:

   ```sh
   keytool -genkeypair -storetype PKCS12 -keyalg RSA -keysize 2048 -validity 10000 -alias upload -keystore slackwater-upload.p12
   ```

2. Store the keystore file and its password in the organization's password manager. A PKCS12 keystore protects its key with the store password, so there is one password.
3. In GitHub, create an environment named `play` under **Settings → Environments**. Set **Deployment branches and tags** to **Selected branches and tags** and add `main`, so a workflow run from any other branch cannot use the environment. Add no required reviewers, which would hold every nightly run for approval.
4. Add three secrets to the `play` environment, not to the repository. A repository secret can be read by a workflow on any branch; an environment secret only by a job the branch rule lets in.

   | Secret | Holds |
   | --- | --- |
   | `UPLOAD_KEYSTORE_BASE64` | The keystore file, base64-encoded: `base64 < slackwater-upload.p12 \| gh secret set UPLOAD_KEYSTORE_BASE64 --env play` |
   | `UPLOAD_KEYSTORE_PASSWORD` | The store password: `gh secret set UPLOAD_KEYSTORE_PASSWORD --env play` |
   | `UPLOAD_KEY_ALIAS` | `upload` |

### 2. Make the first upload by hand

The Google Play Developer API only changes an app that already has an uploaded build, so the first bundle goes in through Play Console. Until then the workflow's upload job fails with "Google Play has no app the API can edit".

1. Get a bundle signed with the upload key. The simplest way is to run the workflow once (see [Run it by hand](#run-it-by-hand)): its `build` and `sign` jobs pass, its `upload` job fails because the rest of this setup is missing, and the signed bundle is the `bundle` artifact on the run page. To build it locally instead, run the following; without `-PslackwaterVersionCode` the bundle has version code 1.

   ```sh
   ./gradlew :app:bundleRelease -PslackwaterUploadStoreFile=/absolute/path/to/slackwater-upload.p12 -PslackwaterUploadStorePassword=… -PslackwaterUploadKeyPassword=… -PslackwaterUploadKeyAlias=upload
   ```

   The bundle is `app/build/outputs/bundle/release/app-release.aab`. The four `slackwaterUpload*` properties can live in `~/.gradle/gradle.properties` instead of the command line.
2. In Play Console, open Slackwater, go to **Test and release → Testing → Internal testing**, create a release, upload the bundle and roll it out.
3. Under **Test and release → App integrity → Play app signing**, check that the upload key certificate's SHA-256 fingerprint matches `keytool -list -v -keystore slackwater-upload.p12 -alias upload`.

### 3. Let GitHub Actions call the Play API

The workflow holds no Google key. Its `upload` job presents its GitHub identity to Google Cloud workload identity federation and receives a token for a service account that lasts 15 minutes. Google only accepts that identity from this repository's `play` environment on `main`.

Run these with `gcloud` signed in as an owner of a Google Cloud project that belongs to Open Waters. `PROJECT_ID` is that project's ID and `PROJECT_NUMBER` its number (`gcloud projects describe PROJECT_ID --format='value(projectNumber)'`).

1. Enable the APIs:

   ```sh
   gcloud services enable androidpublisher.googleapis.com iam.googleapis.com iamcredentials.googleapis.com sts.googleapis.com --project=PROJECT_ID
   ```

2. Create the service account. It needs no Google Cloud roles.

   ```sh
   gcloud iam service-accounts create play-upload --display-name="Slackwater Play upload" --project=PROJECT_ID
   ```

3. Create the identity pool and its GitHub provider. `1392366670` is this repository's ID, which survives a rename.

   ```sh
   gcloud iam workload-identity-pools create github --location=global --display-name="GitHub Actions" --project=PROJECT_ID
   gcloud iam workload-identity-pools providers create-oidc slackwater-android --location=global --workload-identity-pool=github --issuer-uri="https://token.actions.githubusercontent.com" --attribute-mapping="google.subject=assertion.sub,attribute.repository_id=assertion.repository_id" --attribute-condition="assertion.repository_id == '1392366670' && assertion.environment == 'play' && assertion.ref == 'refs/heads/main'" --project=PROJECT_ID
   ```

4. Allow that identity to act as the service account:

   ```sh
   gcloud iam service-accounts add-iam-policy-binding play-upload@PROJECT_ID.iam.gserviceaccount.com --role=roles/iam.workloadIdentityUser --member="principalSet://iam.googleapis.com/projects/PROJECT_NUMBER/locations/global/workloadIdentityPools/github/attribute.repository_id/1392366670" --project=PROJECT_ID
   ```

5. In Play Console, open **Users and permissions → Invite new users**. Enter `play-upload@PROJECT_ID.iam.gserviceaccount.com`. Under **App permissions**, add Slackwater and select only **Release apps to testing tracks**. Leave account permissions empty and send the invitation.
6. Add these variables to the `play` environment. None of them is secret.

   | Variable | Holds |
   | --- | --- |
   | `PLAY_WORKLOAD_IDENTITY_PROVIDER` | `projects/PROJECT_NUMBER/locations/global/workloadIdentityPools/github/providers/slackwater-android` |
   | `PLAY_SERVICE_ACCOUNT` | `play-upload@PROJECT_ID.iam.gserviceaccount.com` |
   | `PLAY_RELEASE_STATUS` | Optional. `draft` uploads each build as a draft release that someone rolls out in Play Console. Unset means `completed`, which delivers the build to the track's testers. |

Run the workflow by hand to test the setup. If Play answers "Only releases with status draft may be created on draft app", it still treats the app as a draft: set `PLAY_RELEASE_STATUS` to `draft`, and remove the variable once Play accepts completed releases.

## What the nightly does

The workflow runs at 09:23 UTC every day on the head of `main`.

1. A re-run fails if its commit is no longer the head of `main`, because it would give old code a newer version code: start a new run instead. `select`, `build` and `upload` each check this, since re-running only the failed jobs skips `select`. A first attempt is not checked; `main` may move while it runs. `select` then waits up to 45 minutes for the [Android workflow](../.github/workflows/android.yml) to finish on that commit, and fails the run if it did not pass. A scheduled run then stops, successfully, if an earlier run already uploaded the same commit to internal testing. An upload to another track does not count.
2. `build` syncs and verifies the pinned upstream sources and builds `:app:bundleRelease`, unsigned. The Android workflow's other checks are not repeated; `select` has already required that workflow to pass on this commit. It then runs `scripts/check-apk.py` on the bundle: the reviewed assets and no others, the two files the app reads in place marked uncompressed, and no test fixture code. No secret reaches this job, so nothing Gradle runs can read the upload key.
3. `sign` downloads that bundle and signs it with `jarsigner` and the upload key. It checks out nothing and runs no code from the repository.
4. `upload` has access to Google and is not given the signing secrets. It runs `scripts/play-upload.mjs`, which opens a Play edit, uploads the bundle, assigns it to the track and commits the edit. If a call fails it discards the edit, so a failed run normally changes nothing in Play. The exception is a commit whose answer is lost on the way back: Play has published the release, the run reports failure, and the next night uploads the same commit again under a new version code.

Scheduled runs upload to the internal testing track. Internal testing takes a build without review and reaches only the internal tester list, so a broken nightly cannot reach the closed testing group. A candidate for the closed track is a deliberate choice: promote a nightly from internal testing in Play Console, or run the workflow by hand with that track.

Each upload has a version code from `node scripts/version-code.mjs`: the UTC date as `YYMMDD` followed by the quarter hour of the day, `00` to `95`. A run at 09:23 UTC on 26 November 2026 produces `26112637`. The code is taken when the `build` job runs, after any wait for CI. Codes rise with time, and two builds collide only in the same quarter hour; start a new run then, since re-running the failed `upload` job sends the same bundle. The Play release is named with the version code and the commit, such as `26112637 (59b0459)`. Local builds keep version code 1 unless given `-PslackwaterVersionCode`. A bundle built for a manual upload in Play Console needs a code Play has not seen, and one higher than the track's current release for testers to receive it; `-PslackwaterVersionCode=$(node scripts/version-code.mjs)` gives both.

The signed bundle stays on the run page as the `bundle` artifact for 14 days.

GitHub disables a scheduled workflow in a public repository after 60 days without repository activity, and shows a banner on the Actions tab to re-enable it.

## Run it by hand

Open **Actions → Play upload → Run workflow**, keep the branch as `main`, and choose the track, or run:

```sh
gh workflow run release.yml --ref main -f track=internal
```

`track` is `internal` by default. The closed testing track that Play Console creates is named `alpha` in the API, and any other closed track goes by the name it was created with. When Play refuses a track, the error lists the app's track names. A run from a branch other than `main` does nothing.

A manual run uploads even when the commit was uploaded before, with a new version code.

Release builds do not sell Premium. To check purchases, run the workflow with `-f premium=true`, which builds with `-PpremiumEnabled=true`.

- Only a Play license tester buys without being charged, and license testers are their own list in Play Console (**Settings → License testing**), separate from any track's testers. Before the run, make sure every internal tester is on it. The workflow cannot check this.
- Such a build goes to internal testing only, and its Play release name ends in "sells Premium" so it is not promoted to another track by mistake.
- The next nightly that uploads replaces it with an ordinary build, whether or not the commit was uploaded before. A nightly uploads nothing while Android CI is failing on the head of `main`, so the build that sells Premium stays until then. Do not leave it to the nightly: when the purchase checks are done, start an ordinary run by hand.
- An ordinary build still asks Play about an account that bought in a test build, so a refund or a lapsed test subscription ends Premium there too.

An upload replaces the track's releases with the new one. On internal testing that is the point. On a closed track it also removes a draft or staged release someone prepared in Play Console, so check the track there first. The open testing track (`beta`) and production are refused.
