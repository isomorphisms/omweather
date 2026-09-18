# CI test signing

`omweather-ci-debug.keystore.b64` is a public, test-only Android signing
identity for debug APKs produced by this repository's GitHub Actions workflow.

It is intentionally not a production or store signing key. The workflow decodes
it to the standard Android debug-keystore location before building, so repeated
CI APKs for `org.woheller69.omweather` retain the same test signer instead of
creating a new signer on every hosted runner.

Identity:

- alias: `androiddebugkey`
- store/key password: `android`
- certificate SHA-256:
  `D9:7B:EA:5A:D8:A6:03:94:B8:4D:3B:24:56:16:EB:69:4A:F6:70:A4:3C:1E:52:7F:8F:0D:3C:AA:A5:1C:AF:2A`

An installation already signed by F-Droid, upstream, or another test key cannot
be replaced by this key; that signer migration can require one uninstall. After
the CI-signed build is installed, later CI builds must preserve this certificate
and use a nondecreasing `versionCode` for replacement installs.

Never reuse this public test key for production signing.
