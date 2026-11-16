# Safe GitHub write access for ARTHER

ARTHER's GitHub repository write tool is disabled by default.

## Required Render environment variables

Configure these on the ARTHER backend service only after creating a dedicated GitHub token:

- `GITHUB_REPOSITORY=cherguihakim34-hub/ARTHER-Android`
- `GITHUB_BRANCH=arther-dev`
- `GITHUB_TOKEN=<secret token stored in Render, never in source code>`
- `GITHUB_ALLOW_WRITES=true`
- `GITHUB_ALLOW_MAIN_WRITES=false`

The token should be limited to this repository and grant only **Contents: Read and write**. Do not grant administration, workflow, or organization permissions unless a separate reviewed feature requires them.

## Runtime safeguards

- Writes remain blocked unless `GITHUB_ALLOW_WRITES=true`.
- Writes to `main` remain blocked unless the separate `GITHUB_ALLOW_MAIN_WRITES=true` switch is deliberately enabled. Keep it false.
- Each write tool call must include `approved=true`, representing explicit user approval in the current conversation.
- Updating an existing file requires its current Git blob SHA. Read the latest file immediately before requesting the update.
- New files are created without a SHA. GitHub rejects accidental replacement if the path already exists.
- A successful tool result includes the commit SHA and file URL. A timeout must be checked in GitHub before retrying.

## Important

Enabling the environment variable does not grant ARTHER access by itself. The GitHub token must also be installed as a secret in the Render service and the service must be redeployed. Never paste the token into chat, GitHub source files, logs, or this document.
