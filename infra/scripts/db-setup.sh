#!/usr/bin/env bash
# Creates the application's database roles inside the VPC, by running the setup task once. Safe to repeat, and needed
# again after the database passwords in the app secret change. Run seed-secrets.sh first.
set -euo pipefail

env="${1:?usage: db-setup.sh <environment>}"
region="${AWS_REGION:-ap-south-1}"
root="$(cd "$(dirname "$0")/../terraform/environments/$env" && pwd)"

setup="$(terraform -chdir="$root" output -json db_setup)"
cluster="$(terraform -chdir="$root" output -raw cluster)"
subnets="$(jq -r '.subnets | join(",")' <<<"$setup")"
group="$(jq -r .security_group <<<"$setup")"
log_group="$(jq -r .log_group <<<"$setup")"

task="$(aws ecs run-task --region "$region" --cluster "$cluster" --launch-type FARGATE \
  --task-definition "$(jq -r .task_definition <<<"$setup")" \
  --network-configuration "awsvpcConfiguration={subnets=[$subnets],securityGroups=[$group],assignPublicIp=DISABLED}" \
  --query 'tasks[0].taskArn' --output text)"
echo "Started $task"

aws ecs wait tasks-stopped --region "$region" --cluster "$cluster" --tasks "$task"
code="$(aws ecs describe-tasks --region "$region" --cluster "$cluster" --tasks "$task" \
  --query 'tasks[0].containers[0].exitCode' --output text)"

aws logs get-log-events --region "$region" --log-group-name "$log_group" \
  --log-stream-name "db-admin/db-admin/${task##*/}" --query 'events[].message' --output text | tr '\t' '\n' || true

if [ "$code" != "0" ]; then
  reason="$(aws ecs describe-tasks --region "$region" --cluster "$cluster" --tasks "$task" \
    --query 'tasks[0].stoppedReason' --output text)"
  echo "Database setup failed (exit $code): $reason" >&2
  exit 1
fi
