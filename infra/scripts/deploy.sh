#!/usr/bin/env bash
# Builds both images from the current commit, pushes them, and applies the environment with that commit as the tag.
#
#   infra/scripts/deploy.sh uat [--no-wait]
#
# --no-wait skips waiting for the services to become healthy: for the first deployment, before the secrets and
# database roles exist.
#
# The images come from `git archive HEAD`, so uncommitted changes never reach an environment, and a tag always names
# exactly one commit. A tag already in the registry is not built again. Terraform shows its plan and asks before
# changing anything.
set -euo pipefail

env="${1:?usage: deploy.sh <environment> [--no-wait]}"
wait_for_services=true
[ "${2:-}" = "--no-wait" ] && wait_for_services=false
region="${AWS_REGION:-ap-south-1}"
repo="$(git rev-parse --show-toplevel)"
root="$repo/infra/terraform/environments/$env"
tag="$(git -C "$repo" rev-parse --short=12 HEAD)"
account="$(aws sts get-caller-identity --query Account --output text)"
registry="$account.dkr.ecr.$region.amazonaws.com"

if ! git -C "$repo" diff --quiet HEAD; then
  echo "Note: uncommitted changes are not deployed. Deploying commit $tag." >&2
fi

source_dir="$(mktemp -d)"
trap 'rm -rf "$source_dir"' EXIT
git -C "$repo" archive HEAD backend frontend | tar -x -C "$source_dir"

aws ecr get-login-password --region "$region" | docker login --username AWS --password-stdin "$registry" >/dev/null

for image in backend frontend; do
  name="$registry/qualityos-$image:$tag"
  if aws ecr describe-images --region "$region" --repository-name "qualityos-$image" \
    --image-ids imageTag="$tag" >/dev/null 2>&1; then
    echo "qualityos-$image:$tag is already in the registry"
    continue
  fi
  docker build --platform linux/amd64 --pull -t "$name" "$source_dir/$image"
  docker push "$name"
done

terraform -chdir="$root" apply -var "image_tag=$tag"

if [ "$wait_for_services" = false ]; then
  echo "Applied $tag to $env without waiting for the services."
  exit 0
fi

cluster="$(terraform -chdir="$root" output -raw cluster)"
echo "Waiting for both services to settle on $tag..."
aws ecs wait services-stable --region "$region" --cluster "$cluster" --services backend frontend
echo "Deployed $tag to $env: $(terraform -chdir="$root" output -raw url)"
