#!/bin/bash
# Usage #
## ./deploy.sh <NAMESPACE> <YEAR> <MODE>
# If <NAMESPACE> is unset, all defined DEPLOYMENT_NAMESPACES will be deployed
# <YEAR> defaults to the current year. The actual deployed year will be the end date of the session (TODO : not working for states), so if you specify 2025, but the session is the 2025-2026 session it will deploy as 2026
# <MODE> May be either "view" or "backend". If specified, will only deploy either the front-end or back-end components. Defaults to 'all'.

### Config ###

export DEPLOYMENT_NAMESPACES=("us/congress" "us/co")

########

# Do not run with sudo
if [ "$EUID" -eq 0 ]; then
  echo "Do not run as root"
  exit
fi

# Exit on error
set -e

source ./set-deployment-target.sh

if [[ "$POLISCORE_DEPLOYMENT" != "Poliscore1" && "$POLISCORE_DEPLOYMENT" != "Poliscore2" ]]; then
  echo "Error: POLISCORE_DEPLOYMENT must be Poliscore1 or Poliscore2."
  exit 1
fi

export NODE_OPTIONS="--max-old-space-size=8192"
export BUCKET_SLOT="${POLISCORE_DEPLOYMENT/Poliscore/}" # extracts 1 or 2
export BUCKET_NAME="poliscore-website$BUCKET_SLOT"

DEPLOY_BACKEND() {
  if [ "${1:-}" != "view" ]; then
    docker ps
    mvn clean install

    cd webapp
    quarkus build --native --no-tests -Dquarkus.native.container-build=true
    cd ..

    cd cdk
    cdk deploy --require-approval never
    cd ..

    echo "Lambda Deployment complete:"
    echo "  Bucket: $BUCKET_NAME"
  fi
}

DEPLOY_VIEW() {
  if [ "${1:-}" != "backend" ]; then
    source ./configure-webapp-session.sh
    DEPLOYMENT_STATE="${DEPLOYMENT_NAMESPACE#us/}"

    cd webapp/src/main/webui

    if [ "$POLISCORE_DEPLOYMENT" == "Poliscore1" ]; then
      sed -i '' "s|https://5hta4jxn7q6cfcyxnvz4qmkyli0tambn.lambda-url.us-east-1.on.aws/|$LAMBDA_DEPLOYMENT_URL|g" src/app/app.config.ts
    fi

    if [ "$POLISCORE_DEPLOYMENT" == "Poliscore2" ]; then
      sed -i '' "s|https://y5i3jhm7k5vy67elvzly4b3b240kjwlp.lambda-url.us-east-1.on.aws/|$LAMBDA_DEPLOYMENT_URL|g" src/app/app.config.ts
    fi

	if [ "$DEPLOYMENT_NAMESPACE" == "us/congress" ]; then
      ng build --base-href /$DEPLOYMENT_YEAR/
    else
      ng build --base-href /$DEPLOYMENT_YEAR/$DEPLOYMENT_STATE/
    fi
    
    cd ../../../../

    aws s3 rm s3://$BUCKET_NAME/$DEPLOYMENT_YEAR/$DEPLOYMENT_STATE --recursive || true
    aws s3 cp webapp/src/main/webui/dist/poliscore/browser s3://$BUCKET_NAME/$DEPLOYMENT_YEAR/$DEPLOYMENT_STATE --recursive

    echo "S3 Deployment complete:"
    echo "  Bucket: $BUCKET_NAME"
    echo "  Namespace: $DEPLOYMENT_NAMESPACE"
    echo "  State: $DEPLOYMENT_STATE"
    echo "  Year: $DEPLOYMENT_YEAR"
  fi
}

# Default action: loop all if no specific namespace is provided
SINGLE_NAMESPACE="${1:-}"
INPUT_YEAR="${2:-}"
MODE="${3:-}"

get_effective_year() {
  local ns="$1"
  local year="$2"

  if [ -z "$year" ]; then
    year=$(date +%Y)
  fi

  if [ "$ns" == "us/congress" ]; then
    if (( year % 2 == 0 )); then
      echo "$year"
    else
      echo $((year + 1))
    fi
  else
    echo "$year"
  fi
}

if [ -n "$SINGLE_NAMESPACE" ]; then
  export DEPLOYMENT_NAMESPACE="$SINGLE_NAMESPACE"
  export DEPLOYMENT_YEAR=$(get_effective_year "$DEPLOYMENT_NAMESPACE" "$INPUT_YEAR")
  
  DEPLOY_BACKEND "$MODE"
  DEPLOY_VIEW "$MODE"
else
  DEPLOY_BACKEND "$MODE"

  for ns in "${DEPLOYMENT_NAMESPACES[@]}"; do
    export DEPLOYMENT_NAMESPACE="$ns"
    export DEPLOYMENT_YEAR=$(get_effective_year "$DEPLOYMENT_NAMESPACE" "")
    
    DEPLOY_VIEW "$MODE"
  done
fi

