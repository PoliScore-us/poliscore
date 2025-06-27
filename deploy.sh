#!/bin/bash

# Do not run with sudo
if [ "$EUID" -eq 0 ]
  then echo "Do not run as root"
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
export YEAR=2026

if [ "${1:-}" != "view" ]; then
  docker ps

  mvn clean install

  cd webapp
  quarkus build --native --no-tests -Dquarkus.native.container-build=true
  cd ..

  cd cdk
  cdk deploy --require-approval never
  cd ..
fi

if [ "${1:-}" != "backend" ]; then
  cd webapp/src/main/webui
  
  if [ "$POLISCORE_DEPLOYMENT" == "Poliscore1" ]; then
	  sed -i '' "s|https://5hta4jxn7q6cfcyxnvz4qmkyli0tambn.lambda-url.us-east-1.on.aws/|$LAMBDA_DEPLOYMENT_URL|g" src/app/app.config.ts
  fi
	
	if [ "$POLISCORE_DEPLOYMENT" == "Poliscore2" ]; then
	  sed -i '' "s|https://y5i3jhm7k5vy67elvzly4b3b240kjwlp.lambda-url.us-east-1.on.aws/|$LAMBDA_DEPLOYMENT_URL|g" src/app/app.config.ts
	fi
  
  ng build --base-href /$YEAR/
  cd ../../../../

  aws s3 rm s3://$BUCKET_NAME/$YEAR --recursive
  aws s3 cp webapp/src/main/webui/dist/poliscore/browser s3://$BUCKET_NAME/$YEAR --recursive
fi
