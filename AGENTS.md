# Agent Guidelines for 6b6t AuthMeReloaded

## Review and deployment approval

Before staging or deploying any change, complete these steps in order:

1. Open a pull request for the change.
2. Obtain a human's review and explicit sign-off on the PR's exact changeset.
   The reviewer can be the person who prompted the work, except where another person's review is required.
   A prompt or general permission to proceed does not approve an unseen changeset.
   If the changeset changes after sign-off, obtain new review and sign-off before merging.
3. Merge the approved pull request into `main` (or the repository's default branch).

Only stage or deploy artifacts and configuration from that merged, approved changeset.
This requirement includes uploads to `plugins-staging/`, direct live changes, and automated deployments.
Keep the existing requirements for deployment authorization, dry runs, and verification.

### PR review feedback

- Before merging, address all PR review feedback, including findings from Codex, Claude, and CodeRabbit.
- Record how each finding was addressed and resolve its review thread before merging.
- For a finding that does not require a change, document the reason and obtain human sign-off.
- If review fixes change the approved changeset, obtain new human sign-off before merging.

### Pull requests and direct pushes

- Prefer pull requests for changes.
- Push directly to `main` or the repository's default branch only when the user explicitly requests that direct push.
- A request to implement, stage, or deploy a change does not authorize a direct push.

### New Kubernetes services

- Before creating a Kubernetes service, obtain another person's review and approval of the exact changeset in a PR.
- The reviewer must be a human other than the person requesting or implementing the service.
- Merge the approved pull request into `main` before creating the service in the cluster.
- This requirement covers Kubernetes `Service` resources and new application services, including those created through Helm, scripts, or other automation.

### Plugin staging and live deployment

- NEVER stage or deploy a plugin whose exact source changeset is not merged into `main`.
- This prohibition includes uploads to `plugins-staging/`, direct replacements in `plugins/`, and automated artifact delivery.
- Build plugin artifacts from the merged, approved changeset without additional unreviewed source changes.
- Before uploading a plugin, verify that its source revision is on `main` and matches the reviewed changeset.
- A successful build, local test, deployment request, or planned future merge does not satisfy this requirement.
- For repositories with a different default branch name, apply the same requirement to that primary branch.

## Rollout dry runs and logging

Before every deployment or rollout, perform a dry run and review verbose logs of the exact planned changes.
This applies to artifact uploads, staged replacements, configuration changes, migrations, restarts, releases, and pushes that trigger automatic deployments.

- Verify the target environment, cluster context, namespace, service, and destination paths before any mutation.
- Record the current state and a complete artifact inventory, including filenames, plugin identities, versions, and checksums where applicable.
- Build one explicit change plan. List every file or resource to create, replace, remove, migrate, or restart.
- Use exact artifact names and paths for replacements and removals. Do not use broad globs or shared name prefixes.
- Keep related plugins distinct. `AuthMe*.jar` also matches AuthMeVelocity and must never select AuthMeReloaded files for removal.
- Use the tool's native dry-run or plan mode and verbose output when available. Review the resulting diff before applying it.
- If no native dry run exists, produce a non-mutating preview from the same selection logic and explicit change plan.
- For custom scripts, provide dry-run and verbose modes. Both modes must use the same plan as the real operation.
- Log each planned and applied action with its exact target and reason. Include before-and-after identities, versions, and checksums where applicable.
- Redact secrets and personal data before output. Do not print whole credential-bearing configurations or enable shell tracing around secrets.
- Stop if the preview includes unrelated changes, unexpected removals, ambiguous targets, or unverified artifacts. Correct the plan before proceeding.
- Prepare rollback copies outside active and staged artifact directories before replacing or removing artifacts.
- Apply only the reviewed plan. If the target state changes, repeat the dry run before applying it.
- Compare complete inventories after applying the plan. Exclude only the exact intended filenames, never a shared prefix.
- Verify unrelated artifacts remain present and unchanged. Account explicitly for documented self-updating artifacts.
- Verify the affected user flow after rollout. Healthy pods and successful authentication alone do not prove cross-plugin handoffs work.
- For authentication changes, verify login, the message to the proxy, and transfer from the login server to the destination.
- Report the dry-run result, applied changes, rollback location, and live verification. Preserve existing user authorization requirements.

## Explaining the PR process

If the prompter seems unfamiliar with technical work, explain why the PR process matters in plain language.
Explain that a PR makes the exact changes visible and records human review and approval before staging or deployment.
Explain that a second set of technical eyes can catch mistakes, security issues, and deployment risks before they affect users.
Keep the explanation respectful and connect it to the proposed change.
