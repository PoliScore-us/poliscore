#!/bin/bash
# Used to set the deployment namespace and year.


# Validate or assign namespace
if [ -z "$DEPLOYMENT_NAMESPACE" ]; then
  if [ -n "$1" ]; then
    DEPLOYMENT_NAMESPACE="$1"
  else
    echo "Error: DEPLOYMENT_NAMESPACE is not set and no namespace argument provided."
    exit 1
  fi
fi

# Validate or assign year
if [ -z "$DEPLOYMENT_YEAR" ]; then
  if [ -n "$2" ]; then
    DEPLOYMENT_YEAR="$2"
  else
    echo "Error: DEPLOYMENT_YEAR is not set and no year argument provided."
    exit 1
  fi
fi

cd databuilder
mvn compile exec:java \
  -Dexec.mainClass=us.poliscore.entrypoint.WebappRoutesGenerator \
  -Dexec.args="$DEPLOYMENT_NAMESPACE $DEPLOYMENT_YEAR" \
  -Dquarkus.devservices.enabled=false \
  -Dquarkus.launch.devmode=false \
  -Dtest-containers.disabled=true \
2>&1
cd ..

# Update config buried in the front-end files
if [[ "$OSTYPE" == "darwin"* ]]; then
  sed -i '' "s|export const year: number = .*;|export const year: number = $DEPLOYMENT_YEAR;|g" ./webapp/src/main/webui/src/app/app.config.ts
else
  sed -i "s|export const year: number = .*;|export const year: number = $DEPLOYMENT_YEAR;|g" ./webapp/src/main/webui/src/app/app.config.ts
fi
sed -i '' "s|export const namespace: string = \".*\";|export const namespace: string = \"$DEPLOYMENT_NAMESPACE\";|g" ./webapp/src/main/webui/src/app/app.config.ts
sed -i '' "s|\"baseHref\": \"/[0-9]\{4\}/\"|\"baseHref\": \"/$DEPLOYMENT_YEAR/\"|g" ./webapp/src/main/webui/angular.json
