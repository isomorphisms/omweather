# Agent instructions

Apply the shared evidence and acceptance guardrails in
`isomorphisms/ai-ci/AGENTS.md`.

OMWeather test APKs must preserve the existing package name, persistent test
signer, and nondecreasing version code. Do not replace the checked stable signer
with a runner-local or freshly generated debug key, and do not uninstall an
existing app to hide a signer or downgrade mismatch. Replacement installation
without uninstall is required acceptance for installable test APKs.

Keep F-Droid or other production/store signing separate from this public
test-only signing identity.
